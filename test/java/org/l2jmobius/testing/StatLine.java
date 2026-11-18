package org.l2jmobius.testing;

import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * A character's numbers as the server's stat calculators give them, one tab-separated line, for locks that compare characters across classes, levels and gear.
 */
public final class StatLine
{
	public static final String HEADER = "maxHp\tmaxMp\tmaxCp\tpAtk\tpDef\tmAtk\tmDef\taccuracy\tevasion\tcritical\tmCritical\tpAtkSpd\tmAtkSpd\trunSpeed\twalkSpeed\tattackRange\tSTR\tDEX\tCON\tINT\tWIT\tMEN\tmaxLoad";

	private StatLine()
	{
	}

	public static String of(Player player)
	{
		return String.join("\t", //
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
			String.valueOf(player.getMaxLoad()));
	}
}
