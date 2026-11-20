package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.managers.CastleManager;
import org.l2jmobius.gameserver.mechanics.siege.Castle;
import org.l2jmobius.testing.TestServer;

/**
 * CP-0012 (docs/spec/asset-service.md §3.3, §11.10): a castle treasury changes atomically, so concurrent income and
 * withdrawals lose nothing and never overdraw it, and its row is written under a treasury lock the Asset Service takes
 * too, so the row ends equal to memory. Retail's clamp and overdraw refusal are unchanged.
 */
class TreasuryTest
{
	private static TestServer _server;
	private Castle _castle;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.boot();
	}

	private static void set(Castle castle, String fieldName, Object value) throws Exception
	{
		final Field field = Castle.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(castle, value);
	}

	@BeforeEach
	void ownedCastle() throws Exception
	{
		_castle = CastleManager.getInstance().getCastleById(1);
		set(_castle, "_ownerId", 1); // only an owned castle has a treasury
		set(_castle, "_treasury", 0L);
	}

	@AfterEach
	void release() throws Exception
	{
		set(_castle, "_treasury", 0L);
		set(_castle, "_ownerId", 0);
	}

	private static void race(int threads, Runnable each) throws Exception
	{
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
				each.run();
			});
			worker.start();
			workers.add(worker);
		}
		start.countDown();
		for (Thread worker : workers)
		{
			worker.join();
		}
	}

	private long row() throws Exception
	{
		try (Connection connection = _server.database().connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery("SELECT treasury FROM castle WHERE id = 1"))
		{
			result.next();
			return result.getLong(1);
		}
	}

	@Test
	void concurrentIncomeAndWithdrawalsLoseNothingAndTheRowEqualsMemory() throws Exception
	{
		_castle.addToTreasuryNoTax(1_000_000);
		final AtomicInteger turn = new AtomicInteger();
		race(8, () ->
		{
			final boolean income = (turn.incrementAndGet() % 2) == 0;
			for (int i = 0; i < 1_500; i++)
			{
				_castle.addToTreasuryNoTax(income ? 3 : -2);
			}
		});
		assertEquals(1_000_000 + (4 * 1_500 * 3) - (4 * 1_500 * 2), _castle.getTreasury());
		assertEquals(_castle.getTreasury(), row(), "the last write carries the last value");
	}

	@Test
	void anOverdrawIsRefusedUnderRace() throws Exception
	{
		_castle.addToTreasuryNoTax(1_000);
		final AtomicInteger taken = new AtomicInteger();
		race(40, () ->
		{
			if (_castle.addToTreasuryNoTax(-100))
			{
				taken.incrementAndGet();
			}
		});
		assertEquals(10, taken.get(), "exactly what the treasury held");
		assertEquals(0, _castle.getTreasury());
	}

	@Test
	void retailClampIsUnchangedAndTheLockExists()
	{
		_castle.addToTreasuryNoTax(Long.MAX_VALUE - 5);
		_castle.addToTreasuryNoTax(10);
		assertEquals(org.l2jmobius.gameserver.entity.itemcontainer.Inventory.MAX_ADENA, _castle.getTreasury());
		assertNotNull(_castle.treasuryLock());
	}
}
