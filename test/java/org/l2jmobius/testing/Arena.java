package org.l2jmobius.testing;

import java.util.function.BooleanSupplier;

import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.livingchronicle.clock.GameScheduler;
import org.l2jmobius.gameserver.managers.ZoneManager;

/**
 * A place in a paused world where a lock case can fight without the world joining in.
 * <p>
 * It is open sea about 196,000 from the nearest spawn of the datapack and outside every zone, so a skill that gathers its targets from around the caster finds exactly what the case put there. Both sides must be spawned for a case to happen at all: the server will not let a character target anything that is not.
 * </p>
 */
public final class Arena
{
	public static final int X = -280000;
	public static final int Y = -66000;
	public static final int Z = -3500;

	/** How far in front of its target a character stands: inside every weapon's and skill's range. */
	private static final int DISTANCE = 30;

	private Arena()
	{
	}

	/**
	 * Fails unless the arena is still outside every zone, which a datapack change could end.
	 */
	public static void checkEmpty()
	{
		if (!ZoneManager.getInstance().getZones(X, Y, Z).isEmpty())
		{
			throw new IllegalStateException("the arena at " + X + ", " + Y + ", " + Z + " is inside a zone now: " + ZoneManager.getInstance().getZones(X, Y, Z));
		}
	}

	/**
	 * A fresh monster of {@code npcId} at full health, spawned in the arena facing east, with its AI switched off: a case records what is done to it, not the fight it would start.
	 */
	public static Monster monster(int npcId)
	{
		final Monster monster = new Monster(NpcData.getInstance().getTemplate(npcId));
		monster.disableCoreAI(true);
		monster.setCurrentHpMp(monster.getMaxHp(), monster.getMaxMp());
		monster.setHeading(0);
		monster.spawnMe(X, Y, Z);
		return monster;
	}

	/**
	 * Spawns {@code player} close in front of {@code target}, facing it.
	 */
	public static void placeInFront(Player player, Creature target)
	{
		player.setHeading(32768);
		player.spawnMe(target.getX() + DISTANCE, target.getY(), target.getZ());
	}

	/**
	 * Steps the paused world from event to event while {@code condition} holds, for at most {@code limitMillis} of game time from now.
	 */
	public static void runWhile(BooleanSupplier condition, long limitMillis)
	{
		final long end = GameClock.millis() + limitMillis;
		while (condition.getAsBoolean() && (GameClock.millis() < end))
		{
			final long next = Math.min(GameScheduler.nextDue(), end);
			GameScheduler.advance(Math.max(0, next - GameClock.millis()));
		}
	}
}
