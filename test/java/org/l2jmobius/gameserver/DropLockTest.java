package org.l2jmobius.gameserver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.enums.npc.DropType;
import org.l2jmobius.gameserver.entity.actor.instance.GrandBoss;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.actor.instance.RaidBoss;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;
import org.l2jmobius.gameserver.livingchronicle.rng.KeyedRandom;
import org.l2jmobius.testing.Golden;
import org.l2jmobius.testing.TestServer;

/**
 * LOCK-DROP: what a monster leaves when it dies, and what it yields to a spoiler.
 * <p>
 * Every monster, raid boss and grand boss with a drop or spoil list is built from its template the way a spawn builds it and has its drops rolled by the template's own calculation, on a random stream of the victim's own. The killer is another monster, so no player bonus (premium, drop rate effects) takes part: those belong to a lock with a player in it.
 * </p>
 */
class DropLockTest
{
	private static final long START = 1_794_216_090_000L;
	private static final long SEED = 1;

	private static final int KILLS = 5;

	/** How far above the victim the killer stands. 0 drops at full chance; 7 is inside the item penalty band (5 to 10) and outside the adena one (8 to 15); 12 is past the item band and inside the adena one. */
	private static final int[] LEVEL_GAPS =
	{
		0,
		7,
		12
	};

	/** One victim in this many is also killed from above; every victim is killed at its own level. */
	private static final int GAP_STRIDE = 10;

	private static TestServer _server;

	/** Every template with something to drop or spoil, by id, with one instance built from it. */
	private static final List<Attackable> VICTIMS = new ArrayList<>();

	/** One monster of each level, the lowest id of that level, to stand as killer. */
	private static final Map<Integer, Monster> KILLERS = new TreeMap<>();

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.bootPaused(START, SEED);

		final List<NpcTemplate> templates = new ArrayList<>(NpcData.getInstance().getTemplates(template -> template.isType("Monster") || template.isType("RaidBoss") || template.isType("GrandBoss")));
		templates.sort(Comparator.comparingInt(NpcTemplate::getId));
		for (NpcTemplate template : templates)
		{
			if (template.isType("Monster"))
			{
				KILLERS.putIfAbsent((int) template.getLevel(), new Monster(template));
			}

			if ((template.getDropGroups() == null) && (template.getDropList() == null) && (template.getSpoilList() == null))
			{
				continue;
			}

			final Attackable victim = template.isType("GrandBoss") ? new GrandBoss(template) : template.isType("RaidBoss") ? new RaidBoss(template) : new Monster(template);
			VICTIMS.add(victim);
		}
	}

	@Test
	void deathDropsGoAsRecorded()
	{
		roll(DropType.DROP, "drop/death.tsv", "LOCK-DROP-0001", "lock.drop.death");
	}

	@Test
	void spoilsGoAsRecorded()
	{
		roll(DropType.SPOIL, "drop/spoil.tsv", "LOCK-DROP-0002", "lock.drop.spoil");
	}

	private void roll(DropType type, String golden, String lockId, String stream)
	{
		final List<String> lines = new ArrayList<>();
		lines.add("# " + lockId + ": " + type + " rolled " + KILLS + " times for every monster, raid boss and grand boss that has a " + (type == DropType.DROP ? "drop" : "spoil") + " list, by a monster killer at the victim's level,");
		lines.add("# and for one victim in " + GAP_STRIDE + " also by killers 7 and 12 levels above it, on the stream (" + SEED + ", \"" + stream + "\", victim * 100 + gap). Items are id x count, in the order the server returns them; - is nothing.");
		lines.add("victim\tname\ttype\tlevel\tgap\tkill\titems");

		for (int v = 0; v < VICTIMS.size(); v++)
		{
			final Attackable victim = VICTIMS.get(v);
			final NpcTemplate template = victim.getTemplate();
			if (((type == DropType.DROP) && (template.getDropGroups() == null) && (template.getDropList() == null)) || ((type == DropType.SPOIL) && (template.getSpoilList() == null)))
			{
				continue;
			}

			for (int gap : LEVEL_GAPS)
			{
				if ((gap != 0) && ((v % GAP_STRIDE) != 0))
				{
					continue;
				}

				final Monster killer = KILLERS.get(victim.getLevel() + gap);
				if (killer == null)
				{
					lines.add(victim.getId() + "\t" + template.getName() + "\t" + template.getType() + "\t" + victim.getLevel() + "\t" + gap + "\t-\tno monster of level " + (victim.getLevel() + gap) + " to kill it");
					continue;
				}

				_server.withRandom(KeyedRandom.of(SEED, stream, (victim.getId() * 100L) + gap), () ->
				{
					for (int kill = 1; kill <= KILLS; kill++)
					{
						final List<ItemHolder> items = template.calculateDrops(type, victim, killer);
						final StringBuilder text = new StringBuilder();
						if ((items == null) || items.isEmpty())
						{
							text.append('-');
						}
						else
						{
							for (ItemHolder item : items)
							{
								if (text.length() > 0)
								{
									text.append(' ');
								}
								text.append(item.getId()).append('x').append(item.getCount());
							}
						}
						lines.add(victim.getId() + "\t" + template.getName() + "\t" + template.getType() + "\t" + victim.getLevel() + "\t" + gap + "\t" + kill + "\t" + text);
					}
				});
			}
		}
		Golden.repository().assertMatches(golden, lines);
	}
}
