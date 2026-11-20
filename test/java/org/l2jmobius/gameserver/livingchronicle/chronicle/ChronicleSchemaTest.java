package org.l2jmobius.gameserver.livingchronicle.chronicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * AT-CHR-00 (docs/spec/chronicle.md): the migration applies; the registry holds exactly the types, fields, rules and delays the specs list; triggers and keys refuse a row before its delay, one dated before its newest source fact or with a missing one, a changed or deleted row, a supersession by an earlier, other-type row or a second one, a second day or issue for a day, a row in two issues, in the wrong section or written after composition, a monument for an unwritten or superseded row, a second monument for a row, an application or a standing slot, two live sagas on one object, an event on an ended saga, a battle sample below two, a letter over its length or outside its machine; the views hide superseded rows and unresolved names. Part of AT-CHR-01: the reader roles read the views and nothing else.
 */
class ChronicleSchemaTest
{
	/** Every publication type with its delay, location flag and field rules, as the specs list them. */
	private static final Set<String> REGISTRY = new TreeSet<>(Set.of(
		// PACT (pact.md §4.10)
		"pact_registered 0 0 contract_type:TEXT_FROM_FACT day:DAY party_characters:NAME_TOKEN party_clans:PUBLIC_ENTITY",
		"pact_completed 0 0 contract_type:TEXT_FROM_FACT day:DAY outcome:TEXT_FROM_FACT party_characters:NAME_TOKEN party_clans:PUBLIC_ENTITY",
		"pact_completed_terms 0 0 clause_kinds:TEXT_FROM_FACT contract_type:TEXT_FROM_FACT day:DAY outcome:TEXT_FROM_FACT party_characters:NAME_TOKEN party_clans:PUBLIC_ENTITY totals:COUNT",
		// The Ledger (black-ledger.md §4.12)
		"ledger_wanted 0 0 cause:TEXT_FROM_FACT day:DAY mark:LEDGER_WRIT_MARK open_total:COUNT",
		"ledger_legend 1440 1 alias:LEDGER_ALIAS day:DAY mark:LEDGER_LEGEND_MARK outcome:TEXT_FROM_FACT territory:AREA_TERRITORY value_band:TEXT_FROM_FACT",
		// Signal (aden-signal.md §4.8)
		"signal_demand 1440 1 band:TEXT_FROM_FACT day:DAY objective:TEXT_FROM_FACT territory:AREA_TERRITORY",
		// Aden Remembers (aden-remembers.md §4.8)
		"world_market 1440 1 day:DAY direction:TEXT_FROM_FACT family:TEXT_FROM_FACT territory:AREA_TERRITORY",
		"world_caravan 1440 1 day:DAY destination:PUBLIC_ENTITY origin:PUBLIC_ENTITY outcome:TEXT_FROM_FACT",
		"world_map_words 1440 1 conflict:TEXT_FROM_FACT prosperity:TEXT_FROM_FACT territory:AREA_TERRITORY week:DAY",
		// Chronicle (chronicle.md §2.3)
		"chr_realm 0 0 castle:PUBLIC_ENTITY clan:PUBLIC_ENTITY day:DAY fortress:PUBLIC_ENTITY kind:TEXT_FROM_FACT lord:NAME_TOKEN",
		"chr_history 1440 1 characters:NAME_TOKEN clans:PUBLIC_ENTITY counts:COUNT kind:TEXT_FROM_FACT period:DAY territory:AREA_TERRITORY title:TEXT_FROM_FACT",
		"chr_monument 1440 1 history_row:PUB_REF slot:COUNT town:PUBLIC_ENTITY",
		"chr_saga_entry 1440 1 characters:NAME_TOKEN counts:COUNT day:DAY entry_kind:TEXT_FROM_FACT item:SAGA_ITEM territory:AREA_TERRITORY",
		"chr_obituary 1440 1 cause:TEXT_FROM_FACT day:DAY item:SAGA_ITEM",
		"chr_replay 1440 1 frames:REPLAY_FRAMES history_row:PUB_REF",
		"chr_arena 0 0 class:TEXT_FROM_FACT heroes:NAME_TOKEN period:DAY",
		"chr_letter 1440 1 author:NAME_TOKEN day:DAY text:MODERATED_TEXT",
		"chr_gazette 0 0 day:DAY issue:COUNT sections:PUB_REF"));

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
	void theRegistryHoldsExactlyTheListedTypes() throws SQLException
	{
		final Set<String> found = new TreeSet<>();
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery("SELECT t.pub_type, t.min_delay_minutes, t.location_related, GROUP_CONCAT(CONCAT(r.field, ':', r.rule) ORDER BY r.field SEPARATOR ' ') FROM lc_pub_type t LEFT JOIN lc_pub_field_rule r ON r.pub_type = t.pub_type GROUP BY t.pub_type, t.min_delay_minutes, t.location_related"))
		{
			while (result.next())
			{
				found.add(result.getString(1) + " " + result.getInt(2) + " " + result.getInt(3) + " " + result.getString(4));
			}
		}
		assertEquals(REGISTRY, found);
	}

	@Test
	void theKeysAndChecksHold() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			// Source facts: one two days ago, one now.
			statement.execute(fact(1, "NOW(3) - INTERVAL 2 DAY"));
			statement.execute(fact(2, "NOW(3)"));
			// Reversed order: a current fact with the lower id, an old one with the higher id. The delay counts from the newest
			// occurrence in the whole range, not from the range's last id (review round 5, finding 35).
			statement.execute(fact(10, "NOW(3)"));
			statement.execute(fact(11, "NOW(3) - INTERVAL 2 DAY"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pub_row (pub_row_id, pub_type, written_at, event_at, fields, source_fact_from, source_fact_to) VALUES (90, 'chr_history', NOW(3), NOW(3) - INTERVAL 2 DAY, '{\"kind\":\"siege\"}', 10, 11)"), "the newest occurrence is the lower id");

			// Rows are written only once due, counted from their newest source fact.
			assertThrows(SQLException.class, () -> statement.execute(row(1, "'chr_history'", "NOW(3) - INTERVAL 2 DAY", 2)), "dated before its newest source fact");
			assertThrows(SQLException.class, () -> statement.execute(row(1, "'chr_history'", "NOW(3)", 2)), "history written the moment it happened");
			assertThrows(SQLException.class, () -> statement.execute(row(1, "'chr_history'", "NOW(3) - INTERVAL 2 DAY", 99)), "a missing source fact");
			statement.execute(row(1, "'chr_history'", "NOW(3) - INTERVAL 2 DAY", 1));
			statement.execute(row(2, "'chr_realm'", "NOW(3)", 2));
			statement.execute(row(3, "'chr_history'", "NOW(3) - INTERVAL 2 DAY", 1));
			statement.execute(row(4, "'chr_realm'", "NOW(3)", 2));
			statement.execute(row(5, "'chr_history'", "NOW(3) - INTERVAL 2 DAY", 1));

			// A supplied future written_at is overridden by the clock: still refused before its delay.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_pub_row (pub_row_id, pub_type, written_at, event_at, fields, source_fact_from, source_fact_to) VALUES (9, 'chr_history', NOW(3) + INTERVAL 2 DAY, NOW(3), '{}', 1, 2)"), "a future written_at");
			statement.execute(row(6, "'chr_realm'", "NOW(3)", 2));
			statement.execute(row(7, "'chr_realm'", "NOW(3)", 2));

			// Frozen; corrected once by a later current row of its type, or withdrawn once; never deleted.
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pub_row SET fields = '{\"kind\":\"other\"}' WHERE pub_row_id = 2"), "changing a row");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pub_row SET superseded_by = 3 WHERE pub_row_id = 2"), "corrected by another type");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pub_row SET superseded_by = 1 WHERE pub_row_id = 3"), "corrected by an earlier row");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pub_row SET superseded_by = 4, pub_row_id = 40 WHERE pub_row_id = 2"), "a changed id");
			statement.execute("UPDATE lc_pub_row SET superseded_by = 6 WHERE pub_row_id = 4");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pub_row SET superseded_by = 4 WHERE pub_row_id = 2"), "corrected by a row that is no longer current");
			statement.execute("UPDATE lc_pub_row SET superseded_by = 6 WHERE pub_row_id = 2");
			statement.execute("UPDATE lc_pub_row SET superseded_by = 7 WHERE pub_row_id = 6");
			statement.execute("UPDATE lc_pub_row SET withdrawn_at = NOW(3) WHERE pub_row_id = 3");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pub_row SET withdrawn_at = NOW(3) WHERE pub_row_id = 2"), "withdrawing a corrected row");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pub_row SET superseded_by = 5 WHERE pub_row_id = 3"), "correcting a withdrawn row");
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM lc_pub_row WHERE pub_row_id = 5"), "deleting a row");
			assertEquals("1,5,7", one(statement, "SELECT GROUP_CONCAT(pub_row_id ORDER BY pub_row_id) FROM lc_pub_visible"), "the view hides corrected and withdrawn rows");
			assertEquals("2>7,4>7,6>7", one(statement, "SELECT GROUP_CONCAT(CONCAT(old_row_id, '>', next_row_id) ORDER BY old_row_id) FROM lc_pub_successor"), "every correction leads to the final current row");

			// Names: bases only on their row types; an opt-in token only of the current consent; re-consent revives nothing.
			statement.execute("INSERT INTO lc_pub_name (char_id, name, opted_in, consent_gen, page_key, changed_at) VALUES (268477000, 'Slava', TRUE, 0, UNHEX('B1000000000000000000000000000000'), NOW(3)), (268477001, 'Ghost', FALSE, 0, UNHEX('B2000000000000000000000000000000'), NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute(token("A9", 1, 268477000, "'HERO'", 0)), "a hero basis on a history row");
			assertThrows(SQLException.class, () -> statement.execute(token("A8", 1, 268477000, "'OPTED_IN'", 5)), "an opt-in of another consent generation");
			statement.execute(token("A1", 1, 268477000, "'OPTED_IN'", 0));
			statement.execute(token("A2", 1, 268477001, "'OPTED_IN'", 0));
			statement.execute(token("A3", 7, 268477001, "'CASTLE_LORD'", 0));
			assertEquals("Slava,Ghost", one(statement, "SELECT GROUP_CONCAT(name ORDER BY token) FROM lc_pub_name_visible"), "opted in, and a lord on his reign's row; not the unconsented");
			assertEquals("Slava", one(statement, "SELECT GROUP_CONCAT(name) FROM lc_pub_character_page WHERE page_key = UNHEX('B1000000000000000000000000000000')"), "the opted-in character's page");
			statement.execute("UPDATE lc_pub_name SET opted_in = FALSE, consent_gen = consent_gen + 1, page_key = UNHEX('B3000000000000000000000000000000') WHERE char_id = 268477000");
			assertEquals("Ghost", one(statement, "SELECT GROUP_CONCAT(name ORDER BY token) FROM lc_pub_name_visible"), "withdrawal unnames at once");
			statement.execute("UPDATE lc_pub_name SET opted_in = TRUE WHERE char_id = 268477000");
			assertEquals("Ghost", one(statement, "SELECT GROUP_CONCAT(name ORDER BY token) FROM lc_pub_name_visible"), "opting in again revives no old token");
			assertEquals(null, one(statement, "SELECT GROUP_CONCAT(name) FROM lc_pub_character_page"), "no page until a new token");

			// Days and issues: one per day; entries of their sections' types, written before composition, in one issue.
			statement.execute("INSERT INTO lc_chr_day (day, closed_at, fact_id) VALUES (CURDATE() - INTERVAL 1 DAY, NOW(3), 10)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_day (day, closed_at, fact_id) VALUES (CURDATE() - INTERVAL 1 DAY, NOW(3), 11)"), "a day closed twice");
			statement.execute("INSERT INTO lc_chr_issue (issue_no, day, composed_at) VALUES (1, CURDATE() - INTERVAL 1 DAY, NOW(3) + INTERVAL 1 MINUTE)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_issue (issue_no, day, composed_at) VALUES (2, CURDATE() - INTERVAL 1 DAY, NOW(3))"), "a second issue for a day");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_issue_entry (issue_no, seq, pub_row_id, section) VALUES (1, 1, 1, 'REALM')"), "history in the realm section");
			statement.execute("INSERT INTO lc_chr_issue_entry (issue_no, seq, pub_row_id, section) VALUES (1, 1, 1, 'HISTORY'), (1, 2, 7, 'REALM')");
			statement.execute("UPDATE lc_chr_day SET composed_at = NOW(3), issue_no = 1 WHERE day = CURDATE() - INTERVAL 1 DAY");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_chr_day SET composed_at = NOW(3), issue_no = NULL WHERE day = CURDATE() - INTERVAL 1 DAY"), "a day composed twice");
			statement.execute("INSERT INTO lc_chr_day (day, closed_at, fact_id) VALUES (CURDATE() - INTERVAL 2 DAY, NOW(3), 12)");
			statement.execute("INSERT INTO lc_chr_issue (issue_no, day, composed_at) VALUES (2, CURDATE() - INTERVAL 2 DAY, NOW(3) - INTERVAL 1 DAY)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_issue_entry (issue_no, seq, pub_row_id, section) VALUES (2, 1, 5, 'HISTORY')"), "a row written after its issue's composition");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_issue_entry (issue_no, seq, pub_row_id, section) VALUES (2, 1, 1, 'HISTORY')"), "a row in two issues");

			// Monuments: for a written, current history row; once per row, application and standing slot.
			statement.execute("INSERT INTO lc_chr_monument_slot (town_id, slot, x, y, z, heading) VALUES (7, 1, 81000, 148000, -3400, 0), (7, 2, 81100, 148000, -3400, 0)");
			assertThrows(SQLException.class, () -> statement.execute(monument(3, 1, "UNHEX('AA000000000000000000000000000009')")), "a monument for a withdrawn row");
			assertThrows(SQLException.class, () -> statement.execute(monument(7, 1, "UNHEX('AA000000000000000000000000000008')")), "a monument for a realm row");
			statement.execute(monument(1, 1, "UNHEX('AA000000000000000000000000000001')"));
			assertThrows(SQLException.class, () -> statement.execute(monument(5, 2, "UNHEX('AA000000000000000000000000000001')")), "one application twice");
			assertThrows(SQLException.class, () -> statement.execute(monument(1, 2, "UNHEX('AA000000000000000000000000000002')")), "one history row twice");
			assertThrows(SQLException.class, () -> statement.execute(monument(5, 1, "UNHEX('AA000000000000000000000000000003')")), "two standing in one slot");
			statement.execute("UPDATE lc_chr_monument SET state = 'RETIRED', retired_at = NOW(3) WHERE history_row_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_chr_monument SET state = 'STANDING', retired_at = NULL WHERE history_row_id = 1"), "raising a retired monument again");
			statement.execute(monument(5, 1, "UNHEX('AA000000000000000000000000000003')"));

			// Sagas: one live saga per object; no event once ended. Samples never show fewer than two.
			statement.execute("INSERT INTO lc_chr_saga (lifecycle_id, item_id, object_id, began_at, began_kind) VALUES (900, 6364, 268500000, NOW(3), 'CRAFTED')");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_saga (lifecycle_id, item_id, object_id, began_at, began_kind) VALUES (901, 6364, 268500000, NOW(3), 'LOOTED')"), "two live sagas on one object");
			statement.execute("INSERT INTO lc_chr_saga_event (lifecycle_id, seq, kind, at, char_id) VALUES (900, 1, 'OWNER', NOW(3), 268477000)");
			statement.execute("UPDATE lc_chr_saga SET ended_at = NOW(3), end_kind = 'CRYSTALLIZED' WHERE lifecycle_id = 900");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_saga_event (lifecycle_id, seq, kind, at) VALUES (900, 2, 'PVP_KILL', NOW(3))"), "an event on an ended saga");
			statement.execute("INSERT INTO lc_chr_saga (lifecycle_id, item_id, object_id, began_at, began_kind) VALUES (901, 6364, 268500000, NOW(3), 'LOOTED')");
			statement.execute("INSERT INTO lc_chr_battle (battle_key, kind, place_id, started_at) VALUES ('SIEGE/3/1', 'CASTLE_SIEGE', 3, NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_battle_sample (battle_key, minute, cell, side, count) VALUES ('SIEGE/3/1', 0, 14, 'ATTACKER', 1)"), "a cell showing one person");

			// Letters: bounded, queued first, decided once, published only when approved, withdrawn with their author.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_letter (author_char, text, submitted_at) VALUES (268477000, REPEAT('x', 601), NOW(3))"), "a letter over its length");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_letter (author_char, text, submitted_at, state) VALUES (268477000, 'hello', NOW(3), 'APPROVED')"), "a letter born approved");
			statement.execute("INSERT INTO lc_chr_letter (letter_id, author_char, text, submitted_at) VALUES (1, 268477000, 'The roads of Dion are safe again.', NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_chr_letter SET state = 'PUBLISHED', moderator = 'gm', decided_at = NOW(3) WHERE letter_id = 1"), "published without approval");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_chr_letter SET state = 'REJECTED', moderator = 'gm', decided_at = NOW(3) WHERE letter_id = 1"), "rejected without a reason");
			// A letter is an authoritative replay input: its identity and submission time never change, even with a valid transition.
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_chr_letter SET state = 'APPROVED', moderator = 'gm', decided_at = NOW(3), submitted_at = NOW(3) - INTERVAL 1 DAY WHERE letter_id = 1"), "a moved submission time");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_chr_letter SET state = 'APPROVED', moderator = 'gm', decided_at = NOW(3), letter_id = 77 WHERE letter_id = 1"), "a changed letter id");
			statement.execute("UPDATE lc_chr_letter SET state = 'APPROVED', moderator = 'gm', decided_at = NOW(3) WHERE letter_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_chr_letter SET text = 'changed' WHERE letter_id = 1"), "editing an approved letter");
			statement.execute("UPDATE lc_chr_letter SET state = 'PUBLISHED' WHERE letter_id = 1");
			statement.execute("UPDATE lc_chr_letter SET state = 'WITHDRAWN' WHERE letter_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_chr_letter SET state = 'PUBLISHED' WHERE letter_id = 1"), "republishing a withdrawn letter");
		}
	}

	@Test
	void theReadersSeeOnlyTheViews() throws SQLException
	{
		for (String role : new String[] { "lc_chronicle_web", "lc_chronicle_narrator" })
		{
			final String user = role.substring(3);
			try (Connection connection = _database.connect();
				Statement statement = connection.createStatement())
			{
				statement.execute("CREATE USER IF NOT EXISTS '" + user + "'@'%' IDENTIFIED BY 'pw'");
				statement.execute("GRANT " + role + " TO '" + user + "'@'%'");
				statement.execute("SET DEFAULT ROLE " + role + " FOR '" + user + "'@'%'");
			}
			try (Connection reader = _database.connectAs(user, "pw");
				Statement statement = reader.createStatement())
			{
				for (String view : new String[] { "lc_pub_visible", "lc_pub_successor", "lc_pub_name_visible", "lc_pub_character_page", "lc_chr_prose" })
				{
					statement.executeQuery("SELECT COUNT(*) FROM " + view).close();
				}
				for (String table : new String[] { "lc_pub_row", "lc_pub_name", "lc_pub_name_ref", "lc_chr_pending", "lc_chr_battle_sample", "lc_chr_saga", "lc_chr_letter", "lc_fact", "lc_ledger_contract" })
				{
					assertThrows(SQLException.class, () -> statement.executeQuery("SELECT COUNT(*) FROM " + table).close(), role + " reading " + table);
				}
				final String insert = "INSERT INTO lc_chr_prose (dto_hash, lang, style, choices, checked_at) VALUES (UNHEX(SHA2('" + role + "', 256)), 'EN', 'plain', '[]', NOW(3))";
				if (role.equals("lc_chronicle_narrator"))
				{
					statement.execute(insert);
				}
				else
				{
					assertThrows(SQLException.class, () -> statement.execute(insert), "the web role writing prose");
				}
			}
		}
	}

	private static String one(Statement statement, String query) throws SQLException
	{
		try (ResultSet result = statement.executeQuery(query))
		{
			result.next();
			return result.getString(1);
		}
	}

	private static String token(String hex, long row, int character, String basis, int generation)
	{
		return "INSERT INTO lc_pub_name_ref (token, pub_row_id, char_id, basis, consent_gen) VALUES (UNHEX('" + hex + "000000000000000000000000000000'), " + row + ", " + character + ", " + basis + ", " + generation + ")";
	}

	private static String fact(long id, String at)
	{
		return "INSERT INTO lc_fact (id, occurred_at, stream, type, schema_ver, logical_time, region_id, sensitivity, payload, batch_id, config_ver, captured_at) VALUES (" + id + ", " + at + ", 'test', 1, 1, " + id + ", 0, 0, '{}', 1, 1, NOW(3))";
	}

	private static String row(long id, String type, String eventAt, long sourceFact)
	{
		return "INSERT INTO lc_pub_row (pub_row_id, pub_type, written_at, event_at, fields, source_fact_from, source_fact_to) VALUES (" + id + ", " + type + ", NOW(3), " + eventAt + ", '{\"kind\":\"siege\"}', 1, " + sourceFact + ")";
	}

	private static String monument(long historyRow, int slot, String application)
	{
		return "INSERT INTO lc_chr_monument (town_id, slot, history_row_id, application_id, raised_at) VALUES (7, " + slot + ", " + historyRow + ", " + application + ", NOW(3))";
	}
}
