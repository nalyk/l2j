package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * {@code route} (docs/spec/asset-service.md §4.3, §4.4, §2.5 {@code LOOT_DROP}; pact.md §4.5): a kill's routed drops
 * enter from the {@code LOOT_DROP} source, each receiver's by one service mail, the auctioned ones into the charter's
 * custody, all in one operation or none; an auctioned drop must be custody-eligible by its template. Part of AT-ASSET-14.
 */
class RouteTest
{
	private static final int SHORT_SWORD = 1; // tradeable, not stackable
	private static final int DARINS_LETTER = 687; // a quest item
	private static final int SHADOW_SWORD = 8821; // a shadow item, duration 90
	private static final int TIMED_HAT = 12781; // a time-limited item
	private static AssetFixtures _f;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), Map.of("PACT", (connection, ref) -> true, "NOHOLD", (connection, ref) -> false), id -> MailManager.getInstance().adopt(id));
		EconomyCounter.activate(Map.of());
	}

	@AfterAll
	static void stop()
	{
		EconomyCounter.deactivate();
	}

	private static Player receiver()
	{
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 20);
		if (World.getPlayer(player.getObjectId()) == null)
		{
			World.addObject(player);
		}
		return player;
	}

	private static Outcome route(int custody, String killKey, RoutedDrop... drops) throws Exception
	{
		try (AssetTx tx = _f.service.begin(_f.op("ROUTE"), "PACT", "ROUTE", custody))
		{
			tx.settlementKey("PACT", killKey);
			tx.route(custody, (7L << 40) | Long.parseLong(killKey.substring(killKey.lastIndexOf('/') + 1)), 20_001, List.of(drops), "Loot Charter", "Your share of the kill.");
			return tx.commit();
		}
	}

	private static long counted(int itemId)
	{
		return EconomyCounter.snapshot().getOrDefault(itemId, 0L);
	}

	@Test
	void routedDropsReachTheirReceiversByServiceMailAndTheAuctionedOnesTheCustody() throws Exception
	{
		final Player a = receiver();
		final Player b = receiver();
		final int custody = _f.open("PACT");
		final long adena = counted(ADENA);
		final long swords = counted(SHORT_SWORD);
		assertEquals(Outcome.COMMITTED, route(custody, "kill/7/1", new RoutedDrop(ADENA, 300, a.getObjectId()), new RoutedDrop(SHORT_SWORD, 2, b.getObjectId()), new RoutedDrop(SHORT_SWORD, 1, 0)));
		assertEquals(300, _f.number("SELECT SUM(i.count) FROM items i JOIN messages m ON m.messageId = i.loc_data WHERE i.loc = 'MAIL' AND m.receiverId = " + a.getObjectId() + " AND i.item_id = " + ADENA));
		assertEquals(2, _f.number("SELECT COUNT(*) FROM items i JOIN messages m ON m.messageId = i.loc_data WHERE i.loc = 'MAIL' AND m.receiverId = " + b.getObjectId() + " AND i.item_id = " + SHORT_SWORD + " AND i.count = 1"), "two swords are two rows of one");
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_mail_delivery d JOIN messages m ON m.messageId = d.message_id WHERE m.receiverId = " + b.getObjectId()), "one mail per receiver");
		assertEquals(1, _f.held(custody, SHORT_SWORD), "the auctioned sword is in the charter's custody");
		assertEquals(1, _f.posted(custody, SHORT_SWORD));
		assertEquals(4, _f.number("SELECT COUNT(DISTINCT leg) FROM lc_ledger_posting WHERE code = 'LOOT_DROP' AND account_kind = 'SOURCE' AND account_id = " + _f.number("SELECT owner_ref FROM lc_custody WHERE custody_id = " + custody) + " AND op_id IN (SELECT op_id FROM lc_ledger_posting WHERE account_kind = 'CUSTODY' AND account_id = " + custody + ")"), "a LOOT_DROP source leg per created row, referenced by the charter");
		assertEquals(adena + 300, counted(ADENA), "what the kill made exists");
		assertEquals(swords + 3, counted(SHORT_SWORD));
		assertEquals(1, _f.facts("LootRouted", custody));
		final String fact = _f.lastPayload("LootRouted", custody);
		final long charter = _f.number("SELECT owner_ref FROM lc_custody WHERE custody_id = " + custody);
		assertTrue(fact.contains("\"charter\":" + charter), fact);
		assertTrue(fact.contains("\"kill\":" + ((7L << 40) | 1)) && fact.contains("\"npc\":20001"), "the kill: its key and its NPC, " + fact);
		final long mailA = _f.number("SELECT messageId FROM messages WHERE receiverId = " + a.getObjectId());
		assertTrue(fact.contains("\"receiver\":" + a.getObjectId()) && fact.contains("\"mail\":" + mailA), "each receiver and its mail, " + fact);
	}

	@Test
	void twoAuctionedSwordsAreTwoObjectsInTheCustody() throws Exception
	{
		final int custody = _f.open("PACT");
		assertEquals(Outcome.COMMITTED, route(custody, "kill/7/6", new RoutedDrop(SHORT_SWORD, 2, 0)));
		assertEquals(2, _f.number("SELECT COUNT(*) FROM items WHERE owner_id = -1 AND loc = 'ESCROW' AND loc_data = " + custody + " AND item_id = " + SHORT_SWORD + " AND count = 1"), "a non-stackable amount was one row counting two");
		assertEquals(Outcome.COMMITTED, route(custody, "kill/7/7", new RoutedDrop(SHORT_SWORD, 1, 0)));
		assertEquals(3, _f.number("SELECT COUNT(*) FROM items WHERE owner_id = -1 AND loc = 'ESCROW' AND loc_data = " + custody + " AND item_id = " + SHORT_SWORD + " AND count = 1"), "never merged into another sword's row");
	}

	@Test
	void anIneligibleAuctionedDropRefusesTheWholeRoute() throws Exception
	{
		final Player a = receiver();
		final int custody = _f.open("PACT");
		final long adena = counted(ADENA);
		final AssetAbort abort = assertThrows(AssetAbort.class, () -> route(custody, "kill/7/2", new RoutedDrop(ADENA, 300, a.getObjectId()), new RoutedDrop(DARINS_LETTER, 1, 0)));
		assertEquals(AssetAbort.Reason.INELIGIBLE, abort.reason());
		assertEquals(0, _f.number("SELECT COUNT(*) FROM messages WHERE receiverId = " + a.getObjectId()), "no mail");
		assertEquals(0, _f.held(custody, DARINS_LETTER));
		assertEquals(adena, counted(ADENA), "nothing created");
	}

	@Test
	void aMailedQuestItemIsNoCustodysAndMayBeRouted() throws Exception
	{
		final Player a = receiver();
		final int custody = _f.open("PACT");
		assertEquals(Outcome.COMMITTED, route(custody, "kill/7/3", new RoutedDrop(DARINS_LETTER, 1, a.getObjectId())), "only a custody's items must be eligible");
	}

	@Test
	void aSecondRouteOfOneKillAbortsAsSettled() throws Exception
	{
		final Player a = receiver();
		final int custody = _f.open("PACT");
		assertEquals(Outcome.COMMITTED, route(custody, "kill/7/4", new RoutedDrop(ADENA, 10, a.getObjectId())));
		final AssetAbort abort = assertThrows(AssetAbort.class, () -> route(custody, "kill/7/4", new RoutedDrop(ADENA, 10, a.getObjectId())));
		assertEquals(AssetAbort.Reason.ALREADY_SETTLED, abort.reason());
		assertEquals(10, _f.number("SELECT SUM(i.count) FROM items i JOIN messages m ON m.messageId = i.loc_data WHERE i.loc = 'MAIL' AND m.receiverId = " + a.getObjectId()), "routed once");
	}

	@Test
	void anOwnerThatMayNotHoldRefusesTheRoute() throws Exception
	{
		final int custody = _f.open("NOHOLD");
		try (AssetTx tx = _f.service.begin(_f.op("ROUTE"), "NOHOLD", "ROUTE", custody))
		{
			tx.route(custody, (7L << 40) | 99, 20_001, List.of(new RoutedDrop(SHORT_SWORD, 1, 0)), "Loot Charter", "Your share of the kill.");
			assertEquals(AssetAbort.Reason.NOT_ALLOWED_TO_HOLD, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		assertEquals(0, _f.held(custody, SHORT_SWORD), "INV05: its owner holds nothing it may not");
	}

	@Test
	void aMailedShadowOrTimedItemKeepsItsLimits() throws Exception
	{
		final Player a = receiver();
		final int custody = _f.open("PACT");
		final long before = System.currentTimeMillis();
		assertEquals(Outcome.COMMITTED, route(custody, "kill/7/8", new RoutedDrop(SHADOW_SWORD, 1, a.getObjectId()), new RoutedDrop(TIMED_HAT, 1, a.getObjectId())));
		assertEquals(90, _f.number("SELECT i.mana_left FROM items i JOIN messages m ON m.messageId = i.loc_data WHERE i.loc = 'MAIL' AND m.receiverId = " + a.getObjectId() + " AND i.item_id = " + SHADOW_SWORD), "a shadow item's mana, as a new item gets it");
		final long minutes = org.l2jmobius.gameserver.data.xml.ItemData.getInstance().getTemplate(TIMED_HAT).getTime();
		final long time = _f.number("SELECT i.time FROM items i JOIN messages m ON m.messageId = i.loc_data WHERE i.loc = 'MAIL' AND m.receiverId = " + a.getObjectId() + " AND i.item_id = " + TIMED_HAT);
		assertTrue((time >= (before + (minutes * 60_000))) && (time <= (System.currentTimeMillis() + (minutes * 60_000))), "a time-limited item's end, as a new item gets it: " + time);
		assertEquals(-1, _f.number("SELECT i.time FROM items i JOIN messages m ON m.messageId = i.loc_data WHERE i.loc = 'MAIL' AND m.receiverId = " + a.getObjectId() + " AND i.item_id = " + SHADOW_SWORD), "no time limit: -1, as Mobius writes it");
	}

	@Test
	void aGoneReceiverRefusesTheWholeRoute() throws Exception
	{
		final int custody = _f.open("PACT");
		final AssetAbort abort = assertThrows(AssetAbort.class, () -> route(custody, "kill/7/5", new RoutedDrop(ADENA, 10, 999_999_999), new RoutedDrop(SHORT_SWORD, 1, 0)));
		assertEquals(AssetAbort.Reason.RECEIVER_GONE, abort.reason());
		assertEquals(0, _f.held(custody, SHORT_SWORD), "all or nothing");
	}
}
