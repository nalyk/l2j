package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.managers.CastleManager;
import org.l2jmobius.gameserver.mechanics.siege.Castle;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * CP-0017 (docs/spec/asset-service.md §3.3): the seams the Asset Service takes through. An item taken out of a container
 * leaves it as retail's own removal does (unequip, Adena caches, weight); a whole object's in-progress write is waited for;
 * a treasury changes in memory only, its row left to the service's transaction.
 */
class TakingSeamsTest
{
	private static final int SQUIRES_SWORD = 2369;
	private static TestServer _server;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.boot();
	}

	@Test
	void anItemTakenForAnAssetLeavesItsContainerAsRetailRemovalDoes()
	{
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 20);
		final Item sword = player.getInventory().addItem(ItemProcessType.NONE, SQUIRES_SWORD, 1, player, null);
		final Item adena = player.getInventory().addItem(ItemProcessType.NONE, Inventory.ADENA_ID, 5_000, player, null);
		final int load = player.getCurrentLoad();
		assertTrue(player.getInventory().removeForAsset(sword));
		assertNull(player.getInventory().getItemByObjectId(sword.getObjectId()));
		assertTrue(player.getCurrentLoad() < load, "the weight is refreshed");
		assertTrue(player.getInventory().removeForAsset(adena));
		assertEquals(0, player.getAdena(), "the inventory's Adena cache is cleared, as by retail's removal");
		assertFalse(player.getInventory().removeForAsset(sword), "an item no longer there is not taken twice");
	}

	@Test
	void awaitingWritesWaitsForAWriteInProgress() throws Exception
	{
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 20);
		final Item sword = player.getInventory().addItem(ItemProcessType.NONE, SQUIRES_SWORD, 1, player, null);
		final CountDownLatch writing = new CountDownLatch(1);
		final Thread writer = new Thread(() ->
		{
			sword.writeLock().lock();
			try
			{
				writing.countDown();
				Thread.sleep(400);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
			finally
			{
				sword.writeLock().unlock();
			}
		});
		writer.start();
		writing.await();
		final long start = System.nanoTime();
		sword.awaitWrites();
		assertTrue(((System.nanoTime() - start) / 1_000_000) >= 300, "it waited for the write that was in progress");
		assertFalse(sword.writeLock().isLocked(), "and holds nothing afterwards");
		writer.join();
	}

	@Test
	void aTreasuryChangesInMemoryOnlyAndRefusesAnOverdrawOrAPassOverTheCap() throws Exception
	{
		final Castle castle = CastleManager.getInstance().getCastleById(2);
		final Field owner = Castle.class.getDeclaredField("_ownerId");
		owner.setAccessible(true);
		owner.set(castle, 1);
		final Field treasury = Castle.class.getDeclaredField("_treasury");
		treasury.setAccessible(true);
		treasury.set(castle, 150L);
		try
		{
			assertFalse(castle.tryDebitTreasury(151));
			assertTrue(castle.tryDebitTreasury(100));
			assertEquals(50, castle.getTreasury());
			assertTrue(castle.creditTreasuryInMemory(30));
			assertEquals(80, castle.getTreasury());
			assertFalse(castle.creditTreasuryInMemory(Inventory.MAX_ADENA), "a credit over the cap is refused, never clamped");
			assertEquals(80, castle.getTreasury());
			try (Connection connection = _server.database().connect();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT treasury FROM castle WHERE id = 2"))
			{
				result.next();
				assertEquals(0, result.getLong(1), "memory only: the service's transaction writes the row");
			}
		}
		finally
		{
			treasury.set(castle, 0L);
			owner.set(castle, 0);
		}
	}
}
