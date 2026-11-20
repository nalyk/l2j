package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.livingchronicle.journal.OutboxStager;
import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * Custody operations (docs/spec/asset-service.md §3.1, §4.3 open, move, close): rows move between custodies under the
 * custody locks, postings balance and match the rows (INV01, INV11), quantities never clamp, and a custody whose rows do
 * not match its postings freezes instead of moving.
 */
class CustodyOperationsTest
{
	private static final int ADENA = 57;
	private static final int SWORD = 2369;
	private static TestDatabase _database;
	private static final AtomicInteger IDS = new AtomicInteger(600_000_000);
	private static final AtomicInteger SUBJECTS = new AtomicInteger();
	private static AssetService _service;

	@BeforeAll
	static void migrate() throws Exception
	{
		_database = TestDatabase.start();
		try (Connection connection = _database.connect())
		{
			Migrations.applyAll(connection);
		}
		final HoldingPolicy always = (connection, ref) -> true;
		final HoldingPolicy never = (connection, ref) -> false;
		_service = new AssetService(_database::connect, IDS::incrementAndGet, new OutboxStager(Clock.systemUTC(), () -> 0L, () -> 1), AssetConfig.defaults(), Map.of("TEST", always, "OTHER", always, "REFUSING", never, "TAMPERED", always));
	}

	private static OpId op(String action)
	{
		return OpId.of("TEST", SUBJECTS.incrementAndGet(), action, 0);
	}

	private static int open(String module) throws Exception
	{
		return _service.open(op("OPEN"), Custody.Kind.CONTRACT, module, SUBJECTS.get());
	}

	/** Puts assets into a custody the way a correction would: an op row, a balanced SOURCE leg, and the row. */
	private static int seed(int custody, int itemId, long qty, boolean wholeObject) throws SQLException
	{
		final OpId op = op("SEED");
		final int objectId = IDS.incrementAndGet();
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO lc_asset_op VALUES (UNHEX('" + op.hex() + "'), 'TEST', 'SEED', 0, NOW(3))");
			statement.execute("INSERT INTO lc_ledger_posting (op_id, leg, account_kind, account_id, code, item_id, object_id, qty, direction) VALUES (UNHEX('" + op.hex() + "'), 0, 'SOURCE', 0, 'ADMIN_CORRECTION', " + itemId + ", NULL, " + qty + ", 'DEBIT'), (UNHEX('" + op.hex() + "'), 1, 'CUSTODY', " + custody + ", NULL, " + itemId + ", " + (wholeObject ? objectId : "NULL") + ", " + qty + ", 'CREDIT')");
			statement.execute("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data) VALUES (-1, " + objectId + ", " + itemId + ", " + qty + ", 0, 'ESCROW', " + custody + ")");
		}
		return objectId;
	}

	private static long held(int custody, int itemId) throws SQLException
	{
		return number("SELECT COALESCE(SUM(count), 0) FROM items WHERE owner_id = -1 AND loc = 'ESCROW' AND loc_data = " + custody + " AND item_id = " + itemId);
	}

	private static long number(String sql) throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery(sql))
		{
			result.next();
			return result.getLong(1);
		}
	}

	private static String text(String sql) throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery(sql))
		{
			result.next();
			return result.getString(1);
		}
	}

	private static Outcome move(int from, int to, Asset... assets) throws Exception
	{
		try (AssetTx tx = _service.begin(op("MOVE"), "TEST", "MOVE", from))
		{
			tx.move(from, to, List.of(assets));
			return tx.commit();
		}
	}

	@Test
	void openWritesAnOpenCustodyAndItsFactOnceForOneId() throws Exception
	{
		final OpId op = op("OPEN");
		final int custody = _service.open(op, Custody.Kind.POOL, "TEST", 9);
		assertEquals(custody, _service.open(op, Custody.Kind.POOL, "TEST", 9), "a retry returns the same custody");
		assertEquals("OPEN", text("SELECT state FROM lc_custody WHERE custody_id = " + custody));
		assertEquals(1, number("SELECT COUNT(*) FROM lc_custody WHERE created_op = UNHEX('" + op.hex() + "')"));
		assertEquals(1, number("SELECT COUNT(*) FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = 'Asset.CustodyOpened' AND o.stream = 'asset.custody." + custody + "'"));
	}

	@Test
	void moveOfAWholeObjectChangesOnlyItsCustody() throws Exception
	{
		final int from = open("TEST");
		final int to = open("OTHER");
		final int sword = seed(from, SWORD, 1, true);
		assertEquals(Outcome.COMMITTED, move(from, to, new Asset(SWORD, 1, sword)));
		assertEquals(to, number("SELECT loc_data FROM items WHERE object_id = " + sword));
		assertEquals(1, number("SELECT version FROM lc_custody WHERE custody_id = " + from));
		assertEquals(1, number("SELECT version FROM lc_custody WHERE custody_id = " + to));
		assertEquals(1, number("SELECT COUNT(*) FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = 'Asset.CustodyMoved' AND o.stream = 'asset.custody." + from + "'"), "one fact, in the source custody's stream");
	}

	@Test
	void moveOfAdenaSplitsAndMergesRows() throws Exception
	{
		final int from = open("TEST");
		final int to = open("TEST");
		seed(from, ADENA, 1000, false);
		move(from, to, new Asset(ADENA, 300, null));
		assertEquals(700, held(from, ADENA));
		assertEquals(300, held(to, ADENA));
		move(from, to, new Asset(ADENA, 200, null));
		assertEquals(1, number("SELECT COUNT(*) FROM items WHERE loc = 'ESCROW' AND loc_data = " + to), "one stackable row per custody");
		move(from, to, new Asset(ADENA, 500, null));
		assertEquals(0, number("SELECT COUNT(*) FROM items WHERE loc = 'ESCROW' AND loc_data = " + from), "an emptied row is deleted");
		assertEquals(1000, held(to, ADENA));
	}

	@Test
	void aMoveBeyondTheBalanceAbortsAndWritesNothing() throws Exception
	{
		final int from = open("TEST");
		final int to = open("TEST");
		seed(from, ADENA, 100, false);
		final OpId op = op("MOVE");
		try (AssetTx tx = _service.begin(op, "TEST", "MOVE", from))
		{
			tx.move(from, to, List.of(new Asset(ADENA, 101, null)));
			assertEquals(AssetAbort.Reason.INSUFFICIENT, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		assertEquals(100, held(from, ADENA));
		assertEquals(0, held(to, ADENA));
		assertEquals(0, number("SELECT COUNT(*) FROM lc_asset_op WHERE op_id = UNHEX('" + op.hex() + "')"));
		assertEquals(0, number("SELECT version FROM lc_custody WHERE custody_id = " + from));
	}

	@Test
	void aWholeObjectNoLongerThereAborts() throws Exception
	{
		final int from = open("TEST");
		final int to = open("TEST");
		final int sword = seed(from, SWORD, 1, true);
		move(from, to, new Asset(SWORD, 1, sword));
		try (AssetTx tx = _service.begin(op("MOVE"), "TEST", "MOVE", from))
		{
			tx.move(from, to, List.of(new Asset(SWORD, 1, sword)));
			assertEquals(AssetAbort.Reason.INSUFFICIENT, assertThrows(AssetAbort.class, tx::commit).reason());
		}
	}

	@Test
	void twoMovesOutOfOneCustodyNeverOverdraw() throws Exception
	{
		final int from = open("TEST");
		final int a = open("TEST");
		final int b = open("TEST");
		seed(from, ADENA, 100, false);
		final ExecutorService pool = Executors.newFixedThreadPool(2);
		final List<Future<Boolean>> results = new ArrayList<>();
		for (int target : new int[] { a, b })
		{
			final Callable<Boolean> task = () ->
			{
				try
				{
					return move(from, target, new Asset(ADENA, 60, null)) == Outcome.COMMITTED;
				}
				catch (AssetAbort e)
				{
					assertEquals(AssetAbort.Reason.INSUFFICIENT, e.reason());
					return false;
				}
			};
			results.add(pool.submit(task));
		}
		int committed = 0;
		for (Future<Boolean> result : results)
		{
			committed += result.get() ? 1 : 0;
		}
		pool.shutdown();
		assertEquals(1, committed);
		assertEquals(40, held(from, ADENA));
		assertEquals(60, held(a, ADENA) + held(b, ADENA));
	}

	@Test
	void closeRefusesANonEmptyCustodyAndAClosedOneAcceptsNothing() throws Exception
	{
		final int custody = open("TEST");
		final int other = open("TEST");
		seed(custody, ADENA, 5, false);
		try (AssetTx tx = _service.begin(op("CLOSE"), "TEST", "CLOSE", custody))
		{
			tx.close(custody);
			assertEquals(AssetAbort.Reason.NOT_EMPTY, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		move(custody, other, new Asset(ADENA, 5, null));
		final OpId closing = op("CLOSE");
		try (AssetTx tx = _service.begin(closing, "TEST", "CLOSE", custody))
		{
			tx.close(custody);
			tx.commit();
		}
		assertEquals("CLOSED", text("SELECT state FROM lc_custody WHERE custody_id = " + custody));
		assertEquals(closing.hex(), text("SELECT LOWER(HEX(closed_op)) FROM lc_custody WHERE custody_id = " + custody));
		try (AssetTx tx = _service.begin(op("MOVE"), "TEST", "MOVE", other))
		{
			tx.move(other, custody, List.of(new Asset(ADENA, 1, null)));
			assertEquals(AssetAbort.Reason.CUSTODY_NOT_OPEN, assertThrows(AssetAbort.class, tx::commit).reason());
		}
	}

	@Test
	void anOwnerThatRefusesToHoldAndAFrozenOwnerAreRefused() throws Exception
	{
		final int from = open("TEST");
		final int refusing = open("REFUSING");
		seed(from, ADENA, 10, false);
		try (AssetTx tx = _service.begin(op("MOVE"), "TEST", "MOVE", from))
		{
			tx.move(from, refusing, List.of(new Asset(ADENA, 1, null)));
			assertEquals(AssetAbort.Reason.NOT_ALLOWED_TO_HOLD, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		final int frozenOwner = _service.open(op("OPEN"), Custody.Kind.POOL, "COLD", 1);
		try (Connection connection = _database.connect())
		{
			ModuleState.freeze(connection, "COLD", "test", null);
		}
		try (AssetTx tx = _service.begin(op("MOVE"), "TEST", "MOVE", from))
		{
			tx.move(from, frozenOwner, List.of(new Asset(ADENA, 1, null)));
			assertEquals(AssetAbort.Reason.MODULE_FROZEN, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		assertEquals(10, held(from, ADENA));
	}

	@Test
	void aTamperedCustodyFreezesInsteadOfMoving() throws Exception
	{
		final int from = open("TAMPERED");
		final int to = open("TEST");
		final int row = seed(from, ADENA, 100, false);
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("UPDATE items SET count = 150 WHERE object_id = " + row); // rows no longer match postings
		}
		try (AssetTx tx = _service.begin(op("MOVE"), "TEST", "MOVE", from))
		{
			tx.move(from, to, List.of(new Asset(ADENA, 10, null)));
			assertEquals(AssetAbort.Reason.UNBALANCED, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		assertEquals(150, held(from, ADENA), "the evidence is kept, never repaired");
		assertEquals("FROZEN", text("SELECT state FROM lc_custody WHERE custody_id = " + from));
		try (Connection connection = _database.connect())
		{
			assertTrue(ModuleState.isFrozen(connection, "TAMPERED"));
		}
		assertEquals(1, number("SELECT COUNT(*) FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = 'Asset.InvariantViolated' AND o.stream = 'asset.custody." + from + "'"));
	}

	@Test
	void postingsBalanceAndMatchRowsAfterRandomMoves() throws Exception
	{
		final List<Integer> custodies = new ArrayList<>();
		for (int i = 0; i < 4; i++)
		{
			custodies.add(open("TEST"));
			seed(custodies.get(i), ADENA, 1000, false);
		}
		final Random random = new Random(20261120);
		for (int i = 0; i < 40; i++)
		{
			final int from = custodies.get(random.nextInt(4));
			int to = custodies.get(random.nextInt(4));
			if (to == from)
			{
				to = custodies.get((custodies.indexOf(from) + 1) % 4);
			}
			try
			{
				move(from, to, new Asset(ADENA, 1 + random.nextInt(400), null));
			}
			catch (AssetAbort e)
			{
				assertEquals(AssetAbort.Reason.INSUFFICIENT, e.reason());
			}
		}
		long total = 0;
		for (int custody : custodies)
		{
			final long posted = number("SELECT COALESCE(SUM(IF(direction = 'CREDIT', qty, -qty)), 0) FROM lc_ledger_posting WHERE account_kind = 'CUSTODY' AND account_id = " + custody + " AND item_id = " + ADENA);
			assertEquals(held(custody, ADENA), posted, "INV11 for custody " + custody);
			total += held(custody, ADENA);
		}
		assertEquals(4000, total, "nothing created or lost");
		assertEquals(0, number("SELECT COUNT(*) FROM (SELECT op_id, item_id, SUM(IF(direction = 'DEBIT', qty, -qty)) AS d FROM lc_ledger_posting GROUP BY op_id, item_id HAVING d <> 0) unbalanced"), "INV01");
		assertNotEquals(0, number("SELECT COUNT(*) FROM lc_ledger_posting"));
	}
}
