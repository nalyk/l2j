package org.l2jmobius.gameserver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.actor.holders.creature.TimeStamp;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.livingchronicle.clock.GameScheduler;
import org.l2jmobius.gameserver.livingchronicle.rng.KeyedRandom;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.testing.Golden;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * LOCK-SKILL: what happens when each third class, at level 85, uses each of its skills.
 * <p>
 * Every case is a fresh character and a fresh target, so no case inherits anything from another. The character asks to use the skill the way the client does ({@code useMagic}), and the paused world is stepped from event to event until the cast is over, so the server's own checks, cast time, reuse, costs and effects are what get recorded. Harmful skills are aimed at a Maluk Warlord (level 85, one of the most spawned monsters of that level) standing in front of the caster; every other skill at the caster itself. Both are spawned, because the server will not let a character target anything that is not, in open sea far west of every spawn and outside every zone, so area skills find exactly the one monster and nothing else can interfere. The Warlord's own AI is switched off: what is recorded is the skill, not the fight it would start.
 * </p>
 * <p>
 * Gear comes from the skill, not from a guess about the class. The caster holds the first loadout, in the order of {@link #LOADOUTS}, with which the server's own condition check lets the skill be used: nothing at all first, then each Vesper weapon alone and with a Vesper shield or sigil, then the same over Vesper heavy, light and robe armour. A bow comes with Shining Arrows, a crossbow with Shining Bolts. No geodata is loaded, so line of sight is never in the way.
 * </p>
 */
class SkillLockTest
{
	private static final long START = 1_794_216_090_000L;
	private static final long SEED = 1;

	private static final int LEVEL = 85;
	private static final int MALUK_WARLORD = 22839;
	
	/** Open sea, about 196,000 from the nearest spawn of the datapack. */
	private static final int ARENA_X = -280000;
	private static final int ARENA_Y = -66000;
	private static final int ARENA_Z = -3500;

	/** A cast still going after this much game time is recorded as such and cut short. */
	private static final long CAST_LIMIT_MILLIS = 60_000;

	private static final int[] GEAR_SLOTS =
	{
		Inventory.PAPERDOLL_RHAND,
		Inventory.PAPERDOLL_LHAND,
		Inventory.PAPERDOLL_CHEST,
		Inventory.PAPERDOLL_LEGS
	};

	private record Piece(String name, int itemId, long count)
	{
	}

	private record Loadout(String name, List<Piece> pieces)
	{
	}

	private static final List<Loadout> LOADOUTS = loadouts();

	private static TestServer _server;

	/** Every third class, in class id order, with the skills a level 85 character of it can use, by skill id. */
	private static final Map<PlayerClass, List<Skill>> SKILLS = new LinkedHashMap<>();

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.bootPaused(START, SEED);
		if (!ZoneManager.getInstance().getZones(ARENA_X, ARENA_Y, ARENA_Z).isEmpty())
		{
			throw new IllegalStateException("the arena at " + ARENA_X + ", " + ARENA_Y + ", " + ARENA_Z + " is inside a zone now: " + ZoneManager.getInstance().getZones(ARENA_X, ARENA_Y, ARENA_Z));
		}

		final List<PlayerClass> classes = new ArrayList<>();
		for (PlayerClass playerClass : PlayerClass.values())
		{
			if ((playerClass.level() == 3) && (PlayerTemplateData.getInstance().getTemplate(playerClass) != null))
			{
				classes.add(playerClass);
			}
		}
		classes.sort(Comparator.comparingInt(PlayerClass::getId));

		for (PlayerClass playerClass : classes)
		{
			final Player probe = TestPlayers.create(playerClass, LEVEL);
			final List<Skill> skills = new ArrayList<>();
			for (Skill skill : probe.getSkills().values())
			{
				if (!skill.isPassive())
				{
					skills.add(skill);
				}
			}
			skills.sort(Comparator.comparingInt(Skill::getId));
			SKILLS.put(playerClass, skills);
			probe.deleteMe();
		}
	}

	@Test
	void everySkillGoesAsRecorded()
	{
		final List<String> lines = new ArrayList<>();
		lines.add("# LOCK-SKILL-0001: every skill a level " + LEVEL + " third class can use, used once by a fresh character (useMagic, then the world stepped until the cast is over) on a fresh Maluk Warlord in front of it if harmful, on itself if not,");
		lines.add("# holding the first loadout the skill's own conditions accept, on the stream (" + SEED + ", \"lock.skill\", class * 100000 + skill). Costs and losses are before minus after; effects are skill-level:abnormal seconds; - is none.");
		lines.add("class\tskill\tlevel\tname\ton\tgear\tresult\tcastMillis\treuseMillis\tmpSpent\thpSpent\ttargetHpLost\ttargetMpLost\ttargetDead\tcharges\tsouls\tcasterEffects\ttargetEffects\ttaskFailures");

		for (Map.Entry<PlayerClass, List<Skill>> entry : SKILLS.entrySet())
		{
			final PlayerClass playerClass = entry.getKey();
			final Map<Skill, Loadout> gear = chooseGear(playerClass, entry.getValue());
			for (Skill skill : entry.getValue())
			{
				lines.add(playerClass + "\t" + skill.getId() + "\t" + skill.getLevel() + "\t" + skill.getName() + "\t" + use(playerClass, skill, gear.get(skill)));
			}
		}
		Golden.repository().assertMatches("skill/third-class-use.tsv", lines);
	}

	/**
	 * For each skill, the first loadout with which the server's condition check accepts it, or {@code null} if none does.
	 */
	private static Map<Skill, Loadout> chooseGear(PlayerClass playerClass, List<Skill> skills)
	{
		final Player probe = TestPlayers.create(playerClass, LEVEL);
		final Monster target = target();
		face(probe, target);

		final Map<Skill, Loadout> chosen = new LinkedHashMap<>();
		final List<Skill> pending = new ArrayList<>(skills);
		for (Loadout loadout : LOADOUTS)
		{
			if (pending.isEmpty())
			{
				break;
			}

			wear(probe, loadout);
			pending.removeIf(skill ->
			{
				if (skill.checkCondition(probe, skill.hasNegativeEffect() ? target : probe, false))
				{
					chosen.put(skill, loadout);
					return true;
				}
				return false;
			});
		}

		probe.deleteMe();
		target.deleteMe();
		return chosen;
	}

	private static String use(PlayerClass playerClass, Skill skill, Loadout loadout)
	{
		final Player caster = TestPlayers.create(playerClass, LEVEL);
		if (loadout != null)
		{
			wear(caster, loadout);
		}
		caster.setCurrentHpMp(caster.getMaxHp(), caster.getMaxMp());
		caster.setCurrentCp(caster.getMaxCp());

		final Monster target = target();
		face(caster, target);
		final boolean harmful = skill.hasNegativeEffect();
		caster.setTarget(harmful ? target : caster);

		final double mpBefore = caster.getCurrentMp();
		final double hpBefore = caster.getCurrentHp();
		final double targetHpBefore = target.getCurrentHp();
		final double targetMpBefore = target.getCurrentMp();
		final int failuresBefore = GameScheduler.failures().size();
		final long start = GameClock.millis();

		final boolean[] accepted = new boolean[1];
		_server.withRandom(KeyedRandom.of(SEED, "lock.skill", (playerClass.getId() * 100_000L) + skill.getId()), () ->
		{
			accepted[0] = caster.useMagic(skill, false, true);
			while ((caster.isCastingNow() || caster.isCastingSimultaneouslyNow()) && ((GameClock.millis() - start) < CAST_LIMIT_MILLIS))
			{
				final long next = Math.min(GameScheduler.nextDue(), start + CAST_LIMIT_MILLIS);
				GameScheduler.advance(Math.max(0, next - GameClock.millis()));
			}
		});

		final String result;
		if (!accepted[0])
		{
			result = "refused";
		}
		else if (caster.isCastingNow() || caster.isCastingSimultaneouslyNow())
		{
			result = "still casting";
			caster.abortCast();
		}
		else
		{
			result = "used";
		}

		final TimeStamp reuse = caster.getSkillReuseTimeStamps().get(skill.getReuseHashCode());
		final List<String> failures = GameScheduler.failures();
		final StringJoiner taskFailures = new StringJoiner(" ");
		for (String failure : failures.subList(failuresBefore, failures.size()))
		{
			// "task class: throwable", of which only the throwable's class is stable from run to run.
			final String throwable = failure.substring(failure.indexOf(": ") + 2);
			taskFailures.add(throwable.contains(":") ? throwable.substring(0, throwable.indexOf(':')) : throwable);
		}

		final String line = String.join("\t", //
			harmful ? "target" : "self", //
			loadout == null ? "(no loadout fits)" : loadout.name(), //
			result, //
			String.valueOf(GameClock.millis() - start), //
			reuse == null ? "-" : String.valueOf(reuse.getReuse()), //
			String.valueOf(mpBefore - caster.getCurrentMp()), //
			String.valueOf(hpBefore - caster.getCurrentHp()), //
			String.valueOf(targetHpBefore - target.getCurrentHp()), //
			String.valueOf(targetMpBefore - target.getCurrentMp()), //
			String.valueOf(target.isDead()), //
			String.valueOf(caster.getCharges()), //
			String.valueOf(caster.getChargedSouls()), //
			effects(caster), //
			effects(target), //
			taskFailures.length() == 0 ? "-" : taskFailures.toString());

		if (caster.hasSummon())
		{
			caster.getSummon().unSummon(caster);
		}
		caster.deleteMe();
		target.deleteMe();
		return line;
	}

	/**
	 * A fresh reference target at full health, with its AI switched off, spawned in the arena facing east.
	 */
	private static Monster target()
	{
		final Monster target = new Monster(NpcData.getInstance().getTemplate(MALUK_WARLORD));
		target.disableCoreAI(true);
		target.setCurrentHpMp(target.getMaxHp(), target.getMaxMp());
		target.setHeading(0);
		target.spawnMe(ARENA_X, ARENA_Y, ARENA_Z);
		return target;
	}

	/**
	 * Spawns {@code player} close in front of {@code target}, facing it.
	 */
	private static void face(Player player, Monster target)
	{
		player.setHeading(32768);
		player.spawnMe(target.getX() + 30, target.getY(), target.getZ());
	}

	private static void wear(Player player, Loadout loadout)
	{
		final Inventory inventory = player.getInventory();
		for (int slot : GEAR_SLOTS)
		{
			inventory.unEquipItemInSlot(slot);
		}

		for (Piece piece : loadout.pieces())
		{
			Item item = inventory.getItemByItemId(piece.itemId());
			if (item == null)
			{
				item = inventory.addItem(ItemProcessType.NONE, piece.itemId(), piece.count(), player, null);
			}
			inventory.equipItem(item);
		}
	}

	private static String effects(Creature creature)
	{
		final List<BuffInfo> infos = new ArrayList<>(creature.getEffectList().getEffects());
		if (infos.isEmpty())
		{
			return "-";
		}

		infos.sort(Comparator.comparingInt((BuffInfo info) -> info.getSkill().getId()).thenComparingInt(info -> info.getSkill().getLevel()));
		final StringJoiner text = new StringJoiner(" ");
		for (BuffInfo info : infos)
		{
			text.add(info.getSkill().getId() + "-" + info.getSkill().getLevel() + ":" + info.getAbnormalTime());
		}
		return text.toString();
	}

	/**
	 * Nothing, then every Vesper weapon alone and with a shield or a sigil when it is one-handed, over no armour, then heavy, light and robe armour.
	 */
	private static List<Loadout> loadouts()
	{
		final List<List<Piece>> armours = List.of( //
			List.of(), //
			List.of(new Piece("heavy", 13432, 1), new Piece("", 13438, 1)), //
			List.of(new Piece("light", 13433, 1), new Piece("", 13441, 1)), //
			List.of(new Piece("robe", 13434, 1), new Piece("", 13444, 1)));

		final List<List<Piece>> weapons = List.of( //
			List.of(), //
			List.of(new Piece("sword", 13457, 1)), //
			List.of(new Piece("two-handed sword", 13458, 1)), //
			List.of(new Piece("blunt", 13463, 1)), //
			List.of(new Piece("two-handed blunt", 13464, 1)), //
			List.of(new Piece("dagger", 13460, 1)), //
			List.of(new Piece("dual daggers", 13884, 1)), //
			List.of(new Piece("dual swords", 52, 1)), //
			List.of(new Piece("fists", 13461, 1)), //
			List.of(new Piece("polearm", 13462, 1)), //
			List.of(new Piece("bow", 13467, 1), new Piece("", 1345, 1000)), //
			List.of(new Piece("crossbow", 13469, 1), new Piece("", 9637, 1000)), //
			List.of(new Piece("rapier", 13468, 1)), //
			List.of(new Piece("ancient sword", 13470, 1)));

		final List<String> oneHanded = List.of("sword", "blunt", "dagger", "rapier");
		final List<List<Piece>> offHands = List.of(List.of(new Piece("shield", 13471, 1)), List.of(new Piece("sigil", 12813, 1)));

		final List<Loadout> loadouts = new ArrayList<>();
		for (List<Piece> armour : armours)
		{
			for (List<Piece> weapon : weapons)
			{
				final List<List<Piece>> offHandChoices = new ArrayList<>();
				offHandChoices.add(List.of());
				if (weapon.isEmpty() || oneHanded.contains(weapon.get(0).name()))
				{
					offHandChoices.addAll(offHands);
				}

				for (List<Piece> offHand : offHandChoices)
				{
					final List<Piece> pieces = new ArrayList<>();
					pieces.addAll(weapon);
					pieces.addAll(offHand);
					pieces.addAll(armour);

					final StringJoiner name = new StringJoiner("+");
					for (Piece piece : pieces)
					{
						if (!piece.name().isEmpty())
						{
							name.add(piece.name());
						}
					}
					loadouts.add(new Loadout(name.length() == 0 ? "-" : name.toString(), List.copyOf(pieces)));
				}
			}
		}
		return List.copyOf(loadouts);
	}
}
