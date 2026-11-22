package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.livingchronicle.asset.Census.Holding;
import org.l2jmobius.testing.TestServer;

/**
 * The census (docs/spec/asset-service.md §2.6): what each holding counts from the database, and an SQL site's statement
 * counted under locks before and after it in its own transaction, posted only when it commits (§11.24, CP-0025).
 */
class CensusTest
{
	private static final int SOULSHOT = 1835;
	private static final AtomicInteger IDS = new AtomicInteger(1_900_000_000);

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

	private static void sql(String statement, Object... params) throws SQLException
	{
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement ps = connection.prepareStatement(statement))
		{
			for (int i = 0; i < params.length; i++)
			{
				ps.setObject(i + 1, params[i]);
			}
			ps.executeUpdate();
		}
	}

	private static void item(int owner, int itemId, long count, String loc) throws SQLException
	{
		sql("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data) VALUES (?, ?, ?, ?, 0, ?, 0)", owner, IDS.incrementAndGet(), itemId, count, loc);
	}

	private static Map<Integer, Long> census() throws SQLException
	{
		try (Connection connection = DatabaseFactory.getConnection())
		{
			return Census.all(connection);
		}
	}

	private static Map<Integer, Long> minus(Map<Integer, Long> after, Map<Integer, Long> before)
	{
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
	void everyHoldingIsCountedAndNothingElse() throws Exception
	{
		final int owner = IDS.incrementAndGet();
		final Map<Integer, Long> before = census();
		item(owner, SOULSHOT, 100, "INVENTORY");
		item(owner, SOULSHOT, 20, "WAREHOUSE");
		item(owner, SOULSHOT, 7, "REFUND"); // outside the census
		item(owner, SOULSHOT, 3, "VOID"); // outside the census
		item(-1, SOULSHOT, 4, "ESCROW"); // a custody's row
		sql("UPDATE castle SET treasury = treasury + 500 WHERE id = 5");
		final int auction = IDS.incrementAndGet();
		sql("INSERT INTO item_auction (auctionId, instanceId, auctionItemId, startingTime, endingTime, auctionStateId) VALUES (?, 0, 0, 0, 0, 2)", auction);
		sql("INSERT INTO item_auction_bid (auctionId, playerObjId, playerBid) VALUES (?, 1, 900), (?, 2, 700), (?, 3, -1)", auction, auction, auction);
		sql("INSERT INTO auction_bid (id, auctionId, bidderId, bidderName, clan_name, maxBid, time_bid) VALUES (?, ?, 1, 'x', 'x', 300, 0)", IDS.incrementAndGet(), IDS.incrementAndGet());
		try
		{
			// 100 + 20 + 4 soulshots; Adena: the treasury's 500, the losing bid's 700 (the winning 900 was paid, -1 is cancelled), the hall bid's 300
			assertEquals(Map.of(SOULSHOT, 124L, ADENA, 1_500L), minus(census(), before));
		}
		finally
		{
			sql("UPDATE castle SET treasury = treasury - 500 WHERE id = 5");
		}
	}

	@Test
	void theSavedGroundCountsOnlyWhenSaved() throws Exception
	{
		final boolean saved = GeneralConfig.SAVE_DROPPED_ITEM;
		try
		{
			GeneralConfig.SAVE_DROPPED_ITEM = false;
			final Map<Integer, Long> before = census();
			sql("INSERT INTO itemsonground (object_id, item_id, count, enchant_level, x, y, z, drop_time, equipable) VALUES (?, ?, 50, 0, 0, 0, 0, -1, 0)", IDS.incrementAndGet(), SOULSHOT);
			assertEquals(Map.of(), minus(census(), before));
			GeneralConfig.SAVE_DROPPED_ITEM = true;
			assertEquals(SOULSHOT, minus(census(), before).keySet().iterator().next());
		}
		finally
		{
			GeneralConfig.SAVE_DROPPED_ITEM = saved;
		}
	}

	@Test
	void aCountedStatementPostsTheDifferenceWhenItCommits() throws Exception
	{
		final int owner = IDS.incrementAndGet();
		item(owner, SOULSHOT, 40, "INVENTORY");
		item(owner, SOULSHOT, 9, "REFUND");
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement delete = connection.prepareStatement("DELETE FROM items WHERE owner_id = ?"))
		{
			delete.setInt(1, owner);
			assertEquals(2, Census.counting(connection, Holding.ITEMS, "owner_id = ?", new Object[]
			{
				owner
			}, delete::executeUpdate));
			assertTrue(connection.getAutoCommit(), "the connection is handed back as it came");
		}
		assertEquals(Map.of(SOULSHOT, -40L), minus(EconomyCounter.snapshot(), before), "only what the census counted left");
	}

	@Test
	void aFailedStatementPostsNothingAndChangesNothing() throws Exception
	{
		final int owner = IDS.incrementAndGet();
		item(owner, SOULSHOT, 40, "INVENTORY");
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement delete = connection.prepareStatement("DELETE FROM items WHERE owner_id = ?"))
		{
			delete.setInt(1, owner);
			assertThrows(SQLException.class, () -> Census.counting(connection, Holding.ITEMS, "owner_id = ?", new Object[]
			{
				owner
			}, () ->
			{
				delete.executeUpdate();
				throw new SQLException("the statement fails after its delete");
			}));
		}
		assertEquals(before, EconomyCounter.snapshot());
		try (Connection connection = DatabaseFactory.getConnection())
		{
			assertEquals(Map.of(SOULSHOT, 40L), Census.count(connection, Holding.ITEMS, "owner_id = ?", owner), "rolled back");
		}
	}

	@Test
	void anInactiveCounterRunsTheStatementAsUpstream() throws Exception
	{
		final int owner = IDS.incrementAndGet();
		item(owner, SOULSHOT, 40, "INVENTORY");
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		EconomyCounter.deactivate();
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement delete = connection.prepareStatement("DELETE FROM items WHERE owner_id = ?"))
		{
			delete.setInt(1, owner);
			assertEquals(1, Census.counting(connection, Holding.ITEMS, "owner_id = ?", new Object[]
			{
				owner
			}, delete::executeUpdate));
		}
		finally
		{
			EconomyCounter.activate(before);
		}
		assertEquals(before, EconomyCounter.snapshot());
	}

	@Test
	void aCountedStatementNeedsATransactionOfItsOwn() throws Exception
	{
		final int owner = IDS.incrementAndGet();
		try (Connection connection = DatabaseFactory.getConnection())
		{
			connection.setAutoCommit(false);
			try (PreparedStatement insert = connection.prepareStatement("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data) VALUES (?, ?, ?, 5, 0, 'INVENTORY', 0)"))
			{
				insert.setInt(1, owner);
				insert.setInt(2, IDS.incrementAndGet());
				insert.setInt(3, SOULSHOT);
				insert.executeUpdate(); // the caller's own transaction, not to be committed by the census
			}
			assertThrows(IllegalStateException.class, () -> Census.counting(connection, Holding.ITEMS, "owner_id = ?", new Object[]
			{
				owner
			}, () -> 0));
			connection.rollback();
			connection.setAutoCommit(true);
			assertEquals(Map.of(), Census.count(connection, Holding.ITEMS, "owner_id = ?", owner), "the caller's change was neither committed nor kept");
		}
	}

	/** A connection whose commit reaches the server or not, and then loses its acknowledgement either way. */
	private static Connection losingTheAcknowledgement(Connection connection, boolean commits)
	{
		return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]
		{
			Connection.class
		}, (proxy, method, args) ->
		{
			if (method.getName().equals("commit"))
			{
				if (commits)
				{
					connection.commit();
				}
				else
				{
					connection.rollback();
				}
				throw new SQLException("the acknowledgement was lost");
			}
			try
			{
				return method.invoke(connection, args);
			}
			catch (java.lang.reflect.InvocationTargetException e)
			{
				throw e.getCause();
			}
		});
	}

	@Test
	void aCommitWhoseAcknowledgementIsLostIsResolvedFromTheRows() throws Exception
	{
		for (boolean commits : new boolean[]
		{
			true,
			false
		})
		{
			final int owner = IDS.incrementAndGet();
			item(owner, SOULSHOT, 40, "INVENTORY");
			final Map<Integer, Long> before = EconomyCounter.snapshot();
			try (Connection real = DatabaseFactory.getConnection())
			{
				final Connection connection = losingTheAcknowledgement(real, commits);
				final PreparedStatement delete = connection.prepareStatement("DELETE FROM items WHERE owner_id = ?");
				delete.setInt(1, owner);
				final Census.Work work = delete::executeUpdate;
				if (commits)
				{
					assertEquals(1, Census.counting(connection, Holding.ITEMS, "owner_id = ?", new Object[]
					{
						owner
					}, work), "it committed: the statement's result stands");
				}
				else
				{
					assertThrows(SQLException.class, () -> Census.counting(connection, Holding.ITEMS, "owner_id = ?", new Object[]
					{
						owner
					}, work));
				}
			}
			assertEquals(commits ? Map.of(SOULSHOT, -40L) : Map.of(), minus(EconomyCounter.snapshot(), before), commits ? "committed: posted" : "not committed: nothing posted");
		}
	}

	@Test
	void aLostAcknowledgementIsDecidedByTheStatementsOwnRowWhateverOthersDidMeanwhile() throws Exception
	{
		final int owner = IDS.incrementAndGet();
		item(owner, SOULSHOT, 40, "INVENTORY");
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		try (Connection real = DatabaseFactory.getConnection())
		{
			final Connection connection = (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]
			{
				Connection.class
			}, (proxy, method, args) ->
			{
				if (method.getName().equals("commit"))
				{
					real.rollback(); // this one did not commit ...
					try (Connection other = DatabaseFactory.getConnection();
						PreparedStatement delete = other.prepareStatement("DELETE FROM items WHERE owner_id = ?"))
					{
						delete.setInt(1, owner);
						Census.counting(other, Holding.ITEMS, "owner_id = ?", new Object[]
						{
							owner
						}, delete::executeUpdate); // ... another deletion of the same rows did, before the first is resolved
					}
					throw new SQLException("the acknowledgement was lost");
				}
				try
				{
					return method.invoke(real, args);
				}
				catch (java.lang.reflect.InvocationTargetException e)
				{
					throw e.getCause();
				}
			});
			final PreparedStatement delete = connection.prepareStatement("DELETE FROM items WHERE owner_id = ?");
			delete.setInt(1, owner);
			assertThrows(SQLException.class, () -> Census.counting(connection, Holding.ITEMS, "owner_id = ?", new Object[]
			{
				owner
			}, delete::executeUpdate));
		}
		assertEquals(Map.of(SOULSHOT, -40L), minus(EconomyCounter.snapshot(), before), "one deletion, one sink");
	}

	private static org.l2jmobius.gameserver.entity.item.instance.Item loadedCopy(long count) throws Exception
	{
		final org.l2jmobius.gameserver.entity.actor.Player player = org.l2jmobius.testing.TestPlayers.create(org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass.FIGHTER, 40);
		if (org.l2jmobius.gameserver.entity.World.getPlayer(player.getObjectId()) == null)
		{
			org.l2jmobius.gameserver.entity.World.addObject(player);
		}
		final org.l2jmobius.gameserver.entity.item.instance.Item copy = player.getInventory().addItem(org.l2jmobius.gameserver.entity.item.enums.ItemProcessType.NONE, SOULSHOT, count, player, null);
		copy.updateDatabase(true);
		return copy;
	}

	private static void deleteCounted(org.l2jmobius.gameserver.entity.item.instance.Item copy, Runnable during) throws Exception
	{
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement delete = connection.prepareStatement("DELETE FROM items WHERE object_id = ?"))
		{
			delete.setInt(1, copy.getObjectId());
			Census.counting(connection, Holding.ITEMS, "object_id = ?", new Object[]
			{
				copy.getObjectId()
			}, () ->
			{
				during.run();
				return delete.executeUpdate();
			});
		}
	}

	@Test
	void aCopyDestroyedWhileItsRowsDeletionRunsCountsNothingMore() throws Exception
	{
		final org.l2jmobius.gameserver.entity.item.instance.Item copy = loadedCopy(30);
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		final Thread destroyer = new Thread(() -> copy.setCount(0));
		deleteCounted(copy, () ->
		{
			destroyer.start();
			final long until = System.nanoTime() + 2_000_000_000L;
			while ((destroyer.getState() != Thread.State.BLOCKED) && (destroyer.getState() != Thread.State.TERMINATED) && (System.nanoTime() < until))
			{
				Thread.onSpinWait();
			}
		});
		destroyer.join();
		assertEquals(Map.of(SOULSHOT, -30L), minus(EconomyCounter.snapshot(), before), "the row's deletion counted it; the copy's destruction waited and counts nothing");
	}

	@Test
	void aCopyThatCountedItselfOutBeforeItsRowWentIsNotCountedTwice() throws Exception
	{
		final org.l2jmobius.gameserver.entity.item.instance.Item copy = loadedCopy(30);
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		copy.setCount(0); // destroyed in memory; its row not yet deleted, as Mobius deletes it a step later
		deleteCounted(copy, () ->
		{
		});
		assertEquals(Map.of(SOULSHOT, -30L), minus(EconomyCounter.snapshot(), before));
	}

	@Test
	void aPartlyConsumedCopysRowIsCountedAsMemoryHoldsIt() throws Exception
	{
		final org.l2jmobius.gameserver.entity.item.instance.Item copy = loadedCopy(30);
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		copy.changeCount(org.l2jmobius.gameserver.entity.item.enums.ItemProcessType.NONE, -10, null, null); // consumed, not yet written
		deleteCounted(copy, () ->
		{
		});
		assertEquals(Map.of(SOULSHOT, -30L), minus(EconomyCounter.snapshot(), before), "10 consumed, then the 20 memory held; never the row's stale 30");
	}

	@Test
	void anItemDestroyedAndGoneFromTheWorldBeforeItsRowWentIsNotCountedTwice() throws Exception
	{
		final org.l2jmobius.gameserver.entity.item.instance.Item copy = loadedCopy(30);
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		copy.setCount(0); // as ItemManager.destroyItem counts it out ...
		org.l2jmobius.gameserver.entity.World.removeObject(copy); // ... and takes it from the world, its row deleted a step later
		deleteCounted(copy, () ->
		{
		});
		assertEquals(Map.of(SOULSHOT, -30L), minus(EconomyCounter.snapshot(), before));
	}

	@Test
	void aCommitThatFailsWithItsTransactionOpenCommitsNothingAndPostsNothing() throws Exception
	{
		final int owner = IDS.incrementAndGet();
		item(owner, SOULSHOT, 40, "INVENTORY");
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		try (Connection real = DatabaseFactory.getConnection())
		{
			final Connection connection = (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]
			{
				Connection.class
			}, (proxy, method, args) ->
			{
				if (method.getName().equals("commit"))
				{
					throw new SQLException("the commit never reached the server"); // the transaction stays open
				}
				try
				{
					return method.invoke(real, args);
				}
				catch (java.lang.reflect.InvocationTargetException e)
				{
					throw e.getCause();
				}
			});
			final PreparedStatement delete = connection.prepareStatement("DELETE FROM items WHERE owner_id = ?");
			delete.setInt(1, owner);
			assertThrows(SQLException.class, () -> Census.counting(connection, Holding.ITEMS, "owner_id = ?", new Object[]
			{
				owner
			}, delete::executeUpdate));
		}
		assertEquals(before, EconomyCounter.snapshot());
		try (Connection connection = DatabaseFactory.getConnection())
		{
			assertEquals(Map.of(SOULSHOT, 40L), Census.count(connection, Holding.ITEMS, "owner_id = ?", owner), "rolled back, never committed by a cleanup");
		}
	}

	@Test
	void anItemWhoseLateRowDeletionRunsBehindTheStatementIsCountedOnce() throws Exception
	{
		for (int i = 0; i < 20; i++)
		{
			final org.l2jmobius.gameserver.entity.item.instance.Item copy = loadedCopy(30);
			final Map<Integer, Long> before = EconomyCounter.snapshot();
			copy.setCount(0); // ItemManager.destroyItem counts it out ...
			org.l2jmobius.gameserver.entity.World.removeObject(copy); // ... leaves the world ...
			final Thread persist = new Thread(() -> copy.updateDatabase(true)); // ... and deletes its row, waiting behind the statement
			deleteCounted(copy, () ->
			{
				persist.start();
				final long until = System.nanoTime() + 2_000_000_000L;
				while ((persist.getState() != Thread.State.WAITING) && (persist.getState() != Thread.State.TIMED_WAITING) && (persist.getState() != Thread.State.TERMINATED) && (System.nanoTime() < until))
				{
					Thread.onSpinWait();
				}
			});
			persist.join();
			assertEquals(Map.of(SOULSHOT, -30L), minus(EconomyCounter.snapshot(), before), "iteration " + i);
		}
	}

	@Test
	void aCommittedStatementIsPostedEvenIfHandingTheConnectionBackFails() throws Exception
	{
		final int owner = IDS.incrementAndGet();
		item(owner, SOULSHOT, 40, "INVENTORY");
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		try (Connection real = DatabaseFactory.getConnection())
		{
			final boolean[] committed = new boolean[1];
			final Connection connection = (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]
			{
				Connection.class
			}, (proxy, method, args) ->
			{
				if (method.getName().equals("commit"))
				{
					real.commit();
					committed[0] = true;
					return null;
				}
				if (method.getName().equals("setAutoCommit") && committed[0] && Boolean.TRUE.equals(args[0]))
				{
					throw new SQLException("the connection failed after its commit");
				}
				try
				{
					return method.invoke(real, args);
				}
				catch (java.lang.reflect.InvocationTargetException e)
				{
					throw e.getCause();
				}
			});
			final PreparedStatement delete = connection.prepareStatement("DELETE FROM items WHERE owner_id = ?");
			delete.setInt(1, owner);
			assertEquals(1, Census.counting(connection, Holding.ITEMS, "owner_id = ?", new Object[]
			{
				owner
			}, delete::executeUpdate));
			real.setAutoCommit(true);
		}
		assertEquals(Map.of(SOULSHOT, -40L), minus(EconomyCounter.snapshot(), before), "it committed: it is posted");
	}

	@Test
	void aCommitWhoseOutcomeCannotBeReadLeavesTheCountersUncertainAndTheBoundaryCertifiesNothing() throws Exception
	{
		final int owner = IDS.incrementAndGet();
		item(owner, SOULSHOT, 40, "INVENTORY");
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		final Connections resolver = Census.resolver;
		Census.resolver = () ->
		{
			throw new SQLException("the database is down while the commit is resolved");
		};
		try (Connection real = DatabaseFactory.getConnection())
		{
			final Connection connection = losingTheAcknowledgement(real, true);
			final PreparedStatement delete = connection.prepareStatement("DELETE FROM items WHERE owner_id = ?");
			delete.setInt(1, owner);
			assertThrows(SQLException.class, () -> Census.counting(connection, Holding.ITEMS, "owner_id = ?", new Object[]
			{
				owner
			}, delete::executeUpdate));
			assertTrue(EconomyCounter.isUncertain());
			assertFalse(EconomyMonitor.atBoundary(List.of()), "uncertain counters certify no shutdown census: the next start is a rebase");
		}
		finally
		{
			Census.resolver = resolver;
			EconomyCounter.activate(before);
		}
	}

	@Test
	void aLoadedCopyOfARowACountedStatementDeletedNoLongerCounts() throws Exception
	{
		final org.l2jmobius.gameserver.entity.actor.Player player = org.l2jmobius.testing.TestPlayers.create(org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass.FIGHTER, 40);
		if (org.l2jmobius.gameserver.entity.World.getPlayer(player.getObjectId()) == null)
		{
			org.l2jmobius.gameserver.entity.World.addObject(player);
		}
		final org.l2jmobius.gameserver.entity.item.instance.Item copy = player.getInventory().addItem(org.l2jmobius.gameserver.entity.item.enums.ItemProcessType.NONE, SOULSHOT, 30, player, null);
		copy.updateDatabase(true);
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement delete = connection.prepareStatement("DELETE FROM items WHERE object_id = ?"))
		{
			delete.setInt(1, copy.getObjectId());
			Census.counting(connection, Holding.ITEMS, "object_id = ?", new Object[]
			{
				copy.getObjectId()
			}, delete::executeUpdate); // as an ended event deletes a copy a loaded warehouse still holds
		}
		assertEquals(Map.of(SOULSHOT, -30L), minus(EconomyCounter.snapshot(), before));
		player.getInventory().destroyItem(org.l2jmobius.gameserver.entity.item.enums.ItemProcessType.NONE, copy, player, null);
		assertEquals(Map.of(SOULSHOT, -30L), minus(EconomyCounter.snapshot(), before), "its row went already: destroying the copy counts nothing more");
	}

	@Test
	void aStatementNamesTheHoldingItWritesAndItsCondition()
	{
		assertEquals(Holding.ITEMS, Census.written("DELETE FROM items WHERE owner_id=?"));
		assertEquals("owner_id=?", Census.whereOf("DELETE FROM items WHERE owner_id=?"));
		assertEquals(Holding.ITEMS, Census.written("UPDATE items SET loc=\"INVENTORY\" WHERE owner_id=?"));
		assertEquals(Holding.ITEM_AUCTION_BIDS, Census.written("DELETE FROM item_auction_bid WHERE item_auction_bid.playerObjId NOT IN (SELECT charId FROM characters)"));
		assertEquals(Holding.CLAN_HALL_BIDS, Census.written("DELETE FROM auction_bid WHERE auction_bid.bidderId NOT IN (SELECT clan_id FROM clan_data)"));
		assertNull(Census.written("DELETE FROM siege_clans WHERE siege_clans.clan_id NOT IN (SELECT clan_id FROM clan_data)"));
		assertNull(Census.written("UPDATE castle SET taxpercent=0 WHERE castle.id NOT IN (SELECT hasCastle FROM clan_data)"));
		assertEquals("1 = 1", Census.whereOf("DELETE FROM itemsonground"));
	}
}
