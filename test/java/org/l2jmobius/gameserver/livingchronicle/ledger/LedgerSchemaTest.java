package org.l2jmobius.gameserver.livingchronicle.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * AT-BL-00 (docs/spec/black-ledger.md): the migration applies; triggers and keys refuse a contract created in another state, transitions outside spec §3.1 (every pair, for each kind), changed terms, a Black principal changing or a Writ shrinking, cancelling after an acceptance, an acceptance by the mark or the client, a kill recorded twice or claimed twice, a seal for anyone but a failed hunter of a Black Contract or twice, a reverse case skipping a step, a second alias per character or name, a falling rank.
 */
class LedgerSchemaTest
{
	private static final String[] STATES =
	{
		"OPEN", "CLAIMABLE", "SETTLING", "HELD", "SETTLED", "RETURNED", "EXPIRED", "CANCELLED", "LAPSED"
	};

	private static TestDatabase _database;
	private static int _nextId = 100;

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
	void everyTransitionPairIsAllowedExactlyWhenTheStateMachineHasIt() throws SQLException
	{
		final Map<String, Set<String>> black = new HashMap<>();
		black.put("OPEN", Set.of("CLAIMABLE", "EXPIRED", "CANCELLED"));
		black.put("CLAIMABLE", Set.of("SETTLING", "OPEN", "EXPIRED"));
		black.put("SETTLING", Set.of("SETTLED", "HELD", "OPEN", "EXPIRED"));
		black.put("HELD", Set.of("SETTLED", "RETURNED"));
		final Map<String, String[]> blackPath = new HashMap<>();
		blackPath.put("OPEN", new String[0]);
		blackPath.put("CLAIMABLE", new String[] { "CLAIMABLE" });
		blackPath.put("SETTLING", new String[] { "CLAIMABLE", "SETTLING" });
		blackPath.put("HELD", new String[] { "CLAIMABLE", "SETTLING", "HELD" });
		blackPath.put("SETTLED", new String[] { "CLAIMABLE", "SETTLING", "SETTLED" });
		blackPath.put("RETURNED", new String[] { "CLAIMABLE", "SETTLING", "HELD", "RETURNED" });
		blackPath.put("EXPIRED", new String[] { "EXPIRED" });
		blackPath.put("CANCELLED", new String[] { "CANCELLED" });

		final Map<String, Set<String>> writ = new HashMap<>();
		writ.put("OPEN", Set.of("SETTLING", "EXPIRED", "LAPSED"));
		writ.put("SETTLING", Set.of("SETTLED", "HELD", "OPEN", "EXPIRED"));
		writ.put("HELD", Set.of("SETTLED", "RETURNED"));
		final Map<String, String[]> writPath = new HashMap<>();
		writPath.put("OPEN", new String[0]);
		writPath.put("SETTLING", new String[] { "SETTLING" });
		writPath.put("HELD", new String[] { "SETTLING", "HELD" });
		writPath.put("SETTLED", new String[] { "SETTLING", "SETTLED" });
		writPath.put("RETURNED", new String[] { "SETTLING", "HELD", "RETURNED" });
		writPath.put("EXPIRED", new String[] { "EXPIRED" });
		writPath.put("LAPSED", new String[] { "LAPSED" });

		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			for (boolean isBlack : new boolean[] { true, false })
			{
				final Map<String, Set<String>> allowed = isBlack ? black : writ;
				final Map<String, String[]> paths = isBlack ? blackPath : writPath;
				for (String from : paths.keySet())
				{
					for (String to : STATES)
					{
						if (from.equals(to))
						{
							continue;
						}
						final int contract = ++_nextId;
						statement.execute(isBlack ? black(contract) : writ(contract));
						int version = 0;
						String state = "OPEN";
						for (String step : paths.get(from))
						{
							statement.execute(move(contract, step, ++version));
							state = step;
						}
						final String sql = move(contract, to, version + 1);
						if (allowed.containsKey(state) && allowed.get(state).contains(to))
						{
							statement.execute(sql);
						}
						else
						{
							assertThrows(SQLException.class, () -> statement.execute(sql), (isBlack ? "BLACK " : "WRIT ") + from + " -> " + to);
						}
					}
				}
			}
		}
	}

	@Test
	void theKeysAndRulesHold() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			// Created open; a Black Contract has a client, a Writ none and no intel budget.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_contract (contract_id, kind, cause, state, mark_char, client_char, created_at, deadline, principal, conditions, terms_hash, origin_npc, origin_town) VALUES (1, 'BLACK', 'OPEN', 'SETTLED', 900, 800, NOW(3), NOW(3) + INTERVAL 1 DAY, 1000000, '{}', UNHEX(SHA2('t', 256)), 'broker.giran', 7)"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_contract (contract_id, kind, cause, state, mark_char, created_at, deadline, principal, conditions, terms_hash, origin_npc, origin_town) VALUES (1, 'BLACK', 'OPEN', 'OPEN', 900, NOW(3), NOW(3) + INTERVAL 1 DAY, 1000000, '{}', UNHEX(SHA2('t', 256)), 'broker.giran', 7)"), "a Black Contract without its client");
			statement.execute(black(1));
			statement.execute(writ(2));

			// Terms never change; a Black principal is fixed; an open Writ only grows.
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_contract SET principal = 2000000, version = 1 WHERE contract_id = 1"));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_contract SET conditions = '{\"x\":1}', version = 1 WHERE contract_id = 1"));
			statement.execute("UPDATE lc_ledger_contract SET principal = principal + 500000, version = 1 WHERE contract_id = 2");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_contract SET principal = principal - 1, version = 2 WHERE contract_id = 2"));

			// Hunters: acceptance by neither mark nor client; cancel only before any acceptance.
			statement.execute("INSERT INTO lc_ledger_hunter (char_id, registered_at) VALUES (700, NOW(3)), (900, NOW(3)), (800, NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute(accept(1, 900)), "the mark accepting its own contract");
			assertThrows(SQLException.class, () -> statement.execute(accept(1, 800)), "the client accepting its own contract");
			statement.execute(accept(1, 700));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_contract SET state = 'CANCELLED', version = 1 WHERE contract_id = 1"), "cancelled after an acceptance");

			// A kill is recorded once per key and contract, and claimed once.
			statement.execute(kill("'BL/kill/3/900/1'", 1));
			assertThrows(SQLException.class, () -> statement.execute(kill("'BL/kill/3/900/1'", 1)), "the death event delivered twice");
			statement.execute(kill("'BL/kill/3/900/1'", 2));
			statement.execute("UPDATE lc_ledger_kill SET claimed_op = UNHEX(REPEAT('51', 16)) WHERE kill_key = 'BL/kill/3/900/1' AND contract_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_kill SET claimed_op = UNHEX(REPEAT('52', 16)) WHERE kill_key = 'BL/kill/3/900/1' AND contract_id = 1"), "claimed twice");

			// A seal only for a failed hunter of a Black Contract, once; its holder's reverse case advances step by step.
			statement.execute("INSERT INTO lc_item_lifecycle (lifecycle_id, object_id, item_id, began_at) VALUES (1, 300100, 21000, NOW(3)), (2, 300101, 21000, NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute(seal(1, 1, 700)), "a seal for an active hunter");
			statement.execute("UPDATE lc_ledger_acceptance SET state = 'FAILED' WHERE contract_id = 1 AND hunter_char = 700");
			statement.execute(seal(1, 1, 700));
			assertThrows(SQLException.class, () -> statement.execute(seal(2, 1, 700)), "a second seal for the same hunter");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_acceptance SET state = 'WON' WHERE contract_id = 1 AND hunter_char = 700"), "an acceptance ending twice");
			statement.execute("INSERT INTO lc_ledger_reverse_case (case_id, lifecycle_id, holder_char, opened_at, expires_at) VALUES (1, 1, 900, NOW(3), NOW(3) + INTERVAL 1 DAY)");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_reverse_case SET step = 2 WHERE case_id = 1"), "skipping a step");
			statement.execute("UPDATE lc_ledger_reverse_case SET step = 1 WHERE case_id = 1");
			statement.execute("UPDATE lc_ledger_reverse_case SET step = 2 WHERE case_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_reverse_case SET holder_char = 1 WHERE case_id = 1"));

			// Aliases: one per character, unique names, owners fixed, exposure once.
			statement.execute("INSERT INTO lc_ledger_alias (alias_id, name, owner_char, card, created_at) VALUES (1, 'Black Feather', 700, 'FEATHER', NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_alias (name, owner_char, card, created_at) VALUES ('Second', 700, 'COIN', NOW(3))"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_alias (name, owner_char, card, created_at) VALUES ('Black Feather', 800, 'COIN', NOW(3))"));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_alias SET owner_char = 800 WHERE alias_id = 1"));
			statement.execute("UPDATE lc_ledger_alias SET exposed_at = NOW(3) WHERE alias_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_alias SET exposed_at = NULL WHERE alias_id = 1"));

			// Ranks never fall.
			statement.execute("UPDATE lc_ledger_hunter SET rank = 'TRACKER', completed = 3 WHERE char_id = 700");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_hunter SET rank = 'LISTENER' WHERE char_id = 700"));

			// The Ledger's expiring tables are registered.
			try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM lc_expiring_table WHERE owner_module = 'LEDGER'"))
			{
				result.next();
				assertEquals(5, result.getInt(1));
			}
		}
	}

	private static String black(int id)
	{
		return "INSERT INTO lc_ledger_contract (contract_id, kind, cause, state, mark_char, client_char, created_at, deadline, principal, intel_budget, broker_fee, conditions, terms_hash, origin_npc, origin_town) VALUES (" + id + ", 'BLACK', 'OPEN', 'OPEN', 900, 800, NOW(3), NOW(3) + INTERVAL 1 DAY, 20000000, 3000000, 500000, '{}', UNHEX(SHA2('t', 256)), 'broker.giran', 7)";
	}

	private static String writ(int id)
	{
		return "INSERT INTO lc_ledger_contract (contract_id, kind, cause, state, mark_char, created_at, deadline, principal, conditions, terms_hash, origin_npc, origin_town) VALUES (" + id + ", 'WRIT', 'KARMA', 'OPEN', 900, NOW(3), NOW(3) + INTERVAL 1 DAY, 1000000, '{}', UNHEX(SHA2('w', 256)), 'magistrate.giran', 7)";
	}

	private static String move(int contract, String to, int version)
	{
		return "UPDATE lc_ledger_contract SET state = '" + to + "', version = " + version + " WHERE contract_id = " + contract;
	}

	private static String accept(int contract, int hunter)
	{
		return "INSERT INTO lc_ledger_acceptance (contract_id, hunter_char, accepted_at, card_mode) VALUES (" + contract + ", " + hunter + ", NOW(3), 'AT_KILL')";
	}

	private static String kill(String key, int contract)
	{
		return "INSERT INTO lc_ledger_kill (kill_key, contract_id, hunter_char, mark_char, at, region_id, context, outcome) VALUES (" + key + ", " + contract + ", 700, 900, NOW(3), 12, '{}', 'QUALIFIED')";
	}

	private static String seal(long lifecycle, int contract, int hunter)
	{
		return "INSERT INTO lc_ledger_seal (lifecycle_id, contract_id, hunter_char, mark_char, value_band, origin_town, issued_op) VALUES (" + lifecycle + ", " + contract + ", " + hunter + ", 900, 3, 7, UNHEX(REPEAT('61', 16)))";
	}
}
