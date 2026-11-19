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
 * AT-ACTOR-00 (docs/spec/actor-identity.md): the migration applies; the database itself keeps an account's origin and login unchanged and its row undeleted, and refuses a human with a sim profile or a citizen without one.
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
		}
	}

	@Test
	void originIsAssignedOnceAndKept() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO lc_principal (kind, name, created_at) VALUES ('HUMAN_ACCOUNT', 'alice', NOW(3))");
			statement.execute("INSERT INTO lc_principal (kind, name, created_at) VALUES ('CITIZEN_HOUSEHOLD', 'House Varn', NOW(3))");
			statement.execute("INSERT INTO lc_account (login, origin, sim_profile, principal_id, created_at, provisioned_by) VALUES ('alice', 'HUMAN', NULL, 1, NOW(3), 'login')");
			statement.execute("INSERT INTO lc_account (login, origin, sim_profile, principal_id, created_at, provisioned_by) VALUES ('varn01', 'SYNTHETIC_CITIZEN', 'RESIDENT', 2, NOW(3), 'admin')");

			// A human has no sim profile and a citizen has one (ACTOR-INV02); a login is one account.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_account (login, origin, sim_profile, principal_id, created_at, provisioned_by) VALUES ('bob', 'HUMAN', 'TEST', 1, NOW(3), 'login')"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_account (login, origin, sim_profile, principal_id, created_at, provisioned_by) VALUES ('varn02', 'SYNTHETIC_CITIZEN', NULL, 2, NOW(3), 'admin')"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_account (login, origin, sim_profile, principal_id, created_at, provisioned_by) VALUES ('alice', 'HUMAN', NULL, 1, NOW(3), 'login')"));

			// Origin and login never change; a row is never deleted (ACTOR-INV01). A citizen's profile may change (by an admin, audited).
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_account SET origin = 'SYNTHETIC_CITIZEN', sim_profile = 'TEST' WHERE login = 'alice'"));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_account SET login = 'alice2' WHERE login = 'alice'"));
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM lc_account WHERE login = 'alice'"));
			statement.execute("UPDATE lc_account SET sim_profile = 'SCENARIO_ADVERSARY' WHERE login = 'varn01'");

			try (ResultSet result = statement.executeQuery("SELECT origin FROM lc_account WHERE login = 'alice'"))
			{
				result.next();
				assertEquals("HUMAN", result.getString(1));
			}
		}
	}

	@Test
	void aPolicyRuleIsOneRowPerOriginAndActivity() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO lc_actor_policy (module, rule, origin, activity, allowed, config_ver) VALUES ('LEDGER', 'contract.party', 'NPC_FAKE', 'ANY', FALSE, 1)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_actor_policy (module, rule, origin, activity, allowed, config_ver) VALUES ('LEDGER', 'contract.party', 'NPC_FAKE', 'ANY', TRUE, 2)"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_actor_policy (module, rule, origin, activity, allowed, config_ver) VALUES ('LEDGER', 'contract.party', 'ROBOT', 'ANY', TRUE, 2)"));
		}
	}
}
