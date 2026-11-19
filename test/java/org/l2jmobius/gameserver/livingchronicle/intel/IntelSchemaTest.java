package org.l2jmobius.gameserver.livingchronicle.intel;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * AT-INTEL-00 (docs/spec/intel-service.md): the migration applies; keys and checks reject what the spec forbids, above all an exact precision, which does not exist as a value.
 */
class IntelSchemaTest
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
	void theKeysAndChecksRejectWhatTheSpecForbids() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			// A trace lives forward in time, points one of eight ways, and a sighting names its witness.
			statement.execute(trace("'SIGHTING'", "'gk.giran'", "3", "NOW(3) + INTERVAL 15 MINUTE"));
			assertThrows(SQLException.class, () -> statement.execute(trace("'SIGHTING'", "NULL", "3", "NOW(3) + INTERVAL 15 MINUTE")), "a sighting without its witness");
			assertThrows(SQLException.class, () -> statement.execute(trace("'GATEKEEPER'", "'gk.giran'", "8", "NOW(3) + INTERVAL 15 MINUTE")), "a ninth direction");
			assertThrows(SQLException.class, () -> statement.execute(trace("'GATEKEEPER'", "'gk.giran'", "3", "NOW(3) - INTERVAL 1 MINUTE")), "a trace that expired before it happened");

			// Intel is never exact: that precision does not exist as a value.
			statement.execute(intel("'REGION'"));
			assertThrows(SQLException.class, () -> statement.execute(intel("'EXACT_INTERNAL'")));

			// The budget is one row per target, witness and window, whoever asked; it never goes negative.
			statement.execute("INSERT INTO lc_disclosure (target_char, witness_ref, window_start, units) VALUES (268477000, 'gk.giran', '2026-11-19 10:00:00', 3)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_disclosure (target_char, witness_ref, window_start, units) VALUES (268477000, 'gk.giran', '2026-11-19 10:00:00', 1)"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_disclosure (target_char, witness_ref, window_start, units) VALUES (268477001, 'gk.giran', '2026-11-19 10:00:00', -1)"));

			// A module row holding intel is registered once.
			statement.execute("INSERT INTO lc_intel_copy (module, table_name, row_key, intel_id, expires_at) VALUES ('WAYFARER', 'mem_observation', '42', 1, NOW(3) + INTERVAL 1 HOUR)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_intel_copy (module, table_name, row_key, intel_id, expires_at) VALUES ('WAYFARER', 'mem_observation', '42', 2, NOW(3) + INTERVAL 1 HOUR)"));
		}
	}

	private static String trace(String kind, String witness, String direction, String expires)
	{
		return "INSERT INTO lc_trace (subject_char, kind, witness_ref, region_id, coarse_dir, occurred_at, expires_at, sensitivity, source_fact_id) VALUES (268477000, " + kind + ", " + witness + ", 12, " + direction + ", NOW(3), " + expires + ", 2, 1)";
	}

	private static String intel(String precision)
	{
		return "INSERT INTO lc_intel (kind, subject_char, precision_level, confidence, payload, audience_kind, audience_id, purpose, created_at, expires_at, shareable, publishable, dedup_key) VALUES ('SIGHTING', 268477000, " + precision + ", 'WITNESS', '{}', 'CONTRACT', 7, 'hunt', NOW(3), NOW(3) + INTERVAL 15 MINUTE, TRUE, FALSE, UNHEX('00'))";
	}
}
