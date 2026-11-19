package org.l2jmobius.gameserver.livingchronicle.actor;

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
 * AT-ACTOR-00 (docs/spec/actor-identity.md): the migration applies; the database itself keeps an account's id, login and origin unchanged and its row undeleted, a character's binding unchanged, a profile or principal change tied to its audit row, and principals of the right kind.
 */
class ActorSchemaTest
{
	private static TestDatabase _database;

	@BeforeAll
	static void migrate() throws Exception
	{
		_database = TestDatabase.start();
		try (Connection connection = _database.connect())
		{
			Migrations.applyAll(connection);
			try (Statement statement = connection.createStatement())
			{
				statement.execute("INSERT INTO lc_principal (principal_id, kind, name, created_at) VALUES (1, 'HUMAN_ACCOUNT', 'alice', NOW(3)), (2, 'CITIZEN_HOUSEHOLD', 'House Varn', NOW(3)), (3, 'HUMAN_ACCOUNT', 'bob', NOW(3)), (4, 'CITIZEN_HOUSEHOLD', 'House Orel', NOW(3))");
				statement.execute(account("alice", "HUMAN", "NULL", 1));
				statement.execute(account("varn01", "SYNTHETIC_CITIZEN", "'RESIDENT'", 2));
			}
		}
	}

	@Test
	void originIsAssignedOnceAndKept() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			// A human has no sim profile, a citizen has one; a login is one account; principals are of the right kind, a human's its own.
			assertThrows(SQLException.class, () -> statement.execute(account("bob", "HUMAN", "'TEST'", 3)));
			assertThrows(SQLException.class, () -> statement.execute(account("varn02", "SYNTHETIC_CITIZEN", "NULL", 2)));
			assertThrows(SQLException.class, () -> statement.execute(account("alice", "HUMAN", "NULL", 3)));
			assertThrows(SQLException.class, () -> statement.execute(account("bob", "HUMAN", "NULL", 2)), "a human in a citizen household");
			assertThrows(SQLException.class, () -> statement.execute(account("bob", "HUMAN", "NULL", 1)), "a human sharing another human's principal");
			assertThrows(SQLException.class, () -> statement.execute(account("varn02", "SYNTHETIC_CITIZEN", "'TEST'", 1)), "a citizen with a human principal");

			// Id, login and origin never change; a row is never deleted.
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_account SET origin = 'SYNTHETIC_CITIZEN', sim_profile = 'TEST' WHERE login = 'alice'"));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_account SET login = 'alice2' WHERE login = 'alice'"));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_account SET account_id = 999 WHERE login = 'alice'"));
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM lc_account WHERE login = 'alice'"));

			// A citizen's profile changes only with its audit row named in the session; a SQL tool without one is refused.
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_account SET sim_profile = 'SCENARIO_ADVERSARY' WHERE login = 'varn01'"));
			statement.execute("INSERT INTO lc_identity_audit (audit_id, account_id, field, before_value, after_value, admin, reason, at) VALUES (77, (SELECT account_id FROM lc_account WHERE login = 'varn01'), 'SIM_PROFILE', 'RESIDENT', 'SCENARIO_ADVERSARY', 'admin', 'scenario', NOW(3))");
			statement.execute("SET @lc_identity_audit = 77");
			statement.execute("UPDATE lc_account SET sim_profile = 'SCENARIO_ADVERSARY' WHERE login = 'varn01'");
			// Even with an audit row, a principal of the wrong kind is refused.
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_account SET principal_id = 3 WHERE login = 'varn01'"));
			statement.execute("UPDATE lc_account SET principal_id = 4 WHERE login = 'varn01'");
			statement.execute("SET @lc_identity_audit = NULL");

			try (ResultSet result = statement.executeQuery("SELECT origin, sim_profile FROM lc_account WHERE login = 'varn01'"))
			{
				result.next();
				assertEquals("SYNTHETIC_CITIZEN", result.getString(1));
				assertEquals("SCENARIO_ADVERSARY", result.getString(2));
			}
		}
	}

	@Test
	void aCharactersBindingNeverChanges() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO lc_character (char_id, account_id, bound_at) VALUES (268477000, (SELECT account_id FROM lc_account WHERE login = 'alice'), NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_character SET account_id = (SELECT account_id FROM lc_account WHERE login = 'varn01') WHERE char_id = 268477000"));
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM lc_character WHERE char_id = 268477000"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_character (char_id, account_id, bound_at) VALUES (268477000, (SELECT account_id FROM lc_account WHERE login = 'varn01'), NOW(3))"));
		}
	}

	@Test
	void aPolicyRuleIsOfAnAllowedDomainAndOneRowPerOriginAndActivity() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO lc_actor_policy (module, rule, domain, origin, activity, allowed, config_ver) VALUES ('LEDGER', 'contract.party', 'PARTICIPATION', 'NPC_FAKE', 'ANY', FALSE, 1)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_actor_policy (module, rule, domain, origin, activity, allowed, config_ver) VALUES ('LEDGER', 'contract.party', 'PARTICIPATION', 'NPC_FAKE', 'ANY', TRUE, 2)"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_actor_policy (module, rule, domain, origin, activity, allowed, config_ver) VALUES ('COMBAT', 'bonus', 'MECHANICS', 'HUMAN', 'ANY', TRUE, 2)"), "a mechanics rule");
		}
	}

	private static String account(String login, String origin, String profile, int principal)
	{
		return "INSERT INTO lc_account (login, origin, sim_profile, principal_id, created_at, provisioned_by) VALUES ('" + login + "', '" + origin + "', " + profile + ", " + principal + ", NOW(3), 'test')";
	}
}
