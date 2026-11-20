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
 * AT-RT-06 on the real datapack (docs/spec/retail-truth.md §4.5): the committed inventory equals a regeneration with the cases' judgments; its ids
 * equal the quest classes, instance template ids, raid and grand boss ids and systems.in lines, counted independently; the systems the spec names
 * are listed; every boss placement and instance reference is confirmed by a reading independent of the generator; known facts are pinned.
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
		final Map<String, Map<String, List<Inventory.Judgment>>> judgments = Inventory.judgments(repo.resolve("truth/cases"));
		final Map<String, List<Map<String, String>>> generated = Inventory.generate(repo.resolve("dist/game/data"), repo.resolve("dist/db_installer/sql/game"), List.of(repo.resolve("java/org/l2jmobius/gameserver")), inventory.resolve("systems.in"));
		final List<String> differing = new ArrayList<>();
		for (Map.Entry<String, List<Map<String, String>>> table : generated.entrySet())
		{
			final Path committedFile = inventory.resolve(table.getKey() + ".csv");
			final List<Map<String, String>> committed = Files.exists(committedFile) ? Inventory.read(committedFile) : List.of();
			final List<Map<String, String>> merged = Inventory.merge(committed, table.getValue(), KEYS.get(table.getKey()), judgments.getOrDefault(table.getKey(), Map.of()));
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
		// Spot checks an independent reviewer made by reading the templates, scripts and tables (review round 1).
		assertEquals("dist/game/data/scripts/instances/ChambersOfDelusion/ChamberOfDelusionEast.java", instances.get("127").get("scripts"));
		assertEquals("dist/game/data/scripts/instances/JiniaGuildHideout4/JiniaGuildHideout4.java", instances.get("146").get("scripts"));
		assertEquals("dist/game/data/scripts/instances/SSQSanctumOfTheLordsOfDawn/SSQSanctumOfTheLordsOfDawn.java", instances.get("111").get("scripts"));
		assertEquals("dist/game/data/scripts/quests/Q00726_LightWithinTheDarkness/Q00726_LightWithinTheDarkness.java;dist/game/data/scripts/quests/Q00727_HopeWithinTheDarkness/Q00727_HopeWithinTheDarkness.java", instances.get("88").get("scripts"));
		assertEquals("dist/game/data/scripts/instances/Kamaloka/Kamaloka.java", instances.get("64").get("scripts"));
		assertEquals("raidboss_spawnlist", bosses.get("25188").get("placed_by"), "Apepi");
		assertEquals("raidboss_spawnlist", bosses.get("25143").get("placed_by"), "Fire of Wrath Shuriel");
		assertEquals("scripts", bosses.get("29177").get("placed_by"), "Freya: her grandboss_data row is commented out; the Ice Queen's Castle script spawns her");
		assertEquals("scripts", bosses.get("25557").get("placed_by"), "Ranger Karankawa");
		assertEquals("scripts", bosses.get("25569").get("placed_by"), "Jax the Destroyer");
	}

	/** The world systems blueprint 11 §1.5 and docs/spec/retail-truth.md §4.5 name: systems.in may add to them, never drop one. */
	private static final Set<String> REQUIRED_SYSTEMS = Set.of("seven_signs", "seven_signs_festival", "hellbound", "seed_of_destruction", "seed_of_infinity", "seed_of_annihilation", "castle_siege", "fortress_siege", "territory_war", "olympiad", "manor", "clan_hall_auction", "clan_hall_siege", "fishing", "underground_coliseum", "kratei_cube", "lottery", "castle_tax_treasury", "raid_boss_respawn", "clan_reputation_skills");

	@Test
	void theSystemsTheSpecNamesAreListed()
	{
		final Set<String> listed = Inventory.read(repository().resolve("truth/inventory/systems.csv")).stream().map(r -> r.get("key")).collect(Collectors.toSet());
		final Set<String> absent = new TreeSet<>(REQUIRED_SYSTEMS);
		absent.removeAll(listed);
		assertEquals(Set.of(), absent, "systems the spec requires and the inventory does not list");
	}

	@Test
	void everyBossPlacementIsConfirmedByAnIndependentReading() throws Exception
	{
		final Path repo = repository();
		// Spawns and the Rift through a DOM parser, which never sees a comment; the tables line by line, a commented line dropped.
		final Set<String> spawns = new TreeSet<>();
		try (Stream<Path> files = Files.walk(repo.resolve("dist/game/data/spawns")))
		{
			for (Path file : files.filter(f -> f.toString().endsWith(".xml")).toList())
			{
				attributes(file, "npc", "id", spawns);
			}
		}
		final Set<String> rift = new TreeSet<>();
		attributes(repo.resolve("dist/game/data/DimensionalRift.xml"), "spawn", "mobId", rift);
		final Map<String, Set<String>> placements = Map.of("spawns", spawns, "dimensional_rift", rift, "raidboss_spawnlist", tableRows(repo.resolve("dist/db_installer/sql/game/raidboss_spawnlist.sql")), "grandboss_data", tableRows(repo.resolve("dist/db_installer/sql/game/grandboss_data.sql")));
		final List<String> wrong = new ArrayList<>();
		for (Map<String, String> boss : Inventory.read(repo.resolve("truth/inventory/bosses.csv")))
		{
			final List<String> placedBy = List.of(boss.get("placed_by").split(";"));
			for (Map.Entry<String, Set<String>> placement : placements.entrySet())
			{
				if (placement.getValue().contains(boss.get("id")) != placedBy.contains(placement.getKey()))
				{
					wrong.add(boss.get("id") + " " + placement.getKey());
				}
			}
		}
		assertEquals(List.of(), wrong, "bosses whose recorded placement an independent reading does not confirm");
	}

	@Test
	void everyInstanceReferenceNamesItsTemplateInCode() throws IOException
	{
		final Path repo = repository();
		final List<String> wrong = new ArrayList<>();
		for (Map<String, String> instance : Inventory.read(repo.resolve("truth/inventory/instances.csv")))
		{
			for (String script : instance.get("scripts").isEmpty() ? new String[0] : instance.get("scripts").split(";"))
			{
				final String code = new String(Files.readAllBytes(repo.resolve(script)), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
				if (!Pattern.compile("(?<![\\w.-])" + instance.get("id") + "(?![\\w.])").matcher(code).find())
				{
					wrong.add(instance.get("id") + " " + script);
				}
			}
		}
		assertEquals(List.of(), wrong, "instance references whose source does not hold the template id outside a comment");
	}

	private static void attributes(Path xml, String element, String attribute, Set<String> into) throws Exception
	{
		final javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		final org.w3c.dom.NodeList nodes = factory.newDocumentBuilder().parse(xml.toFile()).getElementsByTagName(element);
		for (int i = 0; i < nodes.getLength(); i++)
		{
			final String value = ((org.w3c.dom.Element) nodes.item(i)).getAttribute(attribute);
			if (!value.isEmpty())
			{
				into.add(value);
			}
		}
	}

	private static Set<String> tableRows(Path sql) throws IOException
	{
		final Set<String> ids = new TreeSet<>();
		boolean inserting = false;
		for (String line : Files.readAllLines(sql, StandardCharsets.UTF_8))
		{
			final String trimmed = line.strip();
			if (trimmed.toUpperCase().startsWith("INSERT"))
			{
				inserting = true;
			}
			if (!inserting || trimmed.startsWith("--"))
			{
				continue;
			}
			final Matcher row = Pattern.compile("\\(\\s*(\\d+)\\s*,").matcher(trimmed.startsWith("INSERT") ? trimmed.substring(trimmed.toUpperCase().indexOf("VALUES")) : trimmed);
			while (row.find())
			{
				ids.add(row.group(1));
			}
		}
		return ids;
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
