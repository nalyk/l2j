package org.l2jmobius.gameserver.livingchronicle.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * AT-BL-00 (docs/spec/black-ledger.md): the migration applies; triggers and keys refuse transitions outside spec §3.1 for each kind (every pair, with each guard's rows present), each guarded transition without its rows or with another death's, a settlement before every kill is counted, a reopening that keeps the kill, external money outside cursed-weapon Writs, changed terms, a Black principal changing or a Writ shrinking, a patron on a Writ, cancelling after an acceptance, an acceptance by the mark or the client, evidence or a decision changed or duplicated, a late or second claim, a payment off its state machine, a seal for anyone but a failed hunter's mark or twice, a reverse case inserted past step 0, held by another or skipping a step, alias names and owners, a falling rank, an offer not tied to an informant trace of its mark.
 */
class LedgerSchemaTest
{
	private static final String[] STATES =
	{
		"OPEN", "CLAIMABLE", "SETTLING", "REVIEW", "SETTLED", "EXPIRED", "CANCELLED", "LAPSED"
	};

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
	void everyTransitionPairIsAllowedExactlyWhenTheStateMachineHasIt() throws SQLException
	{
		final Map<String, Set<String>> black = new HashMap<>();
		black.put("OPEN", Set.of("CLAIMABLE", "EXPIRED", "REVIEW", "CANCELLED"));
		black.put("CLAIMABLE", Set.of("SETTLING", "OPEN", "EXPIRED"));
		black.put("SETTLING", Set.of("SETTLED", "OPEN", "EXPIRED"));
		black.put("REVIEW", Set.of("OPEN", "EXPIRED", "SETTLING"));
		final Map<String, String[]> blackPath = new HashMap<>();
		blackPath.put("OPEN", new String[0]);
		blackPath.put("CLAIMABLE", new String[] { "CLAIMABLE" });
		blackPath.put("SETTLING", new String[] { "CLAIMABLE", "SETTLING" });
		blackPath.put("REVIEW", new String[] { "REVIEW" });
		blackPath.put("SETTLED", new String[] { "CLAIMABLE", "SETTLING", "SETTLED" });
		blackPath.put("EXPIRED", new String[] { "EXPIRED" });
		blackPath.put("CANCELLED", new String[] { "CANCELLED" });

		final Map<String, Set<String>> writ = new HashMap<>();
		writ.put("OPEN", Set.of("SETTLING", "EXPIRED", "REVIEW", "LAPSED"));
		writ.put("SETTLING", Set.of("SETTLED", "OPEN", "EXPIRED"));
		writ.put("REVIEW", Set.of("OPEN", "EXPIRED", "SETTLING"));
		final Map<String, String[]> writPath = new HashMap<>();
		writPath.put("OPEN", new String[0]);
		writPath.put("SETTLING", new String[] { "SETTLING" });
		writPath.put("REVIEW", new String[] { "REVIEW" });
		writPath.put("SETTLED", new String[] { "SETTLING", "SETTLED" });
		writPath.put("EXPIRED", new String[] { "EXPIRED" });
		writPath.put("LAPSED", new String[] { "LAPSED" });

		int id = 1000;
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
						final int contract = ++id;
						open(statement, contract, isBlack);
						// Every guard's rows present, so only the transition table decides.
						statement.execute(death(contract));
						statement.execute(decision(contract, "'QUALIFIED'", "NULL"));
						statement.execute(count(contract));
						final boolean paid = to.equals("SETTLED") || Arrays.asList(paths.get(from)).contains("SETTLED");
						int version = 0;
						String state = "OPEN";
						for (String step : paths.get(from))
						{
							voidIfLeaving(statement, contract, state, step);
							statement.execute(move(contract, state, step, ++version));
							state = step;
							if (step.equals("CLAIMABLE"))
							{
								// The settling death's claim, made and claimed.
								statement.execute(claim(contract));
								statement.execute("UPDATE lc_ledger_claim SET claimed_at = NOW(3), claim_op = UNHEX(REPEAT('71', 16)) WHERE contract_id = " + contract);
							}
							if (step.equals("SETTLING"))
							{
								// The settling death's bounty, resolved as the next step needs.
								bounty(statement, contract, paid ? "PAID" : "BLOCKED", isBlack ? 20000000 : 1000000);
							}
						}
						voidIfLeaving(statement, contract, state, to);
						final String sql = move(contract, state, to, version + 1);
						if (allowed.containsKey(from) && allowed.get(from).contains(to))
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
	void guardsKeysAndRulesHold() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			// A patron only for a Black Contract; a clan-war Writ records its clans.
			open(statement, 1, true);
			open(statement, 2, false);
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_patron (contract_id, client_char) VALUES (2, 800)"), "a patron on a Writ");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_contract (contract_id, kind, cause, state, mark_char, created_at, deadline, player_principal, conditions, terms_hash, origin_npc, origin_town) VALUES (3, 'WRIT', 'CLAN_WAR', 'OPEN', 900, NOW(3), NOW(3) + INTERVAL 1 DAY, 1000000, '{}', UNHEX(SHA2('w', 256)), 'magistrate.giran', 7)"), "a clan-war Writ without its clans");

			// Guards: no qualifying death, no claim, no paid bounty.
			assertThrows(SQLException.class, () -> statement.execute(move(1, "OPEN", "CLAIMABLE", 1)), "claimable without a qualifying death");
			assertThrows(SQLException.class, () -> statement.execute(move(2, "OPEN", "SETTLING", 1)), "a Writ settling without a qualifying death");
			statement.execute(death(1));
			statement.execute(decision(1, "'EXCLUDED'", "'MARK_COOLDOWN'"));
			assertThrows(SQLException.class, () -> statement.execute(move(1, "OPEN", "CLAIMABLE", 1)), "an excluded death qualifies nothing");
			assertThrows(SQLException.class, () -> statement.execute(claim(1)), "a claim on an excluded death");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_decision SET outcome = 'QUALIFIED', reason = NULL WHERE contract_id = 1"), "a decision changed");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_death SET killer_char = 1 WHERE death_key = 'BL/death/1/1'"), "evidence changed");
			assertThrows(SQLException.class, () -> statement.execute(death(1)), "the same death twice");

			open(statement, 4, true);
			statement.execute(death(4));
			statement.execute(decision(4, "'QUALIFIED'", "NULL"));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_contract SET state = 'CLAIMABLE', settling_death = 'BL/death/1/4', version = 1 WHERE contract_id = 4"), "settling before every required kill is counted");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_contract SET state = 'CLAIMABLE', settling_death = 'BL/death/1/1', kills_done = 1, version = 1 WHERE contract_id = 4"), "settling another contract's death");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_contract SET kills_done = 1, version = 1 WHERE contract_id = 4"), "a kill counted without consuming a decision");
			statement.execute(count(4));
			assertThrows(SQLException.class, () -> statement.execute(count(4)), "the same death counted twice");
			statement.execute(move(4, "OPEN", "CLAIMABLE", 1));
			statement.execute(claim(4));
			assertThrows(SQLException.class, () -> statement.execute(move(4, "CLAIMABLE", "SETTLING", 2)), "settling without a claimed claim");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_claim SET claimed_at = claim_deadline + INTERVAL 1 SECOND, claim_op = UNHEX(REPEAT('72', 16)) WHERE contract_id = 4"), "a late claim");
			statement.execute("UPDATE lc_ledger_claim SET claimed_at = NOW(3), claim_op = UNHEX(REPEAT('72', 16)) WHERE contract_id = 4");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_claim SET claimed_at = NOW(3), claim_op = UNHEX(REPEAT('73', 16)) WHERE contract_id = 4"), "claimed twice");
			statement.execute(move(4, "CLAIMABLE", "SETTLING", 2));
			assertThrows(SQLException.class, () -> statement.execute(move(4, "SETTLING", "SETTLED", 3)), "settled without a paid bounty");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_payment (kind, contract_id, death_key, amount) VALUES ('BOUNTY', 4, 'BL/death/1/1', 20000000)"), "a bounty for a death not being settled");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_payment (kind, contract_id, death_key, funding, amount) VALUES ('BOUNTY', 4, 'BL/death/1/4', 'EXTERNAL', 1)"), "external money on a Black Contract");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_payment (kind, contract_id, death_key, amount) VALUES ('BOUNTY', 4, 'BL/death/1/4', 1)"), "a leg for less than its share");
			statement.execute("INSERT INTO lc_ledger_payment (payment_id, kind, contract_id, death_key, amount) VALUES (40, 'BOUNTY', 4, 'BL/death/1/4', 20000000)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_payment_party (payment_id, beneficiary_char) VALUES (40, 701)"), "a bounty to someone but the killer");
			statement.execute("INSERT INTO lc_ledger_payment_party (payment_id, beneficiary_char) VALUES (40, 700)");
			assertThrows(SQLException.class, () -> statement.execute(move(4, "SETTLING", "OPEN", 3)), "reopened while its bounty is pending");

			// Payments: start pending, resolve once along §3.3; a hold carries its reason and review time.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_payment (kind, contract_id, death_key, amount, state) VALUES ('BOUNTY', 4, 'BL/death/1/4', 20000000, 'PAID')"), "a payment created paid");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_payment (kind, amount) VALUES ('SIGHTING', 1)"), "a sighting payment without a contract");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_payment SET state = 'HELD' WHERE payment_id = 40"), "a hold without reason and review time");
			statement.execute("UPDATE lc_ledger_payment SET state = 'HELD', reason = 'SAME_IP', review_until = NOW(3) + INTERVAL 3 DAY WHERE payment_id = 40");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_payment SET state = 'BLOCKED' WHERE payment_id = 40"), "a held payment blocked");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_payment SET amount = 1 WHERE payment_id = 40"), "an amount changed");
			statement.execute("UPDATE lc_ledger_payment SET state = 'PAID', resolved_by = 'admin' WHERE payment_id = 40");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_payment SET state = 'RETURNED' WHERE payment_id = 40"), "resolved twice");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_payment_party SET expires_at = NOW(3) + INTERVAL 30 DAY WHERE payment_id = 40"), "a party expiring before its contract ends");
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM lc_ledger_payment WHERE payment_id = 40"), "a payment deleted");
			statement.execute(move(4, "SETTLING", "SETTLED", 3));
			statement.execute("UPDATE lc_ledger_payment_party SET expires_at = NOW(3) + INTERVAL 30 DAY WHERE payment_id = 40");

			// A two-kill contract settles on its last counted death, never an earlier one.
			statement.execute("INSERT INTO lc_ledger_contract (contract_id, kind, cause, state, mark_char, created_at, deadline, player_principal, conditions, terms_hash, origin_npc, origin_town, kills_required) VALUES (10, 'BLACK', 'OPEN', 'OPEN', 900, NOW(3), NOW(3) + INTERVAL 1 DAY, 5000000, '{}', UNHEX(SHA2('two', 256)), 'broker.giran', 7, 2)");
			statement.execute("INSERT INTO lc_ledger_patron (contract_id, client_char) VALUES (10, 800)");
			statement.execute(death(10));
			statement.execute(decision(10, "'QUALIFIED'", "NULL"));
			statement.execute(count(10));
			statement.execute("UPDATE lc_ledger_contract SET kills_done = 1, version = 1 WHERE contract_id = 10");
			statement.execute("INSERT INTO lc_ledger_death (death_key, fact_id, victim_char, killer_char, killer_kind, at, region_id, context) VALUES ('BL/death/1/10b', 99010, 900, 701, 'SELF', NOW(3), 12, '{}')");
			statement.execute("INSERT INTO lc_ledger_decision (death_key, contract_id, outcome, decided_at) VALUES ('BL/death/1/10b', 10, 'QUALIFIED', NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_count (contract_id, death_key, ordinal, counted_at) VALUES (10, 'BL/death/1/10b', 1, NOW(3))"), "an ordinal reused");
			statement.execute("INSERT INTO lc_ledger_count (contract_id, death_key, ordinal, counted_at) VALUES (10, 'BL/death/1/10b', 2, NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_contract SET state = 'CLAIMABLE', kills_done = 2, settling_death = 'BL/death/1/10', version = 2 WHERE contract_id = 10"), "settling the earlier kill");
			statement.execute("UPDATE lc_ledger_contract SET state = 'CLAIMABLE', kills_done = 2, settling_death = 'BL/death/1/10b', version = 2 WHERE contract_id = 10");

			// A blocked final bounty reopens the contract, uncounting the kill, so a replacement kill can settle.
			open(statement, 5, false);
			statement.execute(death(5));
			statement.execute(decision(5, "'QUALIFIED'", "NULL"));
			statement.execute(count(5));
			statement.execute(move(5, "OPEN", "SETTLING", 1));
			bounty(statement, 5, "BLOCKED", 1000000);
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_contract SET state = 'OPEN', settling_death = NULL, kills_done = 0, version = 2 WHERE contract_id = 5"), "reopening without voiding the kill");
			voidIfLeaving(statement, 5, "SETTLING", "OPEN");
			statement.execute(move(5, "SETTLING", "OPEN", 2));
			statement.execute("INSERT INTO lc_ledger_death (death_key, fact_id, victim_char, killer_char, killer_kind, at, region_id, context) VALUES ('BL/death/1/5b', 55, 900, 701, 'SELF', NOW(3), 12, '{}')");
			statement.execute("INSERT INTO lc_ledger_decision (death_key, contract_id, outcome, decided_at) VALUES ('BL/death/1/5b', 5, 'QUALIFIED', NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_count SET voided_at = NULL WHERE contract_id = 5 AND death_key = 'BL/death/1/5'"), "a voided count revived");
			statement.execute("INSERT INTO lc_ledger_count (contract_id, death_key, ordinal, counted_at) VALUES (5, 'BL/death/1/5b', 2, NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_contract SET state = 'SETTLING', settling_death = 'BL/death/1/5', kills_done = 1, version = 3 WHERE contract_id = 5"), "a death whose bounty was blocked settled again");
			statement.execute("UPDATE lc_ledger_contract SET state = 'SETTLING', settling_death = 'BL/death/1/5b', kills_done = 1, version = 3 WHERE contract_id = 5");

			// A mixed cursed-weapon Writ: one leg per funding share, unique, created only while settling; the returned
			// external share goes back and the settlement completes on the paid player leg.
			statement.execute("INSERT INTO lc_ledger_contract (contract_id, kind, cause, state, mark_char, created_at, deadline, player_principal, external_principal, conditions, terms_hash, origin_npc, origin_town) VALUES (7, 'WRIT', 'CURSED_WEAPON', 'OPEN', 903, NOW(3), NOW(3) + INTERVAL 1 DAY, 600000, 400000, '{}', UNHEX(SHA2('m', 256)), 'magistrate.aden', 9)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_contract (contract_id, kind, cause, state, mark_char, created_at, deadline, player_principal, external_principal, conditions, terms_hash, origin_npc, origin_town) VALUES (8, 'WRIT', 'KARMA', 'OPEN', 903, NOW(3), NOW(3) + INTERVAL 1 DAY, 600000, 400000, '{}', UNHEX(SHA2('k', 256)), 'magistrate.aden', 9)"), "an external share on a karma Writ");
			statement.execute(death(7));
			statement.execute(decision(7, "'QUALIFIED'", "NULL"));
			statement.execute(count(7));
			assertThrows(SQLException.class, () -> statement.execute(leg(70, 7, "PLAYER", 600000)), "a leg before settling");
			statement.execute(move(7, "OPEN", "SETTLING", 1));
			statement.execute(leg(70, 7, "PLAYER", 600000));
			assertThrows(SQLException.class, () -> statement.execute(leg(72, 7, "PLAYER", 1)), "a second player leg");
			statement.execute("UPDATE lc_ledger_payment SET state = 'PAID' WHERE payment_id = 70");
			assertThrows(SQLException.class, () -> statement.execute(move(7, "SETTLING", "SETTLED", 2)), "settled without its external leg");
			statement.execute(leg(71, 7, "EXTERNAL", 400000));
			statement.execute("UPDATE lc_ledger_payment SET state = 'HELD', reason = 'SAME_IP', review_until = NOW(3) WHERE payment_id = 71");
			assertThrows(SQLException.class, () -> statement.execute(move(7, "SETTLING", "SETTLED", 2)), "settled with a held leg");
			statement.execute("UPDATE lc_ledger_payment SET state = 'RETURNED' WHERE payment_id = 71");
			statement.execute("UPDATE lc_ledger_contract SET state = 'SETTLED', external_principal = 0, version = 2 WHERE contract_id = 7");
			assertThrows(SQLException.class, () -> statement.execute(leg(73, 7, "EXTERNAL", 1)), "a leg after settling");

			// An all-external bounty returned: nothing left, so the contract cannot reopen, only expire.
			statement.execute("INSERT INTO lc_ledger_contract (contract_id, kind, cause, state, mark_char, created_at, deadline, external_principal, conditions, terms_hash, origin_npc, origin_town) VALUES (9, 'WRIT', 'CURSED_WEAPON', 'OPEN', 904, NOW(3), NOW(3) + INTERVAL 1 DAY, 400000, '{}', UNHEX(SHA2('x', 256)), 'magistrate.aden', 9)");
			statement.execute(death(9));
			statement.execute(decision(9, "'QUALIFIED'", "NULL"));
			statement.execute(count(9));
			statement.execute(move(9, "OPEN", "SETTLING", 1));
			statement.execute(leg(90, 9, "EXTERNAL", 400000));
			statement.execute("UPDATE lc_ledger_payment SET state = 'HELD', reason = 'SAME_IP', review_until = NOW(3) WHERE payment_id = 90");
			statement.execute("UPDATE lc_ledger_payment SET state = 'RETURNED' WHERE payment_id = 90");
			voidIfLeaving(statement, 9, "SETTLING", "OPEN");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_contract SET state = 'OPEN', settling_death = NULL, kills_done = 0, version = 2 WHERE contract_id = 9"), "reopened keeping a returned share");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_contract SET state = 'OPEN', settling_death = NULL, kills_done = 0, external_principal = 0, version = 2 WHERE contract_id = 9"), "reopened with nothing left");
			statement.execute("UPDATE lc_ledger_contract SET state = 'EXPIRED', settling_death = NULL, kills_done = 0, external_principal = 0, version = 2 WHERE contract_id = 9");

			// External money only for cursed-weapon Writs.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_funding (contract_id, funding_no, source, amount, op_id, at) VALUES (2, 1, 'CROWN_LAW', 1000000, UNHEX(REPEAT('81', 16)), NOW(3))"), "Crown money on a karma Writ");
			statement.execute("INSERT INTO lc_ledger_contract (contract_id, kind, cause, state, mark_char, created_at, deadline, player_principal, conditions, terms_hash, origin_npc, origin_town) VALUES (6, 'WRIT', 'CURSED_WEAPON', 'OPEN', 902, NOW(3), NOW(3) + INTERVAL 1 DAY, 1000000, '{}', UNHEX(SHA2('c', 256)), 'magistrate.aden', 9)");
			statement.execute("INSERT INTO lc_ledger_funding (contract_id, funding_no, source, amount, op_id, at) VALUES (6, 1, 'CROWN_LAW', 1000000, UNHEX(REPEAT('82', 16)), NOW(3))");

			// Terms fixed; a Black principal fixed; an open Writ only grows; cancel only before any acceptance.
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_contract SET player_principal = 1, version = 2 WHERE contract_id = 1"));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_contract SET conditions = '{\"x\":1}', version = 2 WHERE contract_id = 1"));
			statement.execute("UPDATE lc_ledger_contract SET player_principal = player_principal + 500000, version = 1 WHERE contract_id = 2");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_contract SET player_principal = player_principal - 1, version = 2 WHERE contract_id = 2"));
			statement.execute("INSERT INTO lc_ledger_hunter (char_id, registered_at) VALUES (700, NOW(3)), (900, NOW(3)), (800, NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute(accept(1, 900)), "the mark accepting");
			assertThrows(SQLException.class, () -> statement.execute(accept(1, 800)), "the client accepting");
			statement.execute(accept(1, 700));
			assertThrows(SQLException.class, () -> statement.execute(move(1, "OPEN", "CANCELLED", 1)), "cancelled after an acceptance");

			// Seals: only for a failed hunter, to the contract's mark, once; reverse cases from step 0, by the mark, one step at a time.
			statement.execute("INSERT INTO lc_item_lifecycle (lifecycle_id, object_id, item_id, began_at) VALUES (1, 300100, 21000, NOW(3)), (2, 300101, 21000, NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute(seal(1, 1, 700, 900)), "a seal for an active hunter");
			statement.execute("UPDATE lc_ledger_acceptance SET state = 'FAILED' WHERE contract_id = 1 AND hunter_char = 700");
			assertThrows(SQLException.class, () -> statement.execute(seal(1, 1, 700, 901)), "a seal to someone but the mark");
			statement.execute(seal(1, 1, 700, 900));
			assertThrows(SQLException.class, () -> statement.execute(seal(2, 1, 700, 900)), "a second seal");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_reverse_case (lifecycle_id, holder_char, step, opened_at, expires_at) VALUES (1, 900, 3, NOW(3), NOW(3) + INTERVAL 1 DAY)"), "a case inserted at step 3");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_reverse_case (lifecycle_id, holder_char, opened_at, expires_at) VALUES (1, 800, NOW(3), NOW(3) + INTERVAL 1 DAY)"), "a case held by another");
			statement.execute("INSERT INTO lc_ledger_reverse_case (case_id, lifecycle_id, holder_char, opened_at, expires_at) VALUES (1, 1, 900, NOW(3), NOW(3) + INTERVAL 1 DAY)");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_reverse_case SET step = 2 WHERE case_id = 1"), "skipping a step");
			statement.execute("UPDATE lc_ledger_reverse_case SET step = 1 WHERE case_id = 1");

			// Aliases: unique names kept after retirement; an owner only cleared by retirement; exposure once.
			statement.execute("INSERT INTO lc_ledger_alias (alias_id, name, owner_char, card, created_at) VALUES (1, 'Black Feather', 700, 'FEATHER', NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_alias (name, owner_char, card, created_at) VALUES ('Second', 700, 'COIN', NOW(3))"));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_alias SET owner_char = 800 WHERE alias_id = 1"), "an owner changed");
			statement.execute("UPDATE lc_ledger_alias SET exposed_at = NOW(3) WHERE alias_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_alias SET exposed_at = NULL WHERE alias_id = 1"));
			statement.execute("UPDATE lc_ledger_alias SET owner_char = NULL, retired_at = NOW(3) WHERE alias_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_ledger_alias (name, owner_char, card, created_at) VALUES ('Black Feather', 800, 'COIN', NOW(3))"), "a retired name reused");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_alias SET owner_char = 700, retired_at = NULL WHERE alias_id = 1"), "an alias unretired");

			// Ranks never fall.
			statement.execute("UPDATE lc_ledger_hunter SET rank = 'TRACKER', completed = 3 WHERE char_id = 700");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_ledger_hunter SET rank = 'LISTENER' WHERE char_id = 700"));

			// Offers: an informant trace of the mark, never outliving it, gone with it.
			statement.execute("INSERT INTO lc_trace (trace_id, subject_char, kind, witness_ref, region_id, occurred_at, disclosable_at, expires_at, sensitivity) VALUES (50, 900, 'INFORMANT', 'char:701', 12, NOW(3), NOW(3) + INTERVAL 5 MINUTE, NOW(3) + INTERVAL 1 HOUR, 2), (51, 900, 'SIGHTING', 'gk.giran', 12, NOW(3), NOW(3) + INTERVAL 5 MINUTE, NOW(3) + INTERVAL 1 HOUR, 2)");
			assertThrows(SQLException.class, () -> statement.execute(offer(51, 900, "(SELECT expires_at FROM lc_trace WHERE trace_id = 51)")), "an offer on a non-informant trace");
			assertThrows(SQLException.class, () -> statement.execute(offer(50, 901, "(SELECT expires_at FROM lc_trace WHERE trace_id = 50)")), "an offer about another mark");
			assertThrows(SQLException.class, () -> statement.execute(offer(50, 900, "NOW(3) + INTERVAL 2 HOUR")), "an offer outliving its trace");
			statement.execute(offer(50, 900, "(SELECT expires_at FROM lc_trace WHERE trace_id = 50)"));
			statement.execute("DELETE FROM lc_trace WHERE trace_id = 50");
			try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM lc_ledger_offer"))
			{
				result.next();
				assertEquals(0, result.getInt(1), "the offer dies with its trace");
			}

			// The Ledger's expiring tables are registered.
			try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM lc_expiring_table WHERE owner_module = 'LEDGER'"))
			{
				result.next();
				assertEquals(8, result.getInt(1));
			}
		}
	}

	private static void open(Statement statement, int id, boolean isBlack) throws SQLException
	{
		if (isBlack)
		{
			statement.execute("INSERT INTO lc_ledger_contract (contract_id, kind, cause, state, mark_char, created_at, deadline, player_principal, intel_budget, max_sighting_price, broker_fee, conditions, terms_hash, origin_npc, origin_town) VALUES (" + id + ", 'BLACK', 'OPEN', 'OPEN', 900, NOW(3), NOW(3) + INTERVAL 1 DAY, 20000000, 3000000, 1000000, 500000, '{}', UNHEX(SHA2('t', 256)), 'broker.giran', 7)");
			statement.execute("INSERT INTO lc_ledger_patron (contract_id, client_char) VALUES (" + id + ", 800)");
		}
		else
		{
			statement.execute("INSERT INTO lc_ledger_contract (contract_id, kind, cause, state, mark_char, created_at, deadline, player_principal, conditions, terms_hash, origin_npc, origin_town) VALUES (" + id + ", 'WRIT', 'KARMA', 'OPEN', 900, NOW(3), NOW(3) + INTERVAL 1 DAY, 1000000, '{}', UNHEX(SHA2('w', 256)), 'magistrate.giran', 7)");
		}
	}

	private static String death(int contract)
	{
		return "INSERT INTO lc_ledger_death (death_key, fact_id, victim_char, killer_char, killer_kind, at, region_id, context) VALUES ('BL/death/1/" + contract + "', " + contract + ", 900, 700, 'SELF', NOW(3), 12, '{}')";
	}

	private static String decision(int contract, String outcome, String reason)
	{
		return "INSERT INTO lc_ledger_decision (death_key, contract_id, outcome, reason, decided_at) VALUES ('BL/death/1/" + contract + "', " + contract + ", " + outcome + ", " + reason + ", NOW(3))";
	}

	private static String claim(int contract)
	{
		return "INSERT INTO lc_ledger_claim (contract_id, death_key, claim_deadline) VALUES (" + contract + ", 'BL/death/1/" + contract + "', NOW(3) + INTERVAL 1 DAY)";
	}

	private static String count(int contract)
	{
		return "INSERT INTO lc_ledger_count (contract_id, death_key, ordinal, counted_at) VALUES (" + contract + ", 'BL/death/1/" + contract + "', 1, NOW(3))";
	}

	private static String leg(int payment, int contract, String funding, long amount)
	{
		return "INSERT INTO lc_ledger_payment (payment_id, kind, contract_id, death_key, funding, amount) VALUES (" + payment + ", 'BOUNTY', " + contract + ", 'BL/death/1/" + contract + "', '" + funding + "', " + amount + ")";
	}

	/**
	 * Leaving a settlement voids its death's count first (spec §3.1).
	 */
	private static void voidIfLeaving(Statement statement, int contract, String from, String to) throws SQLException
	{
		if ((from.equals("CLAIMABLE") || from.equals("SETTLING")) && (to.equals("OPEN") || to.equals("EXPIRED")))
		{
			statement.execute("UPDATE lc_ledger_count SET voided_at = NOW(3) WHERE contract_id = " + contract + " AND death_key = 'BL/death/1/" + contract + "' AND voided_at IS NULL");
		}
	}

	private static void bounty(Statement statement, int contract, String state, long amount) throws SQLException
	{
		statement.execute("INSERT INTO lc_ledger_payment (payment_id, kind, contract_id, death_key, amount) VALUES (" + contract + ", 'BOUNTY', " + contract + ", 'BL/death/1/" + contract + "', " + amount + ")");
		statement.execute("UPDATE lc_ledger_payment SET state = '" + state + "'" + (state.equals("BLOCKED") ? ", reason = 'SAME_ACCOUNT'" : "") + " WHERE payment_id = " + contract);
	}

	/**
	 * A transition with the fields spec §3.1 changes with it: entering a settlement names the contract's own death and counts its kill; leaving one clears it and uncounts the kill.
	 */
	private static String move(int contract, String from, String to, int version)
	{
		String extra = ", kills_done = (SELECT COUNT(*) FROM lc_ledger_count WHERE contract_id = " + contract + " AND voided_at IS NULL)";
		if ((from.equals("OPEN") || from.equals("REVIEW")) && (to.equals("CLAIMABLE") || to.equals("SETTLING")))
		{
			extra += ", settling_death = 'BL/death/1/" + contract + "'";
		}
		else if ((from.equals("CLAIMABLE") || from.equals("SETTLING")) && (to.equals("OPEN") || to.equals("EXPIRED")))
		{
			extra += ", settling_death = NULL";
		}
		return "UPDATE lc_ledger_contract SET state = '" + to + "', version = " + version + extra + " WHERE contract_id = " + contract;
	}

	private static String accept(int contract, int hunter)
	{
		return "INSERT INTO lc_ledger_acceptance (contract_id, hunter_char, accepted_at, card_mode) VALUES (" + contract + ", " + hunter + ", NOW(3), 'AT_KILL')";
	}

	private static String seal(long lifecycle, int contract, int hunter, int mark)
	{
		return "INSERT INTO lc_ledger_seal (lifecycle_id, contract_id, hunter_char, mark_char, value_band, origin_town, issued_op) VALUES (" + lifecycle + ", " + contract + ", " + hunter + ", " + mark + ", 3, 7, UNHEX(REPEAT('61', 16)))";
	}

	private static String offer(int trace, int mark, String expires)
	{
		return "INSERT INTO lc_ledger_offer (informant_char, mark_char, trace_id, price, expires_at) VALUES (701, " + mark + ", " + trace + ", 100000, " + expires + ")";
	}
}
