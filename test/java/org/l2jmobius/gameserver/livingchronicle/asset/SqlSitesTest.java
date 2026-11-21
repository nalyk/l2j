package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.actor.holders.player.CursedWeapon;
import org.l2jmobius.gameserver.entity.clan.ClanMember;
import org.l2jmobius.gameserver.entity.item.auction.ItemAuctionInstance;
import org.l2jmobius.gameserver.handler.AdminCommandHandler;
import org.l2jmobius.gameserver.managers.CastleManager;
import org.l2jmobius.gameserver.managers.CursedWeaponsManager;
import org.l2jmobius.gameserver.managers.DatabaseIdManager;
import org.l2jmobius.gameserver.managers.ItemAuctionManager;
import org.l2jmobius.gameserver.mechanics.olympiad.Hero;
import org.l2jmobius.gameserver.mechanics.script.LongTimeEvent;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * Every SQL site that writes census rows directly (docs/spec/asset-service.md §2.6, §2.7; CP-0025): across each statement
 * the existence counters move exactly as the census does, so a sink the statement makes is counted and nothing else is.
 */
class SqlSitesTest
{
	private static final int SOULSHOT = 1835;
	private static final int HERO_WEAPON = 6842;
	private static final int EVENT_ITEM = 6393;
	private static final int ZARICHE = 8190;
	private static final int AKAMANAH = 8689;
	private static final AtomicInteger IDS = new AtomicInteger(1_950_000_000);

	@BeforeAll
	static void boot() throws Exception
	{
		TestServer.boot();
		EconomyCounter.activate(Map.of());
	}

	@AfterAll
	static void stop()
	{
		EconomyCounter.deactivate();
	}

	@FunctionalInterface
	private interface Site
	{
		void run() throws Exception;
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

	/** Runs {@code site} and checks the counters moved exactly as the census did. @return the census's change */
	private static Map<Integer, Long> countedExactly(Site site) throws Exception
	{
		final Map<Integer, Long> census = census();
		final Map<Integer, Long> counters = EconomyCounter.snapshot();
		site.run();
		final Map<Integer, Long> changed = minus(census(), census);
		assertEquals(changed, minus(EconomyCounter.snapshot(), counters), "the counters move as the census does");
		return changed;
	}

	private static Object unsafeInstance(Class<?> type) throws Exception
	{
		final Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		field.setAccessible(true);
		final Object unsafe = field.get(null);
		return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
	}

	private static void set(Object target, Class<?> type, String name, Object value) throws Exception
	{
		final Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static Object call(Object target, Class<?> type, String name, Class<?>[] types, Object... args) throws Exception
	{
		final Method method = type.getDeclaredMethod(name, types);
		method.setAccessible(true);
		return method.invoke(target, args);
	}

	@Test
	void aDeletedCharactersItems() throws Exception
	{
		final int owner = IDS.incrementAndGet();
		item(owner, SOULSHOT, 10, "INVENTORY");
		item(owner, SOULSHOT, 5, "WAREHOUSE");
		assertEquals(Map.of(SOULSHOT, -15L), countedExactly(() -> GameClient.deleteCharByObjId(owner)));
	}

	@Test
	void theHeroItemsOfANewPeriod() throws Exception
	{
		item(IDS.incrementAndGet(), HERO_WEAPON, 1, "INVENTORY");
		assertEquals(-1L, countedExactly(() -> call(Hero.getInstance(), Hero.class, "deleteItemsInDb", new Class<?>[0])).get(HERO_WEAPON));
	}

	@Test
	void anEndedEventsItems() throws Exception
	{
		item(IDS.incrementAndGet(), EVENT_ITEM, 3, "INVENTORY");
		item(IDS.incrementAndGet(), EVENT_ITEM, 4, "WAREHOUSE");
		final LongTimeEvent event = (LongTimeEvent) unsafeInstance(LongTimeEvent.class);
		set(event, LongTimeEvent.class, "_destroyItemsOnEnd", new java.util.ArrayList<>(List.of(EVENT_ITEM)));
		assertEquals(Map.of(EVENT_ITEM, -7L), countedExactly(() -> call(event, LongTimeEvent.class, "destroyItemsOnEnd", new Class<?>[0])));
	}

	@Test
	void anOfflineOwnersExpiredCursedWeapon() throws Exception
	{
		final int owner = IDS.incrementAndGet();
		item(owner, AKAMANAH, 1, "PAPERDOLL");
		final CursedWeapon weapon = new CursedWeapon(AKAMANAH, 3630, "Akamanah");
		set(weapon, CursedWeapon.class, "_isActivated", true);
		set(weapon, CursedWeapon.class, "_playerId", owner);
		assertEquals(Map.of(AKAMANAH, -1L), countedExactly(weapon::endOfLife));
	}

	@Test
	void aCursedWeaponFoundOnACharacterAtStartup() throws Exception
	{
		item(IDS.incrementAndGet(), ZARICHE, 1, "INVENTORY");
		assertEquals(Map.of(ZARICHE, -1L), countedExactly(() -> call(CursedWeaponsManager.getInstance(), CursedWeaponsManager.class, "controlPlayers", new Class<?>[0])));
	}

	@Test
	void anOfflineMembersCirclet() throws Exception
	{
		final int owner = IDS.incrementAndGet();
		final int circlet = CastleManager.getInstance().getCircletByCastleId(1);
		item(owner, circlet, 1, "INVENTORY");
		final ClanMember member = (ClanMember) unsafeInstance(ClanMember.class);
		set(member, ClanMember.class, "_objectId", owner);
		set(member, ClanMember.class, "_name", "offline");
		assertEquals(Map.of(circlet, -1L), countedExactly(() -> CastleManager.getInstance().removeCirclet(member, 1)));
	}

	@Test
	void anAdminRepairMovesUncountedRowsIntoTheInventory() throws Exception
	{
		final Player character = TestPlayers.create(PlayerClass.FIGHTER, 20); // stored, never in the world
		item(character.getObjectId(), SOULSHOT, 7, "REFUND");
		item(character.getObjectId(), SOULSHOT, 5, "WAREHOUSE");
		final Player admin = TestPlayers.create(PlayerClass.FIGHTER, 20);
		assertEquals(Map.of(SOULSHOT, 7L), countedExactly(() -> assertTrue(AdminCommandHandler.getInstance().getHandler("admin_restore").onCommand("admin_restore " + character.getName(), admin))));
	}

	@Test
	void theStartupCleanup() throws Exception
	{
		final int orphan = IDS.incrementAndGet();
		item(orphan, SOULSHOT, 11, "INVENTORY");
		final int auction = IDS.incrementAndGet();
		sql("INSERT INTO item_auction (auctionId, instanceId, auctionItemId, startingTime, endingTime, auctionStateId) VALUES (?, 0, 0, 0, 0, 1)", auction);
		sql("INSERT INTO item_auction_bid (auctionId, playerObjId, playerBid) VALUES (?, ?, 500)", auction, orphan);
		sql("INSERT INTO auction_bid (id, auctionId, bidderId, bidderName, clan_name, maxBid, time_bid) VALUES (?, ?, ?, 'x', 'x', 300, 0)", IDS.incrementAndGet(), IDS.incrementAndGet(), orphan);
		final Map<Integer, Long> changed = countedExactly(DatabaseIdManager::cleanDatabase);
		assertTrue(changed.getOrDefault(SOULSHOT, 0L) <= -11, "the orphan's items left");
		assertTrue(changed.getOrDefault(ADENA, 0L) <= -800, "the orphan's bids left");
	}

	@Test
	void aDeletedAuctionsOpenBidsButNotItsPaidWinningBid() throws Exception
	{
		final int started = IDS.incrementAndGet();
		sql("INSERT INTO item_auction (auctionId, instanceId, auctionItemId, startingTime, endingTime, auctionStateId) VALUES (?, 0, 0, 0, 0, 1)", started);
		sql("INSERT INTO item_auction_bid (auctionId, playerObjId, playerBid) VALUES (?, 1, 500), (?, 2, 300)", started, started);
		assertEquals(Map.of(ADENA, -800L), countedExactly(() -> ItemAuctionManager.deleteAuction(started)));
		final int finished = IDS.incrementAndGet();
		sql("INSERT INTO item_auction (auctionId, instanceId, auctionItemId, startingTime, endingTime, auctionStateId) VALUES (?, 0, 0, 0, 0, 2)", finished);
		sql("INSERT INTO item_auction_bid (auctionId, playerObjId, playerBid) VALUES (?, 1, 900), (?, 2, 700)", finished, finished);
		assertEquals(Map.of(ADENA, -700L), countedExactly(() -> ItemAuctionManager.deleteAuction(finished)), "the 900 were paid when it finished");
	}

	@Test
	void anExpiredAuctionClearedAtLoad() throws Exception
	{
		final ItemAuctionInstance instance = ItemAuctionManager.getInstance().getManagerInstance(32320);
		final int expired = IDS.incrementAndGet();
		sql("INSERT INTO item_auction (auctionId, instanceId, auctionItemId, startingTime, endingTime, auctionStateId) VALUES (?, 32320, 1, 1000, 2000, 2)", expired); // finished long ago
		sql("INSERT INTO item_auction_bid (auctionId, playerObjId, playerBid) VALUES (?, 1, 900), (?, 2, 700)", expired, expired);
		final Object[] loaded = new Object[1];
		assertEquals(Map.of(ADENA, -700L), countedExactly(() -> loaded[0] = call(instance, ItemAuctionInstance.class, "loadAuction", new Class<?>[]
		{
			int.class
		}, expired)));
		assertNull(loaded[0], "an expired auction is cleared, not loaded");
	}
}
