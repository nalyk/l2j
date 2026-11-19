package org.l2jmobius.gameserver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.actor.holders.creature.TimeStamp;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.livingchronicle.clock.GameScheduler;
import org.l2jmobius.gameserver.livingchronicle.rng.KeyedRandom;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.testing.Arena;
import org.l2jmobius.testing.Golden;
import org.l2jmobius.testing.TestGear;
import org.l2jmobius.testing.TestGear.Piece;
import org.l2jmobius.testing.TestGear.Weapon;
import org.l2jmobius.testing.TestGear.Wear;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * LOCK-SKILL: what happens when each third class, at level 85, uses each of its skills.
 * <p>
 * Every case is a fresh character and a fresh target, so no case inherits anything from another. The character asks to use the skill the way the client does ({@code useMagic}), and the paused world is stepped from event to event until the cast is over, so the server's own checks, cast time, reuse, costs and effects are what get recorded. Harmful skills are aimed at a Maluk Warlord (level 85, one of the most spawned monsters of that level) standing in front of the caster; every other skill at the caster itself. Both are spawned, because the server will not let a character target anything that is not, in open sea far west of every spawn and outside every zone, so area skills find exactly the one monster and nothing else can interfere. The Warlord's own AI is switched off: what is recorded is the skill, not the fight it would start.
 * </p>
 * <p>
 * Gear comes from the skill, not from a guess about the class. The caster holds the first loadout, in the order of {@link #LOADOUTS}, with which the server's own condition check lets the skill be used: nothing at all first, then each Vesper weapon alone and with a Vesper shield or sigil, then the same in the Vesper heavy, light and robe sets with Vesper jewels ({@link TestGear}). A bow comes with Shining Arrows, a crossbow with Shining Bolts. No geodata is loaded, so line of sight is never in the way.
 * </p>
 */
class SkillLockTest
{
	private static final long START = 1_794_216_090_000L;
	private static final long SEED = 1;

	private static final int LEVEL = 85;
	private static final int MALUK_WARLORD = 22839;

	/** Soulshot (S-Grade) and Blessed Spiritshot (S-Grade), the grade of every Vesper weapon, and how many of each a case with shots carries. */
	private static final int SOULSHOT_S = 1467;
	private static final int BLESSED_SPIRITSHOT_S = 3952;
	private static final long SHOTS = 1000;
	
	/** A cast still going after this much game time is recorded as such and cut short. */
	private static final long CAST_LIMIT_MILLIS = 60_000;

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
		Arena.checkEmpty();
		Arena.wake();

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
	 * LOCK-SKILL-0002: the same casts with shots, as a player has them: S-grade soulshots and blessed spiritshots in the bag, set to load themselves, loaded before the cast. What a skill spends of each shows which it draws power from.
	 */
	@Test
	void everySkillGoesAsRecordedWithShotsLoaded()
	{
		final List<String> lines = new ArrayList<>();
		lines.add("# LOCK-SKILL-0002: as LOCK-SKILL-0001, with " + SHOTS + " Soulshots (S-Grade) and " + SHOTS + " Blessed Spiritshots (S-Grade) set to load themselves and loaded before the cast, on the stream (" + SEED + ", \"lock.skill.shots\", class * 100000 + skill).");
		lines.add("class\tskill\tlevel\tname\ton\tgear\tresult\tcastMillis\treuseMillis\tmpSpent\thpSpent\ttargetHpLost\ttargetMpLost\ttargetDead\tcharges\tsouls\tcasterEffects\ttargetEffects\ttaskFailures\tsoulshotsSpent\tspiritshotsSpent");

		for (Map.Entry<PlayerClass, List<Skill>> entry : SKILLS.entrySet())
		{
			final PlayerClass playerClass = entry.getKey();
			final Map<Skill, Loadout> gear = chooseGear(playerClass, entry.getValue());
			for (Skill skill : entry.getValue())
			{
				final String[] line = new String[1];
				Arena.align();
				_server.withRandom(KeyedRandom.of(SEED, "lock.skill.shots", (playerClass.getId() * 100_000L) + skill.getId()), () -> line[0] = useOnItsStream(playerClass, skill, gear.get(skill), true));
				lines.add(playerClass + "\t" + skill.getId() + "\t" + skill.getLevel() + "\t" + skill.getName() + "\t" + line[0]);
			}
		}
		Golden.repository().assertMatches("skill/third-class-use-shots.tsv", lines);
	}

	/**
	 * For each skill, the first loadout with which the server's condition check accepts it, or {@code null} if none does.
	 */
	private static Map<Skill, Loadout> chooseGear(PlayerClass playerClass, List<Skill> skills)
	{
		final Player probe = TestPlayers.create(playerClass, LEVEL);
		final Monster target = Arena.monster(MALUK_WARLORD);
		Arena.placeInFront(probe, target);

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

	/**
	 * One case, all of it on its own stream: making and dressing the caster, setting the scene, the cast, the clean-up. What the scene's setting-up sets going (the regions it wakes) draws on the case's stream too, not on whatever the world has drawn by then.
	 */
	private static String use(PlayerClass playerClass, Skill skill, Loadout loadout)
	{
		final String[] line = new String[1];
		Arena.align();
		_server.withRandom(KeyedRandom.of(SEED, "lock.skill", (playerClass.getId() * 100_000L) + skill.getId()), () -> line[0] = useOnItsStream(playerClass, skill, loadout, false));
		return line[0];
	}

	/**
	 * How many of the {@link #SHOTS} shots of {@code shot} a case gave {@code caster} are gone.
	 */
	private static long spent(Player caster, int shot)
	{
		final Item item = caster.getInventory().getItemByItemId(shot);
		return SHOTS - (item == null ? 0 : item.getCount());
	}

	private static String useOnItsStream(PlayerClass playerClass, Skill skill, Loadout loadout, boolean shots)
	{
		final Player caster = TestPlayers.create(playerClass, LEVEL);
		if (loadout != null)
		{
			wear(caster, loadout);
		}
		caster.setCurrentHpMp(caster.getMaxHp(), caster.getMaxMp());
		caster.setCurrentCp(caster.getMaxCp());
		if (shots)
		{
			for (int shot : new int[]
			{
				SOULSHOT_S,
				BLESSED_SPIRITSHOT_S
			})
			{
				caster.getInventory().addItem(ItemProcessType.NONE, shot, SHOTS, caster, null);
				caster.addAutoSoulShot(shot);
			}
			caster.rechargeShots(true, true);
		}

		final Monster target = Arena.monster(MALUK_WARLORD);
		Arena.placeInFront(caster, target);
		final boolean harmful = skill.hasNegativeEffect();
		caster.setTarget(harmful ? target : caster);

		final double mpBefore = caster.getCurrentMp();
		final double hpBefore = caster.getCurrentHp();
		final double targetHpBefore = target.getCurrentHp();
		final double targetMpBefore = target.getCurrentMp();
		final int failuresBefore = GameScheduler.failures().size();
		final long start = GameClock.millis();

		final boolean accepted = caster.useMagic(skill, false, true);
		Arena.runWhile(() -> caster.isCastingNow() || caster.isCastingSimultaneouslyNow(), CAST_LIMIT_MILLIS);

		final String result;
		if (!accepted)
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
			taskFailures.length() == 0 ? "-" : taskFailures.toString()) + (shots ? "\t" + spent(caster, SOULSHOT_S) + "\t" + spent(caster, BLESSED_SPIRITSHOT_S) : "");

		if (caster.hasSummon())
		{
			caster.getSummon().unSummon(caster);
		}
		caster.deleteMe();
		target.deleteMe();
		return line;
	}

	private static void wear(Player player, Loadout loadout)
	{
		TestGear.wear(player, loadout.pieces());
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
		final List<Wear> armours = new ArrayList<>();
		armours.add(null);
		armours.addAll(List.of(TestGear.HEAVY, TestGear.LIGHT, TestGear.ROBE));
		
		final List<Weapon> weapons = new ArrayList<>();
		weapons.add(null);
		weapons.addAll(TestGear.WEAPONS);
		
		final List<Loadout> loadouts = new ArrayList<>();
		for (Wear armour : armours)
		{
			for (Weapon weapon : weapons)
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
					final StringJoiner name = new StringJoiner("+");
					if (weapon != null)
					{
						pieces.addAll(weapon.pieces());
						name.add(weapon.name());
					}
					if (offHand != null)
					{
						pieces.addAll(offHand.pieces());
						name.add(offHand.name());
					}
					if (armour != null)
					{
						pieces.addAll(armour.pieces());
						name.add(armour.name());
					}
					loadouts.add(new Loadout(name.length() == 0 ? "-" : name.toString(), List.copyOf(pieces)));
				}
			}
		}
		return List.copyOf(loadouts);
	}
}
