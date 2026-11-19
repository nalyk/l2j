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
 * AT-AR-00 (docs/spec/aden-remembers.md): the migration applies; triggers and keys refuse a protected item in a family (insert or update, both tables), an actuator row outside its bounds or its kind's ceiling, changed or deleted, a second open situation, a situation born other than emerging, a state change without its newest transition row, a transition outside the machine, active to closed without a freeze or disable reason, a second enacted law of a kind per castle, a second Amnesty in a reign, an ended law changed or deleted, a Guild buy or order price at or above a sell price, a concurrent price writer (it waits for the first's lock, then is refused), a caravan transition outside its machine or a node going back, a rumor beyond its life.
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
	void theKeysAndChecksHold() throws Exception
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO lc_region (region_id, kind, name_key, castle_id, parent_id) VALUES (1, 'TERRITORY', 'giran', 3, NULL), (2, 'TERRITORY', 'oren', 4, NULL), (11, 'SUBREGION', 'dragon_valley', NULL, 1)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_region (region_id, kind, name_key, castle_id, parent_id) VALUES (12, 'SUBREGION', 'orphan', NULL, NULL)"), "a subregion without its territory");

			// Families never hold protected items, either way round, by insert or update.
			statement.execute("INSERT INTO lc_resource_family (family_id, name_key, catch_in, max_relocate_permille, max_rate_permille_day, recovery_days) VALUES (1, 'coal', TRUE, 50, 10, 14)");
			statement.execute("INSERT INTO lc_resource_protected (item_id, reason) VALUES (57, 'ADENA'), (6360, 'SEAL_STONE')");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_resource_item (item_id, family_id) VALUES (57, 1)"), "Adena in a family");
			statement.execute("INSERT INTO lc_resource_item (item_id, family_id) VALUES (1870, 1), (1871, 1)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_resource_protected (item_id, reason) VALUES (1870, 'QUEST')"), "protecting a family item");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_resource_item SET item_id = 57 WHERE item_id = 1871"), "moving a protected item into a family");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_resource_protected SET item_id = 1871 WHERE item_id = 6360"), "moving a protection onto a family item");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_resource_family (family_id, name_key, catch_in, max_relocate_permille, max_rate_permille_day, recovery_days) VALUES (2, 'wide', FALSE, 200, 10, 14)"), "a relocation share above the ceiling");

			// Actuators: within bounds and the kind's ceiling, append-only.
			statement.execute(actuator(APP, "'RELOCATE'", 40, 0, 50));
			assertThrows(SQLException.class, () -> statement.execute(actuator("UNHEX('11111111111111111111111111111111')", "'RELOCATE'", 60, 0, 50)), "a share outside its bounds");
			assertThrows(SQLException.class, () -> statement.execute(actuator("UNHEX('22222222222222222222222222222222')", "'RELOCATE'", 150, 0, 200)), "a share beyond the relocation ceiling");
			assertThrows(SQLException.class, () -> statement.execute(actuator("UNHEX('33333333333333333333333333333333')", "'STOCK'", 1100, 1050, 1500)), "stock bounds that exclude neutral");
			assertThrows(SQLException.class, () -> statement.execute(actuator(APP, "'RELOCATE'", 0, 0, 50)), "a second application of one effect");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_actuator SET value_permille = 0"), "rewriting history");
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM lc_actuator"), "deleting history");

			// Situations: born emerging, one open per kind and region, moved only with their newest transition row.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_situation (kind, region_id, state, opened_fact_id, opened_at, cause, params) VALUES ('SHORTAGE', 11, 'ACTIVE', 99, NOW(3), '{}', '{}')"), "a situation born active");
			statement.execute("INSERT INTO lc_situation (situation_id, kind, region_id, state, opened_fact_id, opened_at, cause, params) VALUES (1, 'OVERHARVEST', 11, 'EMERGING', 100, NOW(3), '{}', '{}')");
			statement.execute(transition(1, 1, "NULL", "'EMERGING'", "'PRESSURE'"));
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_situation (kind, region_id, state, opened_fact_id, opened_at, cause, params) VALUES ('OVERHARVEST', 11, 'EMERGING', 101, NOW(3), '{}', '{}')"), "a second open situation");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_situation SET state = 'ACTIVE' WHERE situation_id = 1"), "a state change without its transition row");
			assertThrows(SQLException.class, () -> statement.execute(transition(1, 2, "'EMERGING'", "'RECEDING'", "'PRESSURE'")), "emerging straight to receding");
			assertThrows(SQLException.class, () -> statement.execute(transition(1, 3, "'EMERGING'", "'ACTIVE'", "'PRESSURE'")), "a gap in the sequence");
			assertThrows(SQLException.class, () -> statement.execute(transition(1, 2, "NULL", "'ACTIVE'", "'PRESSURE'")), "a later transition with no predecessor state");
			statement.execute(transition(1, 2, "'EMERGING'", "'ACTIVE'", "'CONFIRMED'"));
			statement.execute("UPDATE lc_situation SET state = 'ACTIVE' WHERE situation_id = 1");
			assertThrows(SQLException.class, () -> statement.execute(transition(1, 3, "'ACTIVE'", "'CLOSED'", "'PRESSURE'")), "active to closed without a freeze");
			statement.execute(transition(1, 3, "'ACTIVE'", "'CLOSED'", "'FROZEN'"));
			statement.execute("UPDATE lc_situation SET state = 'CLOSED', closed_at = NOW(3) WHERE situation_id = 1");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_situation SET state = 'ACTIVE', closed_at = NULL WHERE situation_id = 1"), "reopening a closed situation");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_situation_transition SET reason_code = 'X'"), "rewriting a transition");
			statement.execute("INSERT INTO lc_situation (kind, region_id, state, opened_fact_id, opened_at, cause, params) VALUES ('OVERHARVEST', 11, 'EMERGING', 102, NOW(3), '{}', '{}')");

			// Crown Law: one enacted law per kind and castle; one Amnesty per reign; ended laws are final and kept.
			statement.execute("INSERT INTO lc_law (castle_id, kind, reign_key, enacted_by, enacted_at, params, custody_id) VALUES (3, 'MARKET_CHARTER', '3/1001/500', 268477000, NOW(3), '{}', 77)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_law (castle_id, kind, reign_key, enacted_by, enacted_at, params, custody_id) VALUES (3, 'MARKET_CHARTER', '3/1001/500', 268477000, NOW(3), '{}', 78)"), "a second charter");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_law (castle_id, kind, reign_key, enacted_by, enacted_at, params, custody_id) VALUES (3, 'AMNESTY', '3/1001/500', 268477000, NOW(3), '{}', 79)"), "an amnesty with money");
			statement.execute("INSERT INTO lc_law (law_id, castle_id, kind, reign_key, enacted_by, enacted_at, params) VALUES (50, 3, 'AMNESTY', '3/1001/500', 268477000, NOW(3), '{}')");
			statement.execute("UPDATE lc_law SET state = 'REPEALED', ended_at = NOW(3), end_reason = 'REPEAL' WHERE law_id = 50");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_law (castle_id, kind, reign_key, enacted_by, enacted_at, params) VALUES (3, 'AMNESTY', '3/1001/500', 268477000, NOW(3), '{}')"), "a second amnesty in the reign");
			statement.execute("INSERT INTO lc_influence_spend (territory_id, clan_id, reign_key, amount, law_id, fact_id) VALUES (1, 1001, '3/1001/500', 1000, 50, 600)");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_influence_spend SET amount = 1"), "rewriting a spend");
			statement.execute("UPDATE lc_law SET state = 'EXPIRED', ended_at = NOW(3), end_reason = 'REIGN_ENDED' WHERE castle_id = 3 AND state = 'ENACTED'");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_law SET state = 'ENACTED', ended_at = NULL, end_reason = NULL WHERE law_id = 50"), "reviving an ended law");
			assertThrows(SQLException.class, () -> statement.execute("DELETE FROM lc_law WHERE law_id = 50"), "deleting a law");
			statement.execute("INSERT INTO lc_law (castle_id, kind, reign_key, enacted_by, enacted_at, params) VALUES (3, 'AMNESTY', '3/1002/900', 268477001, NOW(3), '{}')");

			// The Guild: written in READ COMMITTED only; buy and order prices below every sell price, across towns.
			statement.execute("INSERT INTO lc_guild_town (town_id, territory_id, warehouse_custody_id, pool_custody_id) VALUES (9, 1, 1, 2), (10, 2, 3, 4)");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_guild_price (town_id, item_id, buy_price, sell_price, application_id) VALUES (9, 1870, 10, 20, " + APP + ")"), "a price writer in repeatable read");
			statement.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED");
			statement.execute("INSERT INTO lc_guild_price (town_id, item_id, buy_price, sell_price, application_id) VALUES (9, 1870, 10, 20, " + APP + ")");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_guild_price (town_id, item_id, buy_price, sell_price, application_id) VALUES (10, 1870, 22, 30, " + APP + ")"), "buying above another town's sell price");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_guild_price (town_id, item_id, buy_price, sell_price, application_id) VALUES (10, 1870, 5, 9, " + APP + ")"), "selling below another town's buy price");
			statement.execute("INSERT INTO lc_guild_price (town_id, item_id, buy_price, sell_price, application_id) VALUES (10, 1870, 12, 19, " + APP + ")");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_guild_order (town_id, item_id, qty, unit_price, contract_id) VALUES (9, 1870, 100, 19, 7001)"), "an order at a sell price");
			statement.execute("INSERT INTO lc_guild_order (town_id, item_id, qty, unit_price, contract_id) VALUES (9, 1870, 100, 15, 7002)");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_guild_price SET sell_price = 15 WHERE town_id = 10"), "lowering a sell price to an open order's price");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_guild_price SET buy_price = 19 WHERE town_id = 9"), "raising a buy price to another town's sell price");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_guild_price (town_id, item_id, buy_price, sell_price, application_id) VALUES (9, 1871, 20, 20, " + APP + ")"), "no spread in one town");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_guild_order SET unit_price = 18 WHERE contract_id = 7002"), "repricing an order");

			// Caravans: start scheduled, follow the machine, never go back a node.
			statement.execute("INSERT INTO lc_caravan_route (route_name, origin_town, dest_town, nodes, scheduled_minutes, fort_ids) VALUES ('lc_caravan_giran_oren', 9, 10, 12, 90, '[]')");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_caravan (route_name, manifest_custody_id, state, depart_at) VALUES ('lc_caravan_giran_oren', 5, 'EN_ROUTE', NOW(3))"), "a caravan born en route");
			statement.execute("INSERT INTO lc_caravan (caravan_id, route_name, manifest_custody_id, depart_at) VALUES (1, 'lc_caravan_giran_oren', 5, NOW(3)), (2, 'lc_caravan_giran_oren', 6, NOW(3))");
			statement.execute("UPDATE lc_caravan SET state = 'RECALLED' WHERE caravan_id = 2");
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

	@Test
	void concurrentPriceWritersSerialise() throws Exception
	{
		try (Connection setup = _database.connect();
			Statement statement = setup.createStatement())
		{
			statement.execute("INSERT INTO lc_region (region_id, kind, name_key) VALUES (21, 'TERRITORY', 'dion'), (22, 'TERRITORY', 'aden')");
			statement.execute("INSERT INTO lc_resource_family (family_id, name_key, catch_in, max_relocate_permille, max_rate_permille_day, recovery_days) VALUES (5, 'skins', TRUE, 50, 10, 14)");
			statement.execute("INSERT INTO lc_resource_item (item_id, family_id) VALUES (1867, 5), (1868, 5)");
			statement.execute("INSERT INTO lc_guild_town (town_id, territory_id, warehouse_custody_id, pool_custody_id) VALUES (21, 21, 11, 12), (22, 22, 13, 14)");
			statement.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED");
			statement.execute("INSERT INTO lc_guild_price (town_id, item_id, buy_price, sell_price, application_id) VALUES (21, 1867, 10, 20, " + APP + "), (22, 1867, 12, 19, " + APP + "), (21, 1868, 10, 20, " + APP + "), (22, 1868, 12, 19, " + APP + ")");
		}
		try (Connection first = _database.connect();
			Connection second = _database.connect();
			Statement one = first.createStatement();
			Statement two = second.createStatement())
		{
			one.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED");
			two.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED");
			first.setAutoCommit(false);
			second.setAutoCommit(false);
			two.execute("SET SESSION innodb_lock_wait_timeout = 1");
			one.execute("UPDATE lc_guild_price SET buy_price = 18 WHERE town_id = 21 AND item_id = 1867");
			// The second writer cannot validate against the first's uncommitted row: it waits for the item's lock.
			assertThrows(SQLException.class, () -> two.execute("UPDATE lc_guild_price SET sell_price = 17 WHERE town_id = 22 AND item_id = 1867"), "a concurrent writer must wait");
			second.rollback();
			first.commit();
			// After the first commits, the same write is refused: it would let a buy at 18 meet a sell at 17.
			assertThrows(SQLException.class, () -> two.execute("UPDATE lc_guild_price SET sell_price = 17 WHERE town_id = 22 AND item_id = 1867"), "the write the first made unsafe");
			second.rollback();
		}
		try (Connection first = _database.connect();
			Connection second = _database.connect();
			Statement one = first.createStatement();
			Statement two = second.createStatement())
		{
			one.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED");
			two.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED");
			first.setAutoCommit(false);
			second.setAutoCommit(false);
			// The second transaction reads the prices before the first writes and commits.
			two.executeQuery("SELECT COUNT(*) FROM lc_guild_price WHERE item_id = 1868").close();
			one.execute("UPDATE lc_guild_price SET buy_price = 18 WHERE town_id = 21 AND item_id = 1868");
			first.commit();
			// It read a buy price of 10, but the trigger's reads, taken after the lock, see the committed 18.
			assertThrows(SQLException.class, () -> two.execute("UPDATE lc_guild_price SET sell_price = 17 WHERE town_id = 22 AND item_id = 1868"), "validation against what it read earlier");
			second.rollback();
		}
	}

	private static String transition(int situation, int seq, String from, String to, String reason)
	{
		return "INSERT INTO lc_situation_transition (situation_id, seq, from_state, to_state, at, reason_code, input_fact_from, input_fact_to) VALUES (" + situation + ", " + seq + ", " + from + ", " + to + ", NOW(3), " + reason + ", 90, 100)";
	}

	private static String actuator(String application, String kind, int value, int min, int max)
	{
		return "INSERT INTO lc_actuator (application_id, kind, region_id, target, value_permille, min_permille, max_permille, reason_code, fact_id, applied_at) VALUES (" + application + ", " + kind + ", 11, 1, " + value + ", " + min + ", " + max + ", 'OVERHARVEST', 100, NOW(3))";
	}
}
