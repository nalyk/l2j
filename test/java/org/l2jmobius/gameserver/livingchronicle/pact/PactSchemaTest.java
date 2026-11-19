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
 * AT-PACT-00 (docs/spec/pact.md): the migration applies; triggers and keys refuse a contract created in another state, transitions outside spec §3.1, a version that does not advance by one, leaving signing without every signature on the current terms version, any change of terms, clauses or parties after signing, a terms hash not of the terms, review returning elsewhere than where it came from, a unit settled without its key or twice, and a second or changed craft intent or outcome.
 */
class PactSchemaTest
{
	private static final String TERMS = "'{\"type\":\"CRAFTING_PACT\",\"v\":1}'";
	private static final String TERMS_2 = "'{\"type\":\"CRAFTING_PACT\",\"v\":2}'";

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
	void theStateMachineTermsAndKeysHold() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			// Created only as a draft, with the hash of its terms.
			assertThrows(SQLException.class, () -> statement.execute(contract(2, "'ACTIVE'", TERMS, TERMS)), "created active");
			assertThrows(SQLException.class, () -> statement.execute(contract(2, "'DRAFT'", TERMS, TERMS_2)), "a hash of other terms");
			statement.execute(contract(1, "'DRAFT'", TERMS, TERMS));
			statement.execute("INSERT INTO lc_pact_party (contract_id, party_no, role, kind, char_id) VALUES (1, 1, 'CLIENT', 'CHAR', 268477000), (1, 2, 'CONTRACTOR', 'CHAR', 268477001)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pact_party (contract_id, party_no, role, kind, clan_id) VALUES (1, 3, 'MEMBER', 'CHAR', 5)"), "a party of the wrong kind");
			statement.execute("INSERT INTO lc_pact_clause (contract_id, clause_no, kind, params) VALUES (1, 1, 'CRAFT_ATTEMPT', '{}')");

			// The row version advances by one; the state follows the machine; terms change with the next terms version.
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_contract SET state = 'PROPOSED' WHERE contract_id = 1"), "no version step");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_contract SET state = 'ACTIVE', version = 1 WHERE contract_id = 1"), "a draft jumping to active");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_contract SET terms = " + TERMS_2 + ", terms_hash = UNHEX(SHA2(" + TERMS_2 + ", 256)), version = 1 WHERE contract_id = 1"), "terms changed without the next terms version");
			statement.execute("UPDATE lc_pact_contract SET state = 'PROPOSED', version = 1 WHERE contract_id = 1");

			// Two parties sign the same terms version in turn; the row version moves, the signatures stay valid.
			statement.execute("UPDATE lc_pact_party SET signed_terms_version = 1, signed_at = NOW(3), signed_token = UNHEX(REPEAT('01', 16)) WHERE contract_id = 1 AND party_no = 1");
			statement.execute("UPDATE lc_pact_contract SET state = 'PARTIALLY_SIGNED', version = 2 WHERE contract_id = 1");

			// A counter-proposal: terms version 2, back to proposed; party 1's signature is now on an old terms version.
			statement.execute("UPDATE lc_pact_contract SET state = 'PROPOSED', terms = " + TERMS_2 + ", terms_hash = UNHEX(SHA2(" + TERMS_2 + ", 256)), terms_version = 2, version = 3 WHERE contract_id = 1");
			statement.execute("UPDATE lc_pact_party SET signed_terms_version = 2, signed_at = NOW(3), signed_token = UNHEX(REPEAT('02', 16)) WHERE contract_id = 1 AND party_no = 2");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_contract SET state = 'SIGNED', version = 4 WHERE contract_id = 1"), "party 1 signed the old terms");
			statement.execute("UPDATE lc_pact_party SET signed_terms_version = 2, signed_at = NOW(3), signed_token = UNHEX(REPEAT('03', 16)) WHERE contract_id = 1 AND party_no = 1");
			statement.execute("UPDATE lc_pact_contract SET state = 'SIGNED', version = 4 WHERE contract_id = 1");

			// After signing: terms, parties and clauses are frozen, also across a state change.
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_contract SET state = 'FUNDED', terms = " + TERMS + ", terms_hash = UNHEX(SHA2(" + TERMS + ", 256)), terms_version = 3, version = 5 WHERE contract_id = 1"), "terms changed on a transition");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_contract SET fee_adena = 1, version = 5 WHERE contract_id = 1"), "a fee changed after signing");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_party SET char_id = 268477009 WHERE contract_id = 1 AND party_no = 2"));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_clause SET params = '{\"n\":9}' WHERE contract_id = 1"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pact_clause (contract_id, clause_no, kind, params) VALUES (1, 2, 'PAY_ADENA', '{}')"));
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM lc_pact_party WHERE contract_id = 1 AND party_no = 2"));

			// Review returns only to the state it left, or to a terminal one.
			statement.execute("UPDATE lc_pact_contract SET state = 'ADMIN_REVIEW', review_from = 'SIGNED', review_reason = 'audit 7', version = 5 WHERE contract_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_contract SET state = 'ACTIVE', review_from = NULL, version = 6 WHERE contract_id = 1"), "review resolving into another state");
			statement.execute("UPDATE lc_pact_contract SET state = 'SIGNED', review_from = NULL, version = 6 WHERE contract_id = 1");
			statement.execute("UPDATE lc_pact_contract SET state = 'FUNDED', version = 7 WHERE contract_id = 1");
			statement.execute("UPDATE lc_pact_contract SET state = 'ACTIVE', version = 8 WHERE contract_id = 1");

			// A craft attempt: one immutable intent, at most one immutable outcome referencing it.
			statement.execute("INSERT INTO lc_pact_roll_secret (secret_version, secret, created_at) VALUES (1, UNHEX(SHA2('install', 256)), NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_roll_secret SET secret = UNHEX(SHA2('other', 256))"));
			assertThrows(SQLException.class, () -> statement.execute(outcome(1, "FALSE, FALSE, NULL, 0")), "an outcome without an intent");
			statement.execute("INSERT INTO lc_pact_craft_intent (contract_id, attempt_no, recipe_id, crafter_char, secret_version, success_rate, masterwork_rate_ppm, created_at) VALUES (1, 1, 210, 268477001, 1, 70, 0, NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pact_craft_intent (contract_id, attempt_no, recipe_id, crafter_char, secret_version, success_rate, masterwork_rate_ppm, created_at) VALUES (1, 1, 210, 268477001, 1, 100, 0, NOW(3))"), "a second intent for the attempt");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_craft_intent SET success_rate = 100 WHERE contract_id = 1"));
			statement.execute(outcome(1, "FALSE, FALSE, NULL, 0"));
			assertThrows(SQLException.class, () -> statement.execute(outcome(1, "TRUE, FALSE, 6380, 1")), "a second outcome");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_craft_outcome SET success = TRUE, product_item_id = 6380, product_count = 1 WHERE contract_id = 1"));
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM lc_pact_craft_intent WHERE contract_id = 1"));

			// A unit starts open, settles once with its key, and never changes what it owed.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pact_unit (contract_id, clause_no, unit, state, owed) VALUES (1, 1, 1, 'PAID', '{}')"), "a unit created settled");
			statement.execute("INSERT INTO lc_pact_unit (contract_id, clause_no, unit, owed) VALUES (1, 1, 1, '{\"adena\":400000}')");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_unit SET state = 'PAID' WHERE contract_id = 1"), "settled without a key");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_unit SET owed = '{\"adena\":1}' WHERE contract_id = 1"), "owed changed");
			statement.execute("INSERT INTO lc_asset_op (op_id, module, action, subject_ref, committed_at) VALUES (UNHEX(REPEAT('21', 16)), 'PACT', 'SETTLE', 1, NOW(3))");
			statement.execute("INSERT INTO lc_settlement_key (scope, skey, op_id) VALUES ('PACT', 'PACT/1/1/1', UNHEX(REPEAT('21', 16)))");
			assertThrows(SQLException.class, () -> statement.execute(settle("'PACT/1/1/9'")), "a key that was never written");
			statement.execute(settle("'PACT/1/1/1'"));
			assertThrows(SQLException.class, () -> statement.execute(settle("'PACT/1/1/1'")), "settled twice");
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM lc_pact_unit WHERE contract_id = 1"));

			// A character is in at most one active charter; a kill is routed once.
			statement.execute(contract(3, "'DRAFT'", TERMS, TERMS));
			statement.execute("INSERT INTO lc_pact_charter_member (contract_id, char_id, state) VALUES (3, 268477000, 'FROZEN_IN')");
			statement.execute(contract(5, "'DRAFT'", TERMS, TERMS));
			statement.execute("INSERT INTO lc_pact_charter_member (contract_id, char_id, state) VALUES (5, 268477000, 'FROZEN_IN')");
			statement.execute("INSERT INTO lc_pact_charter_active (char_id, contract_id) VALUES (268477000, 3)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pact_charter_active (char_id, contract_id) VALUES (268477000, 5)"), "a second active charter, its member row valid");
			statement.execute("INSERT INTO lc_pact_kill (kill_key, contract_id, op_id, at) VALUES ('PACT/kill/7/1', 3, UNHEX(REPEAT('31', 16)), NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pact_kill (kill_key, contract_id, op_id, at) VALUES ('PACT/kill/7/1', 3, UNHEX(REPEAT('32', 16)), NOW(3))"));

			// A budget split: the parent settles as SPLIT with its key; a child names it; the remainder stays open.
			statement.execute("INSERT INTO lc_pact_clause (contract_id, clause_no, kind, params) VALUES (5, 1, 'PAY_ADENA', '{}')");
			statement.execute("INSERT INTO lc_pact_unit (contract_id, clause_no, unit, owed) VALUES (5, 1, 1, '{\"adena\":1000}')");
			statement.execute("INSERT INTO lc_asset_op (op_id, module, action, subject_ref, committed_at) VALUES (UNHEX(REPEAT('41', 16)), 'PACT', 'FILL', 5, NOW(3))");
			statement.execute("INSERT INTO lc_settlement_key (scope, skey, op_id) VALUES ('PACT', 'PACT/5/1/1/split', UNHEX(REPEAT('41', 16))), ('PACT', 'PACT/5/1/2', UNHEX(REPEAT('41', 16)))");
			statement.execute("UPDATE lc_pact_unit SET state = 'SPLIT', skey = 'PACT/5/1/1/split', op_id = UNHEX(REPEAT('41', 16)), at = NOW(3) WHERE contract_id = 5 AND unit = 1");
			statement.execute("INSERT INTO lc_pact_unit (contract_id, clause_no, unit, parent_unit, owed) VALUES (5, 1, 2, 1, '{\"adena\":300}'), (5, 1, 3, 1, '{\"adena\":700}')");
			statement.execute("UPDATE lc_pact_unit SET state = 'PAID', skey = 'PACT/5/1/2', op_id = UNHEX(REPEAT('41', 16)), at = NOW(3) WHERE contract_id = 5 AND unit = 2");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pact_unit (contract_id, clause_no, unit, parent_unit, owed) VALUES (5, 1, 4, 99, '{}')"), "a child of no unit");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_unit SET parent_unit = NULL WHERE contract_id = 5 AND unit = 3"), "a child re-parented");

			// Terminal is final; contracts are kept; a private contract is never publishable.
			statement.execute("UPDATE lc_pact_contract SET state = 'COMPLETED', version = 9 WHERE contract_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pact_contract SET state = 'ADMIN_REVIEW', review_from = 'ACTIVE', review_reason = 'x', version = 10 WHERE contract_id = 1"));
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM lc_pact_contract WHERE contract_id = 1"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pact_contract (contract_id, type, state, visibility, publishable, notarius_tier, notarius_town, created_by, created_at, deadline, terms, terms_hash) VALUES (4, 'LOAN', 'DRAFT', 'PRIVATE', TRUE, 'TOWN', 7, 1, NOW(3), NOW(3), " + TERMS + ", UNHEX(SHA2(" + TERMS + ", 256)))"));
		}
	}

	/**
	 * Every pair of states (spec §3.1), each tried on a fresh contract driven to the source state: allowed exactly when the table has it.
	 */
	@Test
	void everyTransitionPairIsAllowedExactlyWhenTheStateMachineHasIt() throws SQLException
	{
		final String[] states =
		{
			"DRAFT", "PROPOSED", "PARTIALLY_SIGNED", "SIGNED", "FUNDED", "ACTIVE", "COMPLETED", "EXPIRED", "MUTUALLY_CANCELLED", "FAILED_BY_CONDITION", "WITHDRAWN", "ADMIN_REVIEW"
		};
		final java.util.Map<String, java.util.Set<String>> allowed = new java.util.HashMap<>();
		allowed.put("DRAFT", java.util.Set.of("PROPOSED", "ADMIN_REVIEW"));
		allowed.put("PROPOSED", java.util.Set.of("PARTIALLY_SIGNED", "SIGNED", "WITHDRAWN", "ADMIN_REVIEW"));
		allowed.put("PARTIALLY_SIGNED", java.util.Set.of("PROPOSED", "SIGNED", "WITHDRAWN", "ADMIN_REVIEW"));
		allowed.put("SIGNED", java.util.Set.of("FUNDED", "EXPIRED", "MUTUALLY_CANCELLED", "ADMIN_REVIEW"));
		allowed.put("FUNDED", java.util.Set.of("ACTIVE", "MUTUALLY_CANCELLED", "ADMIN_REVIEW"));
		allowed.put("ACTIVE", java.util.Set.of("COMPLETED", "EXPIRED", "FAILED_BY_CONDITION", "MUTUALLY_CANCELLED", "ADMIN_REVIEW"));
		// Review is entered from SIGNED below, so it may return to SIGNED or end in a terminal state.
		allowed.put("ADMIN_REVIEW", java.util.Set.of("SIGNED", "COMPLETED", "EXPIRED", "MUTUALLY_CANCELLED", "FAILED_BY_CONDITION", "WITHDRAWN"));
		final java.util.Map<String, String[]> path = new java.util.HashMap<>();
		path.put("DRAFT", new String[0]);
		path.put("PROPOSED", new String[] { "PROPOSED" });
		path.put("PARTIALLY_SIGNED", new String[] { "PROPOSED", "PARTIALLY_SIGNED" });
		path.put("SIGNED", new String[] { "PROPOSED", "SIGNED" });
		path.put("FUNDED", new String[] { "PROPOSED", "SIGNED", "FUNDED" });
		path.put("ACTIVE", new String[] { "PROPOSED", "SIGNED", "FUNDED", "ACTIVE" });
		path.put("COMPLETED", new String[] { "PROPOSED", "SIGNED", "FUNDED", "ACTIVE", "COMPLETED" });
		path.put("EXPIRED", new String[] { "PROPOSED", "SIGNED", "EXPIRED" });
		path.put("MUTUALLY_CANCELLED", new String[] { "PROPOSED", "SIGNED", "MUTUALLY_CANCELLED" });
		path.put("FAILED_BY_CONDITION", new String[] { "PROPOSED", "SIGNED", "FUNDED", "ACTIVE", "FAILED_BY_CONDITION" });
		path.put("WITHDRAWN", new String[] { "PROPOSED", "WITHDRAWN" });
		path.put("ADMIN_REVIEW", new String[] { "PROPOSED", "SIGNED", "ADMIN_REVIEW" });
		int id = 1000;
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			for (String from : states)
			{
				for (String to : states)
				{
					if (from.equals(to))
					{
						continue;
					}
					final int contract = ++id;
					statement.execute(contract(contract, "'DRAFT'", TERMS, TERMS));
					statement.execute("INSERT INTO lc_pact_party (contract_id, party_no, role, kind, char_id, signed_terms_version, signed_at, signed_token) VALUES (" + contract + ", 1, 'CLIENT', 'CHAR', 1, 1, NOW(3), UNHEX(REPEAT('0A', 16))), (" + contract + ", 2, 'CONTRACTOR', 'CHAR', 2, 1, NOW(3), UNHEX(REPEAT('0B', 16)))");
					int version = 0;
					String state = "DRAFT";
					for (String step : path.get(from))
					{
						statement.execute(move(contract, state, step, ++version));
						state = step;
					}
					final String sql = move(contract, state, to, version + 1);
					if (allowed.get(from) != null && allowed.get(from).contains(to))
					{
						statement.execute(sql);
					}
					else
					{
						assertThrows(SQLException.class, () -> statement.execute(sql), from + " -> " + to);
					}
				}
			}
		}
	}

	/**
	 * Review entered from every non-terminal state returns only to that state (or ends in a terminal one), never to another non-terminal state.
	 */
	@Test
	void reviewReturnsOnlyWhereItCameFrom() throws SQLException
	{
		final String[][] paths =
		{
			{},
			{ "PROPOSED" },
			{ "PROPOSED", "PARTIALLY_SIGNED" },
			{ "PROPOSED", "SIGNED" },
			{ "PROPOSED", "SIGNED", "FUNDED" },
			{ "PROPOSED", "SIGNED", "FUNDED", "ACTIVE" }
		};
		final String[] nonTerminal =
		{
			"DRAFT", "PROPOSED", "PARTIALLY_SIGNED", "SIGNED", "FUNDED", "ACTIVE"
		};
		int id = 5000;
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			for (String[] path : paths)
			{
				final String from = path.length == 0 ? "DRAFT" : path[path.length - 1];
				for (String back : nonTerminal)
				{
					final int contract = ++id;
					statement.execute(contract(contract, "'DRAFT'", TERMS, TERMS));
					statement.execute("INSERT INTO lc_pact_party (contract_id, party_no, role, kind, char_id, signed_terms_version, signed_at, signed_token) VALUES (" + contract + ", 1, 'CLIENT', 'CHAR', 1, 1, NOW(3), UNHEX(REPEAT('0A', 16)))");
					int version = 0;
					String state = "DRAFT";
					for (String step : path)
					{
						statement.execute(move(contract, state, step, ++version));
						state = step;
					}
					statement.execute(move(contract, state, "ADMIN_REVIEW", ++version));
					final String sql = move(contract, "ADMIN_REVIEW", back, version + 1);
					if (back.equals(from))
					{
						statement.execute(sql);
					}
					else
					{
						assertThrows(SQLException.class, () -> statement.execute(sql), "review from " + from + " back to " + back);
					}
				}
			}
		}
	}

	private static String move(int contract, String from, String to, int version)
	{
		final String review = to.equals("ADMIN_REVIEW") ? ", review_from = '" + from + "', review_reason = 'audit'" : from.equals("ADMIN_REVIEW") ? ", review_from = NULL" : "";
		return "UPDATE lc_pact_contract SET state = '" + to + "', version = " + version + review + " WHERE contract_id = " + contract;
	}

	private static String contract(int id, String state, String terms, String hashed)
	{
		return "INSERT INTO lc_pact_contract (contract_id, type, state, visibility, notarius_tier, notarius_town, created_by, created_at, deadline, terms, terms_hash) VALUES (" + id + ", 'CRAFTING_PACT', " + state + ", 'PRIVATE', 'TOWN', 7, 268477000, NOW(3), NOW(3) + INTERVAL 1 DAY, " + terms + ", UNHEX(SHA2(" + hashed + ", 256)))";
	}

	private static String outcome(int attempt, String result)
	{
		return "INSERT INTO lc_pact_craft_outcome (contract_id, attempt_no, success, masterwork, product_item_id, product_count, op_id, rolled_at) VALUES (1, " + attempt + ", " + result + ", UNHEX(REPEAT('11', 16)), NOW(3))";
	}

	private static String settle(String skey)
	{
		return "UPDATE lc_pact_unit SET state = 'PAID', skey = " + skey + ", op_id = UNHEX(REPEAT('21', 16)), at = NOW(3) WHERE contract_id = 1 AND clause_no = 1 AND unit = 1";
	}
}
