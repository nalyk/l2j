package org.l2jmobius.gameserver.livingchronicle.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * AT-FACT-00 (docs/spec/fact-journal.md): the journal's migration applies; lc_fact is partitioned by month with a catch-all, so an insert never fails for want of a partition; keys and checks reject what the spec forbids.
 */
class FactSchemaTest
{
	private static TestDatabase _database;

	@BeforeAll
	static void migrate() throws Exception
	{
		_database = TestDatabase.start();
		try (Connection connection = _database.connect())
		{
			Migrations.applyAll(connection);
		}
	}

	@Test
	void factsArePartitionedByMonthWithACatchAll() throws SQLException
	{
		final Set<String> partitions = new HashSet<>();
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery("SELECT partition_name FROM information_schema.partitions WHERE table_schema = '" + TestDatabase.SCHEMA + "' AND table_name = 'lc_fact'"))
		{
			while (result.next())
			{
				partitions.add(result.getString(1));
			}
		}
		assertTrue(partitions.contains("pmax"), "no catch-all partition: " + partitions);
		assertTrue(partitions.size() > 1, "lc_fact is not partitioned by month: " + partitions);

		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			// Far past the last monthly partition: the catch-all takes it.
			statement.execute(fact("2031-06-15 12:00:00", "system", 1, "'{\"a\":1}'"));
			assertEquals(1, count(statement, "SELECT COUNT(*) FROM lc_fact PARTITION (pmax) WHERE stream = 'system'"));
		}
	}

	@Test
	void theKeysAndChecksRejectWhatTheSpecForbids() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			// A fact has a positive sequence number, a sensitivity class 0-3 and a JSON payload.
			assertThrows(SQLException.class, () -> statement.execute(fact("2026-11-19 10:00:00", "region.12", 0, "'{}'")));
			assertThrows(SQLException.class, () -> statement.execute(fact("2026-11-19 10:00:00", "region.12", 1, "'not json'")));
			assertThrows(SQLException.class, () -> statement.execute(fact("2026-11-19 10:00:00", "region.12", 1, "'{}'").replace(", 1, NULL, '{}'", ", 9, NULL, '{}'")), "sensitivity out of range");
			statement.execute(fact("2026-11-19 10:00:00", "region.12", 1, "'{}'"));

			// One head per stream, never negative.
			statement.execute("INSERT INTO lc_stream_head (stream, last_seq) VALUES ('region.12', 1)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_stream_head (stream, last_seq) VALUES ('region.12', 2)"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_stream_head (stream, last_seq) VALUES ('region.13', -1)"));

			// A gap's span runs forward, and an acknowledgement names who and why together.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_gap (stream_or_type, from_at, to_at, refused, reason) VALUES ('system', '2026-11-19 10:00:00', '2026-11-19 09:00:00', 3, 'QUEUE_FULL')"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_gap (stream_or_type, from_at, to_at, refused, reason, acknowledged_by) VALUES ('system', '2026-11-19 10:00:00', '2026-11-19 11:00:00', 3, 'QUEUE_FULL', 'admin')"));
			statement.execute("INSERT INTO lc_journal_gap (stream_or_type, from_at, to_at, refused, reason) VALUES ('system', '2026-11-19 10:00:00', '2026-11-19 11:00:00', 3, 'RESTART')");

			// A telemetry cell is written once per window.
			statement.execute(bucket());
			assertThrows(SQLException.class, () -> statement.execute(bucket()));

			// Fact types have unique names and a positive schema version.
			statement.execute("INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, sensitivity, owner_module) VALUES (1, 'Asset.EscrowFunded', 1, 'DURABLE', 'OUTBOX', 1, 'ASSET')");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, sensitivity, owner_module) VALUES (2, 'Asset.EscrowFunded', 1, 'DURABLE', 'OUTBOX', 1, 'ASSET')"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, sensitivity, owner_module) VALUES (3, 'Asset.Other', 0, 'DURABLE', 'OUTBOX', 1, 'ASSET')"));
		}
	}

	private static String fact(String at, String stream, long seq, String payload)
	{
		return "INSERT INTO lc_fact (occurred_at, stream, stream_seq, type, schema_ver, logical_time, region_id, sensitivity, expires_at, payload) VALUES ('" + at + "', '" + stream + "', " + seq + ", 1, 1, 0, 12, 1, NULL, " + payload + ")";
	}

	private static String bucket()
	{
		return "INSERT INTO lc_telemetry_bucket (window_start, window_len, region_id, family, actor_class, events, sum_a, sum_b, unique_actors) VALUES ('2026-11-19 10:00:00', 60, 12, 1, 0, 5, 5, 100, 3)";
	}

	private static long count(Statement statement, String sql) throws SQLException
	{
		try (ResultSet result = statement.executeQuery(sql))
		{
			result.next();
			return result.getLong(1);
		}
	}
}
