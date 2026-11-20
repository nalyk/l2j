package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.enums.ItemLocation;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * CP-0010 (docs/spec/asset-service.md §3.3, §11.20): an item the Asset Service has taken is retired. Every Mobius
 * operation on it refuses (container add, transfer, destroy, drop, equip, use), so it stays exactly where it was, and
 * nothing ever writes it again, so no state from before the debit reaches the database. Part of AT-ASSET-24.
 */
class RetiredItemTest
{
	private static final int SQUIRES_SWORD = 2369;
	private static TestServer _server;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.boot();
	}

	private static Player player()
	{
		return TestPlayers.create(PlayerClass.FIGHTER, 20);
	}

	private static Item retiredSword(Player player)
	{
		final Item sword = player.getInventory().addItem(ItemProcessType.NONE, SQUIRES_SWORD, 1, player, null);
		sword.retire();
		assertTrue(sword.isRetired());
		return sword;
	}

	private static void stillInInventory(Player player, Item sword)
	{
		assertSame(sword, player.getInventory().getItemByObjectId(sword.getObjectId()), "still in its container");
		assertEquals(player.getObjectId(), sword.getOwnerId());
		assertTrue((sword.getItemLocation() == ItemLocation.INVENTORY) || (sword.getItemLocation() == ItemLocation.PAPERDOLL));
	}

	@Test
	void aRetiredItemIsNotTransferred()
	{
		final Player player = player();
		final Item sword = retiredSword(player);
		assertNull(player.getInventory().transferItem(ItemProcessType.NONE, sword.getObjectId(), 1, player.getWarehouse(), player, null));
		stillInInventory(player, sword);
		assertNull(player.getWarehouse().getItemByObjectId(sword.getObjectId()));
	}

	@Test
	void aRetiredItemIsNotDestroyed()
	{
		final Player player = player();
		final Item sword = retiredSword(player);
		assertNull(player.getInventory().destroyItem(ItemProcessType.NONE, sword, 1, player, null));
		stillInInventory(player, sword);
		assertEquals(1, sword.getCount());
	}

	@Test
	void aRetiredItemIsNotDropped()
	{
		final Player player = player();
		final Item sword = retiredSword(player);
		assertNull(player.getInventory().dropItem(ItemProcessType.NONE, sword, player, null));
		assertNull(player.getInventory().dropItem(ItemProcessType.NONE, sword.getObjectId(), 1, player, null));
		stillInInventory(player, sword);
	}

	@Test
	void aRetiredItemIsNotAddedToAnotherContainer()
	{
		final Player player = player();
		final Item sword = retiredSword(player);
		assertNull(player.getWarehouse().addItem(ItemProcessType.NONE, sword, player, null));
		assertNull(player.getWarehouse().getItemByObjectId(sword.getObjectId()));
	}

	@Test
	void aRetiredItemIsNotEquipped()
	{
		final Player player = player();
		final Item sword = retiredSword(player);
		player.getInventory().setPaperdollItem(Inventory.PAPERDOLL_RHAND, sword);
		assertFalse(sword.isEquipped(), "setPaperdollItem refuses it");
		player.useEquippableItem(sword, true);
		assertFalse(sword.isEquipped(), "a scheduled equip refuses it");
		assertNull(player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND));
	}

	@Test
	void aRetiredItemIsNeverWritten() throws Exception
	{
		final Player player = player();
		final Item sword = player.getInventory().addItem(ItemProcessType.NONE, SQUIRES_SWORD, 1, player, null);
		sword.updateDatabase(true);
		sword.retire();
		sword.setEnchantLevel(5);
		sword.setOwnerId(0); // upstream would delete the row for this
		sword.updateDatabase(true);
		try (Connection connection = _server.database().connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery("SELECT owner_id, enchant_level FROM items WHERE object_id = " + sword.getObjectId()))
		{
			assertTrue(result.next(), "the row is still there");
			assertEquals(player.getObjectId(), result.getInt(1));
			assertEquals(0, result.getInt(2));
		}
	}
}
