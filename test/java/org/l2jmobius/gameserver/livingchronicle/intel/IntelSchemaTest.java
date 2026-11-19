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
 * AT-INTEL-00 (docs/spec/intel-service.md): the migration applies; keys, checks and triggers reject what the spec forbids: an exact precision (no such value), a second item from one trace, evidence, an item, a grant or a copy outliving its parent, a grant finer than its item, any update (an expiry, a parent, a precision), a trace disclosable before it happened, a location-related publication type below the tactical delay.
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

			// Further requesters get grants of the same item, never finer or longer-lived than it; expiries never change.
			statement.execute(grant(item, "'CONTRACT', 7, 'hunt'", "'REGION'", "NOW(3) + INTERVAL 20 MINUTE"));
			statement.execute(grant(item, "'CHAR', 268477001, 'signal'", "'TERRITORY'", "NOW(3) + INTERVAL 10 MINUTE"));
			assertThrows(SQLException.class, () -> statement.execute(grant(item, "'CHAR', 268477002, 'hunt'", "'ZONE'", "NOW(3) + INTERVAL 10 MINUTE")), "a grant finer than its item");
			assertThrows(SQLException.class, () -> statement.execute(grant(item, "'CHAR', 268477003, 'hunt'", "'REGION'", "NOW(3) + INTERVAL 2 HOUR")), "a grant outliving its item");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_intel SET expires_at = expires_at + INTERVAL 1 HOUR WHERE intel_id = " + item));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_trace SET expires_at = expires_at - INTERVAL 10 MINUTE WHERE trace_id = 1"));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_intel_grant SET expires_at = expires_at + INTERVAL 1 MINUTE WHERE intel_id = " + item));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_intel_grant SET view_precision = 'ZONE' WHERE intel_id = " + item), "re-precisioning a grant");
			statement.execute(trace(5, "3", "NOW(3) + INTERVAL 5 MINUTE", "NOW(3) + INTERVAL 10 MINUTE"));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_intel SET trace_id = 5 WHERE intel_id = " + item), "reparenting an item to a shorter-lived trace");

			// A copy never outlives its item, nor depends on one that does not exist; one row per dependency.
			assertThrows(SQLException.class, () -> statement.execute(copy("42", item, "NOW(3) + INTERVAL 2 HOUR")));
			assertThrows(SQLException.class, () -> statement.execute(copy("42", 999999, "NOW(3) + INTERVAL 1 MINUTE")));
			statement.execute(copy("42", item, "NOW(3) + INTERVAL 20 MINUTE"));

			// The budget's ledger never records a disclosure that cost nothing.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_disclosure (target_char, witness_ref, at, units, implied_cells, expires_at) VALUES (268477000, 'gk.giran', NOW(3), 0, 12, NOW(3) + INTERVAL 1 HOUR)"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_disclosure (target_char, witness_ref, at, units, implied_cells, expires_at) VALUES (268477000, 'gk.giran', NOW(3), 1, 12, NOW(3) - INTERVAL 1 HOUR)"), "a ledger row that never expires after its disclosure");

			// Evidence expires exactly with its trace: neither later nor earlier.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_trace_evidence (trace_id, x, y, z, observer_x, observer_y, observed_ms, expires_at) VALUES (1, 1, 2, 3, 40, 50, 6000, NOW(3) + INTERVAL 2 HOUR)"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_trace_evidence (trace_id, x, y, z, observer_x, observer_y, observed_ms, expires_at) VALUES (1, 1, 2, 3, 40, 50, 6000, NOW(3) + INTERVAL 10 MINUTE)"));

			// Publication: a location-related type waits at least the tactical delay; fields belong to registered types.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pub_type (pub_type, min_delay_minutes, location_related) VALUES ('battle_map', 60, TRUE)"));
			statement.execute("INSERT INTO lc_pub_type (pub_type, min_delay_minutes, location_related) VALUES ('battle_map', 1440, TRUE)");
			statement.execute("INSERT INTO lc_pub_field_rule (pub_type, field, rule) VALUES ('battle_map', 'territory', 'AREA_TERRITORY')");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pub_field_rule (pub_type, field, rule) VALUES ('unknown', 'x', 'COUNT')"));

			// Deleting a trace takes its evidence, item and grants with it.
			statement.execute("INSERT INTO lc_trace_evidence (trace_id, x, y, z, observer_x, observer_y, observed_ms, expires_at) VALUES (1, 1, 2, 3, 40, 50, 6000, (SELECT expires_at FROM lc_trace WHERE trace_id = 1))");
			statement.execute("DELETE FROM lc_trace WHERE trace_id = 1");
			try (ResultSet result = statement.executeQuery("SELECT (SELECT COUNT(*) FROM lc_trace_evidence WHERE trace_id = 1) + (SELECT COUNT(*) FROM lc_intel WHERE trace_id = 1) + (SELECT COUNT(*) FROM lc_intel_grant WHERE intel_id = " + item + ")"))
			{
				result.next();
				assertEquals(0, result.getInt(1));
			}

			// The journal's expiring tables are in the registry too.
			try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM lc_expiring_table WHERE table_name IN ('lc_fact_precise', 'lc_fact_expiring', 'lc_trace', 'lc_trace_evidence', 'lc_intel', 'lc_intel_grant', 'lc_intel_copy', 'lc_disclosure', 'lc_disclosure_cell', 'lc_signal_presence')"))
			{
				result.next();
				assertEquals(10, result.getInt(1));
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

	private static String grant(long intelId, String audience, String precision, String expires)
	{
		return "INSERT INTO lc_intel_grant (intel_id, audience_kind, audience_id, purpose, granted_at, view_precision, expires_at) VALUES (" + intelId + ", " + audience + ", NOW(3), " + precision + ", " + expires + ")";
	}

	private static String copy(String rowKey, long intelId, String expires)
	{
		return "INSERT INTO lc_intel_copy (module, table_name, row_key, intel_id, expires_at) VALUES ('WAYFARER', 'mem_observation', '" + rowKey + "', " + intelId + ", " + expires + ")";
	}
}
