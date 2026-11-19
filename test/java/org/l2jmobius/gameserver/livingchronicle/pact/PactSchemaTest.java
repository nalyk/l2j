package org.l2jmobius.gameserver.livingchronicle.pact;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * AT-PACT-00 (docs/spec/pact.md): the migration applies; keys, checks and triggers refuse an impossible transition, a version that does not advance by one, signing on an old version, changing a terminal contract or signed terms, a second or changed outcome for a craft attempt, a settlement unit settled twice, and deleting a contract.
 */
class PactSchemaTest
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
	void theStateMachineAndKeysHold() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO lc_pact_contract (contract_id, type, state, visibility, notarius_tier, notarius_town, created_by, created_at, deadline, terms_hash, terms) VALUES (1, 'CRAFTING_PACT', 'DRAFT', 'PRIVATE', 'TOWN', 7, 268477000, NOW(3), NOW(3) + INTERVAL 1 DAY, UNHEX(SHA2('t1', 256)), '{}')");
			statement.execute("INSERT INTO lc_pact_party (contract_id, party_no, role, char_id) VALUES (1, 1, 'CLIENT', 268477000), (1, 2, 'CONTRACTOR', 268477001)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pact_party (contract_id, party_no, role) VALUES (1, 3, 'MEMBER')"), "a party that is no one");

			// The version advances by one with every change; the state follows the machine.
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_contract SET state = 'PROPOSED' WHERE contract_id = 1"), "no version step");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_contract SET state = 'ACTIVE', version = 1 WHERE contract_id = 1"), "a draft jumping to active");
			statement.execute("UPDATE lc_pact_contract SET state = 'PROPOSED', version = 1 WHERE contract_id = 1");

			// Signed only when every party signed the version being left.
			statement.execute("UPDATE lc_pact_party SET signed_version = 1, signed_at = NOW(3) WHERE contract_id = 1 AND party_no = 1");
			statement.execute("UPDATE lc_pact_contract SET state = 'PARTIALLY_SIGNED', version = 2 WHERE contract_id = 1");
			statement.execute("UPDATE lc_pact_party SET signed_version = 2, signed_at = NOW(3) WHERE contract_id = 1 AND party_no = 2");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_contract SET state = 'SIGNED', version = 3 WHERE contract_id = 1"), "party 1 signed an older version");
			statement.execute("UPDATE lc_pact_party SET signed_version = 2, signed_at = NOW(3) WHERE contract_id = 1 AND party_no = 1");
			statement.execute("UPDATE lc_pact_contract SET state = 'SIGNED', version = 3 WHERE contract_id = 1");

			// Terms never change after signing.
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_contract SET terms_hash = UNHEX(SHA2('t2', 256)), version = 4 WHERE contract_id = 1"));
			statement.execute("UPDATE lc_pact_contract SET state = 'FUNDED', version = 4 WHERE contract_id = 1");
			statement.execute("UPDATE lc_pact_contract SET state = 'ACTIVE', version = 5 WHERE contract_id = 1");

			// A craft attempt has one outcome, consistent, never changed or deleted.
			statement.execute("INSERT INTO lc_pact_craft_outcome (contract_id, attempt_no, recipe_id, crafter_char, success, masterwork, product_item_id, product_count, op_id, rolled_at) VALUES (1, 1, 210, 268477001, FALSE, FALSE, NULL, 0, UNHEX(REPEAT('11', 16)), NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pact_craft_outcome (contract_id, attempt_no, recipe_id, crafter_char, success, masterwork, product_item_id, product_count, op_id, rolled_at) VALUES (1, 1, 210, 268477001, TRUE, FALSE, 6380, 1, UNHEX(REPEAT('12', 16)), NOW(3))"), "a second roll of the same attempt");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pact_craft_outcome (contract_id, attempt_no, recipe_id, crafter_char, success, masterwork, product_item_id, product_count, op_id, rolled_at) VALUES (1, 2, 210, 268477001, TRUE, FALSE, NULL, 0, UNHEX(REPEAT('13', 16)), NOW(3))"), "a success without a product");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_craft_outcome SET success = TRUE, product_item_id = 6380, product_count = 1 WHERE contract_id = 1"));
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM lc_pact_craft_outcome WHERE contract_id = 1"));

			// A settlement unit settles once, with its key in the Asset Service's table.
			statement.execute("INSERT INTO lc_pact_clause (contract_id, clause_no, kind, params) VALUES (1, 1, 'CRAFT_ATTEMPT', '{}')");
			statement.execute("INSERT INTO lc_asset_op (op_id, module, action, subject_ref, committed_at) VALUES (UNHEX(REPEAT('21', 16)), 'PACT', 'SETTLE', 1, NOW(3))");
			statement.execute("INSERT INTO lc_settlement_key (scope, skey, op_id) VALUES ('PACT', '1/1/1', UNHEX(REPEAT('21', 16)))");
			assertThrows(SQLException.class, () -> statement.execute(settlement(1, "'1/1/9'")), "a settlement without its key");
			statement.execute(settlement(1, "'1/1/1'"));
			assertThrows(SQLException.class, () -> statement.execute(settlement(1, "'1/1/1'")), "the same unit twice");

			// Terminal is final; contracts are kept.
			statement.execute("UPDATE lc_pact_contract SET state = 'COMPLETED', version = 6 WHERE contract_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_contract SET state = 'ADMIN_REVIEW', review_reason = 'x', version = 7 WHERE contract_id = 1"));
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM lc_pact_contract WHERE contract_id = 1"));

			// Review needs a reason; a private contract is never publishable.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pact_contract (type, state, visibility, publishable, notarius_tier, notarius_town, created_by, created_at, deadline, terms_hash, terms) VALUES ('LOAN', 'DRAFT', 'PRIVATE', TRUE, 'TOWN', 7, 1, NOW(3), NOW(3), UNHEX(SHA2('t', 256)), '{}')"));
		}
	}

	private static String settlement(int unit, String skey)
	{
		return "INSERT INTO lc_pact_settlement (contract_id, clause_no, unit, skey, outcome, op_id, at) VALUES (1, 1, " + unit + ", " + skey + ", 'PAID', UNHEX(REPEAT('21', 16)), NOW(3))";
	}
}
