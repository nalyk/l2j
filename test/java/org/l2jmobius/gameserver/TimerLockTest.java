package org.l2jmobius.gameserver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import org.l2jmobius.gameserver.config.custom.RandomSpawnsConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.livingchronicle.rng.KeyedRandom;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.npc.OnNpcSpawn;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.taskmanagers.RandomAnimationTaskManager;
import org.l2jmobius.testing.Arena;
import org.l2jmobius.testing.Golden;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * LOCK-TIMER: what the world does with time on its own: characters regenerate, the dead come back.
 * <p>
 * Nothing here is asked of the server but to let time pass. Each test sets up a state and steps the paused world event by event, recording when each timer fired and what it did.
 * </p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TimerLockTest
{
	private static final long START = 1_794_216_090_000L;
	private static final long SEED = 1;

	/** How long a character is watched regenerating in each posture. */
	private static final long POSTURE_MILLIS = 30_000;

	/** How long the world is given to bring every removed NPC back. */
	private static final long RESPAWN_LIMIT_MILLIS = 3_600_000;

	private static TestServer _server;

	private static final int MALUK_WARLORD = 22839;

	/** The heading each NPC of a territory spawn came back with in the respawn lock. */
	private static final List<Integer> TERRITORY_HEADINGS = new ArrayList<>();

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.bootPaused(START, SEED);
		Arena.checkEmpty();
	}

	/**
	 * LOCK-TIMER-0001: every class, at both edges of its level range, regenerating from 1 HP, MP and CP in the arena, standing still and then sitting. Regeneration draws nothing at random and characters do not touch one another, so they all regenerate side by side in one window of game time.
	 */
	@Test
	@Order(1)
	void everyClassRegeneratesAsRecorded()
	{
		final List<PlayerClass> classes = new ArrayList<>();
		for (PlayerClass playerClass : PlayerClass.values())
		{
			if (PlayerTemplateData.getInstance().getTemplate(playerClass) != null)
			{
				classes.add(playerClass);
			}
		}
		classes.sort(Comparator.comparingInt(PlayerClass::getId));

		final List<Watched> watched = new ArrayList<>();
		for (PlayerClass playerClass : classes)
		{
			for (int level : TestPlayers.levelRange(playerClass))
			{
				final Player player = TestPlayers.create(playerClass, level);
				player.setHeading(0);
				player.spawnMe(Arena.X, Arena.Y, Arena.Z);
				watched.add(new Watched(player));
			}
		}

		final long start = GameClock.millis();
		for (Watched character : watched)
		{
			character._player.setCurrentHpMp(1, 1);
			character._player.setCurrentCp(1);
			character.look(start);
		}
		Arena.run(POSTURE_MILLIS, () -> watched.forEach(character -> character.look(start)));
		for (Watched character : watched)
		{
			character._standing = character.ticks();
			character._player.sitDown();
		}
		Arena.run(POSTURE_MILLIS, () -> watched.forEach(character -> character.look(start)));

		final List<String> lines = new ArrayList<>();
		lines.add("# LOCK-TIMER-0001: every class at the lowest and highest level it can have, made by the server, naked, in the arena, set to 1 HP, MP and CP at 0 ms, standing still until " + POSTURE_MILLIS + " ms, then sitting until " + (2 * POSTURE_MILLIS) + " ms.");
		lines.add("# Each regeneration tick is <ms>:+<hp>/+<mp>/+<cp>, the gain of that tick to six decimals; a run of equal ticks at a fixed interval is <first>..<last>/<interval>:+<hp>/+<mp>/+<cp>.");
		lines.add("class\tlevel\tmaxHp\tmaxMp\tmaxCp\tstanding\tsitting");
		for (Watched character : watched)
		{
			final Player player = character._player;
			lines.add(player.getPlayerClass() + "\t" + player.getLevel() + "\t" + player.getMaxHp() + "\t" + player.getMaxMp() + "\t" + player.getMaxCp() + "\t" + character._standing + "\t" + character.ticks());
			player.deleteMe();
		}
		Golden.repository().assertMatches("timer/class-regeneration.tsv", lines);
	}

	/**
	 * LOCK-TIMER-0002: every spawn of the datapack whose NPCs can be attacked and come back, its NPCs taken out of the world at one instant the way a corpse decays, the world then run until they are all back or an hour has passed. Respawn delays with a random part, and spawns that place their NPC at random in a territory, draw on the stream (seed, "lock.timer.respawn", 0).
	 */
	@Test
	@Order(2)
	void everySpawnRespawnsAsRecorded()
	{
		final List<Spawn> spawns = new ArrayList<>();
		for (Set<Spawn> ofNpc : SpawnTable.getInstance().getSpawnTable().values())
		{
			for (Spawn spawn : ofNpc)
			{
				if (spawn.isRespawnEnabled() && !spawn.getSpawnedNpcs().isEmpty() && spawn.getSpawnedNpcs().peekFirst().isAttackable())
				{
					spawns.add(spawn);
				}
			}
		}
		spawns.sort(Comparator.comparingInt(Spawn::getId).thenComparing(TimerLockTest::place).thenComparingInt(Spawn::getRespawnMinDelay).thenComparingInt(Spawn::getRespawnMaxDelay));

		// Every NPC of those spawns, taken before any is removed: removing one can take others with it, a master its minions.
		final List<List<Npc>> removed = new ArrayList<>();
		final Set<Integer> removedIds = new HashSet<>();
		for (Spawn spawn : spawns)
		{
			final List<Npc> npcs = new ArrayList<>(spawn.getSpawnedNpcs());
			removed.add(npcs);
			npcs.forEach(npc -> removedIds.add(npc.getObjectId()));
		}

		// When each removed NPC came back, by object id: the server brings back the same object. Other NPCs spawning meanwhile (minions with their masters, a group's replacements) are not what is asked.
		final Map<Integer, String> back = new HashMap<>();
		final long[] start = new long[1];
		final ConsumerEventListener listener = new ConsumerEventListener(Containers.Global(), EventType.ON_NPC_SPAWN, (OnNpcSpawn event) ->
		{
			final Npc npc = event.getNpc();
			if (removedIds.contains(npc.getObjectId()) && !back.containsKey(npc.getObjectId()))
			{
				back.put(npc.getObjectId(), (GameClock.millis() - start[0]) + (isHome(npc) ? "" : "@" + npc.getX() + "," + npc.getY() + "," + npc.getZ()));
				if (npc.getSpawn().getSpawnTerritory() != null)
				{
					TERRITORY_HEADINGS.add(npc.getHeading());
				}
			}
		}, TimerLockTest.class);

		final List<String> lines = new ArrayList<>();
		lines.add("# LOCK-TIMER-0002: every datapack spawn of attackable NPCs that respawn, its NPCs removed at 0 ms as a decaying corpse is, on a stream of the spawn's own (" + SEED + ", \"lock.timer.respawn <npc> <place>\", occurrence),");
		lines.add("# then the world run until all are back or " + RESPAWN_LIMIT_MILLIS + " ms have passed, on (" + SEED + ", \"lock.timer.respawn\", 0). The delay's random part is drawn at the removal and the respawn itself on the same stream, so one spawn's change moves no other spawn's line.");
		lines.add("# place is the spawn's territory or the spot the datapack gives it; delay its respawn delay in ms, min..max when it has a random part. back is <ms> for each NPC of the spawn back where it belongs (inside its territory,");
		lines.add("# or on its spot give or take the random spawn system's offset, which the truth profile switches off), <ms>@<x>,<y>,<z> for one back anywhere else, - for one still away at the end.");
		lines.add("npc\tname\tplace\tdelay\tgroup\tback");

		Containers.Global().addListener(listener);
		start[0] = GameClock.millis();
		final Map<String, Integer> occurrences = new HashMap<>();
		for (int i = 0; i < spawns.size(); i++)
		{
			final String identity = spawns.get(i).getId() + " " + place(spawns.get(i));
			final List<Npc> npcs = removed.get(i);
			// One already taken out by an earlier removal decays only once.
			_server.withRandom(KeyedRandom.of(SEED, "lock.timer.respawn " + identity, occurrences.merge(identity, 1, Integer::sum)), () -> npcs.forEach(Npc::deleteMe));
		}
		_server.withRandom(KeyedRandom.of(SEED, "lock.timer.respawn", 0), () -> Arena.runWhile(() -> back.size() < removedIds.size(), RESPAWN_LIMIT_MILLIS));
		listener.unregisterMe();

		for (int i = 0; i < spawns.size(); i++)
		{
			final Spawn spawn = spawns.get(i);
			final StringJoiner returns = new StringJoiner(" ");
			for (Npc npc : removed.get(i))
			{
				returns.add(back.getOrDefault(npc.getObjectId(), "-"));
			}
			lines.add(spawn.getId() + "\t" + spawn.getTemplate().getName() + "\t" + place(spawn) + "\t" + (spawn.hasRespawnRandom() ? spawn.getRespawnMinDelay() + ".." + spawn.getRespawnMaxDelay() : String.valueOf(spawn.getRespawnMinDelay())) + "\t" + (spawn.getGroup() == null ? "-" : "group") + "\t" + returns);
		}
		Golden.repository().assertMatches("timer/spawn-respawn.tsv", lines);
	}

	/**
	 * A spawn that places its NPCs at random in a territory turns each of them a random way (CORE_PATCHES.md CP-0005). The -1 that asks for that used to be clamped to 0 on the way into the spawn, and every such NPC faced due east.
	 */
	@Test
	@Order(3)
	void territoryNpcsComeBackFacingAnyWay()
	{
		assertFalse(TERRITORY_HEADINGS.isEmpty(), "no NPC of a territory spawn came back");
		final long east = TERRITORY_HEADINGS.stream().filter(heading -> heading == 0).count();
		assertTrue((east * 100) < TERRITORY_HEADINGS.size(), east + " of " + TERRITORY_HEADINGS.size() + " NPCs of territory spawns came back facing due east");
		assertTrue(new HashSet<>(TERRITORY_HEADINGS).size() > (TERRITORY_HEADINGS.size() / 2), "the NPCs of territory spawns came back facing only " + new HashSet<>(TERRITORY_HEADINGS).size() + " ways");
	}

	/**
	 * An NPC turns idly exactly while it stands in an awake region (CORE_PATCHES.md CP-0007): it is put on the idle-animation list when it spawns into an awake region or walks into one, and taken off when it walks into a sleeping one or decays. The list used to know only the NPCs a region held when it woke, and kept them for good, deleted ones too.
	 */
	@Test
	@Order(4)
	void anNpcTurnsIdlyExactlyWhileItStandsInAnAwakeRegion() throws ReflectiveOperationException
	{
		final Field field = RandomAnimationTaskManager.class.getDeclaredField("PENDING_ANIMATIONS");
		field.setAccessible(true);
		final Map<?, ?> pending = (Map<?, ?>) field.get(null);

		Arena.wake();
		final Npc npc = Arena.spawn(NpcData.getInstance().getTemplate(MALUK_WARLORD));
		assertTrue(pending.containsKey(npc), "an NPC spawned into an awake region is not on the idle-animation list");

		// Far out over the sea, where no player is: a sleeping region.
		npc.setXYZ(Arena.X + 60_000, Arena.Y, Arena.Z);
		assertFalse(pending.containsKey(npc), "an NPC that walked into a sleeping region is still on the list");
		npc.setXYZ(Arena.X, Arena.Y, Arena.Z);
		assertTrue(pending.containsKey(npc), "an NPC that walked back into the awake arena is not on the list");

		npc.deleteMe();
		assertFalse(pending.containsKey(npc), "a deleted NPC is still on the list");
	}

	/**
	 * Whether {@code npc} stands where its spawn puts it: inside the spawn's territory, or on its spot give or take the offset the random spawn system may add (Custom/RandomSpawns.ini).
	 */
	private static boolean isHome(Npc npc)
	{
		final Spawn spawn = npc.getSpawn();
		if (spawn.getSpawnTerritory() != null)
		{
			// As the territory picks its points: a territory may ban only a band of heights over part of it (CORE_PATCHES.md CP-0006).
			return spawn.getSpawnTerritory().holds(npc.getX(), npc.getY(), npc.getZ());
		}
		final Location spot = spot(spawn);
		final int slack = RandomSpawnsConfig.ENABLE_RANDOM_MONSTER_SPAWNS ? RandomSpawnsConfig.MOB_MAX_SPAWN_RANGE : 0;
		return (Math.abs(npc.getX() - spot.getX()) <= slack) && (Math.abs(npc.getY() - spot.getY()) <= slack);
	}

	/**
	 * Where a spawn puts its NPCs: the territory it picks from, or the spot the datapack gives it.
	 */
	private static String place(Spawn spawn)
	{
		if (spawn.getSpawnTerritory() != null)
		{
			return spawn.getSpawnTerritory().getName();
		}
		final Location spot = spot(spawn);
		return spot.getX() + "," + spot.getY() + "," + spot.getZ();
	}

	/**
	 * The spot the datapack gives a spawn. The spawn's own coordinates are not it: the random spawn system moves them to each NPC's random point.
	 */
	private static Location spot(Spawn spawn)
	{
		return spawn.getSpawnLocation() != null ? spawn.getSpawnLocation() : new Location(spawn.getX(), spawn.getY(), spawn.getZ());
	}

	/**
	 * One character being watched regenerate: its HP, MP and CP as last seen, and every tick that changed them.
	 */
	private static final class Watched
	{
		final Player _player;
		final List<Long> _times = new ArrayList<>();
		final List<String> _gains = new ArrayList<>();
		String _standing;
		private double _hp = Double.NaN;
		private double _mp;
		private double _cp;

		Watched(Player player)
		{
			_player = player;
		}

		void look(long start)
		{
			final double hp = _player.getCurrentHp();
			final double mp = _player.getCurrentMp();
			final double cp = _player.getCurrentCp();
			if (!Double.isNaN(_hp) && ((hp != _hp) || (mp != _mp) || (cp != _cp)))
			{
				_times.add(GameClock.millis() - start);
				// Six decimals: the gains are sums of doubles, whose last digits are noise of the addition, not of the formula.
				_gains.add(String.format(Locale.ROOT, "+%.6f/+%.6f/+%.6f", hp - _hp, mp - _mp, cp - _cp));
			}
			_hp = hp;
			_mp = mp;
			_cp = cp;
		}

		/**
		 * The ticks seen since the last call, runs of equal gains at a fixed interval folded into one.
		 */
		String ticks()
		{
			final StringJoiner ticks = new StringJoiner(" ");
			int i = 0;
			while (i < _gains.size())
			{
				int j = i;
				while (((j + 1) < _gains.size()) && _gains.get(j + 1).equals(_gains.get(i)) && ((j == i) || ((_times.get(j + 1) - _times.get(j)) == (_times.get(i + 1) - _times.get(i)))))
				{
					j++;
				}
				ticks.add(j == i ? _times.get(i) + ":" + _gains.get(i) : _times.get(i) + ".." + _times.get(j) + "/" + (_times.get(i + 1) - _times.get(i)) + ":" + _gains.get(i));
				i = j + 1;
			}
			_times.clear();
			_gains.clear();
			return ticks.length() == 0 ? "-" : ticks.toString();
		}
	}
}
