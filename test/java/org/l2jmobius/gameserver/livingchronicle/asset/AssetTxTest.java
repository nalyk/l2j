package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.livingchronicle.journal.DurableFact;
import org.l2jmobius.gameserver.livingchronicle.journal.OutboxStager;
import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * The asset operation's transaction (docs/spec/asset-service.md §3.2, §4.1, §4.2): one commit or none; a retry of a
 * committed or refunded id does nothing; a lost commit result is resolved by a locking read, never by a timeout; settlement
 * keys and owner versions abort a second or stale attempt; contention is retried. AT-ASSET-01/02/03/04/21 for operations
 * that touch no Mobius holder.
 */
class AssetTxTest
{
	private static TestDatabase _database;
	private static final AtomicInteger IDS = new AtomicInteger(500_000_000);
	private static final AtomicInteger SUBJECTS = new AtomicInteger();

	/** What the next transaction connection does at COMMIT. */
	enum Fault
	{
		NONE,
		COMMIT_THEN_THROW,
		THROW_BEFORE_COMMIT,
		THROW_AND_HANG
	}

	private static final AtomicReference<Fault> FAULT = new AtomicReference<>(Fault.NONE);
	private static final AtomicReference<Connection> HUNG = new AtomicReference<>();

	record TestFact(String stream) implements DurableFact
	{
		@Override
		public String typeName()
		{
			return "Asset.CustodyOpened";
		}

		@Override
		public int schemaVersion()
		{
			return 1;
		}

		@Override
		public int sensitivity()
		{
			return 1;
		}

		@Override
		public String payloadJson()
		{
			return "{}";
		}
	}

	@BeforeAll
	static void migrate() throws Exception
	{
		_database = TestDatabase.start();
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			Migrations.applyAll(connection);
			statement.execute("CREATE TABLE lc_test_owner (id BIGINT PRIMARY KEY, version INT NOT NULL) ENGINE = InnoDB");
		}
	}

	/** Connections whose COMMIT can be made to fail as {@link #FAULT} says, once. */
	private static Connection faulty() throws SQLException
	{
		final Connection real = _database.connect();
		return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]
		{
			Connection.class
		}, (proxy, method, args) ->
		{
			if (method.getName().equals("commit"))
			{
				switch (FAULT.getAndSet(Fault.NONE))
				{
					case COMMIT_THEN_THROW:
					{
						real.commit();
						throw new SQLException("connection reset after COMMIT was sent", "08S01");
					}
					case THROW_BEFORE_COMMIT:
					{
						real.rollback();
						throw new SQLException("connection reset before COMMIT was sent", "08S01");
					}
					case THROW_AND_HANG:
					{
						HUNG.set(real);
						throw new SQLException("connection lost; the server still holds the transaction", "08S01");
					}
					default:
					{
						break;
					}
				}
			}
			if (method.getName().equals("close") && (HUNG.get() == real))
			{
				return null; // the server side keeps the transaction open until the test releases it
			}
			try
			{
				return method.invoke(real, args);
			}
			catch (InvocationTargetException e)
			{
				throw e.getCause();
			}
		});
	}

	private static AssetService service(AssetConfig config)
	{
		return new AssetService(AssetTxTest::faulty, IDS::incrementAndGet, new OutboxStager(Clock.systemUTC(), () -> 0L, () -> 1), config, Map.of());
	}

	private static final AssetService SERVICE = service(AssetConfig.defaults());

	private static OpId op()
	{
		return OpId.of("TEST", SUBJECTS.incrementAndGet(), "NOOP", 0);
	}

	private static long count(String sql) throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery(sql))
		{
			result.next();
			return result.getLong(1);
		}
	}

	private static long opRows(OpId op) throws SQLException
	{
		try (Connection connection = _database.connect();
			PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM lc_asset_op WHERE op_id = ?"))
		{
			statement.setBytes(1, op.bytes());
			try (ResultSet result = statement.executeQuery())
			{
				result.next();
				return result.getLong(1);
			}
		}
	}

	@Test
	void anEmptyOperationCommitsItsOpRow() throws Exception
	{
		final OpId op = op();
		try (AssetTx tx = SERVICE.begin(op, "TEST", "NOOP", 42))
		{
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		try (Connection connection = _database.connect();
			PreparedStatement statement = connection.prepareStatement("SELECT module, action, subject_ref FROM lc_asset_op WHERE op_id = ?"))
		{
			statement.setBytes(1, op.bytes());
			try (ResultSet result = statement.executeQuery())
			{
				assertTrue(result.next());
				assertEquals("TEST", result.getString(1));
				assertEquals("NOOP", result.getString(2));
				assertEquals(42, result.getLong(3));
			}
		}
	}

	@Test
	void aRetryOfACommittedOpIdReturnsAlreadyCommittedAndWritesNothing() throws Exception
	{
		final OpId op = op();
		final String stream = "test.retry." + op.hex().substring(0, 8);
		for (int i = 0; i < 2; i++)
		{
			try (AssetTx tx = SERVICE.begin(op, "TEST", "NOOP", 1))
			{
				tx.fact(new TestFact(stream));
				assertEquals(i == 0 ? Outcome.COMMITTED : Outcome.ALREADY_COMMITTED, tx.commit());
			}
		}
		assertEquals(1, opRows(op));
		assertEquals(1, count("SELECT COUNT(*) FROM lc_fact_outbox WHERE stream = '" + stream + "'"));
	}

	@Test
	void anAttemptThatWasRefundedIsFinal() throws Exception
	{
		final OpId op = op();
		try (AssetTx refund = SERVICE.begin(op.refund(), "TEST", "REFUND", 1))
		{
			refund.commit();
		}
		try (AssetTx tx = SERVICE.begin(op, "TEST", "NOOP", 1))
		{
			assertEquals(Outcome.ALREADY_REFUNDED, tx.commit());
		}
		assertEquals(0, opRows(op));
	}

	@Test
	void aRetryAfterALostCommitResultMovesNothing() throws Exception
	{
		final OpId op = op();
		FAULT.set(Fault.COMMIT_THEN_THROW);
		try (AssetTx tx = SERVICE.begin(op, "TEST", "NOOP", 1))
		{
			assertEquals(Outcome.COMMITTED, tx.commit(), "resolved by the locking read: present");
		}
		try (AssetTx tx = SERVICE.begin(op, "TEST", "NOOP", 1))
		{
			assertEquals(Outcome.ALREADY_COMMITTED, tx.commit());
		}
		assertEquals(1, opRows(op));
	}

	@Test
	void aCommitThatNeverHappenedAbortsAfterTheLookup() throws Exception
	{
		final OpId op = op();
		FAULT.set(Fault.THROW_BEFORE_COMMIT);
		try (AssetTx tx = SERVICE.begin(op, "TEST", "NOOP", 1))
		{
			assertEquals(AssetAbort.Reason.NOT_COMMITTED, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		assertEquals(0, opRows(op));
	}

	@Test
	void aLookupThatCannotResolveFreezesTheModuleAndNeverAborts() throws Exception
	{
		final OpId op = OpId.of("HANGING", 1, "NOOP", 0);
		final AssetService service = service(new AssetConfig(15, 100, 50, 0));
		FAULT.set(Fault.THROW_AND_HANG);
		try (AssetTx tx = service.begin(op, "HANGING", "NOOP", 1))
		{
			assertThrows(UncertainCommitException.class, tx::commit);
		}
		try (Connection connection = _database.connect())
		{
			assertTrue(ModuleState.isFrozen(connection, "HANGING"));
		}
		final Connection hung = HUNG.getAndSet(null);
		hung.commit(); // the server's transaction finally commits: the operation did happen
		hung.close();
		assertEquals(1, opRows(op));
	}

	@Test
	void aSecondSettlementOfOneKeyAbortsAsAlreadySettled() throws Exception
	{
		try (AssetTx tx = SERVICE.begin(op(), "TEST", "SETTLE", 1))
		{
			tx.settlementKey("TEST", "contract-1/clause-1");
			tx.commit();
		}
		final OpId second = op();
		try (AssetTx tx = SERVICE.begin(second, "TEST", "SETTLE", 1))
		{
			tx.settlementKey("TEST", "contract-1/clause-1");
			assertEquals(AssetAbort.Reason.ALREADY_SETTLED, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		assertEquals(0, opRows(second));
	}

	@Test
	void aStaleVersionAbortsAndACurrentOneAdvances() throws Exception
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO lc_test_owner VALUES (1, 3)");
		}
		try (AssetTx tx = SERVICE.begin(op(), "TEST", "OWNED", 1))
		{
			tx.expectVersion("lc_test_owner", "id", 1, 2);
			assertEquals(AssetAbort.Reason.STALE_VERSION, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		try (AssetTx tx = SERVICE.begin(op(), "TEST", "OWNED", 1))
		{
			tx.expectVersion("lc_test_owner", "id", 1, 3);
			tx.commit();
		}
		assertEquals(4, count("SELECT version FROM lc_test_owner WHERE id = 1"));
	}

	@Test
	void factsAreStagedWithTheOperationAndNotWithAnAbort() throws Exception
	{
		final OpId op = op();
		final String stream = "test.fact." + op.hex().substring(0, 8);
		try (AssetTx tx = SERVICE.begin(op, "TEST", "FACT", 1))
		{
			tx.fact(new TestFact(stream));
			tx.commit();
		}
		assertEquals(1, count("SELECT COUNT(*) FROM lc_fact_outbox WHERE stream = '" + stream + "' AND op_id = UNHEX('" + op.hex() + "')"));
		final String aborted = stream + ".x";
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO lc_test_owner VALUES (2, 0)");
		}
		try (AssetTx tx = SERVICE.begin(op(), "TEST", "FACT", 1))
		{
			tx.fact(new TestFact(aborted));
			tx.expectVersion("lc_test_owner", "id", 2, 9);
			assertThrows(AssetAbort.class, tx::commit);
		}
		assertEquals(0, count("SELECT COUNT(*) FROM lc_fact_outbox WHERE stream = '" + aborted + "'"));
	}

	@Test
	void contentionIsRetriedThenAbortsWithoutWriting() throws Exception
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO lc_test_owner VALUES (3, 0)");
		}
		final AssetService service = service(new AssetConfig(15, 5000, 50, 2));
		// Held for longer than every attempt: the operation gives up.
		final Connection holder = _database.connect();
		holder.setAutoCommit(false);
		try (Statement statement = holder.createStatement())
		{
			statement.execute("SELECT version FROM lc_test_owner WHERE id = 3 FOR UPDATE");
		}
		final OpId given = op();
		try (AssetTx tx = service.begin(given, "TEST", "CONTENDED", 1))
		{
			tx.expectVersion("lc_test_owner", "id", 3, 0);
			assertEquals(AssetAbort.Reason.CONTENTION, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		assertEquals(0, opRows(given));
		// Released during the retries: the operation commits.
		final OpId later = op();
		final CompletableFuture<Outcome> outcome = CompletableFuture.supplyAsync(() ->
		{
			try (AssetTx tx = service.begin(later, "TEST", "CONTENDED", 1))
			{
				tx.expectVersion("lc_test_owner", "id", 3, 0);
				return tx.commit();
			}
			catch (Exception e)
			{
				throw new IllegalStateException(e);
			}
		});
		Thread.sleep(1500);
		holder.rollback();
		holder.close();
		assertEquals(Outcome.COMMITTED, outcome.get(30, TimeUnit.SECONDS));
	}
}
