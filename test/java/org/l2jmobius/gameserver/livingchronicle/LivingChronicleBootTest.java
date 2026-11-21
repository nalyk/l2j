package org.l2jmobius.gameserver.livingchronicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.livingchronicle.asset.EconomyCounter;
import org.l2jmobius.gameserver.livingchronicle.asset.EconomyMonitor;
import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * Living Chronicle in the server's life (docs/spec/asset-service.md §2.6, §3.2, §11.25; profiles.md §4.2 rule 4): right
 * after the database opens it takes the startup census and starts the counters; once Mobius's managers are up and before
 * any login it recovers; at the shutdown boundary it writes every clan warehouse and takes the shutdown census, only if
 * every write succeeded. In {@code truth} it starts nothing.
 */
class LivingChronicleBootTest
{
	private static final int SOULSHOT = 1835;
	private static final AtomicInteger NAMES = new AtomicInteger();

	@BeforeAll
	static void boot() throws Exception
	{
		TestServer.boot();
		try (Connection connection = DatabaseFactory.getConnection();
			ResultSet tables = connection.getMetaData().getTables(null, null, "lc_custody", null))
		{
			if (!tables.next())
			{
				Migrations.applyAll(connection); // the operator's step on a server (platform.md §4.3)
			}
		}
	}

	@AfterEach
	void truth()
	{
		System.clearProperty(LivingChronicle.PROFILE);
		EconomyCounter.deactivate();
	}

	private static long censuses(String kind) throws Exception
	{
		try (Connection connection = DatabaseFactory.getConnection();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM lc_economy_census WHERE item_id = 0 AND kind = '" + kind + "'"))
		{
			result.next();
			return result.getLong(1);
		}
	}

	@Test
	void inTruthItStartsNothing() throws Exception
	{
		assertEquals(LivingChronicle.Profile.TRUTH, LivingChronicle.profile(), "no lc.profile yet means truth (the profile check is its own node)");
		final long startups = censuses("STARTUP") + censuses("REBASE");
		LivingChronicle.atDatabase();
		LivingChronicle.beforeLogin();
		LivingChronicle.atShutdown();
		assertFalse(EconomyCounter.isActive());
		assertEquals(startups, censuses("STARTUP") + censuses("REBASE"));
	}

	@Test
	void anUnknownProfileRefusesToStart()
	{
		System.setProperty(LivingChronicle.PROFILE, "carnival");
		assertThrows(IllegalStateException.class, LivingChronicle::atDatabase);
	}

	@Test
	void aProfileThatStartsModulesCountsRecoversAndKeepsTheBoundary() throws Exception
	{
		System.setProperty(LivingChronicle.PROFILE, "dev");
		LivingChronicle.atDatabase();
		assertTrue(EconomyCounter.isActive(), "the counters start from the startup census");
		LivingChronicle.beforeLogin();
		final Player leader = TestPlayers.create(PlayerClass.FIGHTER, 40);
		if (World.getPlayer(leader.getObjectId()) == null)
		{
			World.addObject(leader);
		}
		final Clan clan = ClanTable.getInstance().createClan(leader, "Boot" + NAMES.incrementAndGet());
		final Item shots = clan.getWarehouse().addItem(ItemProcessType.NONE, SOULSHOT, 100, null, null);
		shots.updateDatabase(true);
		shots.changeCount(ItemProcessType.NONE, 5, null, null); // in memory only, as an offline leader's warehouse is never saved
		final long shutdowns = censuses("SHUTDOWN");
		LivingChronicle.atShutdown();
		assertEquals(shutdowns + 1, censuses("SHUTDOWN"));
		LivingChronicle.atDatabase();
		assertEquals(EconomyMonitor.Kind.STARTUP, LivingChronicle.startup().kind());
		assertEquals(Map.of(), LivingChronicle.startup().differences(), "the boundary wrote the clan warehouse, so its 5 were not lost");
	}

	@Test
	void aBoundaryWhoseWritesFailTakesNoShutdownCensus() throws Exception
	{
		System.setProperty(LivingChronicle.PROFILE, "dev");
		LivingChronicle.atDatabase();
		final Player leader = TestPlayers.create(PlayerClass.FIGHTER, 40);
		if (World.getPlayer(leader.getObjectId()) == null)
		{
			World.addObject(leader);
		}
		final Clan clan = ClanTable.getInstance().createClan(leader, "Boot" + NAMES.incrementAndGet());
		final Item shots = clan.getWarehouse().addItem(ItemProcessType.NONE, SOULSHOT, 100, null, null);
		shots.updateDatabase(true);
		final long shutdowns = censuses("SHUTDOWN");
		try (Connection connection = DatabaseFactory.getConnection();
			Statement statement = connection.createStatement())
		{
			statement.execute("CREATE TRIGGER lc_test_refuse_write BEFORE UPDATE ON items FOR EACH ROW BEGIN IF OLD.object_id = " + shots.getObjectId() + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'refused'; END IF; END");
			try
			{
				LivingChronicle.atShutdown();
			}
			finally
			{
				statement.execute("DROP TRIGGER lc_test_refuse_write");
			}
		}
		assertEquals(shutdowns, censuses("SHUTDOWN"), "a boundary that could not write everything certifies nothing");
		LivingChronicle.atDatabase();
		assertEquals(EconomyMonitor.Kind.REBASE, LivingChronicle.startup().kind());
	}
}
