package org.l2jmobius.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code truth} profile of blueprint 11 §3 that the behaviour lock runs in: every custom Mobius module neutral or off, applied to the staged copy of {@code dist/game} before the server boots.
 * <p>
 * Each custom module of {@code config/Custom} is classified in {@link #MODULES}: by the setting that switches it on, which the profile holds off, or as neutral as shipped (multipliers left empty, every race allowed). A module upstream adds is unclassified and fails the profile until someone decides what it is in the truth profile; one upstream ships switched on is switched off here. The settings the suite then boots with are themselves locked (LOCK-CONFIG-0001), so an upstream change to any default shows at intake.
 * </p>
 */
public final class TruthProfile
{
	private static final Path CUSTOM = Path.of("config", "Custom");

	/** Neutral as shipped: nothing to switch. */
	private static final String NEUTRAL = "";

	/**
	 * Every custom module by file name, to the setting that switches it on ({@link #NEUTRAL} for none). Settings named here are held at {@code False} in the profile.
	 */
	static final Map<String, String[]> MODULES = new LinkedHashMap<>();
	static
	{
		module("AllowedPlayerRaces.ini"); // Every race allowed, as in retail.
		module("AutoPlay.ini", "EnableAutoPlay");
		module("AutoPotions.ini", "AutoPotionsEnabled");
		module("Banking.ini", "BankingEnabled");
		module("BossAnnouncements.ini", "RaidBossSpawnAnnouncements", "RaidBossDefeatAnnouncements", "RaidBossInstanceAnnouncements", "GrandBossSpawnAnnouncements", "GrandBossDefeatAnnouncements", "GrandBossInstanceAnnouncements");
		module("CancelReturn.ini", "CancelReturn");
		module("Captcha.ini", "EnableCaptcha");
		module("ChampionMonsters.ini", "ChampionEnable");
		module("ChatModeration.ini", "ChatAdmin");
		module("ClassBalance.ini"); // Every multiplier list empty.
		module("CommunityBoard.ini", "CustomCommunityBoard");
		module("CustomMailManager.ini", "CustomMailManagerEnabled");
		module("DelevelManager.ini", "Enabled");
		module("DualboxCheck.ini"); // Every limit 0, which is none.
		module("FactionSystem.ini", "EnableFactionSystem");
		module("FakePlayers.ini", "EnableFakePlayers");
		module("FindPvP.ini", "EnableFindPvP");
		module("FreeMounts.ini", "EnableFreeStrider", "EnableFreeWyvern");
		module("HellboundStatus.ini", "HellboundStatus");
		module("MerchantZeroSellPrice.ini", "MerchantZeroSellPrice");
		module("MultilingualSupport.ini", "MultiLangEnable");
		module("NoblessMaster.ini", "Enabled");
		module("NpcStatMultipliers.ini", "EnableNpcStatMultipliers");
		module("OfflinePlay.ini", "EnableOfflinePlayCommand");
		module("OfflineTrade.ini", "OfflineTradeEnable", "OfflineCraftEnable");
		module("OnlineInfo.ini", "EnableOnlineCommand");
		module("PasswordChange.ini", "AllowChangePassword");
		module("PremiumSystem.ini", "EnablePremiumSystem");
		module("PrivateStoreRange.ini"); // Ranges kept; they only refuse a store too close to another, no store is opened.
		module("PvpAnnounce.ini", "AnnouncePkPvP");
		module("PvpRewardItem.ini", "RewardPvpItem", "RewardPkItem");
		module("PvpTitleColor.ini", "EnablePvPColorSystem");
		module("RandomSpawns.ini", "EnableRandomMonsterSpawns");
		module("Rebirth.ini", "RebirthAllow");
		module("SchemeBuffer.ini"); // Only prices and limits of a buffer the datapack does not spawn.
		module("ScreenWelcomeMessage.ini", "ScreenWelcomeMessageEnable");
		module("SellBuffs.ini", "SellBuffEnable");
		module("ServerTime.ini", "DisplayServerTime");
		module("StartingLocation.ini", "CustomStartingLocation");
		module("StartingTitle.ini", "EnableStartingTitle");
		module("Transmog.ini", "TransmogEnabled");
		module("WalkerBotProtection.ini", "L2WalkerProtection");
		module("WarehouseSorting.ini", "EnableWarehouseSortingClan", "EnableWarehouseSortingPrivate");
		module("Wedding.ini", "AllowWedding");
	}

	private TruthProfile()
	{
	}

	private static void module(String file, String... switches)
	{
		MODULES.put(file, switches.length == 0 ? new String[]
		{
			NEUTRAL
		} : switches);
	}

	/**
	 * Puts the staged runtime under {@code gameRoot} in the truth profile.
	 * @throws IllegalStateException if a custom module is not classified, or a switch named for one is not in its file once and once only
	 */
	public static void apply(Path gameRoot) throws IOException
	{
		try (var files = Files.list(gameRoot.resolve(CUSTOM)))
		{
			for (Path file : files.filter(path -> path.toString().endsWith(".ini")).sorted().toList())
			{
				if (!MODULES.containsKey(file.getFileName().toString()))
				{
					throw new IllegalStateException("custom module " + file.getFileName() + " is not classified for the truth profile; add it to TruthProfile.MODULES as off (its switch) or neutral");
				}
			}
		}

		for (Map.Entry<String, String[]> module : MODULES.entrySet())
		{
			final Path file = gameRoot.resolve(CUSTOM).resolve(module.getKey());
			if (!Files.isRegularFile(file))
			{
				throw new IllegalStateException("custom module " + module.getKey() + " is classified for the truth profile but no longer exists; remove it from TruthProfile.MODULES");
			}
			String text = Files.readString(file, StandardCharsets.UTF_8);
			for (String setting : module.getValue())
			{
				if (setting.equals(NEUTRAL))
				{
					continue;
				}
				final Matcher matcher = Pattern.compile("(?m)^(" + Pattern.quote(setting) + "\\s*=\\s*)\\S*[ \\t]*$").matcher(text);
				if (!matcher.find())
				{
					throw new IllegalStateException(module.getKey() + " has no '" + setting + " = ...' line any more");
				}
				final int start = matcher.start();
				if (matcher.find())
				{
					throw new IllegalStateException(module.getKey() + " sets " + setting + " more than once");
				}
				matcher.reset();
				matcher.find(start);
				text = text.substring(0, matcher.start()) + matcher.group(1) + "False" + text.substring(matcher.end());
			}
			Files.writeString(file, text, StandardCharsets.UTF_8);
		}
	}
}
