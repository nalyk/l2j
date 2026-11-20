package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.BitSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.config.IdManagerConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.ItemManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * CP-0011 (docs/spec/asset-service.md §11.6, ASSET-R24, INV10): Mobius releases an object id only after the item's row
 * is deleted, so an id is never handed out while a row with it exists, even when the delete fails. Part of AT-ASSET-09.
 */
class IdReleaseTest
{
	private static final int SQUIRES_SWORD = 2369;
	private static TestServer _server;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.boot();
	}

	/** Whether the id manager counts the id as in use (its bit set in {@code _freeIds}). */
	private static boolean reserved(int objectId) throws Exception
	{
		final Field field = IdManager.class.getDeclaredField("_freeIds");
		field.setAccessible(true);
		return ((BitSet) field.get(IdManager.getInstance())).get(objectId - IdManagerConfig.FIRST_OBJECT_ID);
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

	private static Item storedSword()
	{
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 20);
		final Item sword = player.getInventory().addItem(ItemProcessType.NONE, SQUIRES_SWORD, 1, player, null);
		sword.updateDatabase(true);
		return sword;
	}

	@Test
	void aDestroyedItemsIdIsReservedUntilItsRowIsDeleted() throws Exception
	{
		final Item sword = storedSword();
		assertEquals(1, rows(sword.getObjectId()));
		ItemManager.destroyItem(ItemProcessType.NONE, sword, null, null);
		assertTrue(reserved(sword.getObjectId()), "the row still exists, so its id stays in use");
		sword.updateDatabase();
		assertEquals(0, rows(sword.getObjectId()));
		assertFalse(reserved(sword.getObjectId()), "released once the row is gone");
	}

	@Test
	void aFailedDeleteKeepsTheIdReserved() throws Exception
	{
		final Item sword = storedSword();
		try (Connection connection = _server.database().connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("CREATE TRIGGER test_refuse_delete BEFORE DELETE ON items FOR EACH ROW IF OLD.object_id = " + sword.getObjectId() + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test: delete refused'; END IF");
		}
		try
		{
			ItemManager.destroyItem(ItemProcessType.NONE, sword, null, null);
			sword.updateDatabase();
			assertEquals(1, rows(sword.getObjectId()), "the delete failed");
			assertTrue(reserved(sword.getObjectId()), "a row with this id exists, so the id is never handed out");
		}
		finally
		{
			try (Connection connection = _server.database().connect();
				Statement statement = connection.createStatement())
			{
				statement.execute("DROP TRIGGER test_refuse_delete");
			}
		}
	}

	@Test
	void anItemWithoutARowIsReleasedAtOnce() throws Exception
	{
		final Item unsaved = new Item(IdManager.getInstance().getNextId(), SQUIRES_SWORD);
		assertTrue(reserved(unsaved.getObjectId()));
		ItemManager.destroyItem(ItemProcessType.NONE, unsaved, null, null);
		assertFalse(reserved(unsaved.getObjectId()), "no row ever existed, so retail's immediate release stays");
	}
}
