/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.BitSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.config.IdManagerConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.livingchronicle.LivingChronicle;
import org.l2jmobius.gameserver.livingchronicle.journal.OutboxStager;
import org.l2jmobius.gameserver.managers.DatabaseIdManager;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.ItemManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * CP-0034 and the lifecycle writer (docs/spec/asset-service.md §11.35, ASSET-INV08, ASSET-R29; part of AT-ASSET-31): while
 * Living Chronicle runs a non-stackable item's id is never released at runtime, every destruction and capture reaches
 * one writer in the order offered, and the writer decides with no row lock held while it takes an item's monitor.
 */
class LifecycleTest
{
	private static final int SQUIRES_SWORD = 2369;
	private static final int ADENA = 57;
	private static TestServer _server;
	private static AssetService _service;
	private static OutboxStager _stager;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.boot();
		_stager = new OutboxStager(Clock.systemUTC(), () -> 0L, () -> 1);
		_service = new AssetFixtures(_server.database(), () -> IdManager.getInstance().getNextId(), Map.of(), id -> {}).service; // the migrations applied
	}

	@AfterEach
	void uninstall()
	{
		LifecycleWriter.install(null, false);
	}

	/** A writer for a fresh run; started or held, so a test can order offers before it drains them. */
	private static LifecycleWriter writer(boolean started) throws SQLException
	{
		final LifecycleWriter writer = new LifecycleWriter(_service, newRun(), LifecycleWriter.DEFAULT_CAPACITY);
		LifecycleWriter.install(writer, started);
		return writer;
	}

	private static int newRun() throws SQLException
	{
		try (Connection connection = _server.database().connect();
			Statement statement = connection.createStatement())
		{
			statement.executeUpdate("INSERT INTO lc_asset_run (started_at) VALUES (NOW(3))", Statement.RETURN_GENERATED_KEYS);
			try (ResultSet keys = statement.getGeneratedKeys())
			{
				keys.next();
				return keys.getInt(1);
			}
		}
	}

	private static void drained(LifecycleWriter writer) throws InterruptedException
	{
		for (int i = 0; (i < 400) && !writer.idle(); i++)
		{
			Thread.sleep(25);
		}
		assertTrue(writer.idle(), "the writer drains its queue");
	}

	private static boolean reserved(int objectId) throws Exception
	{
		final Field field = IdManager.class.getDeclaredField("_freeIds");
		field.setAccessible(true);
		return ((BitSet) field.get(IdManager.getInstance())).get(objectId - IdManagerConfig.FIRST_OBJECT_ID);
	}

	private static String query(String sql) throws SQLException
	{
		try (Connection connection = _server.database().connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery(sql))
		{
			final StringBuilder rows = new StringBuilder();
			while (result.next())
			{
				for (int i = 1; i <= result.getMetaData().getColumnCount(); i++)
				{
					rows.append(i == 1 ? "" : ",").append(result.getString(i));
				}
				rows.append(';');
			}
			return rows.toString();
		}
	}

	/** Every lifecycle of the object, oldest first: {@code end_reason,began_op is null,ended_run;} per row. */
	private static String lifecycles(int objectId) throws SQLException
	{
		return query("SELECT IFNULL(end_reason, 'LIVE'), began_op IS NULL, IFNULL(ended_run, 0) FROM lc_item_lifecycle WHERE object_id = " + objectId + " ORDER BY lifecycle_id");
	}

	private static long facts(String name, int objectId) throws SQLException
	{
		return Long.parseLong(query("SELECT COUNT(*) FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = 'Asset." + name + "' AND JSON_VALUE(o.payload, '$.object') = " + objectId).replace(";", ""));
	}

	private static Item storedSword(Player player)
	{
		final Item sword = player.getInventory().addItem(ItemProcessType.NONE, SQUIRES_SWORD, 1, player, null);
		sword.updateDatabase(true);
		return sword;
	}

	private static void destroy(Item item)
	{
		ItemManager.destroyItem(ItemProcessType.NONE, item, null, null);
		item.updateDatabase();
	}

	@Test
	void aDestroyedNonStackablesIdIsKeptWhileLivingChronicleRuns() throws Exception
	{
		writer(true);
		final Item sword = storedSword(TestPlayers.create(PlayerClass.FIGHTER, 20));
		destroy(sword);
		assertEquals("", query("SELECT object_id FROM items WHERE object_id = " + sword.getObjectId()), "its row is deleted");
		assertTrue(reserved(sword.getObjectId()), "but its id is kept until the next start: no lifecycle can name a reused id");
	}

	@Test
	void aStackablesIdIsReleasedAsUpstream() throws Exception
	{
		writer(true);
		final Item adena = new Item(IdManager.getInstance().getNextId(), ADENA);
		ItemManager.destroyItem(ItemProcessType.NONE, adena, null, null);
		assertFalse(reserved(adena.getObjectId()), "a stack has no lifecycle; its id is released as upstream");
	}

	@Test
	void aTrackedItemThatIsDestroyedEndsDestroyedWithItsFact() throws Exception
	{
		final LifecycleWriter writer = writer(true);
		final Item sword = storedSword(TestPlayers.create(PlayerClass.FIGHTER, 20));
		assertTrue(LifecycleWriter.capture(sword, ItemProcessType.PICKUP, 0));
		drained(writer);
		assertEquals("LIVE,1,0;", lifecycles(sword.getObjectId()), "begun by the capture, began_op null");
		assertEquals(1, facts("LifecycleTracked", sword.getObjectId()));
		destroy(sword);
		drained(writer);
		assertEquals("DESTROYED,1," + writer.run() + ";", lifecycles(sword.getObjectId()));
		assertEquals(1, facts("LifecycleEnded", sword.getObjectId()));
	}

	@Test
	void aCaptureWhoseDestructionWasOfferedFirstWritesNothing() throws Exception
	{
		final LifecycleWriter writer = writer(false);
		final Item sword = storedSword(TestPlayers.create(PlayerClass.FIGHTER, 20));
		final LifecycleWriter.Offer late = LifecycleWriter.captureOf(sword, ItemProcessType.PICKUP, 0); // checked live, offered after the destruction
		destroy(sword);
		writer.offer(late);
		writer.start();
		drained(writer);
		assertEquals("", lifecycles(sword.getObjectId()));
		assertEquals(0, facts("LifecycleTracked", sword.getObjectId()));
	}

	@Test
	void aCaptureOfferedBeforeItsObjectsDestructionIsBegunThenEndedRowOrNoRow() throws Exception
	{
		final LifecycleWriter writer = writer(false);
		final Item sword = storedSword(TestPlayers.create(PlayerClass.FIGHTER, 20));
		assertTrue(LifecycleWriter.capture(sword, ItemProcessType.PICKUP, 0));
		destroy(sword); // the row is deleted before the writer reaches the capture
		writer.start();
		drained(writer);
		assertEquals("DESTROYED,1," + writer.run() + ";", lifecycles(sword.getObjectId()), "the true history: an entry, then a destruction");
	}

	@Test
	void aCaptureOfAnObjectAnOperationTookThisRunWritesNothing() throws Exception
	{
		final LifecycleWriter writer = writer(false);
		final Item sword = storedSword(TestPlayers.create(PlayerClass.FIGHTER, 20));
		assertTrue(LifecycleWriter.capture(sword, ItemProcessType.PICKUP, 0));
		try (Connection connection = _server.database().connect();
			Statement statement = connection.createStatement())
		{
			// as a fund that began a lifecycle and a corrective sink that ended it, both between the writer's check and its transaction
			statement.execute("INSERT INTO lc_item_lifecycle (object_id, item_id, began_at, ended_at, end_reason, ended_run) VALUES (" + sword.getObjectId() + ", " + SQUIRES_SWORD + ", NOW(3), NOW(3), 'DESTROYED', " + writer.run() + ")");
		}
		writer.start();
		drained(writer);
		assertEquals("DESTROYED,1," + writer.run() + ";", lifecycles(sword.getObjectId()), "no second lifecycle for an object gone this run");
	}

	@Test
	void aCaptureOfAnItemAnOperationRetiredWritesNothing() throws Exception
	{
		final LifecycleWriter writer = writer(false);
		final Item sword = storedSword(TestPlayers.create(PlayerClass.FIGHTER, 20));
		assertTrue(LifecycleWriter.capture(sword, ItemProcessType.PICKUP, 0));
		sword.retire();
		writer.start();
		drained(writer);
		assertEquals("", lifecycles(sword.getObjectId()), "the operation that retired it begins its lifecycle");
	}

	@Test
	void aNewUnsavedItemADroppedItemAndAnUnloadedItemAreBegun() throws Exception
	{
		final LifecycleWriter writer = writer(false);
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 20);
		final Item unsaved = player.getInventory().addItem(ItemProcessType.NONE, SQUIRES_SWORD, 1, player, null);
		final Item dropped = storedSword(player);
		final Item unloaded = storedSword(player);
		assertTrue(LifecycleWriter.capture(unsaved, ItemProcessType.PICKUP, 0));
		assertTrue(LifecycleWriter.capture(dropped, ItemProcessType.PICKUP, 0));
		assertTrue(LifecycleWriter.capture(unloaded, ItemProcessType.PICKUP, 0));
		player.getInventory().dropItem(ItemProcessType.DROP, dropped, player, null); // VOID, but alive on the ground
		World.removeObject(unloaded); // as a logout unloads it
		writer.start();
		drained(writer);
		assertEquals("LIVE,1,0;", lifecycles(unsaved.getObjectId()));
		assertEquals("LIVE,1,0;", lifecycles(dropped.getObjectId()));
		assertEquals("LIVE,1,0;", lifecycles(unloaded.getObjectId()));
	}

	@Test
	void aCaptureIsRefusedForAStackableARetiredOrADestroyedItemAndOnAFullQueue() throws Exception
	{
		assertFalse(LifecycleWriter.capture(storedSword(TestPlayers.create(PlayerClass.FIGHTER, 20)), ItemProcessType.PICKUP, 0), "no writer: truth");
		final LifecycleWriter writer = new LifecycleWriter(_service, newRun(), 64);
		LifecycleWriter.install(writer, false);
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 20);
		assertFalse(LifecycleWriter.capture(new Item(IdManager.getInstance().getNextId(), ADENA), ItemProcessType.PICKUP, 0), "a stackable");
		final Item retired = storedSword(player);
		retired.retire();
		assertFalse(LifecycleWriter.capture(retired, ItemProcessType.PICKUP, 0), "a retired item");
		final Item destroyed = storedSword(player);
		destroy(destroyed);
		assertFalse(LifecycleWriter.capture(destroyed, ItemProcessType.PICKUP, 0), "a destroyed item");
		int accepted = 1; // the destruction above took one slot
		while (LifecycleWriter.capture(storedSword(player), ItemProcessType.PICKUP, 0))
		{
			accepted++;
		}
		assertEquals(64, accepted, "the queue is bounded; a full one refuses, changing nothing");
	}

	@Test
	void theStartupPassEndsVanishedLifecyclesInThePreviousRunAndReservesTheLiveOnes() throws Exception
	{
		final Item kept = storedSword(TestPlayers.create(PlayerClass.FIGHTER, 20));
		final int gone = IdManager.getInstance().getNextId();
		final int previous = newRun();
		try (Connection connection = _server.database().connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO lc_item_lifecycle (object_id, item_id, began_at) VALUES (" + kept.getObjectId() + ", " + SQUIRES_SWORD + ", NOW(3)), (" + gone + ", " + SQUIRES_SWORD + ", NOW(3))");
		}
		final LifecycleWriter.Startup startup;
		try (Connection connection = _server.database().connect())
		{
			startup = LifecycleWriter.startupPass(connection, _stager);
		}
		assertTrue(startup.run() > previous);
		assertEquals("VANISHED,1," + previous + ";", lifecycles(gone), "ended in the run its object was last known in, so this run may reuse its id");
		assertEquals(1, facts("LifecycleEnded", gone));
		assertEquals("LIVE,1,0;", lifecycles(kept.getObjectId()));
		assertTrue(startup.live().contains(kept.getObjectId()));
		assertFalse(startup.live().contains(gone));
	}

	@Test
	void theUsedIdScanRunsThePassWhileLivingChronicleRunsWhateverTheCleanupAndNothingInTruth() throws Exception
	{
		DatabaseIdManager.getUsedIds();
		assertFalse(LifecycleWriter.isRunning(), "truth: no writer, every id released as upstream");
		System.setProperty(LivingChronicle.PROFILE, "dev");
		final boolean cleanup = IdManagerConfig.DATABASE_CLEAN_UP;
		IdManagerConfig.DATABASE_CLEAN_UP = false; // the optional cleanup off: the pass must not depend on it
		try
		{
			DatabaseIdManager.getUsedIds();
			assertTrue(LifecycleWriter.isRunning(), "the pass ran before the id set, and this run's writer started");
		}
		finally
		{
			IdManagerConfig.DATABASE_CLEAN_UP = cleanup;
			System.clearProperty(LivingChronicle.PROFILE);
		}
	}

	@Test
	void theUsedIdsHoldEveryIdALiveLifecycleNamesWhateverTheRowScanFound() throws Exception
	{
		final int named = IdManagerConfig.LAST_OBJECT_ID - 7; // in no table the scan reads
		LifecycleWriter.reserve(Set.of(named));
		try
		{
			assertTrue(DatabaseIdManager.getUsedIds().contains(named));
		}
		finally
		{
			LifecycleWriter.reserve(Set.of());
		}
	}
}
