package org.l2jmobius.testing.truth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * AT-RT-06 on the real datapack (docs/spec/retail-truth.md §4.5): the committed inventory equals a regeneration, and its ids
 * equal the quest directories, instance template ids, raid and grand boss ids and systems.in lines, counted independently.
 */
class InventoryCurrentTest
{
	private static final Map<String, String> KEYS = Map.of("quests", "id", "instances", "id", "bosses", "id", "systems", "key");

	private static Path repository()
	{
		return Path.of(System.getProperty("lc.docs.dir")).getParent();
	}

	@Test
	void theCommittedInventoryIsARegeneration() throws IOException
	{
		final Path repo = repository();
		final Path inventory = repo.resolve("truth/inventory");
		final Map<String, List<Map<String, String>>> generated = Inventory.generate(repo.resolve("dist/game/data"), repo.resolve("dist/db_installer/sql/game"), List.of(repo.resolve("java/org/l2jmobius/gameserver")), inventory.resolve("systems.in"));
		final List<String> differing = new ArrayList<>();
		for (Map.Entry<String, List<Map<String, String>>> table : generated.entrySet())
		{
			final Path committedFile = inventory.resolve(table.getKey() + ".csv");
			final List<Map<String, String>> committed = Files.exists(committedFile) ? Inventory.read(committedFile) : List.of();
			final List<Map<String, String>> merged = Inventory.merge(committed, table.getValue(), KEYS.get(table.getKey()));
			if (!merged.equals(committed))
			{
				final Path actual = Path.of(System.getProperty("lc.inventory.actual"));
				Files.createDirectories(actual);
				Inventory.write(merged, actual.resolve(table.getKey() + ".csv"));
				differing.add(table.getKey());
			}
		}
		assertEquals(List.of(), differing, "the committed inventory differs from a regeneration of the datapack; the regeneration is in " + System.getProperty("lc.inventory.actual") + " for review and commit");
	}

	@Test
	void theIdsAreTheDatapacksCountedIndependently() throws IOException
	{
		final Path repo = repository();
		final Path inventory = repo.resolve("truth/inventory");
		// Quests counted from the quest classes' own declarations, not from file or directory names.
		final Set<String> quests = new TreeSet<>();
		try (Stream<Path> files = Files.walk(repo.resolve("dist/game/data/scripts/quests")))
		{
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList())
			{
				final Matcher m = Pattern.compile("public\\s+(?:final\\s+|abstract\\s+)*class\\s+Q(\\d{5})_\\w+").matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
				if (m.find())
				{
					quests.add(String.valueOf(Integer.parseInt(m.group(1))));
				}
			}
		}
		assertEquals(quests, generatedIds(inventory.resolve("quests.csv"), "id"), "quests");
		assertEquals(matches(repo.resolve("dist/game/data/instances"), Pattern.compile("<instance\\s+id=\"(\\d+)\"")), generatedIds(inventory.resolve("instances.csv"), "id"), "instances");
		assertEquals(matches(repo.resolve("dist/game/data/stats/npcs"), Pattern.compile("<npc\\s+id=\"(\\d+)\"[^>]*\\stype=\"(?:RaidBoss|GrandBoss)\"")), generatedIds(inventory.resolve("bosses.csv"), "id"), "bosses");
		final Set<String> systems = new TreeSet<>();
		for (String line : Files.readAllLines(inventory.resolve("systems.in"), StandardCharsets.UTF_8))
		{
			if (!line.isBlank() && !line.strip().startsWith("#"))
			{
				systems.add(line.split("\\|")[0].strip());
			}
		}
		assertEquals(systems, generatedIds(inventory.resolve("systems.csv"), "key"), "systems");
		assertTrue(quests.size() > 500, "the datapack's quests were found");
		for (String table : KEYS.keySet())
		{
			final List<String> keys = Inventory.read(inventory.resolve(table + ".csv")).stream().map(r -> r.get(KEYS.get(table))).toList();
			assertEquals(keys.size(), new TreeSet<>(keys).size(), table + " holds a duplicate key");
		}
	}

	@Test
	void knownFactsOfTheDatapackHold()
	{
		final Path inventory = repository().resolve("truth/inventory");
		final Map<String, Map<String, String>> instances = Inventory.read(inventory.resolve("instances.csv")).stream().collect(Collectors.toMap(r -> r.get("id"), r -> r));
		assertTrue(instances.get("22").get("scripts").contains("Q00511_AwlUnderFoot"), "fortress dungeon 22 is created by Q00511 through a dungeon holder");
		assertTrue(!instances.get("2").get("scripts").contains("DefaultPageHandler"), "a singleton named INSTANCE is not a template reference");
		for (int id = 101; id <= 109; id++)
		{
			assertEquals("no source names this template", instances.get(String.valueOf(id)).get("finding"), "template " + id);
		}
		final Map<String, Map<String, String>> bosses = Inventory.read(inventory.resolve("bosses.csv")).stream().collect(Collectors.toMap(r -> r.get("id"), r -> r));
		assertEquals("placed by no spawn, table or script", bosses.get("25073").get("finding"), "Bloody Priest Rudelto's only table row is commented out");
		assertTrue(bosses.get("25333").get("placed_by").contains("dimensional_rift"), "Anakazel is placed by the Dimensional Rift");
		final Map<String, Map<String, String>> quests = Inventory.read(inventory.resolve("quests.csv")).stream().collect(Collectors.toMap(r -> r.get("id"), r -> r));
		assertTrue(quests.containsKey("717"), "Territory War quests live in a shared directory");
		assertEquals("placeholder quest", quests.get("57").get("finding"));
	}

	private static Set<String> generatedIds(Path csv, String key)
	{
		return Inventory.read(csv).stream().filter(r -> "GENERATED".equals(r.get("source"))).map(r -> r.get(key)).collect(Collectors.toCollection(TreeSet::new));
	}

	private static Set<String> matches(Path dir, Pattern pattern) throws IOException
	{
		final Set<String> ids = new TreeSet<>();
		try (Stream<Path> files = Files.walk(dir))
		{
			for (Path file : files.filter(f -> f.toString().endsWith(".xml")).toList())
			{
				final Matcher m = pattern.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
				while (m.find())
				{
					ids.add(m.group(1));
				}
			}
		}
		return ids;
	}
}
