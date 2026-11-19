package org.l2jmobius.gameserver.livingchronicle.intel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * AT-INTEL-00 (docs/spec/intel-service.md): the migration applies; keys, checks and triggers reject what the spec forbids: an exact precision (no such value), a second item from one trace, an item outliving its trace, a copy outliving its item, a trace disclosable before it happened.
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
	void theKeysChecksAndTriggersRejectWhatTheSpecForbids() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			// A trace is disclosable no earlier than it happened, lives past that, points one of eight ways.
			statement.execute(trace(1, "3", "NOW(3) + INTERVAL 5 MINUTE", "NOW(3) + INTERVAL 1 HOUR"));
			assertThrows(SQLException.class, () -> statement.execute(trace(2, "3", "NOW(3) - INTERVAL 5 MINUTE", "NOW(3) + INTERVAL 1 HOUR")), "disclosable before it happened");
			assertThrows(SQLException.class, () -> statement.execute(trace(3, "8", "NOW(3) + INTERVAL 5 MINUTE", "NOW(3) + INTERVAL 1 HOUR")), "a ninth direction");
			assertThrows(SQLException.class, () -> statement.execute(trace(4, "3", "NOW(3) + INTERVAL 5 MINUTE", "NOW(3) + INTERVAL 1 MINUTE")), "expiring before it is disclosable");

			// Intel is never exact; one item per trace; an item never outlives its trace.
			assertThrows(SQLException.class, () -> statement.execute(intel(1, "'EXACT_INTERNAL'", "NOW(3) + INTERVAL 30 MINUTE")));
			assertThrows(SQLException.class, () -> statement.execute(intel(1, "'REGION'", "NOW(3) + INTERVAL 2 HOUR")), "an item outliving its trace");
			statement.execute(intel(1, "'REGION'", "NOW(3) + INTERVAL 30 MINUTE"));
			assertThrows(SQLException.class, () -> statement.execute(intel(1, "'TERRITORY'", "NOW(3) + INTERVAL 30 MINUTE")), "a second item from one trace");
			final long item;
			try (ResultSet result = statement.executeQuery("SELECT intel_id FROM lc_intel WHERE trace_id = 1"))
			{
				result.next();
				item = result.getLong(1);
			}

			// Further requesters get grants of the same item.
			statement.execute("INSERT INTO lc_intel_grant (intel_id, audience_kind, audience_id, purpose, granted_at) VALUES (" + item + ", 'CONTRACT', 7, 'hunt', NOW(3)), (" + item + ", 'CHAR', 268477001, 'hunt', NOW(3))");

			// A copy never outlives its item, nor depends on one that does not exist; one row per dependency.
			assertThrows(SQLException.class, () -> statement.execute(copy("42", item, "NOW(3) + INTERVAL 2 HOUR")));
			assertThrows(SQLException.class, () -> statement.execute(copy("42", 999999, "NOW(3) + INTERVAL 1 MINUTE")));
			statement.execute(copy("42", item, "NOW(3) + INTERVAL 20 MINUTE"));

			// The budget's ledger never records a disclosure that cost nothing.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_disclosure (target_char, witness_ref, at, units, area_id) VALUES (268477000, 'gk.giran', NOW(3), 0, 12)"));

			// Deleting a trace takes its evidence, item and grants with it.
			statement.execute("INSERT INTO lc_trace_evidence (trace_id, x, y, z, observed_ms, expires_at) VALUES (1, 1, 2, 3, 6000, NOW(3) + INTERVAL 1 HOUR)");
			statement.execute("DELETE FROM lc_trace WHERE trace_id = 1");
			try (ResultSet result = statement.executeQuery("SELECT (SELECT COUNT(*) FROM lc_trace_evidence WHERE trace_id = 1) + (SELECT COUNT(*) FROM lc_intel WHERE trace_id = 1) + (SELECT COUNT(*) FROM lc_intel_grant WHERE intel_id = " + item + ")"))
			{
				result.next();
				assertEquals(0, result.getInt(1));
			}

			// The journal's expiring tables are in the registry too.
			try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM lc_expiring_table WHERE table_name IN ('lc_fact_precise', 'lc_fact_expiring', 'lc_trace', 'lc_trace_evidence', 'lc_intel', 'lc_intel_copy', 'lc_signal_presence')"))
			{
				result.next();
				assertEquals(7, result.getInt(1));
			}
		}
	}

	private static String trace(int id, String direction, String disclosable, String expires)
	{
		return "INSERT INTO lc_trace (trace_id, subject_char, kind, witness_ref, region_id, coarse_dir, occurred_at, disclosable_at, expires_at, sensitivity) VALUES (" + id + ", 268477000, 'SIGHTING', 'gk.giran', 12, " + direction + ", NOW(3), " + disclosable + ", " + expires + ", 2)";
	}

	private static String intel(long trace, String precision, String expires)
	{
		return "INSERT INTO lc_intel (trace_id, kind, subject_char, precision_level, confidence, payload_type, payload, age_band, created_at, expires_at, shareable, publishable) VALUES (" + trace + ", 'SIGHTING', 268477000, " + precision + ", 'WITNESS', 'RegionSighting', '{}', 'FRESH', NOW(3), " + expires + ", TRUE, FALSE)";
	}

	private static String copy(String rowKey, long intelId, String expires)
	{
		return "INSERT INTO lc_intel_copy (module, table_name, row_key, intel_id, expires_at) VALUES ('WAYFARER', 'mem_observation', '" + rowKey + "', " + intelId + ", " + expires + ")";
	}
}
