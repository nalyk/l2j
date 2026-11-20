package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.config.IdManagerConfig;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.enums.ItemLocation;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.itemcontainer.PlayerFreight;
import org.l2jmobius.gameserver.entity.itemcontainer.PlayerInventory;
import org.l2jmobius.gameserver.entity.itemcontainer.PlayerWarehouse;
import org.l2jmobius.gameserver.managers.DatabaseIdManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * CP-0008 (docs/spec/asset-service.md §2.3, §11.4, §11.5): an escrowed item's row has the location {@code ESCROW}, which
 * Mobius restores as a location it knows, which no container loads, and which startup cleanup never deletes, whatever
 * the row's owner. Part of AT-ASSET-08.
 */
class EscrowLocationTest
{
	private static TestServer _server;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.boot();
	}

	private static void insert(int objectId, int ownerId, String loc, int locData) throws Exception
	{
		try (Connection connection = _server.database().connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data, custom_type1, custom_type2, mana_left, time) VALUES (" + ownerId + ", " + objectId + ", 57, 100, 0, '" + loc + "', " + locData + ", 0, 0, -1, 0)");
		}
	}

	private static long rows(int objectId) throws Exception
	{
		try (Connection connection = _server.database().connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM items WHERE object_id = " + objectId))
		{
			result.next();
			return result.getLong(1);
		}
	}

	@Test
	void anEscrowRowRestoresAsAnItemWithItsLocation() throws Exception
	{
		insert(900_000_001, -1, "ESCROW", 7);
		try (Connection connection = _server.database().connect();
			PreparedStatement read = connection.prepareStatement("SELECT object_id, item_id, count, loc, loc_data, enchant_level, custom_type1, custom_type2, mana_left, time FROM items WHERE object_id = ?"))
		{
			read.setInt(1, 900_000_001);
			try (ResultSet result = read.executeQuery())
			{
				result.next();
				final Item item = Item.restoreFromDb(-1, result);
				assertNotNull(item, "a row in ESCROW is a known location, not an unrestorable row");
				assertEquals(ItemLocation.ESCROW, item.getItemLocation());
				assertEquals(7, item.getLocationSlot());
			}
		}
	}

	@Test
	void cleanupKeepsEscrowRowsWhateverTheirOwner() throws Exception
	{
		insert(900_000_002, -1, "ESCROW", 8);
		insert(900_000_003, 999_999_999, "ESCROW", 8); // corrupted owner: evidence for CustodyCheck, never deleted
		insert(900_000_004, 999_999_999, "INVENTORY", 0); // an ordinary orphan: retail cleanup still deletes it
		final boolean previous = IdManagerConfig.DATABASE_CLEAN_UP;
		IdManagerConfig.DATABASE_CLEAN_UP = true;
		try
		{
			DatabaseIdManager.cleanDatabase();
		}
		finally
		{
			IdManagerConfig.DATABASE_CLEAN_UP = previous;
		}
		assertEquals(1, rows(900_000_002));
		assertEquals(1, rows(900_000_003));
		assertEquals(0, rows(900_000_004), "retail cleanup is unchanged for every other row");
	}

	@Test
	void noContainerLoadsAnEscrowRow() throws Exception
	{
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 20);
		insert(900_000_005, player.getObjectId(), "ESCROW", 9);
		final PlayerInventory inventory = new PlayerInventory(player);
		inventory.restore();
		final PlayerWarehouse warehouse = new PlayerWarehouse(player);
		warehouse.restore();
		final PlayerFreight freight = new PlayerFreight(player.getObjectId());
		freight.restore();
		assertFalse(inventory.getItems().stream().anyMatch(i -> i.getObjectId() == 900_000_005));
		assertFalse(warehouse.getItems().stream().anyMatch(i -> i.getObjectId() == 900_000_005));
		assertFalse(freight.getItems().stream().anyMatch(i -> i.getObjectId() == 900_000_005));
	}
}
