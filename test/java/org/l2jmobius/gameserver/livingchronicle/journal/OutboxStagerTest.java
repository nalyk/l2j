package org.l2jmobius.gameserver.livingchronicle.journal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.livingchronicle.asset.OpId;
import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * Outbox facts (docs/spec/fact-journal.md §3.1): staged in the caller's transaction, with consecutive numbers per stream
 * taken under the stream head's lock.
 */
class OutboxStagerTest
{
	private static TestDatabase _database;
	private static final OutboxStager STAGER = new OutboxStager(Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC), () -> 4242L, () -> 1);
	private static final OpId OP = OpId.of("TEST", 1, "STAGE", 0);

	record TestFact(String typeName, String stream, String payloadJson) implements DurableFact
	{
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
	}

	@BeforeAll
	static void migrate() throws Exception
	{
		_database = TestDatabase.start();
		try (Connection connection = _database.connect())
		{
			Migrations.applyAll(connection);
		}
	}

	private static Connection transaction() throws SQLException
	{
		final Connection connection = _database.connect();
		connection.setAutoCommit(false);
		return connection;
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

	@Test
	void stagesInOneTransactionWithTheCaller() throws SQLException
	{
		try (Connection connection = transaction())
		{
			STAGER.stage(connection, OP, List.of(new TestFact("Asset.CustodyOpened", "test.rollback", "{\"custody\":1}")));
			connection.rollback();
		}
		assertEquals(0, count("SELECT COUNT(*) FROM lc_fact_outbox WHERE stream = 'test.rollback'"));
		assertEquals(0, count("SELECT COUNT(*) FROM lc_stream_head WHERE stream = 'test.rollback' AND last_seq > 0"));
	}

	@Test
	void consecutiveNumbersPerStreamAndEveryColumnSet() throws SQLException
	{
		for (int i = 0; i < 2; i++)
		{
			try (Connection connection = transaction())
			{
				STAGER.stage(connection, OP, List.of(new TestFact("Asset.CustodyOpened", "test.seq", "{\"n\":" + i + "}")));
				connection.commit();
			}
		}
		final List<Long> ids;
		try (Connection connection = transaction())
		{
			ids = STAGER.stage(connection, OP, List.of(new TestFact("Asset.CustodyClosed", "test.other", "{}")));
			connection.commit();
		}
		assertEquals(1, ids.size());
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery("SELECT stream_seq FROM lc_fact_outbox WHERE stream = 'test.seq' ORDER BY outbox_id"))
		{
			final List<Long> seqs = new ArrayList<>();
			while (result.next())
			{
				seqs.add(result.getLong(1));
			}
			assertEquals(List.of(1L, 2L), seqs);
		}
		try (Connection connection = _database.connect();
			PreparedStatement statement = connection.prepareStatement("SELECT o.stream_seq, o.type, t.name, o.schema_ver, o.logical_time, o.region_id, o.instance_id, o.sensitivity, o.op_id, o.config_ver, o.staged_at, o.actor_snapshot FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE o.outbox_id = ?"))
		{
			statement.setLong(1, ids.get(0));
			try (ResultSet result = statement.executeQuery())
			{
				assertTrue(result.next());
				assertEquals(1, result.getLong(1), "another stream starts at 1");
				assertEquals("Asset.CustodyClosed", result.getString(3));
				assertEquals(1, result.getInt(4));
				assertEquals(4242L, result.getLong(5));
				assertEquals(0, result.getInt(6));
				assertEquals(0, result.getInt(7));
				assertEquals(1, result.getInt(8));
				assertArrayEquals(OP.bytes(), result.getBytes(9));
				assertEquals(1, result.getInt(10));
				assertEquals(Instant.parse("2026-11-20T10:00:00Z"), result.getTimestamp(11).toInstant());
				assertEquals(null, result.getString(12));
			}
		}
	}

	@Test
	void theHeadIsLockedSoConcurrentStagersSerialise() throws Exception
	{
		final Connection first = transaction();
		STAGER.stage(first, OP, List.of(new TestFact("Asset.CustodyOpened", "test.lock", "{}")));
		final CompletableFuture<Void> second = CompletableFuture.runAsync(() ->
		{
			try (Connection connection = transaction())
			{
				STAGER.stage(connection, OP, List.of(new TestFact("Asset.CustodyOpened", "test.lock", "{}")));
				connection.commit();
			}
			catch (SQLException e)
			{
				throw new IllegalStateException(e);
			}
		});
		Thread.sleep(500);
		assertFalse(second.isDone(), "the second stager waits on the head");
		first.commit();
		first.close();
		second.get(30, TimeUnit.SECONDS);
		assertEquals(2, count("SELECT COUNT(DISTINCT stream_seq) FROM lc_fact_outbox WHERE stream = 'test.lock'"));
		assertEquals(2, count("SELECT last_seq FROM lc_stream_head WHERE stream = 'test.lock'"));
	}

	@Test
	void anUnregisteredTypeIsRefused() throws SQLException
	{
		try (Connection connection = transaction())
		{
			assertThrows(IllegalStateException.class, () -> STAGER.stage(connection, OP, List.of(new TestFact("Asset.NoSuchFact", "test.unknown", "{}"))));
			connection.rollback();
		}
	}

	@Test
	void everyAssetFactTypeOfTheSpecIsRegistered() throws SQLException
	{
		final Set<String> spec = Set.of("Asset.CustodyOpened", "Asset.EscrowFunded", "Asset.EscrowReleased", "Asset.FeeCharged", "Asset.TreasuryCommitted", "Asset.TreasuryReturned", "Asset.Crafted", "Asset.LootRouted", "Asset.ParchmentIssued", "Asset.SealIssued", "Asset.ManifestRaided", "Asset.CustodyMoved", "Asset.Exchanged", "Asset.DropRelocated", "Asset.SinkRecycled", "Asset.LifecycleTracked", "Asset.Refunded", "Asset.CustodyClosed", "Asset.CustodyFrozen", "Asset.AdminCorrection", "Asset.InvariantViolated", "Asset.ConservationBroken", "Asset.MonitorRebased", "Asset.MoveImbalance", "Asset.RecoveryCompleted");
		assertEquals(25, spec.size());
		assertEquals(25, count("SELECT COUNT(*) FROM lc_fact_type WHERE owner_module = 'ASSET' AND durability = 'DURABLE' AND delivery = 'OUTBOX' AND name IN ('" + String.join("','", spec) + "')"));
	}
}
