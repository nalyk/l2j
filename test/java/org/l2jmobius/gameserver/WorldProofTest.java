package org.l2jmobius.gameserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.NpcConfig;
import org.l2jmobius.gameserver.config.custom.RandomSpawnsConfig;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.entity.zone.form.ZoneNPoly;
import org.l2jmobius.gameserver.entity.zone.type.NpcSpawnTerritory;
import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.livingchronicle.clock.GameScheduler;
import org.l2jmobius.gameserver.livingchronicle.rng.KeyedRandom;
import org.l2jmobius.gameserver.taskmanagers.RandomAnimationTaskManager;
import org.l2jmobius.testing.Arena;
import org.l2jmobius.testing.TestServer;

/**
 * Proofs that need a world: of core patches through the server's own paths (spawning, the AI's own decisions, the idle-animation manager's own loop), and of the lock harness's isolation where the world's managers act on a case's actors.
 * <p>
 * Each is written to fail with its fix reverted. The territories are made here, over the {@link Arena}, so that nothing of the datapack's world stands in them.
 * </p>
 */
class WorldProofTest
{
	private static final long START = 1_794_216_090_000L;
	private static final long SEED = 1;
	private static final int MALUK_WARLORD = 22839;

	/** Height band of the territories made here; the arena lies at -3500, inside it. */
	private static final int LOW = -3600;
	private static final int HIGH = -3000;

	/** Above the band a ban covers, inside the territory's own band: where a creature may legally stand over the ban. */
	private static final int ABOVE_THE_BAN = -3300;

	private static TestServer _server;
	private static NpcTemplate _template;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.bootPaused(START, SEED);
		Arena.checkEmpty();
		_template = NpcData.getInstance().getTemplate(MALUK_WARLORD);
	}

	private static ZoneNPoly square(int halfWidth, int low, int high)
	{
		return new ZoneNPoly(new int[]
		{
			Arena.X - halfWidth,
			Arena.X + halfWidth,
			Arena.X + halfWidth,
			Arena.X - halfWidth
		}, new int[]
		{
			Arena.Y - halfWidth,
			Arena.Y - halfWidth,
			Arena.Y + halfWidth,
			Arena.Y + halfWidth
		}, low, high);
	}

	/**
	 * A territory 6,000 wide over the arena, with a ban over its middle 1,000 that covers only the band below -3400: as the Swamp of Screams bans what lies under the swamp.
	 */
	private static NpcSpawnTerritory bannedBelow()
	{
		final NpcSpawnTerritory territory = new NpcSpawnTerritory("proof_banned_below", square(3000, LOW, HIGH));
		territory.addBannedTerritory(square(500, -3700, -3400));
		return territory;
	}

	private static Spawn spawnIn(NpcSpawnTerritory territory)
	{
		try
		{
			final Spawn spawn = new Spawn(_template);
			spawn.setSpawnTerritory(territory);
			spawn.setAmount(1);
			spawn.stopRespawn();
			return spawn;
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/**
	 * An NPC of a territory with a height-banded ban, standing over the ban: in the ban's area on the map, above its band.
	 */
	private static Npc overTheBan(NpcSpawnTerritory territory)
	{
		final Npc npc = spawnIn(territory).doSpawn(false);
		npc.setXYZ(Arena.X, Arena.Y, ABOVE_THE_BAN);
		assertTrue(territory.holds(npc.getX(), npc.getY(), npc.getZ()));
		return npc;
	}

	/**
	 * CP-0006, the AI deciding whether to wake: an idle NPC over a height-banded ban, with no player near, is where its territory put it and goes back to sleep. The flat test woke it as having strayed.
	 */
	@Test
	void cp0006AnNpcOverABanIsNotWokenAsStrayed()
	{
		Arena.wake();
		final Npc npc = overTheBan(bannedBelow());
		npc.getAI().setIntentionIdle();
		assertFalse(npc.hasAI(), "the AI was kept awake: it judged the NPC outside its territory");
		npc.deleteMe();
	}

	/**
	 * CP-0006, the AI deciding whether to go home: an NPC over a height-banded ban, thinking with nothing to fight, stays. The flat test sent it home.
	 */
	@Test
	void cp0006AnNpcOverABanIsNotSentHome()
	{
		Arena.wake();
		final Npc npc = overTheBan(bannedBelow());
		npc.getAI().setIntentionActive();
		npc.getAI().notifyActionThink();
		assertEquals(Intention.ACTIVE, npc.getAI().getIntention(), "the AI sent the NPC home from a place its territory put it");
		npc.deleteMe();
	}

	/**
	 * CP-0006, the AI deciding whether a fighting NPC has strayed (with the distance check switched on, as a server may have it): one over a height-banded ban, far from its spawn point but inside its territory, keeps fighting. The flat test broke off its fight and sent it home.
	 */
	@Test
	void cp0006AFightingNpcOverABanHasNotStrayed()
	{
		final boolean checked = NpcConfig.AGGRO_DISTANCE_CHECK_ENABLED;
		NpcConfig.AGGRO_DISTANCE_CHECK_ENABLED = true;
		try
		{
			Arena.wake();
			final NpcSpawnTerritory territory = bannedBelow();
			final Npc npc = overTheBan(territory);
			npc.getSpawn().setXYZ(Arena.X + 2500, Arena.Y, ABOVE_THE_BAN);
			final Monster enemy = Arena.monster(MALUK_WARLORD);
			npc.asAttackable().addDamageHate(enemy, 0, 100);
			npc.getAI().setIntentionAttack(enemy);
			npc.getAI().notifyActionThink();
			assertNotEquals(Intention.MOVE_TO, npc.getAI().getIntention(), "the AI broke off the fight: it judged the NPC strayed out of its territory");
			npc.deleteMe();
			enemy.deleteMe();
		}
		finally
		{
			NpcConfig.AGGRO_DISTANCE_CHECK_ENABLED = checked;
		}
	}

	/**
	 * CP-0005, with the random spawn system on as upstream ships it: a territory's NPCs appear inside their territory, facing any way; an NPC on a fixed spot is moved by up to the configured range at every spawn, as before, and faces any way.
	 */
	@Test
	void cp0005RandomSpawnsMoveFixedSpotsAndLeaveTerritoriesAlone()
	{
		final boolean was = RandomSpawnsConfig.ENABLE_RANDOM_MONSTER_SPAWNS;
		final Set<Integer> exempt = RandomSpawnsConfig.MOBS_LIST_NOT_RANDOM;
		RandomSpawnsConfig.ENABLE_RANDOM_MONSTER_SPAWNS = true;
		// The loader reads the list of monsters the system leaves alone only when it starts switched on; the Maluk Warlord is not on it.
		RandomSpawnsConfig.MOBS_LIST_NOT_RANDOM = Set.of();
		try
		{
			// A territory no wider than the random spawn system's own offset: an offset added to its points would take most outside it.
			final NpcSpawnTerritory small = new NpcSpawnTerritory("proof_small", square(100, LOW, HIGH));
			final Spawn territorial = spawnIn(small);
			int outside = 0;
			final Set<Integer> territoryHeadings = new HashSet<>();
			for (int i = 0; i < 50; i++)
			{
				final Npc npc = territorial.doSpawn(false);
				if (!small.holds(npc.getX(), npc.getY(), npc.getZ()))
				{
					outside++;
				}
				territoryHeadings.add(npc.getHeading());
				npc.deleteMe();
			}
			assertEquals(0, outside, "NPCs of the territory appeared outside it");
			assertTrue(territoryHeadings.size() > 25, "NPCs of the territory faced only " + territoryHeadings.size() + " ways in 50 spawns");

			final Spawn fixed = spawnIn(null);
			fixed.setXYZ(Arena.X, Arena.Y, Arena.Z);
			fixed.setHeading(0);
			int moved = 0;
			final Set<Integer> fixedHeadings = new HashSet<>();
			for (int i = 0; i < 50; i++)
			{
				final Npc npc = fixed.doSpawn(false);
				final int dx = npc.getX() - Arena.X;
				final int dy = npc.getY() - Arena.Y;
				assertTrue((Math.abs(dx) <= RandomSpawnsConfig.MOB_MAX_SPAWN_RANGE) && (Math.abs(dy) <= RandomSpawnsConfig.MOB_MAX_SPAWN_RANGE), "moved " + dx + "," + dy);
				if ((dx != 0) || (dy != 0))
				{
					moved++;
				}
				fixedHeadings.add(npc.getHeading());
				npc.deleteMe();
			}
			assertTrue(moved > 40, "the random spawn system moved only " + moved + " of 50 spawns on a fixed spot");
			assertTrue(fixedHeadings.size() > 25, "moved NPCs faced only " + fixedHeadings.size() + " ways in 50 spawns");
		}
		finally
		{
			RandomSpawnsConfig.ENABLE_RANDOM_MONSTER_SPAWNS = was;
			RandomSpawnsConfig.MOBS_LIST_NOT_RANDOM = exempt;
		}
	}

	/**
	 * CP-0007, under the race a server's threads can run: an NPC taken off the idle-animation list between the manager reading its entry and rescheduling it stays off. Here the NPC takes itself off from inside its own animation, which is that very moment.
	 */
	@Test
	void cp0007AnNpcTakenOffMidTurnStaysOff() throws ReflectiveOperationException
	{
		final Map<Npc, Long> pending = pendingAnimations();
		Arena.wake();
		final Monster npc = new Monster(_template)
		{
			@Override
			public void onRandomAnimation(int animationId)
			{
				RandomAnimationTaskManager.getInstance().remove(this);
			}
		};
		npc.setHeading(0);
		npc.spawnMe(Arena.X, Arena.Y, Arena.Z);
		assertTrue(pending.containsKey(npc), "an NPC spawned into an awake region is not on the list");

		// Its turn falls within a minute; the arena stays awake for 90 s after the waker left.
		GameScheduler.advance(70_000);
		assertFalse(pending.containsKey(npc), "the manager put back an NPC taken off the list during its turn");
		npc.deleteMe();
	}

	/**
	 * CP-0007, under the other race: a thread that held an NPC from before it left the world (a region waking, going through what it holds) puts it on the list after it has gone. It does not stay there.
	 */
	@Test
	void cp0007AGoneNpcPutOnLateDoesNotStay() throws ReflectiveOperationException
	{
		final Map<Npc, Long> pending = pendingAnimations();
		Arena.wake();
		final Monster npc = Arena.monster(MALUK_WARLORD);
		npc.deleteMe();
		RandomAnimationTaskManager.getInstance().add(npc);
		assertFalse(pending.containsKey(npc), "a deleted NPC was put back on the list by a late caller");
	}

	/**
	 * CP-0007, whatever race left it: an entry for an NPC no longer standing in an awake region is dropped when it falls due, not rescheduled.
	 */
	@Test
	void cp0007AStaleEntryIsDroppedWhenItFallsDue() throws ReflectiveOperationException
	{
		final Map<Npc, Long> pending = pendingAnimations();
		Arena.wake();
		final Monster npc = Arena.monster(MALUK_WARLORD);
		npc.deleteMe();
		pending.put(npc, GameClock.millis());
		GameScheduler.advance(2_000);
		assertFalse(pending.containsKey(npc), "the manager rescheduled an NPC that has left the world");
	}

	@SuppressWarnings("unchecked")
	private static Map<Npc, Long> pendingAnimations() throws ReflectiveOperationException
	{
		final Field field = RandomAnimationTaskManager.class.getDeclaredField("PENDING_ANIMATIONS");
		field.setAccessible(true);
		return (Map<Npc, Long>) field.get(null);
	}

	/**
	 * The harness: an NPC a case kills decays and comes back on the case's stream. The decay manager and the respawn manager serve the whole world, yet where in its territory the NPC comes back and which way it faces are the case's draws, whatever the world has drawn meanwhile.
	 */
	@Test
	void aDeathRespawnsOnTheStreamItWasOn()
	{
		final Spawn spawn = spawnIn(new NpcSpawnTerritory("proof_death", square(1000, LOW, HIGH)));
		spawn.setRespawnDelay(5);
		spawn.startRespawn();
		spawn.init();

		final String first = respawnAfter(spawn, "proof.death", npc -> npc.doDie(null));
		GameScheduler.advance(600_000);
		for (int draw = 0; draw < 1000; draw++)
		{
			Rnd.nextLong();
		}
		assertEquals(first, respawnAfter(spawn, "proof.death", npc -> npc.doDie(null)));

		spawn.stopRespawn();
		spawn.getLastSpawn().deleteMe();
	}

	/**
	 * The harness: an NPC a case removes comes back on the case's stream. Its spawn's respawn manager brings it back as part of the world, yet where in its territory it appears and which way it faces are the case's draws, whatever the world has drawn meanwhile.
	 */
	@Test
	void aRespawnDrawsOnTheStreamItsDeathWasOn()
	{
		final Spawn spawn = spawnIn(new NpcSpawnTerritory("proof_respawn", square(1000, LOW, HIGH)));
		spawn.setRespawnDelay(5);
		spawn.startRespawn();
		spawn.init();

		final String first = respawnAfter(spawn, "proof.respawn", Npc::deleteMe);
		GameScheduler.advance(600_000);
		for (int draw = 0; draw < 1000; draw++)
		{
			Rnd.nextLong();
		}
		assertEquals(first, respawnAfter(spawn, "proof.respawn", Npc::deleteMe));

		spawn.stopRespawn();
		spawn.getLastSpawn().deleteMe();
	}

	/**
	 * Takes the spawn's NPC out on a case's stream, then runs the world until it is back, alive.
	 */
	private static String respawnAfter(Spawn spawn, String stream, Consumer<Npc> takeOut)
	{
		final Npc npc = spawn.getLastSpawn();
		_server.withRandom(KeyedRandom.of(SEED, stream, 0), () -> takeOut.accept(npc));
		Arena.runWhile(() -> npc.isDead() || !npc.isSpawned(), 120_000);
		assertTrue(npc.isSpawned() && !npc.isDead(), "the NPC did not come back");
		return npc.getX() + "," + npc.getY() + "," + npc.getZ() + " facing " + npc.getHeading();
	}
}
