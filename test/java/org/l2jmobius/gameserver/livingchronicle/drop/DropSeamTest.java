package org.l2jmobius.gameserver.livingchronicle.drop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.livingchronicle.rng.KeyedRandom;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * The drop seam (docs/spec/asset-service.md §11.33, CP-0032): routers, in order, take positions of a kill's death-drop
 * list after their own operation committed; what none took is dropped as retail drops it; with none installed the kill
 * is retail's, the very list returned.
 */
class DropSeamTest
{
	private static final long START = 1_794_216_090_000L;
	private static final long SEED = 32;
	private static TestServer _server;
	private static boolean _autoLoot;
	private static Monster _victim;
	private static Player _killer;
	/** A key of the stream under which the victim drops at least two items. */
	private static long _key;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.bootPaused(START, SEED); // a paused world, so a kill runs on a keyed stream
		_autoLoot = PlayerConfig.AUTO_LOOT;
		PlayerConfig.AUTO_LOOT = false; // every drop reaches the ground, where the test reads it
		final List<NpcTemplate> monsters = new ArrayList<>(NpcData.getInstance().getTemplates(t -> t.isType("Monster") && (t.getDropGroups() != null)));
		monsters.sort(Comparator.comparingInt((NpcTemplate t) -> -t.getDropGroups().size()).thenComparingInt(NpcTemplate::getId)); // the most drop groups
		_victim = new Monster(monsters.get(0));
		_victim.setXYZ(-84318, 244579, -3730); // Talking Island
		_killer = TestPlayers.create(PlayerClass.FIGHTER, (int) monsters.get(0).getLevel());
		if (World.getPlayer(_killer.getObjectId()) == null)
		{
			World.addObject(_killer);
		}
		// a key under which retail's own kill drops at least two item ids (doItemDrop draws before it rolls the drops), found
		// without the seam, which the tests then judge
		for (_key = 1; _key < 1000; _key++)
		{
			if (kill().size() >= 2)
			{
				return;
			}
		}
		
		throw new IllegalStateException("no key gives two drops");
	}

	@AfterAll
	static void stop()
	{
		PlayerConfig.AUTO_LOOT = _autoLoot;
	}

	@AfterEach
	void uninstall()
	{
		DropSeam.uninstall();
	}

	/** Kills the victim once on the test's key. @return what reached the ground, id → total count */
	private static TreeMap<Integer, Long> kill()
	{
		final Set<Integer> before = new HashSet<>();
		for (WorldObject object : World.getVisibleObjects())
		{
			before.add(object.getObjectId());
		}
		_server.withRandom(KeyedRandom.of(SEED, "seam", _key), () -> _victim.doItemDrop(_killer));
		final TreeMap<Integer, Long> ground = new TreeMap<>();
		for (WorldObject object : List.copyOf(World.getVisibleObjects()))
		{
			if ((object instanceof Item item) && !before.contains(item.getObjectId()) && item.isSpawned())
			{
				ground.merge(item.getId(), item.getCount(), Long::sum);
				item.decayMe();
			}
		}
		return ground;
	}

	private static TreeMap<Integer, Long> total(List<ItemHolder> items)
	{
		final TreeMap<Integer, Long> total = new TreeMap<>();
		for (ItemHolder item : items)
		{
			total.merge(item.getId(), item.getCount(), Long::sum);
		}
		return total;
	}

	/** A router that records what it is offered and takes the given positions. */
	private static final class Recorder implements DropRouter
	{
		final List<List<ItemHolder>> offered = new ArrayList<>();
		final List<Kill> kills = new ArrayList<>();
		private final Set<Integer> _takes;

		Recorder(Integer... takes)
		{
			_takes = Set.of(takes);
		}

		@Override
		public Set<Integer> route(Kill kill, List<ItemHolder> items)
		{
			kills.add(kill);
			offered.add(List.copyOf(items));
			return _takes;
		}
	}

	@Test
	void withNoRouterTheKillDropsExactlyAsRetail()
	{
		final TreeMap<Integer, Long> retail = kill();
		final Recorder watcher = new Recorder();
		DropSeam.install(7, List.of(watcher));
		assertEquals(retail, kill(), "a router that takes nothing changes nothing");
		assertEquals(retail, total(watcher.offered.get(0)), "it is offered exactly what retail rolled");
		DropSeam.uninstall();
		final List<ItemHolder> list = new ArrayList<>(watcher.offered.get(0));
		assertSame(list, DropSeam.route(_victim, _killer, list), "no router: the very list comes back");
	}

	@Test
	void aRouterTakesItsPositionsAndRetailDropsTheRest()
	{
		final Recorder taker = new Recorder(0);
		DropSeam.install(7, List.of(taker));
		final TreeMap<Integer, Long> ground = kill();
		final ItemHolder taken = taker.offered.get(0).get(0);
		final TreeMap<Integer, Long> expected = total(taker.offered.get(0));
		expected.merge(taken.getId(), -taken.getCount(), Long::sum);
		expected.values().removeIf(count -> count == 0);
		assertEquals(expected, ground, "the taken position is not dropped, everything else is");
	}

	@Test
	void routersRunInOrderOnWhatIsLeft()
	{
		final Recorder first = new Recorder(0);
		final Recorder second = new Recorder();
		DropSeam.install(7, List.of(first, second));
		kill();
		assertEquals(first.offered.get(0).subList(1, first.offered.get(0).size()), second.offered.get(0), "the second is offered the rest");
		assertEquals(first.kills.get(0), second.kills.get(0), "of the same kill");
	}

	/** A router that throws from route, then says from durable state what it had committed. */
	private static DropRouter throwing(Set<Integer> committed, boolean answers)
	{
		return new DropRouter()
		{
			@Override
			public Set<Integer> route(Kill kill, List<ItemHolder> offered)
			{
				throw new IllegalStateException("failed after committing " + committed);
			}

			@Override
			public Set<Integer> committed(Kill kill, List<ItemHolder> offered)
			{
				if (!answers)
				{
					throw new IllegalStateException("the store cannot be read");
				}
				return committed;
			}
		};
	}

	@Test
	void aRouterThatThrowsBeforeCommittingLeavesEverythingToRetail()
	{
		final TreeMap<Integer, Long> retail = kill();
		DropSeam.install(7, List.of(throwing(Set.of(), true)));
		assertEquals(retail, kill(), "it committed nothing, as its durable state says: nothing is lost");
	}

	@Test
	void aRouterThatThrowsAfterCommittingKeepsWhatItCommittedAndRetailTheRest()
	{
		final Recorder seen = new Recorder();
		DropSeam.install(7, List.of(seen, throwing(Set.of(0), true)));
		final TreeMap<Integer, Long> ground = kill();
		final ItemHolder taken = seen.offered.get(0).get(0);
		final TreeMap<Integer, Long> expected = total(seen.offered.get(0));
		expected.merge(taken.getId(), -taken.getCount(), Long::sum);
		expected.values().removeIf(count -> count == 0);
		assertEquals(expected, ground, "the committed position once, everything else on the ground");
	}

	@Test
	void aRouterWhoseOutcomeCannotBeReadYetKeepsItsDropsUntilItCan()
	{
		final java.util.concurrent.atomic.AtomicBoolean readable = new java.util.concurrent.atomic.AtomicBoolean(false);
		final Recorder seen = new Recorder();
		final Recorder next = new Recorder();
		DropSeam.install(7, List.of(seen, new DropRouter()
		{
			@Override
			public Set<Integer> route(Kill kill, List<ItemHolder> offered)
			{
				throw new IllegalStateException("committed position 0, then the connection failed");
			}

			@Override
			public Set<Integer> committed(Kill kill, List<ItemHolder> offered)
			{
				if (!readable.get())
				{
					throw new IllegalStateException("the store cannot be read");
				}
				return Set.of(0);
			}
		}, next));
		assertEquals(new TreeMap<>(), kill(), "unknown: nothing dropped yet, where a committed copy would be a duplicate");
		assertTrue(next.offered.isEmpty(), "nor offered on");
		assertEquals(1, DropSeam.pending(), "held, not lost");
		assertEquals(new TreeMap<>(), resolve(), "still unreadable: still held");
		readable.set(true);
		final TreeMap<Integer, Long> expected = total(seen.offered.get(0));
		final ItemHolder taken = seen.offered.get(0).get(0);
		expected.merge(taken.getId(), -taken.getCount(), Long::sum);
		expected.values().removeIf(count -> count == 0);
		assertEquals(expected, resolve(), "once readable: what it did not commit reaches the ground, once");
		assertEquals(0, DropSeam.pending());
	}

	private static DropRouter unreadable(java.util.concurrent.atomic.AtomicBoolean readable)
	{
		return new DropRouter()
		{
			@Override
			public Set<Integer> route(Kill kill, List<ItemHolder> offered)
			{
				throw new IllegalStateException("committed nothing, then the connection failed");
			}

			@Override
			public Set<Integer> committed(Kill kill, List<ItemHolder> offered)
			{
				if (!readable.get())
				{
					throw new IllegalStateException("the store cannot be read");
				}
				return Set.of();
			}
		};
	}

	@Test
	void withNoRoomToHoldAKillTheRoutersAreNotAskedAndRetailDropsIt()
	{
		final java.util.concurrent.atomic.AtomicBoolean readable = new java.util.concurrent.atomic.AtomicBoolean(false);
		final TreeMap<Integer, Long> retail = kill();
		DropSeam.capacity(1);
		try
		{
			DropSeam.install(7, List.of(unreadable(readable)));
			assertEquals(new TreeMap<>(), kill(), "the first kill is held, filling the room");
			final Recorder late = new Recorder();
			DropSeam.install(7, List.of(late, unreadable(readable)));
			assertEquals(retail, kill(), "no room to hold an unknown outcome: no router runs, retail drops the kill");
			assertTrue(late.offered.isEmpty(), "not even offered");
			readable.set(true);
			assertEquals(retail, resolve(), "the held kill drops once its router answers");
		}
		finally
		{
			DropSeam.capacity(DropSeam.MAX_PENDING);
		}
	}

	private static void setInstance(WorldObject object, int instanceId)
	{
		try
		{
			final java.lang.reflect.Field location = WorldObject.class.getDeclaredField("_location");
			location.setAccessible(true);
			final java.lang.reflect.Field id = org.l2jmobius.gameserver.entity.Location.class.getDeclaredField("_instanceId");
			id.setAccessible(true);
			id.setInt(location.get(object), instanceId);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	@Test
	void aHeldKillWhoseInstanceIsGoneDropsNowhere()
	{
		final java.util.concurrent.atomic.AtomicBoolean readable = new java.util.concurrent.atomic.AtomicBoolean(false);
		DropSeam.install(7, List.of(unreadable(readable)));
		final int instance = _victim.getInstanceId();
		setInstance(_victim, 777_777); // killed in an instance that is destroyed before the kill resolves
		try
		{
			assertEquals(new TreeMap<>(), kill());
		}
		finally
		{
			setInstance(_victim, instance);
		}
		readable.set(true);
		assertEquals(new TreeMap<>(), resolve(), "retail destroys an instance's ground items with it: none appears in the world");
		assertEquals(0, DropSeam.pending(), "resolved, not kept");
	}

	@Test
	void aDropThatCannotEnterItsInstanceIsNotPublished()
	{
		// whenever the instance went (before the seam's check, or between it and the item's assignment), the item's own
		// instance after assignment decides: one that did not enter it is never put in the world outside
		final Set<Integer> before = new HashSet<>();
		for (WorldObject object : World.getVisibleObjects())
		{
			before.add(object.getObjectId());
		}
		final Item item = _victim.dropItemAt(_killer, 57, 10, _victim.getX(), _victim.getY(), _victim.getZ(), 777_777);
		assertEquals(null, item, "no drop");
		for (WorldObject object : List.copyOf(World.getVisibleObjects()))
		{
			assertTrue(!(object instanceof Item dropped) || before.contains(dropped.getObjectId()) || !dropped.isSpawned(), "nothing spawned in instance 0");
		}
	}

	@Test
	void aHeldKillDropsWhereItDiedEvenIfItsMonsterMoved()
	{
		final java.util.concurrent.atomic.AtomicBoolean readable = new java.util.concurrent.atomic.AtomicBoolean(false);
		DropSeam.install(7, List.of(unreadable(readable)));
		final int x = _victim.getX();
		final int y = _victim.getY();
		assertEquals(new TreeMap<>(), kill());
		_victim.setXYZ(x + 20_000, y + 20_000, _victim.getZ()); // respawned elsewhere (Spawn.respawnNpc reuses the object)
		try
		{
			readable.set(true);
			final Set<Integer> before = new HashSet<>();
			for (WorldObject object : World.getVisibleObjects())
			{
				before.add(object.getObjectId());
			}
			DropSeam.resolvePending();
			int dropped = 0;
			for (WorldObject object : List.copyOf(World.getVisibleObjects()))
			{
				if ((object instanceof Item item) && !before.contains(item.getObjectId()) && item.isSpawned())
				{
					dropped++;
					assertTrue((Math.abs(item.getX() - x) <= 500) && (Math.abs(item.getY() - y) <= 500), "at the kill's place, not where the monster is now: " + item.getX() + "," + item.getY());
					item.decayMe();
				}
			}
			assertTrue(dropped > 0, "the held kill dropped");
		}
		finally
		{
			_victim.setXYZ(x, y, _victim.getZ());
		}
	}

	/** Resolves the seam's pending kills. @return what that dropped, id → total count */
	private static TreeMap<Integer, Long> resolve()
	{
		final Set<Integer> before = new HashSet<>();
		for (WorldObject object : World.getVisibleObjects())
		{
			before.add(object.getObjectId());
		}
		DropSeam.resolvePending();
		final TreeMap<Integer, Long> ground = new TreeMap<>();
		for (WorldObject object : List.copyOf(World.getVisibleObjects()))
		{
			if ((object instanceof Item item) && !before.contains(item.getObjectId()) && item.isSpawned())
			{
				ground.merge(item.getId(), item.getCount(), Long::sum);
				item.decayMe();
			}
		}
		return ground;
	}

	@Test
	void aRouterThatReturnsNothingLeavesEverythingToRetail()
	{
		final TreeMap<Integer, Long> retail = kill();
		DropSeam.install(7, List.of((kill, items) -> Set.of()));
		assertEquals(retail, kill(), "a router that took nothing says so, and retail drops everything");
	}

	@Test
	void aNullPositionIsIgnored()
	{
		final Set<Integer> takes = new HashSet<>();
		takes.add(null);
		takes.add(0);
		final Recorder seen = new Recorder();
		DropSeam.install(7, List.of((kill, items) ->
		{
			seen.offered.add(List.copyOf(items));
			return takes;
		}));
		final TreeMap<Integer, Long> ground = kill();
		final ItemHolder taken = seen.offered.get(0).get(0);
		final TreeMap<Integer, Long> expected = total(seen.offered.get(0));
		expected.merge(taken.getId(), -taken.getCount(), Long::sum);
		expected.values().removeIf(count -> count == 0);
		assertEquals(expected, ground, "the rest of the kill still drops");
	}

	@Test
	void reinstallingAnEpochKeepsCountingItsKills()
	{
		final Recorder first = new Recorder();
		DropSeam.install(71, List.of(first));
		kill();
		DropSeam.uninstall();
		final Recorder second = new Recorder();
		DropSeam.install(71, List.of(second));
		kill();
		assertEquals("kill/71/1", first.kills.get(0).key());
		assertEquals("kill/71/2", second.kills.get(0).key(), "a key is never given to two kills");
	}

	@Test
	void anEarlierEpochReinstalledKeepsCountingToo()
	{
		final Recorder first = new Recorder();
		DropSeam.install(73, List.of(first));
		kill();
		DropSeam.install(74, List.of(new Recorder()));
		kill();
		final Recorder again = new Recorder();
		DropSeam.install(73, List.of(again));
		kill();
		assertEquals("kill/73/1", first.kills.get(0).key());
		assertEquals("kill/73/2", again.kills.get(0).key(), "an epoch's counter survives another epoch's install");
	}

	@Test
	void aPositionOutOfRangeIsIgnoredAndTheValidOnesStayTaken()
	{
		final Recorder taker = new Recorder(0, 99);
		DropSeam.install(7, List.of(taker));
		final TreeMap<Integer, Long> ground = kill();
		final ItemHolder taken = taker.offered.get(0).get(0);
		final TreeMap<Integer, Long> expected = total(taker.offered.get(0));
		expected.merge(taken.getId(), -taken.getCount(), Long::sum);
		expected.values().removeIf(count -> count == 0);
		assertEquals(expected, ground);
	}

	@Test
	void oneItemIdTwiceIsTwoPositions()
	{
		final List<ItemHolder> twice = new ArrayList<>(List.of(new ItemHolder(57, 10), new ItemHolder(57, 10)));
		DropSeam.install(7, List.of(new Recorder(1)));
		final List<ItemHolder> left = new ArrayList<>(DropSeam.route(_victim, _killer, twice));
		assertEquals(1, left.size(), "one of the two stays retail's");
		assertEquals(57, left.get(0).getId());
		assertEquals(10, left.get(0).getCount());
	}

	@Test
	void killsAreNumberedFromOne()
	{
		final Recorder watcher = new Recorder();
		DropSeam.install(70, List.of(watcher));
		kill();
		kill();
		assertEquals("kill/70/1", watcher.kills.get(0).key());
		assertEquals("kill/70/2", watcher.kills.get(1).key());
		assertEquals((70L << 40) | 2, watcher.kills.get(1).ref(), "a posting's reference: epoch << 40 | seq");
		assertSame(_victim, watcher.kills.get(0).npc());
		assertSame(_killer, watcher.kills.get(0).owner());
		assertTrue(watcher.kills.get(0).seq() < watcher.kills.get(1).seq());
	}
}
