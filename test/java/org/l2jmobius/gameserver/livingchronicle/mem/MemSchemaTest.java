package org.l2jmobius.gameserver.livingchronicle.mem;

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
 * AT-MEM-00 (docs/spec/wayfarers-memory.md): the migration applies; keys and checks refuse an import without its author and page, a lead without its intel reference (unless an expired lead without content), intel on a non-lead row, a second row for one coalescing key, a durable row with an expiry or a raw row without one, a shared fact carrying a lead, a changed shared fact, a note over its bound; expiring tables registered.
 */
class MemSchemaTest
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
	void theKeysAndChecksHold() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			// A known observation, coalesced: a second row with the same key is refused (repeats update times).
			statement.execute(observation("'OBTAINED_FROM'", "'ITEM'", 1864, "'MONSTER'", 20537, "'{\"count\":3}'", "'KNOWN'", "NULL", "NULL", "NULL", "'RAW'", "NOW(3) + INTERVAL 30 DAY"));
			assertThrows(SQLException.class, () -> statement.execute(observation("'OBTAINED_FROM'", "'ITEM'", 1864, "'MONSTER'", 20537, "'{\"count\":1}'", "'KNOWN'", "NULL", "NULL", "NULL", "'RAW'", "NOW(3) + INTERVAL 30 DAY")), "a second row for one coalescing key");
			statement.execute("UPDATE lc_mem_observation SET times = times + 1, last_at = NOW(3) WHERE relation = 'OBTAINED_FROM'");

			// Imports are TOLD with their author and page, and only then.
			assertThrows(SQLException.class, () -> statement.execute(observation("'SOLD_BY'", "'ITEM'", 1864, "'PLAYER'", 268477010, "'{\"price\":78000}'", "'TOLD'", "NULL", "NULL", "NULL", "'RAW'", "NOW(3) + INTERVAL 30 DAY")), "an import without its author");
			assertThrows(SQLException.class, () -> statement.execute(observation("'SOLD_BY'", "'ITEM'", 1864, "'PLAYER'", 268477010, "'{\"price\":78000}'", "'KNOWN'", "268477011", "5", "NULL", "'RAW'", "NOW(3) + INTERVAL 30 DAY")), "an import passed off as known");
			statement.execute(observation("'SOLD_BY'", "'ITEM'", 1864, "'PLAYER'", 268477010, "'{\"price\":78000}'", "'TOLD'", "268477011", "5", "NULL", "'RAW'", "NOW(3) + INTERVAL 30 DAY"));

			// Leads hold only an intel reference; expired, nothing but the day; intel nowhere else.
			assertThrows(SQLException.class, () -> statement.execute(observation("'LEAD'", "'PLAYER'", 268477099, "NULL", null, "'{}'", "'KNOWN'", "NULL", "NULL", "NULL", "'RAW'", "NOW(3) + INTERVAL 1 DAY")), "a live lead without its intel reference");
			assertThrows(SQLException.class, () -> statement.execute(observation("'SOLD_BY'", "'ITEM'", 57, "NULL", null, "'{}'", "'KNOWN'", "NULL", "NULL", "4242", "'RAW'", "NOW(3) + INTERVAL 1 DAY")), "intel on a non-lead row");
			statement.execute(observation("'LEAD'", "'PLAYER'", 268477099, "NULL", null, "'{}'", "'KNOWN'", "NULL", "NULL", "4242", "'RAW'", "NOW(3) + INTERVAL 1 DAY"));
			assertThrows(SQLException.class, () -> statement.execute(observation("'LEAD'", "'PLAYER'", 268477098, "NULL", null, "'{\"region\":12,\"day\":\"x\"}'", "'HISTORICAL'", "NULL", "NULL", "NULL", "'RAW'", "NOW(3) + INTERVAL 1 DAY")), "an expired lead keeping a place");
			statement.execute(observation("'LEAD'", "'PLAYER'", 268477098, "NULL", null, "'{\"day\":\"2026-11-19\"}'", "'HISTORICAL'", "NULL", "NULL", "NULL", "'RAW'", "NOW(3) + INTERVAL 1 DAY"));

			// Tiers: durable rows never expire, raw and summary rows always do.
			assertThrows(SQLException.class, () -> statement.execute(observation("'VISITED'", "'REGION'", 12, "NULL", null, "'{}'", "'KNOWN'", "NULL", "NULL", "NULL", "'RAW'", "NULL")), "a raw row without expiry");
			assertThrows(SQLException.class, () -> statement.execute(observation("'VISITED'", "'REGION'", 12, "NULL", null, "'{}'", "'KNOWN'", "NULL", "NULL", "NULL", "'DURABLE'", "NOW(3) + INTERVAL 1 DAY")), "a durable row with an expiry");
			statement.execute(observation("'VISITED'", "'REGION'", 12, "NULL", null, "'{}'", "'KNOWN'", "NULL", "NULL", "NULL", "'DURABLE'", "NULL"));

			// Shared pages: no lead relation exists for a shared fact; facts are frozen.
			statement.execute("INSERT INTO lc_mem_shared_page (page_id, author_char, title, created_at, observations_from, observations_to) VALUES (5, 268477011, 'D-grade spoil', NOW(3), NOW(3) - INTERVAL 10 DAY, NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_mem_shared_fact (page_id, seq, relation, subject_kind, subject_ref, value, observed_at) VALUES (5, 1, 'LEAD', 'PLAYER', 268477099, '{}', NOW(3))"), "intel on a shared page");
			statement.execute("INSERT INTO lc_mem_shared_fact (page_id, seq, relation, subject_kind, subject_ref, object_kind, object_ref, value, observed_at) VALUES (5, 1, 'OBTAINED_FROM', 'ITEM', 1864, 'MONSTER', 20537, '{}', NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_mem_shared_fact SET subject_ref = 1 WHERE page_id = 5"));

			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_mem_note (char_id, text, created_at) VALUES (268477000, REPEAT('x', 401), NOW(3))"), "a note over its bound");

			try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM lc_expiring_table WHERE owner_module = 'WAYFARER'"))
			{
				result.next();
				assertEquals(2, result.getInt(1));
			}
		}
	}

	private static String observation(String relation, String subjectKind, long subject, String objectKind, Integer object, String value, String provenance, String sourceChar, String sourcePage, String intel, String tier, String expires)
	{
		return "INSERT INTO lc_mem_observation (char_id, relation, subject_kind, subject_ref, object_kind, object_ref, value, provenance, source_char, source_page, intel_id, first_at, last_at, tier, expires_at) VALUES (268477000, " + relation + ", " + subjectKind + ", " + subject + ", " + objectKind + ", " + (object == null ? "NULL" : object.toString()) + ", " + value + ", " + provenance + ", " + sourceChar + ", " + sourcePage + ", " + intel + ", NOW(3), NOW(3), " + tier + ", " + expires + ")";
	}
}
