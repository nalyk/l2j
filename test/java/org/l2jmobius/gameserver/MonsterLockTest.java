package org.l2jmobius.gameserver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.livingchronicle.rng.KeyedRandom;
import org.l2jmobius.gameserver.mechanics.stats.Formulas;
import org.l2jmobius.testing.Golden;
import org.l2jmobius.testing.TestServer;

/**
 * LOCK-NPC and LOCK-COMBAT: what every monster is, and what happens when one hits another.
 * <p>
 * Each monster is built from its template the way a spawn builds it, in a world that is standing still, so its numbers are what the server's stat calculators make of the datapack and the configuration, and nothing else. The physical attack sequence is the one the server runs for an auto-attack (miss, shield, critical, damage), on a random stream of the matchup's own.
 * </p>
 */
class MonsterLockTest
{
	private static final long START = 1_794_216_090_000L;
	private static final long SEED = 1;
	
	/** One monster in this many is an attacker in the combat lock; every monster is in the stat lock. */
	private static final int ATTACKER_STRIDE = 20;
	private static final int TARGETS_PER_ATTACKER = 3;
	private static final int TRIALS = 3;
	
	private static TestServer _server;
	
	/** Every monster template, by id, with one instance built from it. */
	private static final Map<NpcTemplate, Monster> MONSTERS = new LinkedHashMap<>();
	
	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.bootPaused(START, SEED);
		
		final List<NpcTemplate> templates = new ArrayList<>(NpcData.getInstance().getTemplates(template -> template.isType("Monster")));
		templates.sort(Comparator.comparingInt(NpcTemplate::getId));
		for (NpcTemplate template : templates)
		{
			final Monster monster = new Monster(template);
			monster.setCurrentHpMp(monster.getMaxHp(), monster.getMaxMp());
			MONSTERS.put(template, monster);
		}
	}
	
	@Test
	void everyMonsterHasItsRecordedStats()
	{
		final List<String> lines = new ArrayList<>();
		lines.add("# LOCK-NPC-0001: every template of type Monster as the server builds it, unspawned, at full health.");
		lines.add("id\tname\tlevel\tmaxHp\tmaxMp\tpAtk\tpDef\tmAtk\tmDef\taccuracy\tevasion\tcritical\tmCritical\tpAtkSpd\tmAtkSpd\trunSpeed\twalkSpeed\tattackRange\tSTR\tDEX\tCON\tINT\tWIT\tMEN\texp\tsp");
		for (Map.Entry<NpcTemplate, Monster> entry : MONSTERS.entrySet())
		{
			final NpcTemplate template = entry.getKey();
			final Monster monster = entry.getValue();
			lines.add(String.join("\t", //
				String.valueOf(template.getId()), //
				template.getName(), //
				String.valueOf(monster.getLevel()), //
				String.valueOf(monster.getMaxHp()), //
				String.valueOf(monster.getMaxMp()), //
				String.valueOf(monster.getPAtk(null)), //
				String.valueOf(monster.getPDef(null)), //
				String.valueOf(monster.getMAtk(null, null)), //
				String.valueOf(monster.getMDef(null, null)), //
				String.valueOf(monster.getAccuracy()), //
				String.valueOf(monster.getEvasionRate(null)), //
				String.valueOf(monster.getCriticalHit(null, null)), //
				String.valueOf(monster.getMCriticalHit(null, null)), //
				String.valueOf(monster.getPAtkSpd()), //
				String.valueOf(monster.getMAtkSpd()), //
				String.valueOf(monster.getRunSpeed()), //
				String.valueOf(monster.getWalkSpeed()), //
				String.valueOf(monster.getPhysicalAttackRange()), //
				String.valueOf(monster.getSTR()), //
				String.valueOf(monster.getDEX()), //
				String.valueOf(monster.getCON()), //
				String.valueOf(monster.getINT()), //
				String.valueOf(monster.getWIT()), //
				String.valueOf(monster.getMEN()), //
				String.valueOf(template.getExp()), //
				String.valueOf(template.getSP())));
		}
		Golden.repository().assertMatches("npc/monster-stats.tsv", lines);
	}
	
	@Test
	void physicalAttacksBetweenMonstersGoAsRecorded()
	{
		final List<Monster> monsters = new ArrayList<>(MONSTERS.values());
		
		final List<String> lines = new ArrayList<>();
		lines.add("# LOCK-COMBAT-0001: the auto-attack sequence (miss, shield, critical, damage without and with soulshot) for one monster in " + ATTACKER_STRIDE + " against " + TARGETS_PER_ATTACKER + " others,");
		lines.add("# from in front and from behind, " + TRIALS + " consecutive attacks each, on the stream (" + SEED + ", \"lock.combat.physical\", attacker * 1000000 + target).");
		lines.add("attacker\ttarget\tfrom\tattack\tmissed\tshield\tcritical\tdamage\tdamageWithSoulshot");
		
		for (int a = 0; a < monsters.size(); a += ATTACKER_STRIDE)
		{
			final Monster attacker = monsters.get(a);
			for (int n = 1; n <= TARGETS_PER_ATTACKER; n++)
			{
				// Targets spread across the whole list, so levels far above and below the attacker's are covered.
				final Monster target = monsters.get((a + ((n * monsters.size()) / (TARGETS_PER_ATTACKER + 1))) % monsters.size());
				for (String from : List.of("front", "behind"))
				{
					// The target stands at the origin facing east. An attacker in front stands east of it facing west; one behind stands west of it facing east.
					target.setXYZInvisible(0, 0, 0);
					target.setHeading(0);
					attacker.setXYZInvisible(from.equals("front") ? 100 : -100, 0, 0);
					attacker.setHeading(from.equals("front") ? 32768 : 0);
					
					_server.withRandom(KeyedRandom.of(SEED, "lock.combat.physical", (attacker.getId() * 1_000_000L) + target.getId()), () ->
					{
						for (int attack = 1; attack <= TRIALS; attack++)
						{
							final boolean missed = Formulas.calcHitMiss(attacker, target);
							final byte shield = Formulas.calcShldUse(attacker, target, null, false);
							final boolean critical = Formulas.calcCrit(attacker, target);
							final double damage = Formulas.calcPhysDam(attacker, target, null, shield, critical, false);
							final double damageWithSoulshot = Formulas.calcPhysDam(attacker, target, null, shield, critical, true);
							lines.add(attacker.getId() + "\t" + target.getId() + "\t" + from + "\t" + attack + "\t" + missed + "\t" + shield + "\t" + critical + "\t" + damage + "\t" + damageWithSoulshot);
						}
					});
				}
			}
		}
		Golden.repository().assertMatches("combat/monster-physical.tsv", lines);
	}
}
