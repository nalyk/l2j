package org.l2jmobius.gameserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.livingchronicle.clock.GameScheduler;
import org.l2jmobius.gameserver.livingchronicle.rng.KeyedRandom;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureAttack;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureAttackAvoid;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDamageDealt;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDamageReceived;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureSkillUse;
import org.l2jmobius.gameserver.mechanics.events.listeners.AbstractEventListener;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.mechanics.events.listeners.FunctionEventListener;
import org.l2jmobius.gameserver.mechanics.events.returns.TerminateReturn;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.testing.Arena;
import org.l2jmobius.testing.Golden;
import org.l2jmobius.testing.TestGear;
import org.l2jmobius.testing.TestGear.Piece;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * LOCK-AI: what every monster and raid boss decides to do when a player comes near.
 * <p>
 * Each case spawns the NPC in the {@link Arena} the way the datapack spawns one (its own class, its minions, its scripts, its AI on) and sets an opponent, made fresh for the case, {@value #DISTANCE} in front of it. The opponent stands still. If the NPC has neither swung nor cast at it after {@value #PROVOKE_AT_MILLIS} ms, the opponent attacks it, the way a client's attack request does, until one of its hits lands for damage, and stands still again: a miss or a hit for nothing does not tell an NPC it was attacked. Everything that happens in {@value #CASE_MILLIS} ms of game time is recorded: when the NPC turns on the opponent, every intention it takes, every skill it casts, every hit and miss the opponent takes from it or from anything else.
 * </p>
 * <p>
 * Monsters all meet the same opponent, a level {@value #LEVEL} Phoenix Knight in Vesper heavy armour with sword and shield. A raid boss meets a character of its own level, of the Phoenix Knight's line, holding a Long Sword, which has no grade and so no penalty at any level: one more than eight levels above the boss would be petrified by the raid curse at its first hit, and the boss would never learn it had been attacked.
 * </p>
 * <p>
 * The cases run one after another in one world, monsters first, and each leaves the arena swept for the next; each owns its randomness.
 * </p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiLockTest
{
	private static final long START = 1_794_216_090_000L;
	private static final long SEED = 1;

	private static final int LEVEL = 85;

	/** How far east of the NPC the opponent stands: inside the smallest aggro range of the datapack (100). */
	private static final int DISTANCE = 80;

	private static final long PROVOKE_AT_MILLIS = 10_000;
	private static final long CASE_MILLIS = 20_000;

	private static final List<Piece> MONSTER_OPPONENT_GEAR = new ArrayList<>();
	static
	{
		MONSTER_OPPONENT_GEAR.addAll(TestGear.WEAPONS.get(0).pieces()); // sword
		MONSTER_OPPONENT_GEAR.addAll(TestGear.SHIELD.pieces());
		MONSTER_OPPONENT_GEAR.addAll(TestGear.HEAVY.pieces());
	}

	private static final int LONG_SWORD = 2;

	private static final String HEADER = "id\tname\tlevel\tai\taggroRange\tvs\tfirstAction\tstruck\tstrikes\ttrace\tswings\topponent\tnpc\tfromHome\tintention\tothers";

	private static TestServer _server;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.bootPaused(START, SEED);
		Arena.checkEmpty();

		Arena.wake();
	}

	/** Who every monster meets. */
	private static final IntFunction<Player> MONSTER_OPPONENT = level ->
	{
		final Player opponent = TestPlayers.create(PlayerClass.PHOENIX_KNIGHT, LEVEL);
		TestGear.wear(opponent, MONSTER_OPPONENT_GEAR);
		return opponent;
	};

	/** Who a raid boss of {@code level} meets. */
	private static final IntFunction<Player> RAID_BOSS_OPPONENT = level ->
	{
		final Player opponent = ofKnightLine(Math.min(level, LEVEL));
		TestGear.wear(opponent, List.of(new Piece(LONG_SWORD, 1)));
		return opponent;
	};

	/** An aggressive monster that walks up to its opponent, and a raid boss its opponent walks up to: cases where actors move. */
	private static final int BLOOD_QUEEN = 18001;
	private static final int REMMEL = 25041;

	@Test
	@Order(1)
	void everyMonsterMeetsAPlayerAsRecorded()
	{
		meetAll("Monster", "ai/monster-meets-player.tsv", "LOCK-AI-0001", "a level " + LEVEL + " Phoenix Knight in Vesper heavy armour with sword and shield", MONSTER_OPPONENT);
	}

	@Test
	@Order(2)
	void everyRaidBossMeetsAPlayerAsRecorded()
	{
		meetAll("RaidBoss", "ai/raid-boss-meets-player.tsv", "LOCK-AI-0002", "a character of the Phoenix Knight's line at the boss's level (" + LEVEL + " at most) holding a Long Sword", RAID_BOSS_OPPONENT);
	}

	/**
	 * A case whose actors walk comes out the same after the world has gone on by itself for ten minutes and its stream has been drawn on a thousand times more. Walking and thinking are done by the server's many-actor loops, which tell a case's actors from the world's (CaseStreams, ActorStreams); without that, everything an actor did after arriving drew on the world's stream.
	 */
	@Test
	@Order(4)
	void aCaseWhoseActorsWalkComesOutTheSameAfterTheWorldHasMovedOn()
	{
		for (int[] npcAndRaid : new int[][]
		{
			{
				BLOOD_QUEEN,
				0
			},
			{
				REMMEL,
				1
			}
		})
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(npcAndRaid[0]);
			final IntFunction<Player> opponents = npcAndRaid[1] == 0 ? MONSTER_OPPONENT : RAID_BOSS_OPPONENT;
			Arena.wake();
			final String before = meet(template, opponents);
			GameScheduler.advance(600_000);
			for (int draw = 0; draw < 1000; draw++)
			{
				Rnd.nextLong();
			}
			Arena.wake();
			assertEquals(before, meet(template, opponents));
		}
	}

	/**
	 * The sweeps take the world through most of a game day. The bot report reset at 00:00 once kept rescheduling itself for the present and stopped the world there (CORE_PATCHES.md CP-0004); this says the sweeps still pass a reset, and that nothing the world ran on the way failed.
	 */
	@Test
	@Order(3)
	void theWorldRanThroughMidnightWithoutAFailedTask()
	{
		final ZonedDateTime start = Instant.ofEpochMilli(START).atZone(ZoneId.systemDefault());
		ZonedDateTime reset = start.withHour(Integer.parseInt(GeneralConfig.BOTREPORT_RESETPOINT_HOUR[0])).withMinute(Integer.parseInt(GeneralConfig.BOTREPORT_RESETPOINT_HOUR[1])).withSecond(0).withNano(0);
		if (!reset.isAfter(start))
		{
			reset = reset.plusDays(1);
		}
		assertTrue(GameClock.millis() > reset.toInstant().toEpochMilli(), "the sweeps ended at " + Instant.ofEpochMilli(GameClock.millis()) + ", before the bot report reset at " + reset);
		assertEquals(List.of(), GameScheduler.failures());
	}

	/**
	 * A character of the Phoenix Knight's line at {@code level}: the class of that line a character of that level would be.
	 */
	private static Player ofKnightLine(int level)
	{
		for (PlayerClass playerClass = PlayerClass.PHOENIX_KNIGHT; playerClass != null; playerClass = playerClass.getParent())
		{
			final int[] range = TestPlayers.levelRange(playerClass);
			if ((level >= range[0]) && (level <= range[1]))
			{
				return TestPlayers.create(playerClass, level);
			}
		}
		throw new IllegalArgumentException("no class of the Phoenix Knight's line is level " + level);
	}

	private static void meetAll(String type, String golden, String lock, String opponentDescription, IntFunction<Player> opponents)
	{
		final List<NpcTemplate> templates = new ArrayList<>(NpcData.getInstance().getTemplates(template -> template.isType(type)));
		templates.sort(Comparator.comparingInt(NpcTemplate::getId));

		final List<String> lines = new ArrayList<>();
		lines.add("# " + lock + ": every template of type " + type + ", spawned in the arena as the datapack spawns one, meeting " + opponentDescription + " (vs) " + DISTANCE + " in front of it, on the stream (" + SEED + ", \"lock.ai.meet\", npc id).");
		lines.add("# The opponent stands still; at " + PROVOKE_AT_MILLIS + " ms (struck), if the NPC has not yet swung or cast at it, it attacks the NPC until one of its hits lands for damage, strikes counting its swings. The case lasts " + CASE_MILLIS + " ms of game time, millis counted from the opponent's arrival.");
		lines.add("# Trace: I=intention as it changes, S<id>-<level>@<target> a skill cast begun (p opponent, s self, o other, - none), <damage>[!] and miss an auto-attack of the NPC's landing on the opponent, S<id>=<damage> its skill damage, dot=<damage> damage over time,");
		lines.add("# o=<damage> and o=miss anything else's, P=<damage> or P=miss the opponent's strike, dead the NPC dying, opponent-dead the opponent dying. A hit for 0 damage notifies nothing; swings counts every auto-attack the NPC began at the opponent.");
		lines.add("# firstAction is the first swing, cast or damage of the NPC's at the opponent. opponent is its HP/CP at the end, npc the NPC's HP/max HP, fromHome how far it ended from where it spawned, others how many other NPCs (minions, summons, script spawns) were around the arena at the end.");
		lines.add(HEADER);
		for (NpcTemplate template : templates)
		{
			try
			{
				lines.add(meet(template, opponents));
			}
			catch (RuntimeException | AssertionError e)
			{
				throw new AssertionError("the case of " + template.getId() + " " + template.getName() + " failed", e);
			}
		}
		Golden.repository().assertMatches(golden, lines);
	}

	private static String meet(NpcTemplate template, IntFunction<Player> opponents)
	{
		final StringBuilder line = new StringBuilder();
		Arena.align();
		_server.withRandom(KeyedRandom.of(SEED, "lock.ai.meet", template.getId()), () ->
		{
			final Player opponent = opponents.apply(template.getLevel());
			opponent.setCurrentHpMp(opponent.getMaxHp(), opponent.getMaxMp());
			opponent.setCurrentCp(opponent.getMaxCp());
			line.append(template.getId() + "\t" + template.getName() + "\t" + template.getLevel() + "\t" + template.getAIType() + "\t" + template.getAggroRange() + "\t" + opponent.getPlayerClass() + "@" + opponent.getLevel() + "\t" + new Meeting(template, opponent).play());
		});
		return line.toString();
	}

	/**
	 * One NPC meeting one opponent: who they are, what is listened to, and what was heard.
	 */
	private static final class Meeting
	{
		private final Npc _npc;
		private final Player _opponent;
		private final List<AbstractEventListener> _listeners = new ArrayList<>();
		private final List<String> _trace = new ArrayList<>();
		private final long _start;

		private long _firstAction = -1;
		private long _struck = -1;
		private int _swings;
		private int _opponentSwings;
		private boolean _landed;
		private Intention _intention;
		private boolean _npcDead;
		private boolean _opponentDead;

		Meeting(NpcTemplate template, Player opponent)
		{
			_opponent = opponent;
			_npc = Arena.spawn(template);
			listen();
			Arena.placeInFront(_opponent, _npc, DISTANCE);
			_start = GameClock.millis();
		}

		private void listen()
		{
			listen(_npc, new ConsumerEventListener(_npc, EventType.ON_CREATURE_ATTACK, (OnCreatureAttack event) ->
			{
				if (event.getTarget() == _opponent)
				{
					_swings++;
					acted();
				}
			}, AiLockTest.class));
			listen(_npc, new ConsumerEventListener(_npc, EventType.ON_CREATURE_SKILL_USE, (OnCreatureSkillUse event) ->
			{
				final Creature target = event.getTarget();
				trace("S" + event.getSkill().getId() + "-" + event.getSkill().getLevel() + "@" + (target == null ? "-" : target == _opponent ? "p" : target == _npc ? "s" : "o"));
				if (target == _opponent)
				{
					acted();
				}
			}, AiLockTest.class));

			// Everything that hurts the opponent, from the NPC or from anything else. The server tells of it asynchronously, in the same instant.
			listen(_opponent, new ConsumerEventListener(_opponent, EventType.ON_CREATURE_DAMAGE_RECEIVED, (OnCreatureDamageReceived event) ->
			{
				final Skill skill = event.getSkill();
				final long damage = Math.round(event.getDamage());
				if (event.getAttacker() != _npc)
				{
					trace("o=" + damage);
				}
				else
				{
					trace(event.isDamageOverTime() ? "dot=" + damage : skill != null ? "S" + skill.getId() + "=" + damage : damage + (event.isCritical() ? "!" : ""));
					acted();
				}
			}, AiLockTest.class));
			listen(_opponent, new ConsumerEventListener(_opponent, EventType.ON_CREATURE_ATTACK_AVOID, (OnCreatureAttackAvoid event) ->
			{
				if (!event.isDamageOverTime())
				{
					trace(event.getAttacker() == _npc ? "miss" : "o=miss");
				}
			}, AiLockTest.class));

			// The opponent's strike: the attack request starts it, and once a hit has landed every further swing is refused, which leaves the opponent standing.
			listen(_opponent, new FunctionEventListener(_opponent, EventType.ON_CREATURE_ATTACK, (OnCreatureAttack event) ->
			{
				if (_landed)
				{
					return new TerminateReturn(true, false, false);
				}
				_opponentSwings++;
				return null;
			}, AiLockTest.class));
			listen(_opponent, new ConsumerEventListener(_opponent, EventType.ON_CREATURE_DAMAGE_DEALT, (OnCreatureDamageDealt event) ->
			{
				if ((event.getTarget() == _npc) && (event.getSkill() == null) && !event.isDamageOverTime())
				{
					_landed = true;
					trace("P=" + Math.round(event.getDamage()) + (event.isCritical() ? "!" : ""));
				}
			}, AiLockTest.class));
			listen(_npc, new ConsumerEventListener(_npc, EventType.ON_CREATURE_ATTACK_AVOID, (OnCreatureAttackAvoid event) ->
			{
				if ((event.getAttacker() == _opponent) && !event.isDamageOverTime())
				{
					trace("P=miss");
				}
			}, AiLockTest.class));
		}

		private void listen(Creature creature, AbstractEventListener listener)
		{
			creature.addListener(listener);
			_listeners.add(listener);
		}

		private long now()
		{
			return GameClock.millis() - _start;
		}

		private void trace(String what)
		{
			_trace.add(now() + ":" + what);
		}

		private void acted()
		{
			if (_firstAction < 0)
			{
				_firstAction = now();
			}
		}

		/**
		 * After each step of the world: what changed in the NPC's mind and in who is alive.
		 */
		private void observe()
		{
			// Asking an NPC without an AI for one would give it a new one; an AI it has dropped reads as none.
			final Intention intention = _npc.hasAI() ? _npc.getAI().getIntention() : null;
			if (intention != _intention)
			{
				_intention = intention;
				trace("I=" + (intention == null ? "none" : intention));
			}
			if (!_npcDead && _npc.isDead())
			{
				_npcDead = true;
				trace("dead");
			}
			if (!_opponentDead && _opponent.isDead())
			{
				_opponentDead = true;
				trace("opponent-dead");
			}
		}

		String play()
		{
			observe();
			Arena.run(PROVOKE_AT_MILLIS, this::observe);
			if ((_firstAction < 0) && !_opponentDead && !_npcDead)
			{
				_struck = now();
				_opponent.getAI().setIntentionAttack(_npc);
				observe();
			}
			Arena.run(CASE_MILLIS - PROVOKE_AT_MILLIS, this::observe);

			final int others = (int) World.getVisibleObjects(_opponent, Npc.class).stream().filter(npc -> npc != _npc).count();
			final String result = String.join("\t", //
				_firstAction < 0 ? "-" : String.valueOf(_firstAction), //
				_struck < 0 ? "-" : String.valueOf(_struck), //
				String.valueOf(_opponentSwings), //
				_trace.isEmpty() ? "-" : String.join(" ", _trace), //
				String.valueOf(_swings), //
				_opponentDead ? "dead" : ((int) _opponent.getCurrentHp() + "/" + (int) _opponent.getCurrentCp()), //
				(int) _npc.getCurrentHp() + "/" + _npc.getMaxHp(), //
				String.valueOf((int) _npc.calculateDistance2D(_npc.getSpawn().getSpawnLocation())), //
				_intention == null ? "none" : _intention.toString(), //
				String.valueOf(others));

			for (AbstractEventListener listener : _listeners)
			{
				listener.unregisterMe();
			}
			_opponent.deleteMe();
			_npc.deleteMe();
			Arena.sweep();
			return result;
		}
	}
}
