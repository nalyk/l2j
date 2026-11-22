package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.ItemsOnGroundManager;
import org.l2jmobius.gameserver.managers.MercTicketManager;
import org.l2jmobius.testing.TestServer;

/**
 * The saved ground (docs/spec/asset-service.md §11.27, CP-0024): with {@code SaveDroppedItem}, a placed mercenary ticket is
 * owned by {@code MercTicketManager} and was restored twice (once from the saved ground, once from castle_siege_guards),
 * and a stack's count was read as an int.
 */
class SavedGroundTest
{
	private static boolean _saved;

	@BeforeAll
	static void boot() throws Exception
	{
		TestServer.boot();
		org.l2jmobius.testing.Migrations.ensure();
		_saved = GeneralConfig.SAVE_DROPPED_ITEM;
		GeneralConfig.SAVE_DROPPED_ITEM = true;
		EconomyCounter.activate(Map.of());
	}

	@AfterAll
	static void stop()
	{
		EconomyCounter.deactivate();
		GeneralConfig.SAVE_DROPPED_ITEM = _saved;
	}

	@SuppressWarnings("unchecked")
	private static Collection<Item> ground() throws Exception
	{
		final Field items = ItemsOnGroundManager.class.getDeclaredField("_items");
		items.setAccessible(true);
		return (Collection<Item>) items.get(ItemsOnGroundManager.getInstance());
	}

	@Test
	void aPlacedMercenaryTicketIsNotSavedToTheGround() throws Exception
	{
		final Field ids = MercTicketManager.class.getDeclaredField("ITEM_IDS");
		ids.setAccessible(true);
		final int ticketId = ((int[]) ids.get(null))[0];
		assertTrue(MercTicketManager.getInstance().getTicketCastleId(ticketId) > 0);
		final Item ticket = new Item(IdManager.getInstance().getNextId(), ticketId);
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		ItemsOnGroundManager.getInstance().save(ticket);
		assertFalse(ground().contains(ticket), "MercTicketManager keeps placed tickets in castle_siege_guards");
		assertEquals(before, EconomyCounter.snapshot());
	}

	@Test
	void aTicketSavedBeforeThePatchIsNotRestoredFromTheGroundAndItsRowGoesCounted() throws Exception
	{
		final Field ids = MercTicketManager.class.getDeclaredField("ITEM_IDS");
		ids.setAccessible(true);
		final int ticketId = ((int[]) ids.get(null))[0];
		final int objectId = IdManager.getInstance().getNextId();
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement insert = connection.prepareStatement("INSERT INTO itemsonground (object_id, item_id, count, enchant_level, x, y, z, drop_time, equipable) VALUES (?, ?, 1, 0, 83400, 147900, -3400, -1, 0)"))
		{
			insert.setInt(1, objectId);
			insert.setInt(2, ticketId);
			insert.executeUpdate();
		}
		final Map<Integer, Long> census = census();
		final Map<Integer, Long> counters = EconomyCounter.snapshot();
		final Method load = ItemsOnGroundManager.class.getDeclaredMethod("load");
		load.setAccessible(true);
		load.invoke(ItemsOnGroundManager.getInstance());
		assertEquals(null, World.findObject(objectId), "MercTicketManager rebuilds the ticket from castle_siege_guards");
		final Map<Integer, Long> changed = minus(census(), census);
		assertEquals(Map.of(ticketId, -1L), changed, "the stale row is gone");
		assertEquals(changed, minus(EconomyCounter.snapshot(), counters), "and counted out");
	}

	private static Map<Integer, Long> census() throws Exception
	{
		try (Connection connection = DatabaseFactory.getConnection())
		{
			return Census.all(connection);
		}
	}

	private static Map<Integer, Long> minus(Map<Integer, Long> after, Map<Integer, Long> before)
	{
		final java.util.Set<Integer> keys = new java.util.HashSet<>(before.keySet());
		keys.addAll(after.keySet());
		final Map<Integer, Long> change = new java.util.TreeMap<>();
		for (int id : keys)
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
	void aSavedStackLargerThanAnIntIsRestoredWhole() throws Exception
	{
		final int objectId = IdManager.getInstance().getNextId();
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement insert = connection.prepareStatement("INSERT INTO itemsonground (object_id, item_id, count, enchant_level, x, y, z, drop_time, equipable) VALUES (?, 57, 3000000000, 0, 83400, 147900, -3400, -1, 0)"))
		{
			insert.setInt(1, objectId);
			insert.executeUpdate();
		}
		final Method load = ItemsOnGroundManager.class.getDeclaredMethod("load");
		load.setAccessible(true);
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		load.invoke(ItemsOnGroundManager.getInstance());
		final WorldObject restored = World.findObject(objectId);
		assertNotNull(restored, "the row was restored");
		assertEquals(3_000_000_000L, ((Item) restored).getCount());
		assertEquals(before, EconomyCounter.snapshot(), "a restore is never counted");
	}
}
