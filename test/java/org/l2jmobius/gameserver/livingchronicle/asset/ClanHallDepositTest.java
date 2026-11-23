package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.l2jmobius.gameserver.entity.residences.ClanHallAuction.Confirmation.ALREADY_FOR_SALE;
import static org.l2jmobius.gameserver.entity.residences.ClanHallAuction.Confirmation.CONFIRMED;
import static org.l2jmobius.gameserver.entity.residences.ClanHallAuction.Confirmation.NOT_ENOUGH_ADENA;
import static org.l2jmobius.gameserver.entity.residences.ClanHallAuction.Confirmation.NOT_WRITTEN;
import static org.l2jmobius.gameserver.entity.residences.ClanHallAuction.Confirmation.UNRESOLVED;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

import java.lang.reflect.Field;
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
		assertEquals(CONFIRMED, auction.confirmAuction(), "the sale is registered");
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
		assertEquals(NOT_ENOUGH_ADENA, auction.confirmAuction(), "refused");
		assertFalse(registered(), "no auction");
		assertEquals(lease() - 1, seller.getWarehouse().getAdena(), "nothing taken");
		assertEquals(before, counted());
	}

	@Test
	void aClanHoldingExactlyTheDepositPaysIt()
	{
		final Clan seller = clan(lease());
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		assertEquals(CONFIRMED, auction.confirmAuction(), "the sale page asks for the deposit, no more");
		assertEquals(0, seller.getWarehouse().getAdena());
	}

	private static void waitPast(long millis) throws InterruptedException
	{
		Thread.sleep(millis);
	}

	@Test
	void aCancelledSaleIsNotEndedByItsTimer() throws Exception
	{
		final Clan seller = clan(1_000_000);
		final Clan bidder = clan(1_000_000);
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 500, 10_000, "Deposit Hall");
		assertEquals(CONFIRMED, auction.confirmAuction());
		auction.setBid(World.getPlayer(bidder.getLeaderId()), 30_000);
		auction.cancelAuction();
		final long afterCancel = seller.getWarehouse().getAdena();
		waitPast(1_500);
		assertEquals(afterCancel, seller.getWarehouse().getAdena(), "the timer paid a cancelled seller 90 % and the deposit");
		assertEquals(1_000_000 - 3_000, bidder.getWarehouse().getAdena(), "the bidder was refunded once, 90 %");
	}

	@Test
	void aCancelledSalesTimerLeavesTheNextSaleAlone() throws Exception
	{
		final Clan seller = clan(2_000_000);
		final ClanHallAuction cancelled = new ClanHallAuction(HALL, seller, 500, 10_000, "Deposit Hall");
		assertEquals(CONFIRMED, cancelled.confirmAuction());
		cancelled.cancelAuction();
		final ClanHallAuction next = new ClanHallAuction(HALL, seller, 3_600_000, 20_000, "Deposit Hall");
		assertEquals(CONFIRMED, next.confirmAuction());
		waitPast(1_500);
		assertTrue(registered(), "the cancelled sale's timer ended the next one");
		assertEquals(next.getEndDate(), rows("endDate"), "the next sale's row is there");
	}

	@Test
	void aSaleWhoseRowCannotBeResolvedIsNotListedAndKeepsItsDeposit() throws Exception
	{
		final Clan seller = clan(1_000_000);
		try (Connection con = DatabaseFactory.getConnection();
			java.sql.Statement statement = con.createStatement())
		{
			statement.execute("CREATE TRIGGER lc_test_refuse_sale BEFORE INSERT ON auction FOR EACH ROW BEGIN IF NEW.id = " + HALL + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'refused'; END IF; END");
			try
			{
				final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall")
				{
					private int _reads;

					@Override
					protected Boolean rowState()
					{
						return (++_reads == 1) ? super.rowState() : null; // the database fails after the insert
					}
				};
				assertEquals(UNRESOLVED, auction.confirmAuction(), "an unreadable outcome is no row");
				assertFalse(registered(), "not listed: a restart would lose it");
				assertEquals(1_000_000 - lease(), seller.getWarehouse().getAdena(), "kept: had the row been written, a refund would pay the deposit twice");
			}
			finally
			{
				statement.execute("DROP TRIGGER lc_test_refuse_sale");
			}
		}
	}

	@Test
	void aBidAfterTheSaleClosedTakesNothing() throws Exception
	{
		final Clan seller = clan(1_000_000);
		final Clan bidder = clan(1_000_000);
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		assertEquals(CONFIRMED, auction.confirmAuction());
		auction.endAuction(); // closed without bids, while an auctioneer still holds the reference
		auction.setBid(World.getPlayer(bidder.getLeaderId()), 30_000);
		assertEquals(1_000_000, bidder.getWarehouse().getAdena(), "upstream took the bid into a deleted auction");
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM auction_bid WHERE auctionId = ?"))
		{
			ps.setInt(1, HALL);
			try (java.sql.ResultSet rs = ps.executeQuery())
			{
				rs.next();
				assertEquals(0, rs.getInt(1), "no bid row");
			}
		}
	}

	private static void refusingBidDeletion(java.util.concurrent.Callable<Void> body) throws Exception
	{
		try (Connection con = DatabaseFactory.getConnection();
			java.sql.Statement statement = con.createStatement())
		{
			statement.execute("CREATE TRIGGER lc_test_refuse_bids BEFORE DELETE ON auction_bid FOR EACH ROW BEGIN IF OLD.auctionId = " + HALL + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'refused'; END IF; END");
			try
			{
				body.call();
			}
			finally
			{
				statement.execute("DROP TRIGGER lc_test_refuse_bids");
			}
		}
	}

	@Test
	void aCancelWhoseBidsCannotBeDeletedCanBeRetried() throws Exception
	{
		final Clan seller = clan(1_000_000);
		final Clan bidder = clan(1_000_000);
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		assertEquals(CONFIRMED, auction.confirmAuction());
		auction.setBid(World.getPlayer(bidder.getLeaderId()), 30_000);
		refusingBidDeletion(() ->
		{
			auction.cancelAuction();
			return null;
		});
		assertTrue(registered(), "still for sale: its bids are still there");
		assertEquals(1_000_000 - 30_000, bidder.getWarehouse().getAdena(), "nothing refunded yet (CP-0026)");
		auction.cancelAuction();
		assertFalse(registered());
		assertEquals(1_000_000 - 3_000, bidder.getWarehouse().getAdena(), "upstream had closed the sale and stranded the bid");
	}

	/** A client that records what its player is sent (allocated without a connection; nothing else of it is used). */
	static class RecordingClient extends org.l2jmobius.gameserver.network.GameClient
	{
		private java.util.List<String> _texts;

		RecordingClient()
		{
			super(null); // never run: the client is allocated without a constructor
		}

		@Override
		public String getIp()
		{
			return null;
		}

		@Override
		public void sendPacket(org.l2jmobius.gameserver.network.serverpackets.ServerPacket packet)
		{
			if (packet instanceof org.l2jmobius.gameserver.network.serverpackets.SystemMessage message)
			{
				for (org.l2jmobius.gameserver.network.serverpackets.SystemMessage.SMParam param : message.getParams())
				{
					texts().add(String.valueOf(param.getValue()));
				}
			}
		}

		synchronized java.util.List<String> texts()
		{
			if (_texts == null)
			{
				_texts = new java.util.concurrent.CopyOnWriteArrayList<>();
			}
			return _texts;
		}
	}

	@Test
	void theAuctioneerSaysWhenACancelFailed() throws Exception
	{
		final Clan seller = clan(1_000_000);
		final Clan bidder = clan(1_000_000);
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		assertEquals(CONFIRMED, auction.confirmAuction());
		auction.setBid(World.getPlayer(bidder.getLeaderId()), 30_000);
		final Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		final Object unsafe = unsafeField.get(null);
		final RecordingClient client = (RecordingClient) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, RecordingClient.class);
		final Player leader = World.getPlayer(seller.getLeaderId());
		leader.setClient(client);
		final org.l2jmobius.gameserver.entity.actor.instance.Auctioneer npc = new org.l2jmobius.gameserver.entity.actor.instance.Auctioneer(org.l2jmobius.gameserver.data.xml.NpcData.getInstance().getTemplate(30767));
		npc.setXYZ(81424, 149728, -3472); // Giran's auctioneer
		seller.setHideoutId(HALL);
		try
		{
			refusingBidDeletion(() ->
			{
				npc.onBypassFeedback(leader, "doCancelAuction");
				return null;
			});
			assertTrue(registered(), "the sale is still open");
			assertFalse(client.texts().contains("Your auction has been canceled"), "upstream told the seller it was cancelled, and the timer could still sell the hall");
			assertTrue(client.texts().contains("Your auction could not be canceled; try again."), client.texts().toString());
			npc.onBypassFeedback(leader, "doCancelAuction");
			assertFalse(registered());
			assertTrue(client.texts().contains("Your auction has been canceled"));
		}
		finally
		{
			seller.setHideoutId(0);
			leader.setClient(null);
		}
	}

	@Test
	void anEndWhoseBidsCannotBeDeletedIsRetried() throws Exception
	{
		final Clan seller = clan(1_000_000);
		final Clan bidder = clan(1_000_000);
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		assertEquals(CONFIRMED, auction.confirmAuction());
		auction.setBid(World.getPlayer(bidder.getLeaderId()), 30_000);
		refusingBidDeletion(() ->
		{
			auction.endAuction();
			return null;
		});
		assertTrue(registered(), "not ended: its bids are still there");
		assertEquals(1_000_000 - lease(), seller.getWarehouse().getAdena(), "the seller is paid only once the bids are gone");
		auction.endAuction();
		assertFalse(registered());
		assertEquals(1_000_000 + 27_000, seller.getWarehouse().getAdena(), "90 % and the deposit back, once");
	}

	@Test
	void aSaleWhoseRowCannotBeWrittenIsRefusedAndRefunded() throws Exception
	{
		final Clan seller = clan(1_000_000);
		try (Connection con = DatabaseFactory.getConnection();
			java.sql.Statement statement = con.createStatement())
		{
			statement.execute("CREATE TRIGGER lc_test_refuse_sale BEFORE INSERT ON auction FOR EACH ROW BEGIN IF NEW.id = " + HALL + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'refused'; END IF; END");
			try
			{
				final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
				assertEquals(NOT_WRITTEN, auction.confirmAuction(), "upstream listed a sale it could not store");
				assertFalse(registered());
				assertEquals(1_000_000, seller.getWarehouse().getAdena(), "the deposit came back");
			}
			finally
			{
				statement.execute("DROP TRIGGER lc_test_refuse_sale");
			}
		}
	}

	@Test
	void aHallWhoseRowStillExistsIsNotPutUpAgain() throws Exception
	{
		final Clan seller = clan(1_000_000);
		final ClanHallAuction stale = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		assertEquals(CONFIRMED, stale.confirmAuction());
		ClanHallAuctionManager.getInstance().getAuctions().remove(stale); // listed no more, its row not yet deleted
		final ClanHallAuction next = new ClanHallAuction(HALL, seller, 7_200_000, 20_000, "Deposit Hall");
		assertEquals(ALREADY_FOR_SALE, next.confirmAuction(), "its INSERT would fail on the row and the sale live in memory only");
		assertEquals(1_000_000 - lease(), seller.getWarehouse().getAdena(), "one deposit");
	}

	@Test
	void readingTheSalesWhileOneCloses() throws Exception
	{
		// a sale now closes on a timer thread while game threads read the list (the Auctioneer's pages): reading must
		// never fail (upstream's ArrayList threw ConcurrentModificationException)
		final Clan seller = clan(1_000_000);
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		assertEquals(CONFIRMED, auction.confirmAuction());
		final java.util.Iterator<ClanHallAuction> reading = ClanHallAuctionManager.getInstance().getAuctions().iterator();
		reading.next();
		auction.cancelAuction(); // closes meanwhile
		while (reading.hasNext())
		{
			reading.next();
		}
	}

	@Test
	void aConfirmedSaleEndsOnItsDate() throws Exception
	{
		final Clan seller = clan(1_000_000);
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 300, 10_000, "Deposit Hall");
		assertEquals(CONFIRMED, auction.confirmAuction());
		final long deadline = System.currentTimeMillis() + 10_000;
		while (registered() && (System.currentTimeMillis() < deadline))
		{
			Thread.sleep(100);
		}
		assertFalse(registered(), "upstream scheduled a sale's end only when it was loaded at startup");
		assertEquals(-1, rows("id"), "ended without bids: deleted");
	}

	@Test
	void theDepositComesBackWhenTheAuctionEndsWithAWinner()
	{
		final Clan seller = clan(1_000_000);
		final Clan bidder = clan(1_000_000);
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		final long before = counted();
		assertEquals(CONFIRMED, auction.confirmAuction());
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
		assertEquals(CONFIRMED, first.confirmAuction());
		assertEquals(ALREADY_FOR_SALE, second.confirmAuction(), "the hall is already for sale");
		assertEquals(1_000_000 - lease(), seller.getWarehouse().getAdena(), "one deposit");
		assertEquals(1, ClanHallAuctionManager.getInstance().getAuctions().stream().filter(a -> a.getId() == HALL).count(), "one sale");
	}

	@Test
	void aSaleWithoutBidsKeepsItsDepositAndFreesTheHallForItsNextSale() throws Exception
	{
		final Clan seller = clan(2_000_000);
		final ClanHallAuction unsold = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		assertEquals(CONFIRMED, unsold.confirmAuction());
		unsold.endAuction();
		assertEquals(2_000_000 - lease(), seller.getWarehouse().getAdena(), "retail: no bids, no deposit back");
		assertFalse(registered());
		assertEquals(-1, rows("id"), "upstream left the row, so the hall's next sale could not be written");
		final ClanHallAuction next = new ClanHallAuction(HALL, seller, 7_200_000, 20_000, "Deposit Hall");
		assertEquals(CONFIRMED, next.confirmAuction());
		assertEquals(next.getEndDate(), rows("endDate"), "the next sale is the one stored");
	}

	@Test
	void aCancelledSaleKeepsItsDeposit()
	{
		final Clan seller = clan(1_000_000);
		final ClanHallAuction auction = new ClanHallAuction(HALL, seller, 3_600_000, 10_000, "Deposit Hall");
		assertEquals(CONFIRMED, auction.confirmAuction());
		auction.cancelAuction();
		assertEquals(1_000_000 - lease(), seller.getWarehouse().getAdena(), "retail: a cancelled auction's deposit is not returned");
	}
}
