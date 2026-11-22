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

	@Test
	void aRouterThatThrowsWithholdsWhatItWasOffered()
	{
		// a throw is no answer: the router may have committed before it threw, or its commit may be unresolved
		final Recorder next = new Recorder();
		DropSeam.install(7, List.of((kill, items) ->
		{
			throw new IllegalStateException("committed, then failed");
		}, next));
		assertEquals(new TreeMap<>(), kill(), "nothing offered to it reaches the ground, where a committed copy would dupe it");
		assertTrue(next.offered.isEmpty(), "nor the next router");
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
