package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.livingchronicle.asset.EconomyMonitor.Kind;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * INV06 (docs/spec/asset-service.md §2.6): a clean restart compares the shutdown census, the counters persisted with it
 * and the startup census per item id; a difference is {@code Asset.ConservationBroken}; a startup with no shutdown census
 * before it is a {@code REBASE}, never a pass, recorded in {@code Asset.MonitorRebased}.
 */
class EconomyMonitorTest
{
	private static final int SOULSHOT = 1835;
	private static AssetFixtures _f;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), Map.of("TEST", (connection, ref) -> true), id -> MailManager.getInstance().adopt(id));
	}

	@AfterAll
	static void stop()
	{
		EconomyCounter.deactivate();
	}

	private static EconomyMonitor.Startup startup() throws Exception
	{
		try (Connection connection = _f.database.connect())
		{
			return EconomyMonitor.atStartup(connection, _f.service.stager());
		}
	}

	private static void shutdown() throws Exception
	{
		try (Connection connection = _f.database.connect())
		{
			EconomyMonitor.atShutdown(connection);
		}
	}

	private static long facts(String name) throws Exception
	{
		return _f.number("SELECT COUNT(*) FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = '" + name + "'");
	}

	@Test
	void aCleanRestartWithNothingChangedConserves() throws Exception
	{
		startup();
		shutdown();
		final long broken = facts("Asset.ConservationBroken");
		final EconomyMonitor.Startup restart = startup();
		assertEquals(Kind.STARTUP, restart.kind());
		assertEquals(Map.of(), restart.differences(), "every item id conserved");
		assertEquals(broken, facts("Asset.ConservationBroken"));
		assertTrue(EconomyCounter.isActive(), "the counters start from the startup census");
	}

	@Test
	void aRowChangedWhileTheServerWasDownIsReportedPerItem() throws Exception
	{
		final int custody = _f.open("TEST");
		final int row = _f.seed(custody, SOULSHOT, 10, false); // written straight to the database: in the census, before the start
		startup();
		shutdown();
		_f.execute("UPDATE items SET count = 13 WHERE object_id = " + row); // between the boundary and the next start
		final long broken = facts("Asset.ConservationBroken");
		final EconomyMonitor.Startup restart = startup();
		assertEquals(Kind.STARTUP, restart.kind());
		assertEquals(Map.of(SOULSHOT, 3L), restart.differences(), "the startup census holds 3 the shutdown census and the counters did not");
		assertEquals(broken + 1, facts("Asset.ConservationBroken"));
	}

	@Test
	void aChangeLostAfterItsLastSaveIsReported() throws Exception
	{
		startup(); // counting before the item exists, as on a server
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 40);
		if (World.getPlayer(player.getObjectId()) == null)
		{
			World.addObject(player);
		}
		final Item shots = player.getInventory().addItem(ItemProcessType.NONE, SOULSHOT, 100, player, null);
		shots.updateDatabase(true);
		shots.changeCount(ItemProcessType.NONE, 5, player, null); // counted, never saved: Mobius loses it at shutdown
		shutdown();
		final EconomyMonitor.Startup restart = startup();
		assertEquals(Map.of(SOULSHOT, -5L), restart.differences(), "the counters held 5 the database never got");
	}

	@Test
	void aStartupWithoutAShutdownBeforeItIsARebase() throws Exception
	{
		startup(); // the previous start; no boundary follows it, as after a crash
		final long rebased = facts("Asset.MonitorRebased");
		final EconomyMonitor.Startup restart = startup();
		assertEquals(Kind.REBASE, restart.kind());
		assertEquals(rebased + 1, facts("Asset.MonitorRebased"));
	}
}
