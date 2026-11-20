package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


/**
 * Custody operations (docs/spec/asset-service.md §3.1, §4.3 open, move, close): rows move between custodies under the
 * custody locks, postings balance and match the rows (INV01, INV11), quantities never clamp, and a custody whose rows do
 * not match its postings freezes instead of moving.
 */
class CustodyOperationsTest
{
	private static final int ADENA = AssetFixtures.ADENA;
	private static final int SWORD = AssetFixtures.SWORD;
	private static AssetFixtures _f;

	@BeforeAll
	static void migrate() throws Exception
	{
		final HoldingPolicy always = (connection, ref) -> true;
		final HoldingPolicy never = (connection, ref) -> false;
		_f = new AssetFixtures(600_000_000, Map.of("TEST", always, "OTHER", always, "REFUSING", never, "TAMPERED", always));
	}

	private static Outcome move(int from, int to, Asset... assets) throws Exception
	{
		try (AssetTx tx = _f.service.begin(_f.op("MOVE"), "TEST", "MOVE", from))
		{
			tx.move(from, to, List.of(assets));
			return tx.commit();
		}
	}

	@Test
	void openWritesAnOpenCustodyAndItsFactOnceForOneId() throws Exception
	{
		final OpId op = _f.op("OPEN");
		final int custody = _f.service.open(op, Custody.Kind.POOL, "TEST", 9);
		assertEquals(custody, _f.service.open(op, Custody.Kind.POOL, "TEST", 9), "a retry returns the same custody");
		assertEquals("OPEN", _f.text("SELECT state FROM lc_custody WHERE custody_id = " + custody));
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_custody WHERE created_op = UNHEX('" + op.hex() + "')"));
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = 'Asset.CustodyOpened' AND o.stream = 'asset.custody." + custody + "'"));
	}

	@Test
	void moveOfAWholeObjectChangesOnlyItsCustody() throws Exception
	{
		final int from = _f.open("TEST");
		final int to = _f.open("OTHER");
		final int sword = _f.seed(from, SWORD, 1, true);
		assertEquals(Outcome.COMMITTED, move(from, to, new Asset(SWORD, 1, sword)));
		assertEquals(to, _f.number("SELECT loc_data FROM items WHERE object_id = " + sword));
		assertEquals(1, _f.number("SELECT version FROM lc_custody WHERE custody_id = " + from));
		assertEquals(1, _f.number("SELECT version FROM lc_custody WHERE custody_id = " + to));
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = 'Asset.CustodyMoved' AND o.stream = 'asset.custody." + from + "'"), "one fact, in the source custody's stream");
	}

	@Test
	void moveOfAdenaSplitsAndMergesRows() throws Exception
	{
		final int from = _f.open("TEST");
		final int to = _f.open("TEST");
		_f.seed(from, ADENA, 1000, false);
		move(from, to, new Asset(ADENA, 300, null));
		assertEquals(700, _f.held(from, ADENA));
		assertEquals(300, _f.held(to, ADENA));
		move(from, to, new Asset(ADENA, 200, null));
		assertEquals(1, _f.number("SELECT COUNT(*) FROM items WHERE loc = 'ESCROW' AND loc_data = " + to), "one stackable row per custody");
		move(from, to, new Asset(ADENA, 500, null));
		assertEquals(0, _f.number("SELECT COUNT(*) FROM items WHERE loc = 'ESCROW' AND loc_data = " + from), "an emptied row is deleted");
		assertEquals(1000, _f.held(to, ADENA));
	}

	@Test
	void aMoveBeyondTheBalanceAbortsAndWritesNothing() throws Exception
	{
		final int from = _f.open("TEST");
		final int to = _f.open("TEST");
		_f.seed(from, ADENA, 100, false);
		final OpId op = _f.op("MOVE");
		try (AssetTx tx = _f.service.begin(op, "TEST", "MOVE", from))
		{
			tx.move(from, to, List.of(new Asset(ADENA, 101, null)));
			assertEquals(AssetAbort.Reason.INSUFFICIENT, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		assertEquals(100, _f.held(from, ADENA));
		assertEquals(0, _f.held(to, ADENA));
		assertEquals(0, _f.number("SELECT COUNT(*) FROM lc_asset_op WHERE op_id = UNHEX('" + op.hex() + "')"));
		assertEquals(0, _f.number("SELECT version FROM lc_custody WHERE custody_id = " + from));
	}

	@Test
	void aWholeObjectNoLongerThereAborts() throws Exception
	{
		final int from = _f.open("TEST");
		final int to = _f.open("TEST");
		final int sword = _f.seed(from, SWORD, 1, true);
		move(from, to, new Asset(SWORD, 1, sword));
		try (AssetTx tx = _f.service.begin(_f.op("MOVE"), "TEST", "MOVE", from))
		{
			tx.move(from, to, List.of(new Asset(SWORD, 1, sword)));
			assertEquals(AssetAbort.Reason.INSUFFICIENT, assertThrows(AssetAbort.class, tx::commit).reason());
		}
	}

	@Test
	void twoMovesOutOfOneCustodyNeverOverdraw() throws Exception
	{
		final int from = _f.open("TEST");
		final int a = _f.open("TEST");
		final int b = _f.open("TEST");
		_f.seed(from, ADENA, 100, false);
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
		assertEquals(40, _f.held(from, ADENA));
		assertEquals(60, _f.held(a, ADENA) + _f.held(b, ADENA));
	}

	@Test
	void closeRefusesANonEmptyCustodyAndAClosedOneAcceptsNothing() throws Exception
	{
		final int custody = _f.open("TEST");
		final int other = _f.open("TEST");
		_f.seed(custody, ADENA, 5, false);
		try (AssetTx tx = _f.service.begin(_f.op("CLOSE"), "TEST", "CLOSE", custody))
		{
			tx.close(custody);
			assertEquals(AssetAbort.Reason.NOT_EMPTY, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		move(custody, other, new Asset(ADENA, 5, null));
		final OpId closing = _f.op("CLOSE");
		try (AssetTx tx = _f.service.begin(closing, "TEST", "CLOSE", custody))
		{
			tx.close(custody);
			tx.commit();
		}
		assertEquals("CLOSED", _f.text("SELECT state FROM lc_custody WHERE custody_id = " + custody));
		assertEquals(closing.hex(), _f.text("SELECT LOWER(HEX(closed_op)) FROM lc_custody WHERE custody_id = " + custody));
		try (AssetTx tx = _f.service.begin(_f.op("MOVE"), "TEST", "MOVE", other))
		{
			tx.move(other, custody, List.of(new Asset(ADENA, 1, null)));
			assertEquals(AssetAbort.Reason.CUSTODY_NOT_OPEN, assertThrows(AssetAbort.class, tx::commit).reason());
		}
	}

	@Test
	void anOwnerThatRefusesToHoldAndAFrozenOwnerAreRefused() throws Exception
	{
		final int from = _f.open("TEST");
		final int refusing = _f.open("REFUSING");
		_f.seed(from, ADENA, 10, false);
		try (AssetTx tx = _f.service.begin(_f.op("MOVE"), "TEST", "MOVE", from))
		{
			tx.move(from, refusing, List.of(new Asset(ADENA, 1, null)));
			assertEquals(AssetAbort.Reason.NOT_ALLOWED_TO_HOLD, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		final int frozenOwner = _f.service.open(_f.op("OPEN"), Custody.Kind.POOL, "COLD", 1);
		try (Connection connection = _f.database.connect())
		{
			ModuleState.freeze(connection, "COLD", "test", null);
		}
		try (AssetTx tx = _f.service.begin(_f.op("MOVE"), "TEST", "MOVE", from))
		{
			tx.move(from, frozenOwner, List.of(new Asset(ADENA, 1, null)));
			assertEquals(AssetAbort.Reason.MODULE_FROZEN, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		assertEquals(10, _f.held(from, ADENA));
	}

	@Test
	void aTamperedCustodyFreezesInsteadOfMoving() throws Exception
	{
		final int from = _f.open("TAMPERED");
		final int to = _f.open("TEST");
		final int row = _f.seed(from, ADENA, 100, false);
		_f.execute("UPDATE items SET count = 150 WHERE object_id = " + row); // rows no longer match postings
		try (AssetTx tx = _f.service.begin(_f.op("MOVE"), "TEST", "MOVE", from))
		{
			tx.move(from, to, List.of(new Asset(ADENA, 10, null)));
			assertEquals(AssetAbort.Reason.UNBALANCED, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		assertEquals(150, _f.held(from, ADENA), "the evidence is kept, never repaired");
		assertEquals("FROZEN", _f.text("SELECT state FROM lc_custody WHERE custody_id = " + from));
		try (Connection connection = _f.database.connect())
		{
			assertTrue(ModuleState.isFrozen(connection, "TAMPERED"));
		}
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = 'Asset.InvariantViolated' AND o.stream = 'asset.custody." + from + "'"));
	}

	@Test
	void postingsBalanceAndMatchRowsAfterRandomMoves() throws Exception
	{
		final List<Integer> custodies = new ArrayList<>();
		for (int i = 0; i < 4; i++)
		{
			custodies.add(_f.open("TEST"));
			_f.seed(custodies.get(i), ADENA, 1000, false);
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
			final long posted = _f.number("SELECT COALESCE(SUM(IF(direction = 'CREDIT', qty, -qty)), 0) FROM lc_ledger_posting WHERE account_kind = 'CUSTODY' AND account_id = " + custody + " AND item_id = " + ADENA);
			assertEquals(_f.held(custody, ADENA), posted, "INV11 for custody " + custody);
			total += _f.held(custody, ADENA);
		}
		assertEquals(4000, total, "nothing created or lost");
		assertEquals(0, _f.number("SELECT COUNT(*) FROM (SELECT op_id, item_id, SUM(IF(direction = 'DEBIT', qty, -qty)) AS d FROM lc_ledger_posting GROUP BY op_id, item_id HAVING d <> 0) unbalanced"), "INV01");
		assertNotEquals(0, _f.number("SELECT COUNT(*) FROM lc_ledger_posting"));
	}

	@Test
	void twoRowsOfOneStackableItemAreTakenTogether() throws Exception
	{
		final int from = _f.open("TEST");
		final int to = _f.open("TEST");
		_f.seed(from, ADENA, 100, false);
		_f.seed(from, ADENA, 50, false);
		assertEquals(Outcome.COMMITTED, move(from, to, new Asset(ADENA, 120, null)));
		assertEquals(30, _f.held(from, ADENA));
		assertEquals(120, _f.held(to, ADENA));
		assertEquals(30, _f.posted(from, ADENA));
	}
}
