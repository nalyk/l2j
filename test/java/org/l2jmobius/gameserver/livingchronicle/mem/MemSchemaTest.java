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
 * AT-MEM-00 (docs/spec/wayfarers-memory.md): the migration applies; keys and checks refuse an import without teller or origin, an import as KNOWN, intel content on a row, intel on a durable or summary row, a second row for one intel item, a second row for one coalescing key, a lead without intel, a durable row with an expiry, a shared fact without origin or of a rumor, a changed shared fact, a note over its bound; a new price or a second teller is its own row; expiring tables registered.
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
			// Coalescing: a repeat is refused as a new row; a new price is its own row; a second teller is its own row.
			statement.execute(row("'SOLD_BY'", "'ITEM'", 1864, "'PLAYER'", "268477010", "'{\"price\":78000}'", "'78000/0'", "'KNOWN'", "NULL", "NULL", "NULL", "NULL", "NULL", "NULL", "'RAW'", "NOW(3) + INTERVAL 30 DAY"));
			assertThrows(SQLException.class, () -> statement.execute(row("'SOLD_BY'", "'ITEM'", 1864, "'PLAYER'", "268477010", "'{\"price\":78000}'", "'78000/0'", "'KNOWN'", "NULL", "NULL", "NULL", "NULL", "NULL", "NULL", "'RAW'", "NOW(3) + INTERVAL 30 DAY")), "the same observation as a new row");
			statement.execute(row("'SOLD_BY'", "'ITEM'", 1864, "'PLAYER'", "268477010", "'{\"price\":81000}'", "'81000/0'", "'KNOWN'", "NULL", "NULL", "NULL", "NULL", "NULL", "NULL", "'RAW'", "NOW(3) + INTERVAL 30 DAY"));
			statement.execute(told("268477011", "5"));
			statement.execute(told("268477012", "6"));

			// Imports are TOLD with teller and origin, never KNOWN with a teller.
			assertThrows(SQLException.class, () -> statement.execute(row("'SOLD_BY'", "'ITEM'", 57, "NULL", "NULL", "'{}'", "''", "'TOLD'", "NULL", "268477011", "5", "NULL", "NULL", "NULL", "'RAW'", "NOW(3) + INTERVAL 30 DAY")), "an import without its origin");
			assertThrows(SQLException.class, () -> statement.execute(row("'SOLD_BY'", "'ITEM'", 57, "NULL", "NULL", "'{}'", "''", "'KNOWN'", "NULL", "268477011", "5", "268477011", "NOW(3)", "NULL", "'RAW'", "NOW(3) + INTERVAL 30 DAY")), "an import passed off as known");
			assertThrows(SQLException.class, () -> statement.execute(row("'SOLD_BY'", "'ITEM'", 57, "NULL", "NULL", "'{}'", "''", "'KNOWN'", "NULL", "268477011", "5", "NULL", "NULL", "NULL", "'RAW'", "NOW(3) + INTERVAL 30 DAY")), "a known row with a teller but no origin");
			// One page carrying the same report first observed by two characters imports as two rows.
			statement.execute(row("'SOLD_BY'", "'ITEM'", 1864, "'PLAYER'", "268477010", "'{\"price\":78000}'", "'78000/0'", "'TOLD'", "NULL", "268477011", "5", "268477020", "NOW(3) - INTERVAL 3 DAY", "NULL", "'DURABLE'", "NULL"));

			// Intel rows: content-free, unique by item, raw only, a lead always has one; an Intel rumor too.
			statement.execute(intel("'LEAD'", "'KNOWN'", "NULL", 4242, "'{}'", "NULL", "'RAW'", "NOW(3) + INTERVAL 1 HOUR"));
			assertThrows(SQLException.class, () -> statement.execute(intel("'LEAD'", "'KNOWN'", "NULL", 4242, "'{}'", "NULL", "'RAW'", "NOW(3) + INTERVAL 1 HOUR")), "a second row for one intel item");
			assertThrows(SQLException.class, () -> statement.execute(intel("'LEAD'", "'KNOWN'", "NULL", 4243, "'{\"region\":12}'", "NULL", "'RAW'", "NOW(3) + INTERVAL 1 HOUR")), "intel content kept on a row");
			assertThrows(SQLException.class, () -> statement.execute(intel("'LEAD'", "'KNOWN'", "NULL", 4244, "'{}'", "12", "'RAW'", "NOW(3) + INTERVAL 1 HOUR")), "an intel row with a region");
			assertThrows(SQLException.class, () -> statement.execute(intel("'LEAD'", "'KNOWN'", "NULL", 4245, "'{}'", "NULL", "'DURABLE'", "NULL")), "a durable intel row");
			assertThrows(SQLException.class, () -> statement.execute(row("'LEAD'", "'PLAYER'", 268477099, "NULL", "NULL", "'{}'", "''", "'KNOWN'", "NULL", "NULL", "NULL", "NULL", "NULL", "NULL", "'RAW'", "NOW(3) + INTERVAL 1 HOUR")), "a lead without its intel reference");
			assertThrows(SQLException.class, () -> statement.execute(row("'RUMOR'", "'PLAYER'", 268477099, "NULL", "NULL", "'{}'", "''", "'RUMORED'", "'INTEL'", "NULL", "NULL", "NULL", "NULL", "NULL", "'RAW'", "NOW(3) + INTERVAL 1 HOUR")), "an Intel rumor without its item");
			statement.execute(intel("'RUMOR'", "'RUMORED'", "'INTEL'", 4246, "'{}'", "NULL", "'RAW'", "NOW(3) + INTERVAL 1 HOUR"));
			statement.execute(row("'RUMOR'", "'ITEM'", 1864, "NULL", "NULL", "'{\"supply\":\"up\"}'", "''", "'RUMORED'", "'WORLD'", "NULL", "NULL", "NULL", "NULL", "NULL", "'RAW'", "NOW(3) + INTERVAL 1 DAY"));

			// Tiers.
			assertThrows(SQLException.class, () -> statement.execute(row("'VISITED'", "'REGION'", 12, "NULL", "NULL", "'{}'", "''", "'KNOWN'", "NULL", "NULL", "NULL", "NULL", "NULL", "NULL", "'RAW'", "NULL")), "a raw row without expiry");
			assertThrows(SQLException.class, () -> statement.execute(row("'VISITED'", "'REGION'", 12, "NULL", "NULL", "'{}'", "''", "'KNOWN'", "NULL", "NULL", "NULL", "NULL", "NULL", "NULL", "'DURABLE'", "NOW(3) + INTERVAL 1 DAY")), "a durable row with an expiry");

			// Shared pages: every fact has an origin; no lead or rumor relation exists; facts are frozen.
			statement.execute("INSERT INTO lc_mem_shared_page (page_id, author_char, title, created_at, observations_from, observations_to) VALUES (7, 268477011, 'D-grade spoil', NOW(3), NOW(3) - INTERVAL 10 DAY, NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_mem_shared_fact (page_id, seq, relation, subject_kind, subject_ref, value, observed_at) VALUES (7, 1, 'OBTAINED_FROM', 'ITEM', 1864, '{}', NOW(3))"), "a fact without origin");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_mem_shared_fact (page_id, seq, relation, subject_kind, subject_ref, value, observed_at, origin_char, origin_at) VALUES (7, 1, 'RUMOR', 'ITEM', 1864, '{}', NOW(3), 268477011, NOW(3))"), "a rumor on a page");
			statement.execute("INSERT INTO lc_mem_shared_fact (page_id, seq, relation, subject_kind, subject_ref, value, observed_at, origin_char, origin_at) VALUES (7, 1, 'OBTAINED_FROM', 'ITEM', 1864, '{}', NOW(3), 268477013, NOW(3) - INTERVAL 3 DAY)");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_mem_shared_fact SET subject_ref = 1 WHERE page_id = 7"));

			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_mem_note (char_id, text, created_at) VALUES (268477000, REPEAT('x', 401), NOW(3))"), "a note over its bound");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_mem_companion (char_id, other_char, party_key, overlap_minutes, at, expires_at) VALUES (1, 1, 'p', 30, NOW(3), NOW(3) + INTERVAL 90 DAY)"), "a companion of oneself");

			try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM lc_mem_observation WHERE relation = 'SOLD_BY'"))
			{
				result.next();
				assertEquals(5, result.getInt(1), "two prices, two tellers and a second first observer are five rows");
			}
			try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM lc_expiring_table WHERE owner_module = 'WAYFARER'"))
			{
				result.next();
				assertEquals(3, result.getInt(1));
			}
		}
	}

	private static String told(String teller, String page)
	{
		return row("'SOLD_BY'", "'ITEM'", 1864, "'PLAYER'", "268477010", "'{\"price\":78000}'", "'78000/0'", "'TOLD'", "NULL", teller, page, "268477013", "NOW(3) - INTERVAL 2 DAY", "NULL", "'DURABLE'", "NULL");
	}

	private static String intel(String relation, String provenance, String rumorSource, long intelId, String value, String region, String tier, String expires)
	{
		return row(relation, "'PLAYER'", 268477099, "NULL", "NULL", value, "''", provenance, rumorSource, "NULL", "NULL", "NULL", "NULL", Long.toString(intelId), tier, expires).replace("object_ref, value,", "object_ref, region_id, value,").replace(", NULL, NULL, " + value + ", ''", ", NULL, NULL, " + region + ", " + value + ", ''");
	}

	private static String row(String relation, String subjectKind, long subject, String objectKind, String object, String value, String valueKey, String provenance, String rumorSource, String sourceChar, String sourcePage, String originChar, String originAt, String intel, String tier, String expires)
	{
		return "INSERT INTO lc_mem_observation (char_id, relation, subject_kind, subject_ref, object_kind, object_ref, value, value_key, provenance, rumor_source, source_char, source_page, origin_char, origin_at, intel_id, first_at, last_at, tier, expires_at) VALUES (268477000, " + relation + ", " + subjectKind + ", " + subject + ", " + objectKind + ", " + object + ", " + value + ", " + valueKey + ", " + provenance + ", " + rumorSource + ", " + sourceChar + ", " + sourcePage + ", " + originChar + ", " + originAt + ", " + intel + ", NOW(3), NOW(3), " + tier + ", " + expires + ")";
	}
}
