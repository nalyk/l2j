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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.livingchronicle.journal.OutboxStager;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * {@code raid} (docs/spec/asset-service.md §4.3, §2.5 {@code RAID_SHARE}, {@code ROAD_LOSS}, ASSET-R25, CP-0035; AT-ASSET-27,
 * part of AT-ASSET-31): share and rest exactly the manifest, every row deleted, the share dropped once after a known
 * commit at the Master's place, a non-stackable keeping its id, state and lifecycle, a stackable's share one new object.
 */
class RaidTest
{
	private static final int SHORT_SWORD = 1;
	private static final int STEEL = 1880;
	private static AssetFixtures _f;
	private static Monster _master;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.boot();
		_f = new AssetFixtures(_server.database(), () -> IdManager.getInstance().getNextId(), Map.of("TEST", (connection, ref) -> true), id -> MailManager.getInstance().adopt(id));
		final List<NpcTemplate> monsters = new ArrayList<>(NpcData.getInstance().getTemplates(t -> t.isType("Monster")));
		monsters.sort(Comparator.comparingInt(NpcTemplate::getId));
		_master = new Monster(monsters.get(0));
		_master.setXYZ(-84318, 244579, -3730); // Talking Island
		EconomyCounter.activate(Map.of()); // the existence counters, relative to this start
	}

	@AfterAll
	static void stop()
	{
		EconomyCounter.deactivate();
	}

	private enum Fault
	{
		NONE,
		COMMIT_THEN_THROW,
		THROW_BEFORE_COMMIT
	}

	private static final AtomicReference<Fault> FAULT = new AtomicReference<>(Fault.NONE);
	private static TestServer _server;

	/** The fixtures' service over connections whose COMMIT fails once as {@link #FAULT} says (as AssetTxTest does). */
	private static AssetService faulty()
	{
		return new AssetService(() ->
		{
			final Connection real = _server.database().connect();
			return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]
			{
				Connection.class
			}, (proxy, method, args) ->
			{
				if (method.getName().equals("commit"))
				{
					switch (FAULT.getAndSet(Fault.NONE))
					{
						case COMMIT_THEN_THROW:
						{
							real.commit();
							throw new SQLException("connection reset after COMMIT was sent", "08S01");
						}
						case THROW_BEFORE_COMMIT:
						{
							real.rollback();
							throw new SQLException("connection reset before COMMIT was sent", "08S01");
						}
						default:
						{
							break;
						}
					}
				}
				try
				{
					return method.invoke(real, args);
				}
				catch (InvocationTargetException e)
				{
					throw e.getCause();
				}
			});
		}, () -> IdManager.getInstance().getNextId(), new OutboxStager(Clock.systemUTC(), () -> 0L, () -> 1), AssetConfig.defaults(), Map.of("TEST", (connection, ref) -> true));
	}

	/** Moves the Master, so each test's drops lie apart from the others'. */
	private static void masterAt(int dx)
	{
		_master.setXYZ(-84318 + dx, 244579, -3730);
	}

	private static long steelPieces()
	{
		long pieces = 0;
		for (WorldObject object : World.getVisibleObjects())
		{
			if ((object instanceof Item item) && (item.getId() == STEEL) && item.isSpawned() && (Math.abs(item.getX() - _master.getX()) <= 100) && (Math.abs(item.getY() - _master.getY()) <= 100))
			{
				pieces++;
			}
		}
		return pieces;
	}

	private static Player inWorld()
	{
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 40);
		if (World.getPlayer(player.getObjectId()) == null)
		{
			World.addObject(player);
		}
		return player;
	}

	/** Four swords of one player funded (each with its lifecycle, one enchanted +3 with a variable), 100 steel in two rows. */
	private static int[] manifest(int custody, Player player) throws Exception
	{
		final int[] swords = new int[4];
		for (int i = 0; i < 4; i++)
		{
			final Item sword = player.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, player, null);
			sword.updateDatabase(true);
			if (i == 0)
			{
				sword.setEnchantLevel(3);
				sword.getVariables().set("lc_test_mark", "raided");
			}
			swords[i] = sword.getObjectId();
			try (AssetTx tx = _f.service.begin(_f.op("FUND"), "TEST", "FUND", custody))
			{
				tx.fund(custody, Holder.player(player.getObjectId()), List.of(new Asset(SHORT_SWORD, 1, sword.getObjectId())));
				assertEquals(Outcome.COMMITTED, tx.commit());
			}
		}
		_f.seed(custody, STEEL, 60, false);
		_f.seed(custody, STEEL, 40, false);
		return swords;
	}

	private static OpId raid(int custody, List<Asset> share, List<Asset> rest) throws Exception
	{
		final OpId op = _f.op("RAID");
		assertEquals(Outcome.COMMITTED, raid(_f.service, op, custody, share, rest));
		return op;
	}

	private static Outcome raid(AssetService service, OpId op, int custody, List<Asset> share, List<Asset> rest) throws Exception
	{
		try (AssetTx tx = service.begin(op, "TEST", "RAID", custody))
		{
			tx.raid(custody, share, rest, _master);
			return tx.commit();
		}
	}

	private static List<Asset> share(int[] swords)
	{
		return List.of(new Asset(STEEL, 30, null), new Asset(SHORT_SWORD, 1, swords[0]));
	}

	private static List<Asset> rest(int[] swords)
	{
		return List.of(new Asset(STEEL, 70, null), new Asset(SHORT_SWORD, 1, swords[1]), new Asset(SHORT_SWORD, 1, swords[2]), new Asset(SHORT_SWORD, 1, swords[3]));
	}

	private static long rows(int custody) throws Exception
	{
		return _f.number("SELECT COUNT(*) FROM items WHERE owner_id = -1 AND loc = 'ESCROW' AND loc_data = " + custody);
	}

	@Test
	void aRaidDropsItsShareOnceAfterTheCommitAndLosesTheRestToTheRoad() throws Exception
	{
		final Player player = inWorld();
		final int custody = _f.open("TEST");
		final int[] swords = manifest(custody, player);
		final long lifecycle = _f.number("SELECT lifecycle_id FROM lc_item_lifecycle WHERE object_id = " + swords[0] + " AND ended_at IS NULL");
		// 300 per mille: 30 of 100 steel, 1 of 4 swords (the lowest id), as aden-remembers.md §4.6 computes it
		final List<Asset> share = List.of(new Asset(STEEL, 30, null), new Asset(SHORT_SWORD, 1, swords[0]));
		final List<Asset> rest = List.of(new Asset(STEEL, 70, null), new Asset(SHORT_SWORD, 1, swords[1]), new Asset(SHORT_SWORD, 1, swords[2]), new Asset(SHORT_SWORD, 1, swords[3]));
		masterAt(0);
		final long steelBefore = EconomyCounter.snapshot().getOrDefault(STEEL, 0L);
		raid(custody, share, rest);
		assertEquals(steelBefore - 100, EconomyCounter.snapshot().getOrDefault(STEEL, 0L), "the share and the rest leave the counted holdings: the ground counts nothing unsaved");
		assertEquals(0, rows(custody), "every manifest row is gone");
		final WorldObject dropped = World.findObject(swords[0]);
		assertTrue((dropped instanceof Item sword) && sword.isSpawned(), "the share's sword lies on the ground, as itself");
		final Item sword = (Item) dropped;
		assertEquals(3, sword.getEnchantLevel(), "with its enchant");
		assertEquals("raided", sword.getVariables().getString("lc_test_mark", null), "and its variables");
		assertTrue(Math.abs(sword.getX() - _master.getX()) <= 100, "at the Master's place");
		assertEquals(lifecycle, _f.number("SELECT lifecycle_id FROM lc_item_lifecycle WHERE object_id = " + swords[0] + " AND ended_at IS NULL"), "its lifecycle stays live");
		for (int i = 1; i < 4; i++)
		{
			assertEquals("DESTROYED", _f.text("SELECT end_reason FROM lc_item_lifecycle WHERE object_id = " + swords[i]), "the rest is lost to the road");
			assertNull(World.findObject(swords[i]));
		}
		assertEquals(30, steelOnTheGround(), "the steel's share is one new object of 30");
		assertEquals(2, _f.number("SELECT COUNT(*) FROM lc_ledger_posting p JOIN lc_asset_op o ON o.op_id = p.op_id WHERE o.action = 'RAID' AND p.code = 'RAID_SHARE' AND p.account_kind = 'SINK' AND p.op_id = (SELECT op_id FROM lc_ledger_posting WHERE object_id = " + swords[0] + " AND code = 'RAID_SHARE')"));
		final Item steelDrop = (Item) World.getVisibleObjects().stream().filter(o -> (o instanceof Item i) && (i.getId() == STEEL) && i.isSpawned() && (Math.abs(i.getX() - _master.getX()) <= 100) && (Math.abs(i.getY() - _master.getY()) <= 100)).findFirst().orElseThrow();
		player.getInventory().addItem(ItemProcessType.PICKUP, steelDrop, player, null);
		assertEquals(steelBefore - 70, EconomyCounter.snapshot().getOrDefault(STEEL, 0L), "its pickup counts the 30 once: the rebuilt object was uncounted");
		final Item picked = player.getInventory().addItem(ItemProcessType.PICKUP, sword, player, null);
		picked.updateDatabase(true);
		assertEquals(player.getObjectId() + "|3", _f.text("SELECT CONCAT_WS('|', owner_id, enchant_level) FROM items WHERE object_id = " + swords[0]), "its pickup saves it as a new row");
		assertEquals(0, _f.number("SELECT COUNT(*) FROM item_variables WHERE id = " + swords[0]), "its variables stay in memory, as a retail drop's do (Mobius deletes a dropped item's variable rows)");
	}

	private static long steelOnTheGround()
	{
		long steel = 0;
		for (WorldObject object : World.getVisibleObjects())
		{
			if ((object instanceof Item item) && (item.getId() == STEEL) && item.isSpawned() && (Math.abs(item.getX() - _master.getX()) <= 100) && (Math.abs(item.getY() - _master.getY()) <= 100))
			{
				steel += item.getCount();
			}
		}
		return steel;
	}

	@Test
	void aRaidThatIsNotExactlyTheManifestIsRefusedAndDropsNothing() throws Exception
	{
		final Player player = inWorld();
		final int custody = _f.open("TEST");
		final int[] swords = manifest(custody, player);
		final List<Asset> share = List.of(new Asset(STEEL, 30, null), new Asset(SHORT_SWORD, 1, swords[0]));
		final List<Asset> missingOne = List.of(new Asset(STEEL, 70, null), new Asset(SHORT_SWORD, 1, swords[1]), new Asset(SHORT_SWORD, 1, swords[2]));
		assertEquals(AssetAbort.Reason.INSUFFICIENT, assertThrows(AssetAbort.class, () -> raid(custody, share, missingOne)).reason());
		final List<Asset> steelTwice = List.of(new Asset(STEEL, 71, null), new Asset(SHORT_SWORD, 1, swords[1]), new Asset(SHORT_SWORD, 1, swords[2]), new Asset(SHORT_SWORD, 1, swords[3]));
		assertEquals(AssetAbort.Reason.INSUFFICIENT, assertThrows(AssetAbort.class, () -> raid(custody, share, steelTwice)).reason());
		final List<Asset> swordByAmount = List.of(new Asset(STEEL, 70, null), new Asset(SHORT_SWORD, 3, null));
		assertEquals(AssetAbort.Reason.INSUFFICIENT, assertThrows(AssetAbort.class, () -> raid(custody, share, swordByAmount)).reason());
		assertEquals(6, rows(custody), "nothing moved");
		assertNull(World.findObject(swords[0]), "nothing dropped: the rollback built nothing");
	}

	@Test
	void aStackShareNamedInSeveralEntriesFallsAsOneObject() throws Exception
	{
		masterAt(3_000);
		final int custody = _f.open("TEST");
		final int[] swords = manifest(custody, inWorld());
		raid(custody, List.of(new Asset(STEEL, 10, null), new Asset(STEEL, 20, null), new Asset(SHORT_SWORD, 1, swords[0])), rest(swords));
		assertEquals(1, steelPieces(), "one new object per stackable item id");
		assertEquals(30, steelOnTheGround());
	}

	@Test
	void aCommitWhoseAcknowledgementWasLostDropsOnceAndARepeatOfTheOperationDropsNothing() throws Exception
	{
		masterAt(6_000);
		final int custody = _f.open("TEST");
		final int[] swords = manifest(custody, inWorld());
		final AssetService service = faulty();
		final OpId op = _f.op("RAID");
		FAULT.set(Fault.COMMIT_THEN_THROW);
		assertEquals(Outcome.COMMITTED, raid(service, op, custody, share(swords), rest(swords)), "resolved by the locking read");
		assertTrue(World.findObject(swords[0]) instanceof Item, "dropped once its commit was known");
		assertEquals(Outcome.ALREADY_COMMITTED, raid(service, op, custody, share(swords), rest(swords)));
		assertEquals(1, steelPieces(), "a repeat of a committed operation drops nothing");
		assertEquals(30, steelOnTheGround());
	}

	@Test
	void aCommitThatNeverHappenedDropsNothing() throws Exception
	{
		masterAt(9_000);
		final int custody = _f.open("TEST");
		final int[] swords = manifest(custody, inWorld());
		FAULT.set(Fault.THROW_BEFORE_COMMIT);
		assertEquals(AssetAbort.Reason.NOT_COMMITTED, assertThrows(AssetAbort.class, () -> raid(faulty(), _f.op("RAID"), custody, share(swords), rest(swords))).reason());
		assertEquals(6, rows(custody), "the manifest is whole");
		assertNull(World.findObject(swords[0]));
		assertEquals(0, steelPieces());
	}

	@Test
	void anAttemptRetriedAfterALockWaitDropsOnce() throws Exception
	{
		masterAt(12_000);
		final int custody = _f.open("TEST");
		final int[] swords = manifest(custody, inWorld());
		final Connection holder = _server.database().connect();
		holder.setAutoCommit(false);
		try (Statement statement = holder.createStatement())
		{
			statement.executeQuery("SELECT object_id FROM items WHERE object_id = " + swords[3] + " FOR UPDATE").close();
		}
		final Thread release = new Thread(() ->
		{
			try
			{
				Thread.sleep(1_500); // past the first attempt's lock wait, within the retries
				holder.commit();
				holder.close();
			}
			catch (Exception e)
			{
				throw new IllegalStateException(e);
			}
		});
		release.start();
		raid(custody, share(swords), rest(swords));
		release.join();
		assertEquals(1, steelPieces(), "the retried attempt rebuilt its drops from nothing");
		assertEquals(30, steelOnTheGround());
		assertTrue(World.findObject(swords[0]) instanceof Item);
	}

	@Test
	void aRestartBeforeTheShareIsSavedEndsItsLifecycleVanished() throws Exception
	{
		masterAt(15_000);
		final int custody = _f.open("TEST");
		final int[] swords = manifest(custody, inWorld());
		raid(custody, share(swords), rest(swords));
		assertEquals("", _f.text("SELECT IFNULL(GROUP_CONCAT(end_reason), '') FROM lc_item_lifecycle WHERE object_id = " + swords[0]), "live while it lies on the ground");
		try (Connection connection = _server.database().connect())
		{
			LifecycleWriter.startupPass(connection, new OutboxStager(Clock.systemUTC(), () -> 0L, () -> 1));
		}
		assertEquals("VANISHED", _f.text("SELECT end_reason FROM lc_item_lifecycle WHERE object_id = " + swords[0]), "an unsaved ground item is gone after a restart; so is its lifecycle");
	}
}
