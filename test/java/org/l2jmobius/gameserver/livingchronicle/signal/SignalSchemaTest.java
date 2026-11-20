package org.l2jmobius.gameserver.livingchronicle.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * AT-SIG-00 and part of AT-SIG-03 (docs/spec/aden-signal.md): the migration applies; keys and checks refuse latent detection without opt-in, a schedule ending before it starts or expiring other than at its end, a self block, a member twice, a second session for one introduction, a demand cell outside its bands; the expiring tables are registered; every PlayerClass has a row in the class-role table.
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
			statement.execute("INSERT INTO lc_signal_block (char_id, blocked_char, anonymous, at) VALUES (1, 2, TRUE, NOW(3))");

			statement.execute(session(1, 77));
			assertThrows(SQLException.class, () -> statement.execute(session(2, 77)), "a second session for one introduction");
			statement.execute("INSERT INTO lc_signal_session_member (session_id, char_id, origin) VALUES (1, 10, 'HUMAN'), (1, 11, 'SYNTHETIC_CITIZEN')");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_signal_session_member (session_id, char_id, origin) VALUES (1, 10, 'HUMAN')"), "a member twice");

			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_signal_demand (day, territory_id, objective, intents_band) VALUES (CURDATE(), 5, 'SPOIL', '4')"), "a band below five");
			statement.execute("INSERT INTO lc_signal_demand (day, territory_id, objective, intents_band) VALUES (CURDATE(), 5, 'SPOIL', '5-9')");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_signal_demand (day, territory_id, objective, intents_band) VALUES (CURDATE(), 5, 'SPOIL', '10-19')"), "a second cell for one day, territory and objective");

			try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM lc_expiring_table WHERE owner_module = 'SIGNAL'"))
			{
				result.next();
				assertEquals(3, result.getInt(1));
			}
		}
	}

	@Test
	void everyClassHasRoles() throws Exception
	{
		final Path table = Path.of(System.getProperty("lc.docs.dir", "docs"), "spec", "signal-class-roles.csv");
		final List<String> lines = Files.readAllLines(table);
		final Set<Integer> ids = new HashSet<>();
		for (String line : lines.subList(1, lines.size()))
		{
			final String[] cells = line.split(",");
			assertTrue(!cells[4].isEmpty(), line + " has no role");
			ids.add(Integer.parseInt(cells[0]));
		}
		for (PlayerClass playerClass : PlayerClass.values())
		{
			assertTrue(ids.contains(playerClass.getId()), playerClass + " is missing from the class-role table");
		}
	}

	private static String schedule(String start, String end, String expires)
	{
		return "INSERT INTO lc_signal_schedule (char_id, objective, role_sought, region_pref, start_at, end_at, expires_at) VALUES (1, 'XP', 'HEALER,BUFFER', '[\"cruma\"]', " + start + ", " + end + ", " + expires + ")";
	}

	private static String session(int id, long introduction)
	{
		return "INSERT INTO lc_signal_session (session_id, introduction_id, formed_at, cohort, objective, introduced_by, expires_at) VALUES (" + id + ", " + introduction + ", NOW(3), 'MIXED', 'XP', 'INTENT', NOW(3) + INTERVAL 30 DAY)";
	}
}
