package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.residences.ClanHallAuction;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * A clan-hall re-bid after being outbid (docs/spec/asset-service.md §11.26, CP-0023): upstream charged the whole new bid
 * and overwrote the clan's standing bid, so the first bid was lost; a clan pays the difference over its own standing bid.
 */
class ClanHallRebidTest
{
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

	private static Clan clan()
	{
		final Player leader = TestPlayers.create(PlayerClass.FIGHTER, 40);
		if (World.getPlayer(leader.getObjectId()) == null)
		{
			World.addObject(leader);
		}
		final Clan clan = ClanTable.getInstance().createClan(leader, "Rebid" + NAMES.incrementAndGet());
		assertTrue(clan != null, "the test clan is created");
		clan.getWarehouse().addItem(ItemProcessType.NONE, ADENA, 1_000_000, null, null);
		return clan;
	}

	@Test
	void aClanOutbidPaysOnlyTheDifferenceOverItsStandingBid()
	{
		final Clan seller = clan();
		final Clan a = clan();
		final Clan b = clan();
		final ClanHallAuction auction = new ClanHallAuction(24, seller, 3_600_000, 10_000, "Topaz Hall");
		final Player leaderA = World.getPlayer(a.getLeaderId());
		auction.setBid(leaderA, 20_000);
		auction.setBid(World.getPlayer(b.getLeaderId()), 30_000);
		final long counted = EconomyCounter.snapshot().getOrDefault(ADENA, 0L);
		auction.setBid(leaderA, 40_000);
		assertEquals(1_000_000 - 40_000, a.getWarehouse().getAdena(), "A's bid of 40 000 cost A 40 000, not 60 000");
		assertEquals(40_000, auction.getHighestBidderMaxBid());
		assertEquals(counted, EconomyCounter.snapshot().getOrDefault(ADENA, 0L), "nothing left existence");
	}
}
