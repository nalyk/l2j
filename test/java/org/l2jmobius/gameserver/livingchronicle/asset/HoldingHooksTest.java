package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.item.auction.AuctionItem;
import org.l2jmobius.gameserver.entity.item.auction.ItemAuction;
import org.l2jmobius.gameserver.entity.item.auction.ItemAuctionState;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.residences.ClanHallAuction;
import org.l2jmobius.gameserver.managers.CastleManager;
import org.l2jmobius.gameserver.managers.ItemsOnGroundManager;
import org.l2jmobius.gameserver.mechanics.siege.Castle;
import org.l2jmobius.gameserver.util.StatSet;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * The existence counters' holding hooks (docs/spec/asset-service.md §2.6, §11.24; CP-0022): a castle treasury, the saved
 * ground list and the open bids of both auctions are census holdings no item call changes, so each change posts where
 * Mobius makes it, and a move between them and an inventory or warehouse nets to nothing.
 */
class HoldingHooksTest
{
	private static final int SOULSHOT = 1835;
	private static final int HALL = 23;
	private static final AtomicInteger NAMES = new AtomicInteger();

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

	private static Clan clan()
	{
		final Clan clan = ClanTable.getInstance().createClan(player(), "Hooks" + NAMES.incrementAndGet());
		assertTrue(clan != null, "the test clan is created");
		clan.getWarehouse().addItem(ItemProcessType.NONE, ADENA, 1_000_000, null, null);
		return clan;
	}

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

	private static void set(Object target, Class<?> type, String name, Object value) throws Exception
	{
		final Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	@Test
	void aTreasuryCountsWhatMobiusAppliesAndRecordsItsClampApart() throws Exception
	{
		final Castle castle = CastleManager.getInstance().getCastleById(2);
		set(castle, Castle.class, "_ownerId", 1); // only an owned castle has a treasury
		set(castle, Castle.class, "_treasury", 0L);
		try
		{
			assertEquals(Map.of(ADENA, 1_000L), change(() -> assertTrue(castle.addToTreasuryNoTax(1_000))), "tax in");
			assertEquals(Map.of(ADENA, -400L), change(() -> assertTrue(castle.addToTreasuryNoTax(-400))), "a withdrawal out");
			assertEquals(Map.of(), change(() -> assertTrue(castle.tryDebitTreasury(100))), "the service's commitment moves to a custody");
			assertEquals(Map.of(), change(() -> assertTrue(castle.creditTreasuryInMemory(100))), "and its return moves back");
			set(castle, Castle.class, "_treasury", org.l2jmobius.gameserver.entity.itemcontainer.Inventory.MAX_ADENA - 10);
			final long clampedBefore = EconomyCounter.clamped().getOrDefault(ADENA, 0L);
			assertEquals(Map.of(ADENA, 10L), change(() -> assertTrue(castle.addToTreasuryNoTax(30))), "only what fits");
			assertEquals(clampedBefore + 20, EconomyCounter.clamped().getOrDefault(ADENA, 0L), "the rest was clamped away");
		}
		finally
		{
			set(castle, Castle.class, "_treasury", 0L);
			set(castle, Castle.class, "_ownerId", 0);
		}
	}

	@Test
	void theSavedGroundIsAHoldingSoADropAndAPickupNetToNothing()
	{
		final boolean saved = GeneralConfig.SAVE_DROPPED_ITEM;
		GeneralConfig.SAVE_DROPPED_ITEM = true;
		try
		{
			final Player player = player();
			final Item shots = player.getInventory().addItem(ItemProcessType.NONE, SOULSHOT, 100, player, null);
			final Item[] dropped = new Item[1];
			assertEquals(Map.of(), change(() ->
			{
				dropped[0] = player.getInventory().dropItem(ItemProcessType.NONE, shots.getObjectId(), 40, player, null);
				dropped[0].dropMe(player, player.getX(), player.getY(), player.getZ());
			}), "the inventory's 40 are the saved ground's");
			assertEquals(Map.of(), change(() ->
			{
				dropped[0].pickupMe(player);
				ItemsOnGroundManager.getInstance().removeObject(dropped[0]);
				player.getInventory().addItem(ItemProcessType.NONE, dropped[0], player, null);
			}), "and back");
			assertEquals(Map.of(), change(() -> ItemsOnGroundManager.getInstance().removeObject(dropped[0])), "an object the ground does not hold leaves nothing");
		}
		finally
		{
			GeneralConfig.SAVE_DROPPED_ITEM = saved;
		}
	}

	@Test
	void anEntryRacingARemovalAndADestructionSettlesExactly() throws Exception
	{
		final boolean saved = GeneralConfig.SAVE_DROPPED_ITEM;
		GeneralConfig.SAVE_DROPPED_ITEM = true;
		try
		{
			for (int i = 0; i < 3_000; i++)
			{
				final Item drop = new Item(org.l2jmobius.gameserver.managers.IdManager.getInstance().getNextId(), SOULSHOT);
				drop.setCount(10);
				final long before = EconomyCounter.snapshot().getOrDefault(SOULSHOT, 0L);
				final java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
				final Thread dropper = new Thread(() ->
				{
					await(start);
					ItemsOnGroundManager.getInstance().save(drop);
				});
				final Thread picker = new Thread(() ->
				{
					await(start);
					ItemsOnGroundManager.getInstance().removeObject(drop);
					drop.setCount(0); // merged into the picker's stack and destroyed
				});
				dropper.start();
				picker.start();
				dropper.join();
				picker.join();
				final long onGround = ground().contains(drop) ? drop.getCount() : 0;
				assertEquals(before + onGround, EconomyCounter.snapshot().getOrDefault(SOULSHOT, 0L), "iteration " + i);
				ItemsOnGroundManager.getInstance().removeObject(drop);
			}
		}
		finally
		{
			GeneralConfig.SAVE_DROPPED_ITEM = saved;
		}
	}

	private static void await(java.util.concurrent.CyclicBarrier barrier)
	{
		try
		{
			barrier.await();
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static java.util.Collection<Item> ground() throws Exception
	{
		final Field items = ItemsOnGroundManager.class.getDeclaredField("_items");
		items.setAccessible(true);
		return (java.util.Collection<Item>) items.get(ItemsOnGroundManager.getInstance());
	}

	private static ItemAuction auction(int id)
	{
		final ItemAuction auction = new ItemAuction(id, 0, System.currentTimeMillis(), System.currentTimeMillis() + 3_600_000, new AuctionItem(id, 60, 1_000, SOULSHOT, 1, new StatSet()));
		assertTrue(auction.setAuctionState(ItemAuctionState.CREATED, ItemAuctionState.STARTED));
		return auction;
	}

	@Test
	void anItemAuctionBidMovesAdenaIntoTheBidAndBackAndTheWinningBidIsSunk()
	{
		final ItemAuction auction = auction(900_000 + NAMES.incrementAndGet());
		final Player first = player();
		final Player second = player();
		first.getInventory().addItem(ItemProcessType.NONE, ADENA, 100_000, first, null);
		second.getInventory().addItem(ItemProcessType.NONE, ADENA, 100_000, second, null);
		assertEquals(Map.of(), change(() -> auction.registerBid(first, 5_000)), "placed: from the inventory into the bid");
		assertEquals(95_000, first.getAdena());
		assertEquals(Map.of(), change(() -> auction.registerBid(first, 7_000)), "raised by the difference");
		assertEquals(93_000, first.getAdena());
		assertEquals(Map.of(), change(() -> auction.registerBid(second, 9_000)), "a second bidder");
		assertEquals(Map.of(), change(() -> assertTrue(auction.cancelBid(first))), "cancelled: back to the inventory");
		assertEquals(100_000, first.getAdena());
		assertEquals(Map.of(ADENA, -9_000L), change(() -> assertTrue(auction.setAuctionState(ItemAuctionState.STARTED, ItemAuctionState.FINISHED))), "the winning bid is paid to the NPC");
	}

	@Test
	void aClanHallBidMovesAdenaFromTheClanWarehouseIntoTheBidAndBack()
	{
		final Clan seller = clan();
		final Clan a = clan();
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Onyx Hall");
		final Player leaderA = World.getPlayer(a.getLeaderId());
		assertEquals(Map.of(), change(() -> auction.setBid(leaderA, 20_000)), "placed");
		assertEquals(980_000, a.getWarehouse().getAdena());
		assertEquals(Map.of(ADENA, -2_000L), change(() -> auction.cancelBid(a.getId())), "cancelled: 90 % back, 10 % a sink");
		assertEquals(998_000, a.getWarehouse().getAdena());
	}

	@Test
	void aClanHallBidWhoseRowCannotBeDeletedIsNotRefunded() throws Exception
	{
		final Clan seller = clan();
		final Clan a = clan();
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Onyx Hall");
		auction.setBid(World.getPlayer(a.getLeaderId()), 20_000);
		try (java.sql.Connection connection = org.l2jmobius.commons.database.DatabaseFactory.getConnection();
			java.sql.Statement statement = connection.createStatement())
		{
			statement.execute("CREATE TRIGGER lc_test_refuse_bid BEFORE DELETE ON auction_bid FOR EACH ROW BEGIN IF OLD.bidderId = " + a.getId() + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'refused'; END IF; END");
			try
			{
				assertEquals(Map.of(), change(() -> auction.cancelBid(a.getId())), "nothing left the bid, nothing came back");
				assertEquals(980_000, a.getWarehouse().getAdena(), "upstream refunded a bid whose row stayed");
			}
			finally
			{
				statement.execute("DROP TRIGGER lc_test_refuse_bid");
			}
		}
	}

	@Test
	void aClanHallCreditPastTheCapIsRecordedAsClamped()
	{
		final Clan seller = clan();
		final Clan b = clan();
		final long max = org.l2jmobius.gameserver.entity.itemcontainer.Inventory.MAX_ADENA;
		seller.getWarehouse().addItem(ItemProcessType.NONE, ADENA, max - 1_000 - seller.getWarehouse().getAdena(), null, null);
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Onyx Hall");
		auction.setBid(World.getPlayer(b.getLeaderId()), 30_000);
		final long lease = org.l2jmobius.gameserver.data.sql.ClanHallTable.getInstance().getAuctionableHallById(HALL).getLease();
		final long clamped = EconomyCounter.clamped().getOrDefault(ADENA, 0L);
		auction.endAuction();
		assertEquals(max, seller.getWarehouse().getAdena(), "only 1 000 fitted");
		assertEquals(clamped + (27_000 - 1_000) + lease, EconomyCounter.clamped().getOrDefault(ADENA, 0L), "the rest of the share and the whole lease were clamped away");
	}

	@Test
	void anEndedClanHallAuctionPaysTheSellerItsShareAndTheLeaseAndRefundsTheLosers()
	{
		final Clan seller = clan();
		final Clan a = clan();
		final Clan b = clan();
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Onyx Hall");
		auction.setBid(World.getPlayer(a.getLeaderId()), 20_000);
		auction.setBid(World.getPlayer(b.getLeaderId()), 30_000);
		final long lease = org.l2jmobius.gameserver.data.sql.ClanHallTable.getInstance().getAuctionableHallById(HALL).getLease();
		// the seller gets 90 % of 30 000 and the lease (a source: its deposit was a sink when paid); A gets 90 % of 20 000
		assertEquals(Map.of(ADENA, lease - 3_000L - 2_000L), change(auction::endAuction));
		assertEquals(1_000_000 + 27_000 + lease, seller.getWarehouse().getAdena());
		assertEquals(1_000_000 - 20_000 + 18_000, a.getWarehouse().getAdena());
		assertEquals(1_000_000 - 30_000, b.getWarehouse().getAdena());
	}
}
