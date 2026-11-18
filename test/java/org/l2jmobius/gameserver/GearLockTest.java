package org.l2jmobius.gameserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.data.enums.CategoryType;
import org.l2jmobius.gameserver.data.xml.CategoryData;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.livingchronicle.clock.GameScheduler;
import org.l2jmobius.gameserver.livingchronicle.rng.KeyedRandom;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureAttackAvoid;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDamageDealt;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.testing.Arena;
import org.l2jmobius.testing.Golden;
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

	private static final List<CategoryType> ARMOUR_CATEGORIES = List.of(CategoryType.HEAVY_ARMOR_CLASS, CategoryType.LIGHT_ARMOR_CLASS, CategoryType.ROBE_CLASS);

	private static TestServer _server;

	/** Every third class, in class id order. */
	private static final List<PlayerClass> CLASSES = new ArrayList<>();

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.bootPaused(START, SEED);
		Arena.checkEmpty();

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
	 * A case's outcome is a function of the case: the same attack, run again after the world has gone on by itself for ten minutes, its own timers drawing on its own stream, lands the same hits at the same moments.
	 */
	@Test
	void aCaseComesOutTheSameAfterTheWorldHasMovedOn()
	{
		final PlayerClass playerClass = CLASSES.get(0);
		final String before = attack(playerClass, armourOf(playerClass), TestGear.WEAPONS.get(0), 1);
		GameScheduler.advance(600_000);
		assertEquals(before, attack(playerClass, armourOf(playerClass), TestGear.WEAPONS.get(0), 1));
	}

	private static String attack(PlayerClass playerClass, Wear armour, Weapon weapon, int weaponIndex)
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
		_server.withRandom(KeyedRandom.of(SEED, "lock.combat.auto", (playerClass.getId() * 100L) + weaponIndex), () ->
		{
			attacker.getAI().setIntentionAttack(target);
			Arena.runWhile(() -> hits.size() < HITS, ATTACK_LIMIT_MILLIS);
			attacker.abortAttack();
			attacker.getAI().setIntentionIdle();
		});

		final String line = String.join("\t", //
			hits.isEmpty() ? "-" : String.join(" ", hits.subList(0, Math.min(HITS, hits.size()))), //
			String.valueOf(mpBefore - attacker.getCurrentMp()), //
			String.valueOf(ammoBefore - ammo(attacker, weapon)));

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
