package org.l2jmobius.gameserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.enums.CategoryType;
import org.l2jmobius.gameserver.data.xml.CategoryData;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.item.type.CrystalType;
import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.livingchronicle.clock.GameScheduler;
import org.l2jmobius.gameserver.livingchronicle.rng.KeyedRandom;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureAttackAvoid;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDamageDealt;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.testing.Arena;
import org.l2jmobius.testing.Golden;
import org.l2jmobius.testing.GradeGear;
import org.l2jmobius.testing.StatLine;
import org.l2jmobius.testing.TestGear;
import org.l2jmobius.testing.TestGear.Piece;
import org.l2jmobius.testing.TestGear.Weapon;
import org.l2jmobius.testing.TestGear.Wear;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * LOCK-GEAR and LOCK-COMBAT-0002: what gear does to every third class, and how each of them hits with each weapon.
 * <p>
 * Characters are made by {@link TestPlayers} at level 85 and dressed in the Vesper gear of {@link TestGear} through the inventory's own equip, so set bonuses, item skills and the class's mastery passives follow as they do in play.
 * </p>
 */
class GearLockTest
{
	private static final long START = 1_794_216_090_000L;
	private static final long SEED = 1;

	private static final int LEVEL = 85;
	private static final int MALUK_WARLORD = 22839;

	/** How many hits, landed or missed, an auto-attack case records. A dual weapon lands two per swing. */
	private static final int HITS = 4;

	/** An auto-attack case that has not seen its hits after this much game time is recorded as it stands. */
	private static final long ATTACK_LIMIT_MILLIS = 30_000;

	private static final List<Wear> ARMOURS = List.of(TestGear.HEAVY, TestGear.LIGHT, TestGear.ROBE);

	/** Soulshot (S-Grade), the grade of every Vesper weapon, and how many a case carries. */
	private static final int SOULSHOT_S = 1467;
	private static final long SOULSHOTS = 1000;

	private static final List<CategoryType> ARMOUR_CATEGORIES = List.of(CategoryType.HEAVY_ARMOR_CLASS, CategoryType.LIGHT_ARMOR_CLASS, CategoryType.ROBE_CLASS);

	private static TestServer _server;

	/** Every third class, in class id order. */
	private static final List<PlayerClass> CLASSES = new ArrayList<>();

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.bootPaused(START, SEED);
		Arena.checkEmpty();
		Arena.wake();

		for (PlayerClass playerClass : PlayerClass.values())
		{
			if ((playerClass.level() == 3) && (PlayerTemplateData.getInstance().getTemplate(playerClass) != null))
			{
				CLASSES.add(playerClass);
			}
		}
		CLASSES.sort(Comparator.comparingInt(PlayerClass::getId));
	}

	@Test
	void everyClassInEveryGearHasItsRecordedStats()
	{
		final List<String> lines = new ArrayList<>();
		lines.add("# LOCK-GEAR-0001: every third class at level " + LEVEL + ", in no armour and in each full Vesper set with Vesper jewels, holding nothing and each Vesper weapon, one-handed ones also with a Vesper shield and sigil.");
		lines.add("# One character per class, re-dressed for each line by the inventory's own equip.");
		lines.add("class\tarmour\tweapon\t" + StatLine.HEADER);

		final List<Wear> armours = new ArrayList<>();
		armours.add(null);
		armours.addAll(ARMOURS);

		for (PlayerClass playerClass : CLASSES)
		{
			final Player player = TestPlayers.create(playerClass, LEVEL);
			for (Wear armour : armours)
			{
				for (Weapon weapon : weaponsAndNothing())
				{
					final List<Wear> offHands = new ArrayList<>();
					offHands.add(null);
					if ((weapon == null) || weapon.oneHanded())
					{
						offHands.add(TestGear.SHIELD);
						offHands.add(TestGear.SIGIL);
					}

					for (Wear offHand : offHands)
					{
						final List<Piece> pieces = new ArrayList<>();
						final StringJoiner held = new StringJoiner("+");
						if (weapon != null)
						{
							pieces.addAll(weapon.pieces());
							held.add(weapon.name());
						}
						if (offHand != null)
						{
							pieces.addAll(offHand.pieces());
							held.add(offHand.name());
						}
						if (armour != null)
						{
							pieces.addAll(armour.pieces());
						}

						TestGear.wear(player, pieces);
						lines.add(playerClass + "\t" + (armour == null ? "-" : armour.name()) + "\t" + (held.length() == 0 ? "-" : held.toString()) + "\t" + StatLine.of(player));
					}
				}
			}
			player.deleteMe();
		}
		Golden.repository().assertMatches("gear/third-class-stats.tsv", lines);
	}

	@Test
	void everyClassHitsWithEveryWeaponAsRecorded()
	{
		final List<String> lines = new ArrayList<>();
		lines.add("# LOCK-COMBAT-0002: every third class at level " + LEVEL + ", in the Vesper set its armour category names (its own, or its nearest ancestor's), auto-attacking a Maluk Warlord in front of it with nothing and with each Vesper weapon,");
		lines.add("# as the client's attack request starts it (AI attack intention), the world stepped until " + HITS + " hits have landed or missed, on the stream (" + SEED + ", \"lock.combat.auto\", class * 100 + weapon), weapon 0 being none.");
		lines.add("# Hits are millis:damage, ! marking a critical, or millis:miss, millis from the attack request. MP and ammunition spent are over the whole case.");
		lines.add("# A hit that lands for 0 damage notifies nothing (Creature.onHitTimer), so it shows only as a gap: a fighter's bare fists do that to a Warlord, and such cases run to the " + (ATTACK_LIMIT_MILLIS / 1000) + " s limit.");
		lines.add("class\tarmour\tweapon\thits\tmpSpent\tammoSpent");

		final List<Weapon> weapons = weaponsAndNothing();
		for (PlayerClass playerClass : CLASSES)
		{
			final Wear armour = armourOf(playerClass);
			final PlayerClass source = armourSource(playerClass);
			final String armourName = armour == null ? "-" : source == playerClass ? armour.name() : armour.name() + " (from " + source + ")";
			for (int w = 0; w < weapons.size(); w++)
			{
				lines.add(playerClass + "\t" + armourName + "\t" + (weapons.get(w) == null ? "-" : weapons.get(w).name()) + "\t" + attack(playerClass, armour, weapons.get(w), w));
			}
		}
		Golden.repository().assertMatches("combat/third-class-auto-attack.tsv", lines);
	}

	/**
	 * LOCK-COMBAT-0003: the same attacks with soulshots, as a player has them: S-grade soulshots in the bag and set to load themselves, loaded before the first swing and again after every hit by the server itself.
	 */
	@Test
	void everyClassHitsWithSoulshotsAsRecorded()
	{
		final List<String> lines = new ArrayList<>();
		lines.add("# LOCK-COMBAT-0003: as LOCK-COMBAT-0002, with each Vesper weapon and " + SOULSHOTS + " S-grade soulshots set to load themselves, on the stream (" + SEED + ", \"lock.combat.soulshot\", class * 100 + weapon).");
		lines.add("# Hits are millis:damage, ! marking a critical, or millis:miss. MP, ammunition and soulshots spent are over the whole case.");
		lines.add("class\tarmour\tweapon\thits\tmpSpent\tammoSpent\tsoulshotsSpent");

		for (PlayerClass playerClass : CLASSES)
		{
			final Wear armour = armourOf(playerClass);
			final PlayerClass source = armourSource(playerClass);
			final String armourName = armour == null ? "-" : source == playerClass ? armour.name() : armour.name() + " (from " + source + ")";
			for (int w = 0; w < TestGear.WEAPONS.size(); w++)
			{
				final Weapon weapon = TestGear.WEAPONS.get(w);
				final String[] line = new String[1];
				final int weaponIndex = w + 1;
				Arena.align();
				_server.withRandom(KeyedRandom.of(SEED, "lock.combat.soulshot", (playerClass.getId() * 100L) + weaponIndex), () -> line[0] = attackOnItsStream(armour, weapon, playerClass, true));
				lines.add(playerClass + "\t" + armourName + "\t" + weapon.name() + "\t" + line[0]);
			}
		}
		Golden.repository().assertMatches("combat/third-class-auto-attack-soulshot.tsv", lines);
	}

	/**
	 * LOCK-GEAR-0002: every class at the first level of each grade it can be at, in that grade's plainest armour of its armour category, holding nothing and each of the grade's plainest weapons ({@link GradeGear}); and, one line each, in the next grade's armour with its sword, which its expertise does not reach yet.
	 */
	@Test
	void everyClassInEveryGradeHasItsRecordedStats() throws IOException
	{
		final List<String> lines = new ArrayList<>();
		lines.add("# LOCK-GEAR-0002: every class at the first level of each grade whose level it can have, made and taught by the server, in the lowest armour set of that grade and of its armour category (its own or its nearest ancestor's),");
		lines.add("# holding nothing and the lowest plain weapon of each kind of that grade (no special ability, PvP, shadow, common or timed copy), a bow or crossbow with the grade's arrows or bolts; no jewels.");
		lines.add("# A grade marked (above) is the next one, its armour and sword, which the character's expertise does not reach.");
		lines.add("class\tlevel\tgrade\tarmour\tweapon\t" + StatLine.HEADER);

		final List<PlayerClass> classes = new ArrayList<>();
		for (PlayerClass playerClass : PlayerClass.values())
		{
			if (PlayerTemplateData.getInstance().getTemplate(playerClass) != null)
			{
				classes.add(playerClass);
			}
		}
		classes.sort(Comparator.comparingInt(PlayerClass::getId));

		final List<CrystalType> grades = new ArrayList<>(GradeGear.FIRST_LEVEL.keySet());
		for (int g = 0; g < grades.size(); g++)
		{
			final CrystalType grade = grades.get(g);
			final int level = GradeGear.FIRST_LEVEL.get(grade);
			final Map<String, Wear> armours = GradeGear.armours(grade);
			final List<Weapon> held = new ArrayList<>();
			held.add(null);
			held.addAll(GradeGear.weapons(grade).values());
			final CrystalType above = (g + 1) < grades.size() ? grades.get(g + 1) : null;
			for (PlayerClass playerClass : classes)
			{
				final int[] range = TestPlayers.levelRange(playerClass);
				if ((level < range[0]) || (level > range[1]))
				{
					continue;
				}
				final String type = armourTypeOf(playerClass);
				final Player player = TestPlayers.create(playerClass, level);
				for (Weapon weapon : held)
				{
					lines.add(gradeLine(player, grade.toString(), type == null ? null : armours.get(type), weapon));
				}
				if (above != null)
				{
					lines.add(gradeLine(player, above + " (above)", type == null ? null : GradeGear.armours(above).get(type), GradeGear.weapons(above).get("sword")));
				}
				player.deleteMe();
			}
		}
		Golden.repository().assertMatches("gear/grade-stats.tsv", lines);
	}

	private static String gradeLine(Player player, String grade, Wear armour, Weapon weapon)
	{
		final List<Piece> pieces = new ArrayList<>();
		if (weapon != null)
		{
			pieces.addAll(weapon.pieces());
		}
		if (armour != null)
		{
			pieces.addAll(armour.pieces());
		}
		TestGear.wear(player, pieces);
		return player.getPlayerClass() + "\t" + player.getLevel() + "\t" + grade + "\t" + (armour == null ? "-" : armour.name()) + "\t" + (weapon == null ? "-" : weapon.name()) + "\t" + StatLine.of(player);
	}

	/**
	 * The armour type ("heavy", "light", "robe") of the armour category {@code playerClass} or its nearest ancestor is in, {@code null} for none.
	 */
	private static String armourTypeOf(PlayerClass playerClass)
	{
		final Wear armour = armourOf(playerClass);
		return armour == null ? null : armour.name();
	}

	/**
	 * A case's outcome is a function of the case: the same attack, run again after the world has gone on by itself for ten minutes and its stream has been drawn on a thousand times more, as a world with other scripts or other spawns would have, lands the same hits at the same moments. Both runs find the arena awake, as every case of the sweep does.
	 */
	@Test
	void aCaseComesOutTheSameAfterTheWorldHasMovedOn()
	{
		final PlayerClass playerClass = CLASSES.get(0);
		Arena.wake();
		final String before = attack(playerClass, armourOf(playerClass), TestGear.WEAPONS.get(0), 1);
		GameScheduler.advance(600_000);
		for (int draw = 0; draw < 1000; draw++)
		{
			Rnd.nextLong();
		}
		Arena.wake();
		assertEquals(before, attack(playerClass, armourOf(playerClass), TestGear.WEAPONS.get(0), 1));
	}

	/**
	 * One case, all of it on its own stream: making and dressing the attacker, setting the scene, the attack, the clean-up. What the scene's setting-up sets going (the regions it wakes) draws on the case's stream too, not on whatever the world has drawn by then.
	 */
	private static String attack(PlayerClass playerClass, Wear armour, Weapon weapon, int weaponIndex)
	{
		final String[] line = new String[1];
		Arena.align();
		_server.withRandom(KeyedRandom.of(SEED, "lock.combat.auto", (playerClass.getId() * 100L) + weaponIndex), () -> line[0] = attackOnItsStream(armour, weapon, playerClass, false));
		return line[0];
	}

	private static String attackOnItsStream(Wear armour, Weapon weapon, PlayerClass playerClass, boolean soulshots)
	{
		final Player attacker = TestPlayers.create(playerClass, LEVEL);
		final List<Piece> pieces = new ArrayList<>();
		if (weapon != null)
		{
			pieces.addAll(weapon.pieces());
		}
		if (armour != null)
		{
			pieces.addAll(armour.pieces());
		}
		TestGear.wear(attacker, pieces);
		attacker.setCurrentHpMp(attacker.getMaxHp(), attacker.getMaxMp());
		attacker.setCurrentCp(attacker.getMaxCp());
		if (soulshots)
		{
			attacker.getInventory().addItem(ItemProcessType.NONE, SOULSHOT_S, SOULSHOTS, attacker, null);
			attacker.addAutoSoulShot(SOULSHOT_S);
			attacker.rechargeShots(true, false);
		}

		final Monster target = Arena.monster(MALUK_WARLORD);
		Arena.placeInFront(attacker, target);

		final long start = GameClock.millis();
		final List<String> hits = new ArrayList<>();
		final ConsumerEventListener dealt = new ConsumerEventListener(attacker, EventType.ON_CREATURE_DAMAGE_DEALT, (OnCreatureDamageDealt event) ->
		{
			if ((event.getTarget() == target) && (event.getSkill() == null) && !event.isDamageOverTime())
			{
				hits.add((GameClock.millis() - start) + ":" + event.getDamage() + (event.isCritical() ? "!" : ""));
			}
		}, GearLockTest.class);
		final ConsumerEventListener avoided = new ConsumerEventListener(target, EventType.ON_CREATURE_ATTACK_AVOID, (OnCreatureAttackAvoid event) ->
		{
			if ((event.getAttacker() == attacker) && !event.isDamageOverTime())
			{
				hits.add((GameClock.millis() - start) + ":miss");
			}
		}, GearLockTest.class);
		attacker.addListener(dealt);
		target.addListener(avoided);

		final double mpBefore = attacker.getCurrentMp();
		final long ammoBefore = ammo(attacker, weapon);
		attacker.getAI().setIntentionAttack(target);
		Arena.runWhile(() -> hits.size() < HITS, ATTACK_LIMIT_MILLIS);
		attacker.abortAttack();
		attacker.getAI().setIntentionIdle();

		final Item shots = attacker.getInventory().getItemByItemId(SOULSHOT_S);
		final String line = String.join("\t", //
			hits.isEmpty() ? "-" : String.join(" ", hits.subList(0, Math.min(HITS, hits.size()))), //
			String.valueOf(mpBefore - attacker.getCurrentMp()), //
			String.valueOf(ammoBefore - ammo(attacker, weapon))) + (soulshots ? "\t" + (SOULSHOTS - (shots == null ? 0 : shots.getCount())) : "");

		attacker.removeListener(dealt);
		target.removeListener(avoided);
		attacker.deleteMe();
		target.deleteMe();
		return line;
	}

	/**
	 * How much of its weapon's ammunition {@code player} carries, 0 for a weapon that fires none.
	 */
	private static long ammo(Player player, Weapon weapon)
	{
		if ((weapon == null) || (weapon.pieces().size() < 2))
		{
			return 0;
		}
		final Item ammunition = player.getInventory().getItemByItemId(weapon.pieces().get(1).itemId());
		return ammunition == null ? 0 : ammunition.getCount();
	}

	/**
	 * The Vesper set of the armour category {@code playerClass} is in, or failing that the one of its nearest ancestor that is in one.
	 */
	private static Wear armourOf(PlayerClass playerClass)
	{
		final PlayerClass source = armourSource(playerClass);
		if (source == null)
		{
			return null;
		}
		for (int i = 0; i < ARMOUR_CATEGORIES.size(); i++)
		{
			if (CategoryData.getInstance().isInCategory(ARMOUR_CATEGORIES.get(i), source.getId()))
			{
				return ARMOURS.get(i);
			}
		}
		return null;
	}

	/**
	 * {@code playerClass} itself if it is in an armour category, else its nearest ancestor that is, else {@code null}.
	 */
	private static PlayerClass armourSource(PlayerClass playerClass)
	{
		for (PlayerClass current = playerClass; current != null; current = current.getParent())
		{
			for (CategoryType category : ARMOUR_CATEGORIES)
			{
				if (CategoryData.getInstance().isInCategory(category, current.getId()))
				{
					return current;
				}
			}
		}
		return null;
	}

	private static List<Weapon> weaponsAndNothing()
	{
		final List<Weapon> weapons = new ArrayList<>();
		weapons.add(null);
		weapons.addAll(TestGear.WEAPONS);
		return weapons;
	}
}
