package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.testing.TestServer;

/**
 * CP-0009 (docs/spec/asset-service.md §11.15): a stack's count changes atomically, so two changes never lose one another,
 * and the Asset Service's debit {@code tryDecrease} takes a quantity only if at least one unit remains. Retail clamps are
 * unchanged.
 */
class AtomicCountTest
{
	private static final int ADENA = 57;
	private static final int SOULSHOT_D = 1463;

	@BeforeAll
	static void boot() throws Exception
	{
		TestServer.boot();
	}

	private static Item stack(int itemId, long count)
	{
		final Item item = new Item(IdManager.getInstance().getNextId(), itemId);
		item.setCount(count);
		return item;
	}

	@Test
	void concurrentChangesLoseNothing() throws Exception
	{
		final Item item = stack(SOULSHOT_D, 0);
		final int threads = 8;
		final int each = 20_000;
		final CountDownLatch start = new CountDownLatch(1);
		final List<Thread> workers = new ArrayList<>();
		for (int t = 0; t < threads; t++)
		{
			final Thread worker = new Thread(() ->
			{
				try
				{
					start.await();
				}
				catch (InterruptedException e)
				{
					return;
				}
				for (int i = 0; i < each; i++)
				{
					item.changeCount(ItemProcessType.NONE, 1, null, null);
				}
			});
			worker.start();
			workers.add(worker);
		}
		start.countDown();
		for (Thread worker : workers)
		{
			worker.join();
		}
		assertEquals((long) threads * each, item.getCount(), "every change counted");
	}

	@Test
	void tryDecreaseLeavesAtLeastOne()
	{
		final Item item = stack(SOULSHOT_D, 10);
		assertTrue(item.tryDecrease(9));
		assertEquals(1, item.getCount());
		assertFalse(item.tryDecrease(1), "the last unit is never taken: taking a whole stack is a whole-object debit");
		assertEquals(1, item.getCount());
		assertFalse(stack(SOULSHOT_D, 5).tryDecrease(6));
	}

	@Test
	void tryDecreaseRacingIncreasesLosesNothing() throws Exception
	{
		final Item item = stack(SOULSHOT_D, 1_000_001);
		final Thread taker = new Thread(() ->
		{
			for (int i = 0; i < 100_000; i++)
			{
				item.tryDecrease(10);
			}
		});
		final Thread giver = new Thread(() ->
		{
			for (int i = 0; i < 100_000; i++)
			{
				item.changeCount(ItemProcessType.NONE, 3, null, null);
			}
		});
		taker.start();
		giver.start();
		taker.join();
		giver.join();
		assertEquals(1_000_001 - 1_000_000 + 300_000, item.getCount());
	}

	@Test
	void retailClampsAreUnchanged()
	{
		final Item adena = stack(ADENA, Inventory.MAX_ADENA - 1);
		adena.changeCount(ItemProcessType.NONE, 5, null, null);
		assertEquals(Inventory.MAX_ADENA, adena.getCount(), "Adena stops at MAX_ADENA");
		final Item shots = stack(SOULSHOT_D, 3);
		shots.changeCount(ItemProcessType.NONE, -5, null, null);
		assertEquals(0, shots.getCount(), "a count never goes below 0");
	}
}
