package org.l2jmobius.gameserver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.testing.Golden;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * LOCK-PLAYER: what every class is at the edges of its level range, and what it has learned by then.
 * <p>
 * Each character is made by {@link TestPlayers}: created by the server, levelled by the server, taught everything its class can learn by that level, carrying nothing. Its numbers are what the server's stat calculators make of the class template, the level tables and the passive skills, and nothing else.
 * </p>
 */
class PlayerLockTest
{
	private static final long START = 1_794_216_090_000L;
	private static final long SEED = 1;

	/** "class@level" to the character, in class id order, lowest level first. */
	private static final Map<String, Player> PLAYERS = new LinkedHashMap<>();

	@BeforeAll
	static void boot() throws Exception
	{
		TestServer.bootPaused(START, SEED);

		final List<PlayerClass> classes = new ArrayList<>();
		for (PlayerClass playerClass : PlayerClass.values())
		{
			if (PlayerTemplateData.getInstance().getTemplate(playerClass) != null)
			{
				classes.add(playerClass);
			}
		}
		classes.sort(Comparator.comparingInt(PlayerClass::getId));

		for (PlayerClass playerClass : classes)
		{
			for (int level : TestPlayers.levelRange(playerClass))
			{
				PLAYERS.put(playerClass + "@" + level, TestPlayers.create(playerClass, level));
			}
		}
	}

	@Test
	void everyClassHasItsRecordedStats()
	{
		final List<String> lines = new ArrayList<>();
		lines.add("# LOCK-PLAYER-0001: every class at the lowest and highest level it can have, made, levelled and taught by the server, naked, at full HP/MP/CP.");
		lines.add("class\tid\tlevel\tskills\tmaxHp\tmaxMp\tmaxCp\tpAtk\tpDef\tmAtk\tmDef\taccuracy\tevasion\tcritical\tmCritical\tpAtkSpd\tmAtkSpd\trunSpeed\twalkSpeed\tattackRange\tSTR\tDEX\tCON\tINT\tWIT\tMEN\tmaxLoad");
		for (Player player : PLAYERS.values())
		{
			lines.add(String.join("\t", //
				player.getPlayerClass().toString(), //
				String.valueOf(player.getPlayerClass().getId()), //
				String.valueOf(player.getLevel()), //
				String.valueOf(player.getSkills().size()), //
				String.valueOf(player.getMaxHp()), //
				String.valueOf(player.getMaxMp()), //
				String.valueOf(player.getMaxCp()), //
				String.valueOf(player.getPAtk(null)), //
				String.valueOf(player.getPDef(null)), //
				String.valueOf(player.getMAtk(null, null)), //
				String.valueOf(player.getMDef(null, null)), //
				String.valueOf(player.getAccuracy()), //
				String.valueOf(player.getEvasionRate(null)), //
				String.valueOf(player.getCriticalHit(null, null)), //
				String.valueOf(player.getMCriticalHit(null, null)), //
				String.valueOf(player.getPAtkSpd()), //
				String.valueOf(player.getMAtkSpd()), //
				String.valueOf(player.getRunSpeed()), //
				String.valueOf(player.getWalkSpeed()), //
				String.valueOf(player.getPhysicalAttackRange()), //
				String.valueOf(player.getSTR()), //
				String.valueOf(player.getDEX()), //
				String.valueOf(player.getCON()), //
				String.valueOf(player.getINT()), //
				String.valueOf(player.getWIT()), //
				String.valueOf(player.getMEN()), //
				String.valueOf(player.getMaxLoad())));
		}
		Golden.repository().assertMatches("player/class-stats.tsv", lines);
	}

	@Test
	void everyClassKnowsItsRecordedSkills()
	{
		final List<String> lines = new ArrayList<>();
		lines.add("# LOCK-PLAYER-0002: the skills every class knows at the lowest and highest level it can have, having learned all it could (forgotten scrolls excepted), by skill id.");
		lines.add("class\tlevel\tskill\tskillLevel\tname\tkind");
		for (Player player : PLAYERS.values())
		{
			final List<Skill> skills = new ArrayList<>(player.getSkills().values());
			skills.sort(Comparator.comparingInt(Skill::getId));
			for (Skill skill : skills)
			{
				lines.add(player.getPlayerClass() + "\t" + player.getLevel() + "\t" + skill.getId() + "\t" + skill.getLevel() + "\t" + skill.getName() + "\t" + (skill.isPassive() ? "passive" : skill.isToggle() ? "toggle" : "active"));
			}
		}
		Golden.repository().assertMatches("player/class-skills.tsv", lines);
	}
}
