package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.data.sql.ClanHallTable;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.residences.ClanHallAuction;
import org.l2jmobius.gameserver.managers.ClanHallAuctionManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * A clan hall put up for sale pays its deposit (docs/spec/asset-service.md §11.29, CP-0029): retail's own sale page
 * (AgitSale1.htm) says the deposit, the hall's lease, is taken from the clan warehouse when the hall is put up and is
 * returned only when the auction completes normally; upstream never took it and paid it back at the end, a source of
 * Adena from nothing on every sale.
 */
class ClanHallDepositTest
{
	private static final int HALL = 25;
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

	@BeforeEach
	@AfterEach
	void noAuctionOfTheHall() throws Exception
	{
		ClanHallAuctionManager.getInstance().getAuctions().removeIf(a -> a.getId() == HALL);
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("DELETE FROM auction WHERE id = ?"))
		{
			ps.setInt(1, HALL);
			ps.executeUpdate();
		}
	}

	private static Clan clan(long adena)
	{
		final Player leader = TestPlayers.create(PlayerClass.FIGHTER, 40);
		if (World.getPlayer(leader.getObjectId()) == null)
		{
			World.addObject(leader);
		}
		final Clan clan = ClanTable.getInstance().createClan(leader, "Deposit" + NAMES.incrementAndGet());
		assertTrue(clan != null, "the test clan is created");
		clan.getWarehouse().addItem(ItemProcessType.NONE, ADENA, adena, null, null);
		return clan;
	}

	private static long lease()
	{
		return ClanHallTable.getInstance().getAuctionableHallById(HALL).getLease();
	}

	private static long counted()
	{
		return EconomyCounter.snapshot().getOrDefault(ADENA, 0L);
	}

	private static boolean registered()
	{
		return ClanHallAuctionManager.getInstance().getAuctions().stream().anyMatch(a -> a.getId() == HALL);
	}

	@Test
	void puttingAHallUpForSaleTakesItsDepositFromTheClanWarehouse()
	{
		final Clan seller = clan(1_000_000);
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		final long before = counted();
		assertTrue(auction.confirmAuction(), "the sale is registered");
		assertTrue(registered());
		assertEquals(1_000_000 - lease(), seller.getWarehouse().getAdena(), "the deposit is the hall's lease");
		assertEquals(before - lease(), counted(), "the deposit left existence, as retail's NPC takes it");
	}

	@Test
	void aClanThatCannotPayTheDepositCannotPutItsHallUpForSale()
	{
		final Clan seller = clan(lease() - 1);
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		final long before = counted();
		assertFalse(auction.confirmAuction(), "refused");
		assertFalse(registered(), "no auction");
		assertEquals(lease() - 1, seller.getWarehouse().getAdena(), "nothing taken");
		assertEquals(before, counted());
	}

	@Test
	void theDepositComesBackWhenTheAuctionEndsWithAWinner()
	{
		final Clan seller = clan(1_000_000);
		final Clan bidder = clan(1_000_000);
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		final long before = counted();
		assertTrue(auction.confirmAuction());
		auction.setBid(World.getPlayer(bidder.getLeaderId()), 30_000);
		auction.endAuction();
		assertEquals(1_000_000 + 27_000, seller.getWarehouse().getAdena(), "90 % of the winning bid, and the deposit back");
		assertEquals(before - 3_000, counted(), "only the 10 % fee left existence: no Adena came from nothing");
	}

	private static long rows(String column) throws Exception
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("SELECT " + column + " FROM auction WHERE id = ?"))
		{
			ps.setInt(1, HALL);
			try (java.sql.ResultSet rs = ps.executeQuery())
			{
				return rs.next() ? rs.getLong(1) : -1;
			}
		}
	}

	@Test
	void aHallIsPutUpForSaleOnceWhoeverConfirmsIt()
	{
		// two members at two auctioneers each hold a pending sale of the same hall (Auctioneer._pendingAuctions is per NPC)
		final Clan seller = clan(1_000_000);
		final ClanHallAuction first = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		final ClanHallAuction second = new ClanHallAuction(HALL, seller, 7_200_000, 20_000, "Deposit Hall");
		assertTrue(first.confirmAuction());
		assertFalse(second.confirmAuction(), "the hall is already for sale");
		assertEquals(1_000_000 - lease(), seller.getWarehouse().getAdena(), "one deposit");
		assertEquals(1, ClanHallAuctionManager.getInstance().getAuctions().stream().filter(a -> a.getId() == HALL).count(), "one sale");
	}

	@Test
	void aSaleWithoutBidsKeepsItsDepositAndFreesTheHallForItsNextSale() throws Exception
	{
		final Clan seller = clan(1_000_000);
		final ClanHallAuction unsold = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		assertTrue(unsold.confirmAuction());
		unsold.endAuction();
		assertEquals(1_000_000 - lease(), seller.getWarehouse().getAdena(), "retail: no bids, no deposit back");
		assertFalse(registered());
		assertEquals(-1, rows("id"), "upstream left the row, so the hall's next sale could not be written");
		final ClanHallAuction next = new ClanHallAuction(HALL, seller, 7_200_000, 20_000, "Deposit Hall");
		assertTrue(next.confirmAuction());
		assertEquals(next.getEndDate(), rows("endDate"), "the next sale is the one stored");
	}

	@Test
	void aCancelledSaleKeepsItsDeposit()
	{
		final Clan seller = clan(1_000_000);
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		assertTrue(auction.confirmAuction());
		auction.cancelAuction();
		assertEquals(1_000_000 - lease(), seller.getWarehouse().getAdena(), "retail: a cancelled auction's deposit is not returned");
	}
}
