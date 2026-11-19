package org.l2jmobius.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code truth} profile of blueprint 11 §3 that the behaviour lock runs in: every custom Mobius module neutral or off, applied to the staged copy of {@code dist/game} before the server boots.
 * <p>
 * Every setting of {@code config/Custom} that is {@code true} is set to {@code false}: a master switch, and any switch of a module that works on its own whatever its master says (automatic potions and skills, for one). The only ones kept are those that are retail as they are ({@link #RETAIL_AS_SHIPPED}: every race allowed). Settings that are not switches but restrict play are set to their neutral value ({@link #NEUTRAL_VALUES}: private stores may stand anywhere). Every custom module must be known here: one upstream adds fails the profile until someone decides what it is in the truth profile. The settings the suite then boots with are themselves locked (LOCK-CONFIG-0001), so an upstream change to any default shows at intake.
 * </p>
 */
public final class TruthProfile
{
	private static final Path CUSTOM = Path.of("config", "Custom");

	/** Every custom module upstream ships, by file name. */
	static final Set<String> MODULES = Set.of("AllowedPlayerRaces.ini", "AutoPlay.ini", "AutoPotions.ini", "Banking.ini", "BossAnnouncements.ini", "CancelReturn.ini", "Captcha.ini", "ChampionMonsters.ini", "ChatModeration.ini", "ClassBalance.ini", "CommunityBoard.ini", "CustomMailManager.ini", "DelevelManager.ini", "DualboxCheck.ini", "FactionSystem.ini", "FakePlayers.ini", "FindPvP.ini", "FreeMounts.ini", "HellboundStatus.ini", "MerchantZeroSellPrice.ini", "MultilingualSupport.ini", "NoblessMaster.ini", "NpcStatMultipliers.ini", "OfflinePlay.ini", "OfflineTrade.ini", "OnlineInfo.ini", "PasswordChange.ini", "PremiumSystem.ini", "PrivateStoreRange.ini", "PvpAnnounce.ini", "PvpRewardItem.ini", "PvpTitleColor.ini", "RandomSpawns.ini", "Rebirth.ini", "SchemeBuffer.ini", "ScreenWelcomeMessage.ini", "SellBuffs.ini", "ServerTime.ini", "StartingLocation.ini", "StartingTitle.ini", "Transmog.ini", "WalkerBotProtection.ini", "WarehouseSorting.ini", "Wedding.ini");

	/** Switches left on because on is retail: every race may be created. */
	static final Set<String> RETAIL_AS_SHIPPED = Set.of("AllowedPlayerRaces.ini");

	/** Settings that are not switches, by file, and the value that makes them change nothing. */
	static final Map<String, Map<String, String>> NEUTRAL_VALUES = new LinkedHashMap<>();
	static
	{
		// The minimum distance of a private store from an NPC or another seller; 0 is none.
		NEUTRAL_VALUES.put("PrivateStoreRange.ini", Map.of("ShopMinRangeFromPlayer", "0", "ShopMinRangeFromNpc", "0"));
	}

	private static final Pattern SWITCHED_ON = Pattern.compile("(?mi)^([A-Za-z0-9_]+\\s*=\\s*)true[ \\t]*$");

	private TruthProfile()
	{
	}

	/**
	 * Puts the staged runtime under {@code gameRoot} in the truth profile.
	 * @throws IllegalStateException if a custom module is not known, a known one is gone, or a neutral value's setting is not in its file once and once only
	 */
	public static void apply(Path gameRoot) throws IOException
	{
		final Set<String> present;
		try (var files = Files.list(gameRoot.resolve(CUSTOM)))
		{
			present = Set.copyOf(files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".ini")).toList());
		}
		for (String module : present)
		{
			if (!MODULES.contains(module))
			{
				throw new IllegalStateException("custom module " + module + " is not known to the truth profile; read what it does and add it to TruthProfile.MODULES, with any neutral values it needs");
			}
		}
		for (String module : MODULES)
		{
			if (!present.contains(module))
			{
				throw new IllegalStateException("custom module " + module + " is known to the truth profile but no longer exists; remove it from TruthProfile.MODULES");
			}
		}

		for (String module : MODULES)
		{
			final Path file = gameRoot.resolve(CUSTOM).resolve(module);
			String text = Files.readString(file, StandardCharsets.UTF_8);
			if (!RETAIL_AS_SHIPPED.contains(module))
			{
				text = SWITCHED_ON.matcher(text).replaceAll("$1False");
			}
			for (Map.Entry<String, String> neutral : NEUTRAL_VALUES.getOrDefault(module, Map.of()).entrySet())
			{
				final Matcher matcher = Pattern.compile("(?m)^(" + Pattern.quote(neutral.getKey()) + "\\s*=\\s*)\\S*[ \\t]*$").matcher(text);
				if (!matcher.find())
				{
					throw new IllegalStateException(module + " has no '" + neutral.getKey() + " = ...' line any more");
				}
				final int start = matcher.start();
				if (matcher.find())
				{
					throw new IllegalStateException(module + " sets " + neutral.getKey() + " more than once");
				}
				matcher.reset();
				matcher.find(start);
				text = text.substring(0, matcher.start()) + matcher.group(1) + neutral.getValue() + text.substring(matcher.end());
			}
			Files.writeString(file, text, StandardCharsets.UTF_8);
		}
	}
}
