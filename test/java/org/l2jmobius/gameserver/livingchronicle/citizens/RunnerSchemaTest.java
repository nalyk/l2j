package org.l2jmobius.gameserver.livingchronicle.citizens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * AT-CIT-00 (docs/spec/citizens.md): the runner's migration applies to its own schema, never the game's; keys and triggers refuse a second relation row per pair, a relation with oneself, a stored sentiment other than the function of the counters, two characters of one account online, an observation that is told, out-of-band or inferred without a valid source of its own citizen (or of its principal's channel) at or before it, a changed observation, an out-of-band message without references, from a non-member, citing another citizen's, a missing or a later observation, or changed afterwards, a decision citing another citizen's, a missing or a later observation or another principal's governor cap, a changed decision, a governor cap without a day's lag, a login guard whose failures and credentials in flight could reach the ban, a failure count reset other than by a solo credential resolving or an admin clearing a halt with nothing in flight, a participation slot above the limit or for another principal's character, a principal change without a newer identity version or while a character is online or holds a participation, a citizen returning after quitting; the runner's user, with the grants of §2, is refused every game table and every write it is not granted, and the game's user is refused every runner table.
 */
class RunnerSchemaTest
{
	private static final String RUNNER_SCHEMA = "lc_runner";

	private static final Set<String> RUNNER_TABLES = Set.of("cit_principal", "cit_account", "cit_character", "cit_persona_state", "cit_schedule", "cit_observation", "cit_observation_source", "cit_relation", "cit_oob_message", "cit_decision_log", "cit_governor_cap", "cit_login_guard", "cit_participation", "cit_participation_limit");

	/** The runner user's grants, exactly as citizens.md §2 lists them. */
	private static final String[] RUNNER_GRANTS =
	{
		"GRANT SELECT ON lc_runner.* TO 'runner'@'%'",
		"GRANT INSERT, DELETE ON lc_runner.cit_observation TO 'runner'@'%'",
		"GRANT INSERT, DELETE ON lc_runner.cit_observation_source TO 'runner'@'%'",
		"GRANT INSERT, DELETE ON lc_runner.cit_oob_message TO 'runner'@'%'",
		"GRANT INSERT, DELETE ON lc_runner.cit_decision_log TO 'runner'@'%'",
		"GRANT INSERT ON lc_runner.cit_governor_cap TO 'runner'@'%'",
		"GRANT INSERT, UPDATE ON lc_runner.cit_principal TO 'runner'@'%'",
		"GRANT INSERT, UPDATE ON lc_runner.cit_account TO 'runner'@'%'",
		"GRANT INSERT, UPDATE ON lc_runner.cit_character TO 'runner'@'%'",
		"GRANT INSERT, UPDATE, DELETE ON lc_runner.cit_persona_state TO 'runner'@'%'",
		"GRANT INSERT, UPDATE, DELETE ON lc_runner.cit_schedule TO 'runner'@'%'",
		"GRANT INSERT, UPDATE, DELETE ON lc_runner.cit_relation TO 'runner'@'%'",
		"GRANT INSERT, UPDATE, DELETE ON lc_runner.cit_participation TO 'runner'@'%'",
		"GRANT INSERT, UPDATE ON lc_runner.cit_login_guard TO 'runner'@'%'",
		"GRANT INSERT, UPDATE ON lc_runner.cit_participation_limit TO 'runner'@'%'",
	};

	private static TestDatabase _database;

	@BeforeAll
	static void migrate() throws Exception
	{
		_database = TestDatabase.start();
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("CREATE DATABASE " + RUNNER_SCHEMA + " CHARACTER SET utf8mb4");
			connection.setCatalog(RUNNER_SCHEMA);
			Migrations.apply(connection, Path.of(System.getProperty("lc.migrations.dir")).resolveSibling("runner").resolve("V001__cit_runner.sql"));
		}
	}

	private static Connection connect(String schema, String user, String password) throws SQLException
	{
		return DriverManager.getConnection("jdbc:mysql://" + _database.host() + ":" + _database.port() + "/" + schema + "?useSSL=false&allowPublicKeyRetrieval=true", user, password);
	}

	private static long lastId(Statement statement) throws SQLException
	{
		try (ResultSet result = statement.executeQuery("SELECT LAST_INSERT_ID()"))
		{
			result.next();
			return result.getLong(1);
		}
	}

	@Test
	void theKeysAndChecksHold() throws SQLException
	{
		try (Connection connection = connect(RUNNER_SCHEMA, "root", "");
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO cit_principal (principal_id, name, budget_policy, information_policy, goals) VALUES (1, 'Iron Covenant', '{}', '{}', '{}'), (2, 'Silver Wharf', '{}', '{}', '{}')");
			statement.execute("INSERT INTO cit_account (account_id, login, secret_ref, principal_id, identity_ver, created_at) VALUES (10, 'cit_0001', 'vault:cit_0001', 1, 1, NOW(3)), (11, 'cit_0002', 'vault:cit_0002', 2, 1, NOW(3)), (12, 'cit_0003', 'vault:cit_0003', 1, 1, NOW(3))");
			statement.execute("INSERT INTO cit_character (char_id, account_id, name, class_id, persona) VALUES (268500001, 10, 'Brenna', 0, 'farmer'), (268500002, 11, 'Toma', 18, 'merchant'), (268500003, 10, 'Brenna2', 10, 'crafter')");

			// Relations: one row per pair, never with oneself, sentiment the function of the counters.
			statement.execute("INSERT INTO cit_relation (citizen_char_id, other_char_id, first_seen, last_seen, trades, killed_by, notes_tags) VALUES (268500001, 268477000, NOW(3), NOW(3), 2, 1, '[]')");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_relation (citizen_char_id, other_char_id, first_seen, last_seen, notes_tags) VALUES (268500001, 268477000, NOW(3), NOW(3), '[]')"), "a second relation row");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_relation (citizen_char_id, other_char_id, first_seen, last_seen, notes_tags) VALUES (268500001, 268500001, NOW(3), NOW(3), '[]')"), "a relation with oneself");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_relation SET sentiment = 500 WHERE citizen_char_id = 268500001"), "a sentiment written by hand");
			try (ResultSet result = statement.executeQuery("SELECT sentiment FROM cit_relation WHERE citizen_char_id = 268500001 AND other_char_id = 268477000"))
			{
				result.next();
				assertEquals((2 * 30) - 250, result.getInt(1), "sentiment is the function of the counters");
			}

			// One character of an account online at a time.
			statement.execute("UPDATE cit_character SET online = TRUE WHERE char_id = 268500001");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_character SET online = TRUE WHERE char_id = 268500003"), "a second character of one account online");

			// Observations: provenance with a valid source, never changed.
			statement.execute("INSERT INTO cit_observation (char_id, at, kind, subject_kind, subject_ref, payload, provenance) VALUES (268500001, '2026-01-01 10:00:00', 'attacked_by', 'PLAYER', 268477000, '{}', 'SEEN')");
			final long seen = lastId(statement);
			statement.execute("INSERT INTO cit_observation (char_id, at, kind, subject_kind, subject_ref, payload, provenance) VALUES (268500002, '2026-01-01 10:00:00', 'saw', 'PLAYER', 268477000, '{}', 'SEEN')");
			final long othersSeen = lastId(statement);
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_observation (char_id, at, kind, subject_kind, subject_ref, payload, provenance) VALUES (268500001, '2026-01-01 10:01:00', 'killed_by', 'PLAYER', 268477000, '{}', 'INFERRED')"), "an inference without its evidence");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_observation (char_id, at, kind, subject_kind, subject_ref, payload, provenance, source_ref) VALUES (268500001, '2026-01-01 10:01:00', 'killed_by', 'PLAYER', 268477000, '{}', 'INFERRED', " + othersSeen + ")"), "an inference from another citizen's observation");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_observation (char_id, at, kind, subject_kind, subject_ref, payload, provenance, source_ref) VALUES (268500001, '2026-01-01 09:59:00', 'killed_by', 'PLAYER', 268477000, '{}', 'INFERRED', " + seen + ")"), "an inference from a later observation");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_observation (char_id, at, kind, subject_kind, subject_ref, payload, provenance, source_ref) VALUES (268500001, '2026-01-01 10:01:00', 'killed_by', 'PLAYER', 268477000, '{}', 'INFERRED', 999999)"), "an inference from no observation");
			statement.execute("INSERT INTO cit_observation (char_id, at, kind, subject_kind, subject_ref, payload, provenance, source_ref) VALUES (268500001, '2026-01-01 10:01:00', 'killed_by', 'PLAYER', 268477000, '{}', 'INFERRED', " + seen + ")");
			final long inferred = lastId(statement);
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_observation (char_id, at, kind, subject_kind, subject_ref, payload, provenance, source_ref) VALUES (268500001, NOW(3), 'saw', 'PLAYER', 268477000, '{}', 'SEEN', 5)"), "a seen observation claiming a source");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_observation (char_id, at, kind, subject_kind, subject_ref, payload, provenance, source_ref, tier) VALUES (268500001, NOW(3), 'told', 'PLAYER', 268477000, '{}', 'TOLD', 268477000, 'SUMMARY')"), "a summary of told observations");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_observation SET payload = '{\"x\":1}' WHERE obs_id = " + seen), "a changed observation");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_observation_source (obs_id, source_obs_id) VALUES (" + inferred + ", " + othersSeen + ")"), "evidence of another citizen");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_observation_source (obs_id, source_obs_id) VALUES (" + seen + ", " + seen + ")"), "a source of a raw observation");
			statement.execute("INSERT INTO cit_observation_source (obs_id, source_obs_id) VALUES (" + inferred + ", " + seen + ")");

			// The out-of-band channel: members only, each reference an earlier observation of the writer's own, never changed.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_oob_message (principal_id, from_char, channel, at, template_id, args, text, observation_refs) VALUES (1, 268500001, 'clan', '2026-01-01 10:05:00', 'killed_by', '{}', 'Krell killed me', '[]')"), "a message without observations");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_oob_message (principal_id, from_char, channel, at, template_id, args, text, observation_refs) VALUES (1, 268500002, 'clan', '2026-01-01 10:05:00', 'killed_by', '{}', 'Krell killed me', '[" + othersSeen + "]')"), "a non-member writing to another house's channel");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_oob_message (principal_id, from_char, channel, at, template_id, args, text, observation_refs) VALUES (1, 268500001, 'clan', '2026-01-01 10:05:00', 'killed_by', '{}', 'Krell killed me', '[" + othersSeen + "]')"), "a message citing another citizen's observation");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_oob_message (principal_id, from_char, channel, at, template_id, args, text, observation_refs) VALUES (1, 268500001, 'clan', '2026-01-01 10:05:00', 'killed_by', '{}', 'Krell killed me', '[999999]')"), "a message citing no observation");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_oob_message (principal_id, from_char, channel, at, template_id, args, text, observation_refs) VALUES (1, 268500001, 'clan', '2026-01-01 09:00:00', 'killed_by', '{}', 'Krell killed me', '[" + seen + "]')"), "a message citing a later observation");
			statement.execute("INSERT INTO cit_oob_message (principal_id, from_char, channel, at, template_id, args, text, observation_refs) VALUES (1, 268500001, 'clan', '2026-01-01 10:05:00', 'killed_by', '{}', 'Krell killed me', '[" + seen + "]')");
			final long message = lastId(statement);
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_oob_message SET principal_id = 2, from_char = 268500002 WHERE msg_id = " + message), "a message reassigned to another house");

			// Reading the channel: only a member of the message's principal, after it was written.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_observation (char_id, at, kind, subject_kind, subject_ref, payload, provenance, source_ref) VALUES (268500002, '2026-01-01 10:06:00', 'oob', 'MESSAGE', " + message + ", '{}', 'OOB', " + message + ")"), "another house reading the channel");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_observation (char_id, at, kind, subject_kind, subject_ref, payload, provenance, source_ref) VALUES (268500003, '2026-01-01 10:04:00', 'oob', 'MESSAGE', " + message + ", '{}', 'OOB', " + message + ")"), "reading a message before it was written");
			statement.execute("INSERT INTO cit_observation (char_id, at, kind, subject_kind, subject_ref, payload, provenance, source_ref) VALUES (268500003, '2026-01-01 10:06:00', 'oob', 'MESSAGE', " + message + ", '{}', 'OOB', " + message + ")");

			// Governor caps: at least a day after the telemetry they come from.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_governor_cap (principal_id, kind, region_id, family, value, window_end, valid_from, imported_by) VALUES (1, 'EXTRACTION', 4, 2, 30, '2026-01-01 00:00:00', '2026-01-01 12:00:00', 'admin')"), "a cap without a day's lag");
			statement.execute("INSERT INTO cit_governor_cap (principal_id, kind, region_id, family, value, window_end, valid_from, imported_by) VALUES (2, 'EXTRACTION', 4, 2, 30, '2025-12-30 00:00:00', '2025-12-31 00:00:00', 'admin')");
			final long othersCap = lastId(statement);
			statement.execute("INSERT INTO cit_governor_cap (principal_id, kind, region_id, family, value, window_end, valid_from, imported_by) VALUES (1, 'EXTRACTION', 4, 2, 30, '2025-12-30 00:00:00', '2025-12-31 00:00:00', 'admin')");
			final long cap = lastId(statement);
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_governor_cap SET value = 99 WHERE cap_id = " + cap), "a changed cap");

			// Decisions: own, earlier observations; own principal's caps; never changed.
			final String decision = "INSERT INTO cit_decision_log (char_id, at, policy, policy_ver, config_ver, inputs, constraints, persona_state, input_snapshot, snapshot_sha256, choice, rng_key) VALUES (268500001, '2026-01-01 10:10:00', 'farm_spot', 1, 1, '%s', '%s', '{}', '[]', UNHEX(SHA2('[]', 256)), '{}', UNHEX('00112233445566778899AABBCCDDEEFF'))";
			assertThrows(SQLException.class, () -> statement.execute(String.format(decision, "[" + othersSeen + "]", "[]")), "a decision reading another citizen's observation");
			assertThrows(SQLException.class, () -> statement.execute(String.format(decision, "[999999]", "[]")), "a decision reading no observation");
			assertThrows(SQLException.class, () -> statement.execute(String.format(decision.replace("'2026-01-01 10:10:00'", "'2026-01-01 09:00:00'"), "[" + seen + "]", "[]")), "a decision reading a later observation");
			assertThrows(SQLException.class, () -> statement.execute(String.format(decision, "[" + seen + "]", "[" + othersCap + "]")), "a decision constrained by another house's cap");
			assertThrows(SQLException.class, () -> statement.execute(String.format(decision, "{}", "[]")), "inputs that are not a list of observations");
			statement.execute(String.format(decision, "[" + seen + "]", "[" + cap + "]"));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_decision_log SET choice = '{\"x\":1}'"), "rewriting a decision");

			// The login guard: failures and credentials in flight stay below the ban; failures reset only by a solo success.
			statement.execute("INSERT INTO cit_login_guard (guard_id, ban_threshold, halt_at, failures, in_flight) VALUES (1, 5, 2, 0, 0)");
			statement.execute("UPDATE cit_login_guard SET failures = 1, in_flight = 3");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_login_guard SET in_flight = 4"), "credentials in flight that could reach the ban");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_login_guard SET failures = 0, in_flight = 2"), "failures reset while other credentials are in flight");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_login_guard (guard_id, ban_threshold, halt_at, failures, in_flight) VALUES (2, 5, 2, 0, 0)"), "a second guard");
			statement.execute("UPDATE cit_login_guard SET failures = 4, in_flight = 0");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_login_guard SET failures = 0"), "failures reset with nothing resolved and no halt cleared");
			statement.execute("UPDATE cit_login_guard SET halted_at = NOW(3)");
			statement.execute("UPDATE cit_login_guard SET failures = 0, halted_at = NULL");

			// Participation: slots up to the limit, a character of the slot's principal only.
			statement.execute("INSERT INTO cit_participation_limit (kind, max_slots) VALUES ('OLYMPIAD', 1)");
			statement.execute("INSERT INTO cit_participation (principal_id, kind, slot, char_id, state, since) VALUES (1, 'OLYMPIAD', 1, 268500001, 'RESERVED', NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_participation (principal_id, kind, slot, char_id, state, since) VALUES (1, 'OLYMPIAD', 2, 268500003, 'RESERVED', NOW(3))"), "a slot above the limit");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_participation (principal_id, kind, slot, char_id, state, since) VALUES (1, 'OLYMPIAD', 1, 268500003, 'RESERVED', NOW(3))"), "a slot taken twice");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO cit_participation (principal_id, kind, slot, char_id, state, since) VALUES (2, 'EVENT', 1, 268500001, 'RESERVED', NOW(3))"), "a slot of another principal");
			statement.execute("UPDATE cit_participation SET state = 'REGISTERED' WHERE char_id = 268500001");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_participation SET state = 'RESERVED' WHERE char_id = 268500001"), "a registration going back");

			// The identity mirror: a principal change needs a newer version, no character online and no participation.
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_account SET principal_id = 2 WHERE account_id = 12"), "a principal change without a newer identity version");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_account SET principal_id = 2, identity_ver = 2 WHERE account_id = 10"), "a principal change while a character is online");
			statement.execute("UPDATE cit_character SET online = FALSE WHERE char_id = 268500001");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_account SET principal_id = 2, identity_ver = 2 WHERE account_id = 10"), "a principal change while a participation is held");
			statement.execute("UPDATE cit_account SET principal_id = 2, identity_ver = 2 WHERE account_id = 12");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_account SET identity_ver = 1 WHERE account_id = 12"), "an identity version going back");

			// Quitting is final.
			statement.execute("UPDATE cit_character SET state = 'QUIT' WHERE char_id = 268500002");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE cit_character SET state = 'ACTIVE' WHERE char_id = 268500002"), "returning after quitting");
		}
	}

	@Test
	void theSchemasAreMutuallyIsolated() throws SQLException
	{
		final List<String> gameTables = new ArrayList<>();
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			final Set<String> runnerTables = new TreeSet<>();
			try (ResultSet result = statement.executeQuery("SELECT table_schema, table_name FROM information_schema.tables WHERE table_name LIKE 'cit\\_%' OR table_schema = '" + RUNNER_SCHEMA + "'"))
			{
				while (result.next())
				{
					assertEquals(RUNNER_SCHEMA, result.getString(1), "a runner table outside the runner's schema: " + result.getString(2));
					runnerTables.add(result.getString(2));
				}
			}
			assertEquals(new TreeSet<>(RUNNER_TABLES), runnerTables, "the runner's schema holds exactly the runner's tables");

			try (ResultSet result = statement.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + TestDatabase.SCHEMA + "' AND table_type = 'BASE TABLE'"))
			{
				while (result.next())
				{
					gameTables.add(result.getString(1));
				}
			}
			assertTrue(gameTables.size() > 50, "the stock game schema is installed");

			statement.execute("CREATE USER IF NOT EXISTS 'runner'@'%' IDENTIFIED BY 'pw'");
			for (String grant : RUNNER_GRANTS)
			{
				statement.execute(grant);
			}
			// The game's user (platform.md §4.3): everything of the game's schema, nothing else.
			statement.execute("CREATE USER IF NOT EXISTS 'game'@'%' IDENTIFIED BY 'pw'");
			statement.execute("GRANT ALL ON " + TestDatabase.SCHEMA + ".* TO 'game'@'%'");
		}

		try (Connection runner = connect(RUNNER_SCHEMA, "runner", "pw");
			Statement statement = runner.createStatement())
		{
			statement.executeQuery("SELECT COUNT(*) FROM cit_relation").close();
			for (String table : gameTables)
			{
				assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 1 FROM " + TestDatabase.SCHEMA + "." + table + " LIMIT 1").close(), "the runner reading " + table);
				assertThrows(SQLException.class, () -> statement.execute("INSERT INTO " + TestDatabase.SCHEMA + "." + table + " () VALUES ()"), "the runner writing " + table);
			}
			// The append-only tables: no update grant (their triggers refuse an update by anyone as well).
			for (String[] tableAndColumn : new String[][] { { "cit_observation", "char_id" }, { "cit_observation_source", "obs_id" }, { "cit_oob_message", "from_char" }, { "cit_decision_log", "char_id" }, { "cit_governor_cap", "principal_id" } })
			{
				assertThrows(SQLException.class, () -> statement.execute("UPDATE " + tableAndColumn[0] + " SET " + tableAndColumn[1] + " = " + tableAndColumn[1]), "the runner granted an update of " + tableAndColumn[0]);
			}
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM cit_account"), "the runner deleting an account");
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM cit_governor_cap"), "the runner deleting a cap");
		}

		try (Connection game = connect(TestDatabase.SCHEMA, "game", "pw");
			Statement statement = game.createStatement())
		{
			statement.executeQuery("SELECT COUNT(*) FROM characters").close();
			for (String table : RUNNER_TABLES)
			{
				assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 1 FROM " + RUNNER_SCHEMA + "." + table + " LIMIT 1").close(), "the game reading " + table);
			}
		}
	}
}
