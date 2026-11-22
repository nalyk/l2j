package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.enums.ItemLocation;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * The existence counters (docs/spec/asset-service.md §2.6, revision 13; CP-0021): an item's quantity counts while its
 * location is one the census counts, so every change of count or location is counted where it happens, a restored object
 * is never counted again, and the service's own takes post nothing.
 */
class ExistenceCounterTest
{
	private static final int SHORT_SWORD = 1;
	private static final int SOULSHOT = 1835;

	@BeforeAll
	static void boot() throws Exception
	{
		TestServer.boot();
		org.l2jmobius.testing.Migrations.ensure();
		EconomyCounter.activate(Map.of());
	}

	@AfterAll
	static void stop()
	{
		EconomyCounter.deactivate();
	}

	private static Player player()
	{
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 40);
		if (World.getPlayer(player.getObjectId()) == null)
		{
			World.addObject(player);
		}
		return player;
	}

	/** What the counters changed by, per item id, while {@code action} ran. */
	private static Map<Integer, Long> change(Runnable action)
	{
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		action.run();
		final Map<Integer, Long> after = EconomyCounter.snapshot();
		final Set<Integer> ids = new HashSet<>(before.keySet());
		ids.addAll(after.keySet());
		final Map<Integer, Long> change = new TreeMap<>();
		for (int id : ids)
		{
			final long delta = after.getOrDefault(id, 0L) - before.getOrDefault(id, 0L);
			if (delta != 0)
			{
				change.put(id, delta);
			}
		}
		return change;
	}

	@Test
	void anItemCreatedIntoAnInventoryCountsAndAMergeAddsOnlyItsQuantity()
	{
		final Player player = player();
		assertEquals(Map.of(SOULSHOT, 100L), change(() -> player.getInventory().addItem(ItemProcessType.NONE, SOULSHOT, 100, player, null)));
		assertEquals(Map.of(SOULSHOT, 50L), change(() -> player.getInventory().addItem(ItemProcessType.NONE, SOULSHOT, 50, player, null)));
		assertEquals(Map.of(SHORT_SWORD, 1L), change(() -> player.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, player, null)));
	}

	@Test
	void aDestroyedQuantityLeaves()
	{
		final Player player = player();
		final Item shots = player.getInventory().addItem(ItemProcessType.NONE, SOULSHOT, 100, player, null);
		assertEquals(Map.of(SOULSHOT, -30L), change(() -> player.getInventory().destroyItem(ItemProcessType.NONE, shots, 30, player, null)));
		assertEquals(Map.of(SOULSHOT, -70L), change(() -> player.getInventory().destroyItem(ItemProcessType.NONE, shots, player, null)));
	}

	@Test
	void aDropLeavesTheCountedHoldingsAndAPickupEntersThem()
	{
		assertFalse(GeneralConfig.SAVE_DROPPED_ITEM, "the ground is a holding only with SaveDroppedItem");
		final Player player = player();
		final Item shots = player.getInventory().addItem(ItemProcessType.NONE, SOULSHOT, 100, player, null);
		final AtomicReference<Item> dropped = new AtomicReference<>();
		assertEquals(Map.of(SOULSHOT, -40L), change(() -> dropped.set(player.getInventory().dropItem(ItemProcessType.NONE, shots.getObjectId(), 40, player, null))));
		assertEquals(Map.of(SOULSHOT, 40L), change(() -> player.getInventory().addItem(ItemProcessType.NONE, dropped.get(), player, null)));
	}

	@Test
	void aMoveBetweenCountedHoldingsCountsNothing()
	{
		final Player player = player();
		final Item shots = player.getInventory().addItem(ItemProcessType.NONE, SOULSHOT, 100, player, null);
		final Item sword = player.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, player, null);
		assertEquals(Map.of(), change(() -> player.getInventory().transferItem(ItemProcessType.NONE, shots.getObjectId(), 60, player.getWarehouse(), player, null)));
		assertEquals(Map.of(), change(() -> player.getInventory().transferItem(ItemProcessType.NONE, sword.getObjectId(), 1, player.getWarehouse(), player, null)));
	}

	@Test
	void aSaleToTheBuybackListLeavesTheCountedHoldings()
	{
		final Player player = player();
		final Item sword = player.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, player, null);
		assertEquals(Map.of(SHORT_SWORD, -1L), change(() -> player.getInventory().transferItem(ItemProcessType.NONE, sword.getObjectId(), 1, player.getRefund(), player, null)));
	}

	@Test
	void aRestoredObjectCountsOnlyItsLaterChanges() throws Exception
	{
		final Player player = player();
		final int objectId = IdManager.getInstance().getNextId();
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement insert = connection.prepareStatement("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data) VALUES (?, ?, ?, 10, 0, 'WAREHOUSE', 0)"))
		{
			insert.setInt(1, player.getObjectId());
			insert.setInt(2, objectId);
			insert.setInt(3, SOULSHOT);
			insert.executeUpdate();
		}
		final AtomicReference<Item> restored = new AtomicReference<>();
		assertEquals(Map.of(), change(() -> restored.set(restore(player.getObjectId(), objectId))), "a restored row is already in the census");
		assertEquals(ItemLocation.WAREHOUSE, restored.get().getItemLocation());
		assertEquals(Map.of(SOULSHOT, 1L), change(() -> restored.get().changeCount(ItemProcessType.NONE, 1, player, null)));
	}

	private static Item restore(int ownerId, int objectId)
	{
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement select = connection.prepareStatement("SELECT object_id, item_id, count, loc, loc_data, enchant_level, custom_type1, custom_type2, mana_left, time FROM items WHERE object_id = ?"))
		{
			select.setInt(1, objectId);
			try (ResultSet rs = select.executeQuery())
			{
				assertTrue(rs.next());
				return Item.restoreFromDb(ownerId, rs);
			}
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	@Test
	void theServicesOwnTakesPostNothingAndALaterChangeCountsOnlyItself()
	{
		final Player player = player();
		final Item shots = player.getInventory().addItem(ItemProcessType.NONE, SOULSHOT, 100, player, null);
		assertEquals(Map.of(), change(() -> assertTrue(shots.tryDecrease(30))), "a stack decrease moves the quantity to a custody");
		assertEquals(Map.of(SOULSHOT, 1L), change(() -> shots.changeCount(ItemProcessType.NONE, 1, player, null)));
		final Item sword = player.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, player, null);
		assertEquals(Map.of(), change(sword::retire), "a retired whole object is the custody's");
		assertEquals(Map.of(), change(() -> sword.setItemLocation(ItemLocation.VOID, 0, false)));
	}

	@Test
	void aCountAndALocationChangingTogetherSettleExactly() throws Exception
	{
		final Player player = player();
		final Item shots = player.getInventory().addItem(ItemProcessType.NONE, SOULSHOT, 1000, player, null);
		final long before = EconomyCounter.snapshot().getOrDefault(SOULSHOT, 0L);
		final Thread counts = new Thread(() ->
		{
			for (int i = 0; i < 20_000; i++)
			{
				shots.changeCount(ItemProcessType.NONE, 1, player, null);
			}
		});
		final Thread moves = new Thread(() ->
		{
			for (int i = 0; i < 20_000; i++)
			{
				shots.setItemLocation((i % 2) == 0 ? ItemLocation.VOID : ItemLocation.INVENTORY, 0, false);
			}
			shots.setItemLocation(ItemLocation.INVENTORY, 0, false);
		});
		counts.start();
		moves.start();
		counts.join();
		moves.join();
		assertEquals(21_000, shots.getCount());
		assertEquals(before + 20_000, EconomyCounter.snapshot().getOrDefault(SOULSHOT, 0L));
	}

	@Test
	void aStackDecreaseRacingAMobiusChangeStillPostsOnlyTheMobiusChange() throws Exception
	{
		final Player player = player();
		final Item shots = player.getInventory().addItem(ItemProcessType.NONE, SOULSHOT, 100_000, player, null);
		final long before = EconomyCounter.snapshot().getOrDefault(SOULSHOT, 0L);
		final Thread mobius = new Thread(() ->
		{
			for (int i = 0; i < 20_000; i++)
			{
				shots.changeCount(ItemProcessType.NONE, 1, player, null);
			}
		});
		final Thread service = new Thread(() ->
		{
			for (int i = 0; i < 20_000; i++)
			{
				assertTrue(shots.tryDecrease(1));
			}
		});
		mobius.start();
		service.start();
		mobius.join();
		service.join();
		assertEquals(100_000, shots.getCount());
		assertEquals(before + 20_000, EconomyCounter.snapshot().getOrDefault(SOULSHOT, 0L), "the service's 20 000 are a custody's, still counted");
	}

	@Test
	void aRetireCountsAMobiusChangeNotYetSettled()
	{
		final Player player = player();
		final Item shots = player.getInventory().addItem(ItemProcessType.NONE, SOULSHOT, 100, player, null);
		assertEquals(Map.of(), change(shots::retire));
	}

	@Test
	void aClampIsCountedAsAppliedAndRecordedApart()
	{
		final Player player = player();
		final Item adena = player.getInventory().addItem(ItemProcessType.NONE, AssetFixtures.ADENA, 1_000, player, null);
		final long room = org.l2jmobius.gameserver.entity.itemcontainer.Inventory.MAX_ADENA - adena.getCount();
		final long before = EconomyCounter.clamped().getOrDefault(AssetFixtures.ADENA, 0L);
		assertEquals(Map.of(AssetFixtures.ADENA, room), change(() -> adena.changeCount(ItemProcessType.NONE, room + 500, player, null)), "only what fits is counted");
		assertEquals(before + 500, EconomyCounter.clamped().getOrDefault(AssetFixtures.ADENA, 0L), "the 500 that did not fit were clamped away");
	}

	@Test
	void aCountedOutMarkLastsUntilItsRowHoldsNothing()
	{
		final Player player = player();
		final Item shots = player.getInventory().addItem(ItemProcessType.NONE, SOULSHOT, 30, player, null);
		shots.updateDatabase(true);
		shots.setCount(0); // destroyed in memory, its row still holding 30
		assertTrue(EconomyCounter.isCountedOut(shots.getObjectId()));
		EconomyCounter.rowWritten(shots.getObjectId(), 30); // a save that began before and wrote the older quantity
		assertTrue(EconomyCounter.isCountedOut(shots.getObjectId()), "that row still holds the 30");
		shots.updateDatabase(true); // Mobius's own write of the destroyed object deletes its row
		assertFalse(EconomyCounter.isCountedOut(shots.getObjectId()));
	}

	@Test
	void aDestructionDuringTheFirstInsertLeavesItsMark() throws Exception
	{
		final Item fresh = new Item(IdManager.getInstance().getNextId(), SOULSHOT);
		fresh.setCount(30);
		fresh.setItemLocation(ItemLocation.INVENTORY, 0, false); // counted, its row not yet known to Mobius
		final java.lang.reflect.Field inserting = Item.class.getDeclaredField("_inserting");
		inserting.setAccessible(true);
		inserting.set(fresh, true); // its INSERT has run, Mobius has not yet recorded the row
		fresh.setCount(0); // destroyed meanwhile
		assertTrue(EconomyCounter.isCountedOut(fresh.getObjectId()), "a row may hold it: a counted deletion must not count it again");
	}

	@Test
	void anInactiveCounterPostsNothing()
	{
		final Player player = player();
		final Item shots = player.getInventory().addItem(ItemProcessType.NONE, SOULSHOT, 100, player, null);
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		EconomyCounter.deactivate();
		try
		{
			assertFalse(EconomyCounter.isActive());
			shots.changeCount(ItemProcessType.NONE, 5, player, null);
			player.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, player, null);
			assertEquals(before, EconomyCounter.snapshot());
		}
		finally
		{
			EconomyCounter.activate(before);
		}
	}
}
