package org.l2jmobius.gameserver.livingchronicle.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

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

	/**
	 * Monthly partitions with exact month boundaries, each starting where the previous ended, ending in a catch-all (spec §4.5). That they stay two months ahead is the maintenance job's (AT-FACT-00 covers the shape only).
	 */
	@Test
	void factsArePartitionedByMonthWithACatchAll() throws SQLException
	{
		final List<String> bounds = new ArrayList<>();
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery("SELECT partition_description FROM information_schema.partitions WHERE table_schema = '" + TestDatabase.SCHEMA + "' AND table_name = 'lc_fact' ORDER BY partition_ordinal_position"))
		{
			while (result.next())
			{
				bounds.add(result.getString(1));
			}
		}
		assertEquals("MAXVALUE", bounds.get(bounds.size() - 1), "no catch-all partition: " + bounds);
		assertTrue(bounds.size() > 2, "lc_fact is not partitioned by month: " + bounds);
		LocalDate previous = null;
		for (String bound : bounds.subList(0, bounds.size() - 1))
		{
			final LocalDate day = Instant.ofEpochSecond(Long.parseLong(bound)).atZone(ZoneOffset.UTC).toLocalDate();
			assertEquals(1, day.getDayOfMonth(), "a boundary that is not a month's start: " + day);
			assertTrue((previous == null) || day.equals(previous.plusMonths(1)), "months not contiguous: " + previous + " then " + day);
			previous = day;
		}

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
			assertThrows(SQLException.class, () -> statement.execute(fact("2026-11-19 10:00:00", "region.12", 1, "'{}'").replace(", 12, 1, '{}'", ", 12, 9, '{}'")), "sensitivity out of range");
			statement.execute(fact("2026-11-19 10:00:00", "region.12", 1, "'{}'"));

			// One head per stream, never negative.
			statement.execute("INSERT INTO lc_stream_head (stream, epoch, last_seq, last_logical_time) VALUES ('region.12', 0, 1, 0)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_stream_head (stream, epoch, last_seq, last_logical_time) VALUES ('region.12', 0, 2, 0)"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_stream_head (stream, epoch, last_seq, last_logical_time) VALUES ('region.13', 0, -1, 0)"));

			// A gap's span runs forward; only a RESTART gap may leave its end and count unknown; an acknowledgement names who and why together.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_gap (stream, epoch, from_seq, to_seq, refused, reason) VALUES ('cap.pvp', 1, 10, 9, 3, 'QUEUE_FULL')"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_gap (stream, epoch, from_seq, to_seq, refused, reason) VALUES ('cap.pvp', 1, 10, NULL, NULL, 'QUEUE_FULL')"), "an open-ended gap that is not a restart");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_gap (stream, epoch, from_seq, to_seq, refused, reason, acknowledged_by) VALUES ('cap.pvp', 1, 10, 12, 3, 'QUEUE_FULL', 'admin')"));
			statement.execute("INSERT INTO lc_journal_gap (stream, epoch, from_seq, to_seq, refused, reason) VALUES ('cap.pvp', 1, 10, NULL, NULL, 'RESTART')");

			// A batch id is committed once; a clean epoch has ended.
			statement.execute("INSERT INTO lc_journal_batch (batch_id, epoch, committed_at, facts, frontier_logical_time) VALUES (7, 1, NOW(3), 0, 1000)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_batch (batch_id, epoch, committed_at, facts, frontier_logical_time) VALUES (7, 1, NOW(3), 3, 2000)"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_epoch (started_at, clean) VALUES (NOW(3), TRUE)"));

			// An effect is done exactly when it has a done time; a captured type names its stream family, an outbox type none.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_effect_outbox (application_id, projector, fact_id, kind, payload, state, created_at) VALUES (UNHEX('00'), 'ledger', 1, 'payout', '{}', 'DONE', NOW(3))"));
			statement.execute("INSERT INTO lc_effect_outbox (application_id, projector, fact_id, kind, payload, state, created_at) VALUES (UNHEX('00'), 'ledger', 1, 'payout', '{}', 'PENDING', NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, sensitivity, owner_module) VALUES (9, 'Ledger.Kill', 1, 'DURABLE', 'CAPTURED', 1, 'LEDGER')"), "a captured type without its stream family");

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
		return "INSERT INTO lc_fact (occurred_at, stream, epoch, stream_seq, type, schema_ver, logical_time, region_id, sensitivity, payload) VALUES ('" + at + "', '" + stream + "', 0, " + seq + ", 1, 1, 0, 12, 1, " + payload + ")";
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
