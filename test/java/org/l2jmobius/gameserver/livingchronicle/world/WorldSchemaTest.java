package org.l2jmobius.gameserver.livingchronicle.world;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * AT-AR-00 (docs/spec/aden-remembers.md): the migration applies; keys, checks and triggers refuse a protected item in a family, an actuator row outside its bounds, a changed or deleted actuator row, a second open situation of one kind and region, a transition outside the situation machine, a changed transition row, a second enacted law of one kind per castle, an ended law changed, a Guild buy price at or above any town's sell price, a caravan transition outside its machine, a rumor beyond its life.
 */
class WorldSchemaTest
{
	private static final String APP = "UNHEX('0123456789ABCDEF0123456789ABCDEF')";

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
			statement.execute("INSERT INTO lc_region (region_id, kind, name_key, castle_id, parent_id) VALUES (1, 'TERRITORY', 'giran', 3, NULL), (2, 'TERRITORY', 'oren', 4, NULL), (11, 'SUBREGION', 'dragon_valley', NULL, 1)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_region (region_id, kind, name_key, castle_id, parent_id) VALUES (12, 'SUBREGION', 'orphan', NULL, NULL)"), "a subregion without its territory");

			// Families never hold protected items, either way round.
			statement.execute("INSERT INTO lc_resource_family (family_id, name_key, catch_in, max_variation_permille, max_rate_permille_day, recovery_days) VALUES (1, 'coal', TRUE, 50, 10, 14)");
			statement.execute("INSERT INTO lc_resource_protected (item_id, reason) VALUES (57, 'ADENA')");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_resource_item (item_id, family_id) VALUES (57, 1)"), "Adena in a family");
			statement.execute("INSERT INTO lc_resource_item (item_id, family_id) VALUES (1870, 1), (1871, 1)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_resource_protected (item_id, reason) VALUES (1870, 'QUEST')"), "protecting a family item");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_resource_item SET item_id = 57 WHERE item_id = 1871"), "moving a protected item into a family");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_resource_family (family_id, name_key, catch_in, max_variation_permille, max_rate_permille_day, recovery_days) VALUES (2, 'wide', FALSE, 200, 10, 14)"), "a variation above the ceiling");

			// Actuators: within bounds, the kind's ceiling, append-only.
			statement.execute(actuator(APP, "'YIELD'", 1040, 950, 1050));
			assertThrows(SQLException.class, () -> statement.execute(actuator("UNHEX('11111111111111111111111111111111')", "'YIELD'", 1060, 950, 1050)), "a value outside its bounds");
			assertThrows(SQLException.class, () -> statement.execute(actuator("UNHEX('22222222222222222222222222222222')", "'YIELD'", 1150, 850, 1200)), "bounds beyond the yield ceiling");
			assertThrows(SQLException.class, () -> statement.execute(actuator("UNHEX('33333333333333333333333333333333')", "'TEMPO'", 1100, 1050, 1250)), "bounds that exclude neutral");
			assertThrows(SQLException.class, () -> statement.execute(actuator(APP, "'YIELD'", 1000, 950, 1050)), "a second application of one effect");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_actuator SET value_permille = 1000"), "rewriting history");
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM lc_actuator"), "deleting history");

			// Situations: one open per kind and region, the machine, transitions frozen.
			statement.execute("INSERT INTO lc_situation (situation_id, kind, region_id, state, opened_fact_id, opened_at, cause, params) VALUES (1, 'OVERHARVEST', 11, 'EMERGING', 100, NOW(3), '{}', '{}')");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_situation (kind, region_id, state, opened_fact_id, opened_at, cause, params) VALUES ('OVERHARVEST', 11, 'EMERGING', 101, NOW(3), '{}', '{}')"), "a second open situation");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_situation SET state = 'RECEDING' WHERE situation_id = 1"), "emerging straight to receding");
			statement.execute("UPDATE lc_situation SET state = 'ACTIVE' WHERE situation_id = 1");
			statement.execute("UPDATE lc_situation SET state = 'CLOSED', closed_at = NOW(3) WHERE situation_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_situation SET state = 'ACTIVE', closed_at = NULL WHERE situation_id = 1"), "reopening a closed situation");
			statement.execute("INSERT INTO lc_situation (kind, region_id, state, opened_fact_id, opened_at, cause, params) VALUES ('OVERHARVEST', 11, 'EMERGING', 102, NOW(3), '{}', '{}')");
			statement.execute("INSERT INTO lc_situation_transition (situation_id, seq, from_state, to_state, at, reason_code, input_fact_from, input_fact_to) VALUES (1, 1, NULL, 'EMERGING', NOW(3), 'PRESSURE', 90, 100)");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_situation_transition SET reason_code = 'X'"), "rewriting a transition");

			// Crown Law: one enacted law per kind and castle; ended laws are final; funding matches the kind.
			statement.execute("INSERT INTO lc_law (castle_id, kind, reign_key, enacted_by, enacted_at, params, custody_id) VALUES (3, 'MARKET_CHARTER', '3/1001/500', 268477000, NOW(3), '{}', 77)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_law (castle_id, kind, reign_key, enacted_by, enacted_at, params, custody_id) VALUES (3, 'MARKET_CHARTER', '3/1001/500', 268477000, NOW(3), '{}', 78)"), "a second charter");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_law (castle_id, kind, reign_key, enacted_by, enacted_at, params, custody_id) VALUES (3, 'AMNESTY', '3/1001/500', 268477000, NOW(3), '{}', 79)"), "an amnesty with money");
			statement.execute("UPDATE lc_law SET state = 'EXPIRED', ended_at = NOW(3), end_reason = 'REIGN_ENDED' WHERE castle_id = 3");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_law SET state = 'ENACTED', ended_at = NULL, end_reason = NULL WHERE castle_id = 3"), "reviving an ended law");
			statement.execute("INSERT INTO lc_law (castle_id, kind, reign_key, enacted_by, enacted_at, params, custody_id) VALUES (3, 'MARKET_CHARTER', '3/1002/900', 268477001, NOW(3), '{}', 80)");

			// The Guild: buy below every sell price, across towns.
			statement.execute("INSERT INTO lc_guild_town (town_id, territory_id, warehouse_custody_id, pool_custody_id) VALUES (9, 1, 1, 2), (10, 2, 3, 4)");
			statement.execute("INSERT INTO lc_guild_price (town_id, item_id, buy_price, sell_price, application_id) VALUES (9, 1870, 10, 20, " + APP + ")");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_guild_price (town_id, item_id, buy_price, sell_price, application_id) VALUES (10, 1870, 22, 30, " + APP + ")"), "buying above another town's sell price");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_guild_price (town_id, item_id, buy_price, sell_price, application_id) VALUES (10, 1870, 5, 9, " + APP + ")"), "selling below another town's buy price");
			statement.execute("INSERT INTO lc_guild_price (town_id, item_id, buy_price, sell_price, application_id) VALUES (10, 1870, 12, 19, " + APP + ")");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_guild_price SET buy_price = 19 WHERE town_id = 9"), "raising a buy price to another town's sell price");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_guild_price (town_id, item_id, buy_price, sell_price, application_id) VALUES (9, 1871, 20, 20, " + APP + ")"), "no spread in one town");

			// Caravans: start scheduled, follow the machine, never go back a node.
			statement.execute("INSERT INTO lc_caravan_route (route_name, origin_town, dest_town, nodes, scheduled_minutes, fort_ids) VALUES ('lc_caravan_giran_oren', 9, 10, 12, 90, '[]')");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_caravan (route_name, manifest_custody_id, state, depart_at) VALUES ('lc_caravan_giran_oren', 5, 'EN_ROUTE', NOW(3))"), "a caravan born en route");
			statement.execute("INSERT INTO lc_caravan (caravan_id, route_name, manifest_custody_id, depart_at) VALUES (1, 'lc_caravan_giran_oren', 5, NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_caravan SET state = 'ARRIVED' WHERE caravan_id = 1"), "arriving without leaving");
			statement.execute("UPDATE lc_caravan SET state = 'EN_ROUTE', last_node = 4 WHERE caravan_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_caravan SET last_node = 3 WHERE caravan_id = 1"), "going back a node");
			statement.execute("UPDATE lc_caravan SET state = 'RAIDED' WHERE caravan_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_caravan SET state = 'ARRIVED' WHERE caravan_id = 1"), "arriving after a raid");
			statement.execute("UPDATE lc_caravan SET state = 'SETTLED' WHERE caravan_id = 1");

			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_world_rumor (territory_id, kind, template_key, params, from_at, until_at) VALUES (1, 'SITUATION', 'coal_wanted', '{}', NOW(3), NOW(3) + INTERVAL 200 HOUR)"), "a rumor beyond its life");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_world_rumor (territory_id, kind, template_key, params, from_at, until_at) VALUES (1, 'SITUATION', 'coal_wanted', '[1]', NOW(3), NOW(3) + INTERVAL 2 HOUR)"), "rumor params not an object");
		}
	}

	private static String actuator(String application, String kind, int value, int min, int max)
	{
		return "INSERT INTO lc_actuator (application_id, kind, region_id, target, value_permille, min_permille, max_permille, reason_code, fact_id, applied_at) VALUES (" + application + ", " + kind + ", 11, 1, " + value + ", " + min + ", " + max + ", 'OVERHARVEST', 100, NOW(3))";
	}
}
