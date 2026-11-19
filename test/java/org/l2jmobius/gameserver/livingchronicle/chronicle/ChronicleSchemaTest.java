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
 * AT-CHR-00 (docs/spec/chronicle.md): the migration applies; the registry holds every module's publication types; triggers and keys refuse a row published sooner than its type's delay, a changed, deleted or twice-superseded row, a second issue for a day, a row in two issues or in an issue before its publish time, a second monument for a history row, an application id or a standing slot, a retired monument changed, an event on an ended saga, a battle sample below two, a letter over its length or outside its machine; the visible view hides unpublished and superseded rows.
 */
class ChronicleSchemaTest
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
	void theRegistryHoldsEveryModulesTypes() throws SQLException
	{
		final Set<String> types = new TreeSet<>();
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery("SELECT t.pub_type FROM lc_pub_type t WHERE EXISTS (SELECT 1 FROM lc_pub_field_rule r WHERE r.pub_type = t.pub_type)"))
		{
			while (result.next())
			{
				types.add(result.getString(1));
			}
		}
		assertEquals(new TreeSet<>(Set.of("pact_registered", "pact_completed", "ledger_wanted", "ledger_legend", "signal_demand", "world_market", "world_caravan", "world_map_words", "chr_realm", "chr_history", "chr_monument", "chr_saga_entry", "chr_obituary", "chr_replay", "chr_arena", "chr_letter", "chr_gazette")), types);
	}

	@Test
	void theKeysAndChecksHold() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			// Rows: published no sooner than their type allows; frozen; superseded once by a later row; never deleted.
			assertThrows(SQLException.class, () -> statement.execute(row(1, "'chr_history'", "NOW(3) - INTERVAL 2 HOUR", "NOW(3)")), "history published two hours after the event");
			statement.execute(row(1, "'chr_history'", "NOW(3) - INTERVAL 2 DAY", "NOW(3) - INTERVAL 1 DAY"));
			statement.execute(row(2, "'chr_realm'", "NOW(3)", "NOW(3)"));
			statement.execute(row(3, "'chr_history'", "NOW(3)", "NOW(3) + INTERVAL 1 DAY"));
			statement.execute(row(4, "'chr_realm'", "NOW(3)", "NOW(3)"));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pub_row SET fields = '{\"kind\":\"other\"}' WHERE pub_row_id = 2"), "changing a row");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pub_row SET superseded_by = 1 WHERE pub_row_id = 2"), "superseded by an earlier row");
			statement.execute("UPDATE lc_pub_row SET superseded_by = 4 WHERE pub_row_id = 2");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_pub_row SET superseded_by = 3 WHERE pub_row_id = 2"), "superseded twice");
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM lc_pub_row WHERE pub_row_id = 4"), "deleting a row");
			assertThrows(SQLException.class, () -> statement.execute(row(9, "'no_such_type'", "NOW(3)", "NOW(3)")), "an unregistered type");
			try (ResultSet result = statement.executeQuery("SELECT GROUP_CONCAT(pub_row_id ORDER BY pub_row_id) FROM lc_pub_visible"))
			{
				result.next();
				assertEquals("1,4", result.getString(1), "the view hides the superseded row and the one not yet published");
			}

			// Issues: one per day, a row in at most one issue and only once it is published.
			statement.execute("INSERT INTO lc_chr_issue (issue_no, day, closed_fact_id, composed_at) VALUES (1, CURDATE(), 500, NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_issue (issue_no, day, closed_fact_id, composed_at) VALUES (2, CURDATE(), 501, NOW(3))"), "a second issue for a day");
			statement.execute("INSERT INTO lc_chr_issue_entry (issue_no, seq, pub_row_id, section) VALUES (1, 1, 1, 'HISTORY')");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_issue_entry (issue_no, seq, pub_row_id, section) VALUES (1, 2, 3, 'HISTORY')"), "a row before its publish time");
			statement.execute("INSERT INTO lc_chr_issue (issue_no, day, closed_fact_id, composed_at) VALUES (2, CURDATE() + INTERVAL 1 DAY, 502, NOW(3) + INTERVAL 2 DAY)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_issue_entry (issue_no, seq, pub_row_id, section) VALUES (2, 1, 1, 'HISTORY')"), "a row in two issues");

			// Monuments: once per history row, application and standing slot; they only retire, once.
			statement.execute("INSERT INTO lc_chr_monument_slot (town_id, slot, x, y, z, heading) VALUES (7, 1, 81000, 148000, -3400, 0), (7, 2, 81100, 148000, -3400, 0)");
			statement.execute(monument(1, 1, "UNHEX('AA000000000000000000000000000001')"));
			assertThrows(SQLException.class, () -> statement.execute(monument(2, 1, "UNHEX('AA000000000000000000000000000001')")), "one application twice");
			assertThrows(SQLException.class, () -> statement.execute(monument(1, 2, "UNHEX('AA000000000000000000000000000002')")), "one history row twice");
			assertThrows(SQLException.class, () -> statement.execute(monument(3, 1, "UNHEX('AA000000000000000000000000000003')")), "two standing in one slot");
			statement.execute("UPDATE lc_chr_monument SET state = 'RETIRED', retired_at = NOW(3) WHERE history_row_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_chr_monument SET state = 'STANDING', retired_at = NULL WHERE history_row_id = 1"), "raising a retired monument again");
			statement.execute(monument(3, 1, "UNHEX('AA000000000000000000000000000003')"));

			// Sagas take no event once ended; battle samples never show fewer than two.
			statement.execute("INSERT INTO lc_chr_saga (lifecycle_id, item_id, began_at, began_kind) VALUES (900, 6364, NOW(3), 'CRAFTED')");
			statement.execute("INSERT INTO lc_chr_saga_event (lifecycle_id, seq, kind, at, char_id) VALUES (900, 1, 'OWNER', NOW(3), 268477000)");
			statement.execute("UPDATE lc_chr_saga SET ended_at = NOW(3), end_kind = 'CRYSTALLIZED' WHERE lifecycle_id = 900");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_saga_event (lifecycle_id, seq, kind, at) VALUES (900, 2, 'PVP_KILL', NOW(3))"), "an event on an ended saga");
			statement.execute("INSERT INTO lc_chr_battle (battle_key, kind, place_id, started_at) VALUES ('SIEGE/3/1', 'CASTLE_SIEGE', 3, NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_battle_sample (battle_key, minute, cell, side, count) VALUES ('SIEGE/3/1', 0, 14, 'ATTACKER', 1)"), "a cell showing one person");

			// Letters: bounded, queued first, decided once, published only when approved.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_letter (author_char, text, submitted_at) VALUES (268477000, REPEAT('x', 601), NOW(3))"), "a letter over its length");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_chr_letter (author_char, text, submitted_at, state) VALUES (268477000, 'hello', NOW(3), 'APPROVED')"), "a letter born approved");
			statement.execute("INSERT INTO lc_chr_letter (letter_id, author_char, text, submitted_at) VALUES (1, 268477000, 'The roads of Dion are safe again.', NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_chr_letter SET state = 'PUBLISHED', moderator = 'gm', decided_at = NOW(3) WHERE letter_id = 1"), "published without approval");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_chr_letter SET state = 'REJECTED', moderator = 'gm', decided_at = NOW(3) WHERE letter_id = 1"), "rejected without a reason");
			statement.execute("UPDATE lc_chr_letter SET state = 'APPROVED', moderator = 'gm', decided_at = NOW(3) WHERE letter_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_chr_letter SET text = 'changed' WHERE letter_id = 1"), "editing an approved letter");
			statement.execute("UPDATE lc_chr_letter SET state = 'PUBLISHED' WHERE letter_id = 1");
		}
	}

	private static String row(long id, String type, String eventAt, String publishAt)
	{
		return "INSERT INTO lc_pub_row (pub_row_id, pub_type, written_at, event_at, publish_at, fields, source_fact_from, source_fact_to) VALUES (" + id + ", " + type + ", LEAST(NOW(3), " + publishAt + "), " + eventAt + ", " + publishAt + ", '{\"kind\":\"siege\"}', 10, 20)";
	}

	private static String monument(long historyRow, int slot, String application)
	{
		return "INSERT INTO lc_chr_monument (town_id, slot, history_row_id, application_id, raised_at) VALUES (7, " + slot + ", " + historyRow + ", " + application + ", NOW(3))";
	}
}
