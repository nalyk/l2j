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
			final long seconds = Long.parseLong(bound);
			final LocalDate day = Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC).toLocalDate();
			assertEquals(1, day.getDayOfMonth(), "a boundary that is not a month's start: " + day);
			assertEquals(day.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), seconds, "a boundary that is not midnight UTC: " + bound);
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
			assertThrows(SQLException.class, () -> statement.execute(fact("2026-11-19 10:00:00", "region.12", 1, "'{}'").replace(", 12, 1, '{}', 1, 1, ", ", 12, 9, '{}', 1, 1, ")), "sensitivity out of range");
			// One retention watermark per family, never negative.
			statement.execute("INSERT INTO lc_journal_retention (family, max_dropped_fact_id) VALUES ('kill', 0)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_retention (family, max_dropped_fact_id) VALUES ('kill', 5)"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_retention (family, max_dropped_fact_id) VALUES ('asset', -1)"));

			// A replay lease starts valid and never reads below zero.
			statement.execute("INSERT INTO lc_replay_lease (projector, families, read_through_id, heartbeat_at) VALUES ('history', 'kill,siege', 0, NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_replay_lease (projector, families, read_through_id, heartbeat_at) VALUES ('history', 'kill', -1, NOW(3))"));
			statement.execute(fact("2026-11-19 10:00:00", "region.12", 1, "'{}'"));

			// One head per outbox stream, never negative; a staged fact has a positive number, one per stream and number.
			statement.execute("INSERT INTO lc_stream_head (stream, last_seq) VALUES ('pact.7', 1)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_stream_head (stream, last_seq) VALUES ('pact.7', 2)"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_stream_head (stream, last_seq) VALUES ('pact.8', -1)"));
			statement.execute(staged("pact.7", 1));
			assertThrows(SQLException.class, () -> statement.execute(staged("pact.7", 1)), "two staged facts with one number in a stream");
			assertThrows(SQLException.class, () -> statement.execute(staged("pact.7", 0)));

			// A QUEUE_FULL gap knows its count, a STALLED or RESTART one does not; its span runs forward; an acknowledgement names who and why together.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_gap (fact_id, epoch, family, refused, from_logical_time, to_logical_time, reason) VALUES (1, 1, 'kill', NULL, 0, 10, 'QUEUE_FULL')"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_gap (fact_id, epoch, family, refused, from_logical_time, to_logical_time, reason) VALUES (2, 1, 'kill', 3, 0, 10, 'RESTART')"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_gap (fact_id, epoch, family, refused, from_logical_time, to_logical_time, reason) VALUES (3, 1, 'kill', 3, 10, 0, 'QUEUE_FULL')"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_gap (fact_id, epoch, family, refused, from_logical_time, to_logical_time, reason, acknowledged_by) VALUES (4, 1, 'kill', 3, 0, 10, 'QUEUE_FULL', 'admin')"));
			statement.execute("INSERT INTO lc_journal_gap (fact_id, epoch, family, refused, from_logical_time, to_logical_time, reason) VALUES (5, 1, 'kill', NULL, 0, 10, 'STALLED')");

			// A batch id is committed once; ids in a batch run forward; a closed phase carries its frontier; a clean epoch has ended.
			statement.execute("INSERT INTO lc_journal_batch (batch_id, epoch, committed_at, first_id, last_id, relayed, captured, phase, frontier_logical_time) VALUES (7, 1, NOW(3), 10, 20, 5, 6, 3, 1000)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_batch (batch_id, epoch, committed_at, relayed, captured) VALUES (7, 1, NOW(3), 0, 0)"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_batch (batch_id, epoch, committed_at, first_id, last_id, relayed, captured) VALUES (8, 1, NOW(3), 20, 10, 0, 0)"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_batch (batch_id, epoch, committed_at, relayed, captured, phase) VALUES (9, 1, NOW(3), 0, 0, 4)"), "a closed phase without its frontier");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_journal_epoch (started_at, clean) VALUES (NOW(3), TRUE)"));

			// A telemetry cell is written once per window.
			statement.execute(bucket());
			assertThrows(SQLException.class, () -> statement.execute(bucket()));

			// An effect has one position per projector, and is done exactly when it has a done time.
			statement.execute("INSERT INTO lc_effect_outbox (application_id, projector, fact_id, effect_index, kind, payload, state, created_at) VALUES (UNHEX('01'), 'ledger', 1, 0, 'payout', '{}', 'PENDING', NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_effect_outbox (application_id, projector, fact_id, effect_index, kind, payload, state, created_at) VALUES (UNHEX('02'), 'ledger', 1, 0, 'payout', '{}', 'PENDING', NOW(3))"), "two effects at one position");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_effect_outbox (application_id, projector, fact_id, effect_index, kind, payload, state, created_at) VALUES (UNHEX('03'), 'ledger', 1, 1, 'payout', '{}', 'DONE', NOW(3))"));

			// Fact types have unique names and a positive schema version.
			statement.execute("INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, owner_module) VALUES (1, 'Test.UniqueName', 1, 'DURABLE', 'OUTBOX', 'asset', 1, 'ASSET')");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, owner_module) VALUES (2, 'Test.UniqueName', 1, 'DURABLE', 'OUTBOX', 'asset', 1, 'ASSET')"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_fact_type (type, name, schema_ver, durability, delivery, family, sensitivity, owner_module) VALUES (3, 'Test.OtherName', 0, 'DURABLE', 'OUTBOX', 'asset', 1, 'ASSET')"));
		}
	}

	private static String fact(String at, String stream, long seq, String payload)
	{
		return "INSERT INTO lc_fact (occurred_at, stream, stream_seq, type, schema_ver, logical_time, region_id, sensitivity, payload, batch_id, config_ver, captured_at) VALUES ('" + at + "', '" + stream + "', " + seq + ", 1, 1, 0, 12, 1, " + payload + ", 1, 1, '" + at + "')";
	}

	private static String staged(String stream, long seq)
	{
		return "INSERT INTO lc_fact_outbox (staged_at, stream, stream_seq, type, schema_ver, logical_time, region_id, sensitivity, payload, config_ver) VALUES (NOW(3), '" + stream + "', " + seq + ", 1, 1, 0, 12, 1, '{}', 1)";
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
