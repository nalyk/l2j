package org.l2jmobius.gameserver.livingchronicle.signal;

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
 * AT-SIG-00 (docs/spec/aden-signal.md): the migration applies; keys and checks refuse latent detection without opt-in, a schedule ending before it starts or expiring other than at its end, a self block, a session of one or of ten, a demand cell below the floor; the expiring tables are registered.
 */
class SignalSchemaTest
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
	void theKeysAndChecksHold() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_signal_profile (char_id, opted_in, latent, updated_at) VALUES (1, FALSE, TRUE, NOW(3))"), "latent detection without opt-in");
			statement.execute("INSERT INTO lc_signal_profile (char_id, opted_in, latent, languages, standing_objectives, updated_at) VALUES (1, TRUE, TRUE, 'RO,RU,EN', 'XP,SPOIL', NOW(3))");

			assertThrows(SQLException.class, () -> statement.execute(schedule("NOW(3) + INTERVAL 2 HOUR", "NOW(3) + INTERVAL 1 HOUR", "NOW(3) + INTERVAL 1 HOUR")), "ending before it starts");
			assertThrows(SQLException.class, () -> statement.execute(schedule("NOW(3) + INTERVAL 1 HOUR", "NOW(3) + INTERVAL 2 HOUR", "NOW(3) + INTERVAL 30 DAY")), "outliving its end");
			statement.execute(schedule("NOW(3) + INTERVAL 1 HOUR", "NOW(3) + INTERVAL 2 HOUR", "NOW(3) + INTERVAL 2 HOUR"));

			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_signal_block (char_id, blocked_char, at) VALUES (1, 1, NOW(3))"));

			assertThrows(SQLException.class, () -> statement.execute(session("'[1]'")), "a session of one");
			assertThrows(SQLException.class, () -> statement.execute(session("'[1,2,3,4,5,6,7,8,9,10]'")), "a session of ten");
			statement.execute(session("'[1,2,3]'"));

			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_signal_demand (window_start, territory_id, objective, role_sought, cohort, intents) VALUES (NOW(), 5, 'SPOIL', 'SPOILER', 'HUMAN', 2)"), "a cell below the floor");
			statement.execute("INSERT INTO lc_signal_demand (window_start, territory_id, objective, role_sought, cohort, intents) VALUES (NOW(), 5, 'SPOIL', 'SPOILER', 'HUMAN', 5)");

			try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM lc_expiring_table WHERE owner_module = 'SIGNAL'"))
			{
				result.next();
				assertEquals(3, result.getInt(1));
			}
		}
	}

	private static String schedule(String start, String end, String expires)
	{
		return "INSERT INTO lc_signal_schedule (char_id, objective, role_sought, region_pref, start_at, end_at, expires_at) VALUES (1, 'XP', 'HEALER,BUFFER', '[\"cruma\"]', " + start + ", " + end + ", " + expires + ")";
	}

	private static String session(String members)
	{
		return "INSERT INTO lc_signal_session (formed_at, members, cohort, objective, introduced_by, expires_at) VALUES (NOW(3), " + members + ", 'MIXED', 'XP', 'INTENT', NOW(3) + INTERVAL 30 DAY)";
	}
}
