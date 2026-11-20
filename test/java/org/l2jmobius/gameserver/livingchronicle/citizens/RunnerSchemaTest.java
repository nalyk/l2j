package org.l2jmobius.gameserver.livingchronicle.citizens;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * AT-CIT-00 (docs/spec/citizens.md): the runner's migration applies; keys and triggers refuse a second relation row per pair, a relation with oneself, an observation that is told, out-of-band or inferred without its source, an out-of-band message without observation references or from a non-member, a citizen returning after quitting, a changed decision; a runner user granted only the runner's tables is refused a game table.
 */
class RunnerSchemaTest
{
	private static TestDatabase _database;

	@BeforeAll
	static void migrate() throws Exception
	{
		_database = TestDatabase.start();
		try (Connection connection = _database.connect())
		{
			Migrations.apply(connection, Path.of(System.getProperty("lc.migrations.dir")).resolveSibling("runner").resolve("V001__cit_runner.sql"));
		}
	}

	@Test
	void theKeysAndChecksHold() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO cit_principal (principal_id, name, budget_policy, information_policy, goals) VALUES (1, 'Iron Covenant', '{}', '{}', '{}'), (2, 'Silver Wharf', '{}', '{}', '{}')");
			statement.execute("INSERT INTO cit_account (account_id, login, secret_ref, principal_id, created_at) VALUES (10, 'cit_0001', 'vault:cit_0001', 1, NOW(3)), (11, 'cit_0002', 'vault:cit_0002', 2, NOW(3))");
			statement.execute("INSERT INTO cit_character (char_id, account_id, name, class_id, persona) VALUES (268500001, 10, 'Brenna', 0, 'farmer'), (268500002, 11, 'Toma', 18, 'merchant')");

			statement.execute("INSERT INTO cit_relation (citizen_char_id, other_char_id, first_seen, last_seen, notes_tags) VALUES (268500001, 268477000, NOW(3), NOW(3), '[]')");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_relation (citizen_char_id, other_char_id, first_seen, last_seen, notes_tags) VALUES (268500001, 268477000, NOW(3), NOW(3), '[]')"), "a second relation row");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_relation (citizen_char_id, other_char_id, first_seen, last_seen, notes_tags) VALUES (268500001, 268500001, NOW(3), NOW(3), '[]')"), "a relation with oneself");

			statement.execute("INSERT INTO cit_observation (char_id, at, kind, subject_kind, subject_ref, payload, provenance) VALUES (268500001, NOW(3), 'saw', 'PLAYER', 268477000, '{}', 'SEEN')");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_observation (char_id, at, kind, subject_kind, subject_ref, payload, provenance) VALUES (268500001, NOW(3), 'killed_by', 'PLAYER', 268477000, '{}', 'INFERRED')"), "an inference without its evidence");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_observation (char_id, at, kind, subject_kind, subject_ref, payload, provenance, source_ref) VALUES (268500001, NOW(3), 'saw', 'PLAYER', 268477000, '{}', 'SEEN', 5)"), "a seen observation claiming a source");

			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_oob_message (principal_id, from_char, channel, at, text, observation_refs) VALUES (1, 268500001, 'clan', NOW(3), 'coal wanted in Giran', '[]')"), "a message without observations");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_oob_message (principal_id, from_char, channel, at, text, observation_refs) VALUES (1, 268500002, 'clan', NOW(3), 'coal wanted in Giran', '[1]')"), "a non-member writing to another house's channel");
			statement.execute("INSERT INTO cit_oob_message (principal_id, from_char, channel, at, text, observation_refs) VALUES (1, 268500001, 'clan', NOW(3), 'coal wanted in Giran', '[1]')");

			statement.execute("UPDATE cit_character SET state = 'QUIT' WHERE char_id = 268500002");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_character SET state = 'ACTIVE' WHERE char_id = 268500002"), "returning after quitting");

			statement.execute("INSERT INTO cit_decision_log (char_id, at, policy, policy_ver, inputs, choice, rng_key) VALUES (268500001, NOW(3), 'farm_spot', 1, '[1]', '{}', UNHEX('00112233445566778899AABBCCDDEEFF'))");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_decision_log SET choice = '{\"x\":1}'"), "rewriting a decision");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_decision_log (char_id, at, policy, policy_ver, inputs, choice, rng_key) VALUES (268500001, NOW(3), 'farm_spot', 1, '{}', '{}', UNHEX('00112233445566778899AABBCCDDEEFF'))"), "inputs that are not a list of observations");
		}
	}

	@Test
	void theRunnerReadsNoGameTable() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("CREATE USER IF NOT EXISTS 'runner'@'%' IDENTIFIED BY 'pw'");
			for (String table : new String[] { "cit_principal", "cit_account", "cit_character", "cit_persona_state", "cit_schedule", "cit_observation", "cit_relation", "cit_oob_message", "cit_decision_log" })
			{
				statement.execute("GRANT SELECT, INSERT, UPDATE ON " + table + " TO 'runner'@'%'");
			}
		}
		try (Connection runner = _database.connectAs("runner", "pw");
			Statement statement = runner.createStatement())
		{
			statement.executeQuery("SELECT COUNT(*) FROM cit_relation").close();
			for (String table : new String[] { "characters", "account_gsdata", "items" })
			{
				assertThrows(SQLException.class, () -> statement.executeQuery("SELECT COUNT(*) FROM " + table).close(), "the runner reading " + table);
			}
		}
	}
}
