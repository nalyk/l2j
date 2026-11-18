package org.l2jmobius.testing;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.appearance.PlayerAppearance;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.actor.templates.PlayerTemplate;

/**
 * Characters for lock and truth cases, made the way the server makes them.
 * <p>
 * A character is created by upstream's own {@link Player#create}, which also writes its row to the {@link TestDatabase}, then raised to its level by the same {@code addLevel} a level-up runs, and finally taught everything its class can learn by then, as if it had visited every trainer and bought every book. It is not connected, not in the world and carries nothing: a case adds what it needs.
 * </p>
 */
public final class TestPlayers
{
	/** Kamael classes that only a female character can be; every other class is made male. */
	private static final Set<PlayerClass> FEMALE_ONLY = EnumSet.of(PlayerClass.FEMALE_SOLDIER, PlayerClass.WARDER, PlayerClass.FEMALE_SOULBREAKER, PlayerClass.ARBALESTER, PlayerClass.FEMALE_SOUL_HOUND, PlayerClass.TRICKSTER);

	private static final String ACCOUNT = "lock";

	private static final AtomicInteger NAMES = new AtomicInteger();

	private TestPlayers()
	{
	}

	/**
	 * The lowest and highest level a character of {@code playerClass} can have: a class change needs level 20, 40 and 76, and 85 is the cap.
	 */
	public static int[] levelRange(PlayerClass playerClass)
	{
		return switch (playerClass.level())
		{
			case 0 -> new int[]
			{
				1,
				19
			};
			case 1 -> new int[]
			{
				20,
				39
			};
			case 2 -> new int[]
			{
				40,
				75
			};
			default -> new int[]
			{
				76,
				85
			};
		};
	}

	/**
	 * A character of {@code playerClass} at {@code level}, with every skill its class teaches up to that level, at full HP, MP and CP.
	 */
	public static Player create(PlayerClass playerClass, int level)
	{
		final PlayerTemplate template = PlayerTemplateData.getInstance().getTemplate(playerClass);
		if (template == null)
		{
			throw new IllegalArgumentException("no player template for " + playerClass);
		}

		final Player player = Player.create(template, ACCOUNT, "Lock" + NAMES.incrementAndGet(), new PlayerAppearance((byte) 0, (byte) 0, (byte) 0, FEMALE_ONLY.contains(playerClass)));
		if (player == null)
		{
			throw new IllegalStateException("the server could not store a new " + playerClass);
		}

		if (level > 1)
		{
			player.getStat().addLevel((byte) (level - 1));
		}
		if (player.getLevel() != level)
		{
			throw new IllegalArgumentException(playerClass + " cannot be level " + level + ", the server made it " + player.getLevel());
		}

		player.giveAvailableSkills(false, true, true);
		player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
		player.setCurrentCp(player.getMaxCp());
		return player;
	}
}
