package org.l2jmobius.testing.truth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AT-RT-06, its unit part (docs/spec/retail-truth.md §4.5): the generator on fixture datapacks, and the merge rules.
 */
class InventoryTest
{
	@TempDir
	Path _root;

	private Path _data;
	private Path _sql;
	private Path _core;
	private Path _systems;
	private Path _cases;

	@BeforeEach
	void datapack() throws IOException
	{
		_data = _root.resolve("data");
		_sql = _root.resolve("sql");
		_core = _root.resolve("core");
		_systems = _root.resolve("systems.in");
		_cases = _root.resolve("cases");
		Files.createDirectories(_cases);
		write("scripts/quests/Q00001_LettersOfLove/Q00001_LettersOfLove.java", "class Q00001_LettersOfLove {}");
		write("scripts/quests/Q00255_Tutorial/Q00255_Tutorial.java", "class Q00255_Tutorial {}");
		write("scripts/quests/TerritoryWarScripts/Q00717_ForTheSakeOfTheTerritoryGludio.java", "class Q00717_ForTheSakeOfTheTerritoryGludio {}");
		write("scripts/quests/Dummy/Q00057_NewbieShots.java", "class Q00057_NewbieShots {}");
		write("scripts/quests/AbstractSagaQuest.java", "abstract class AbstractSagaQuest {}");
		for (int id : new int[] { 2, 9, 13, 17, 22, 24, 57, 58, 101, 102, 103, 104, 105, 106, 107, 131, 147, 200 })
		{
			write("instances/T" + id + ".xml", "<?xml version=\"1.0\"?>\n<instance id=\"" + id + "\">\n</instance>\n");
		}
		write("scripts/instances/Mansion/Mansion.java", "class Mansion { private static final int TEMPLATE_ID = 9; private static final int INSTANCE_LEVEL_MIN = 17; }");
		write("scripts/instances/Kamaloka/Kamaloka.java", "class Kamaloka { private static final int[] TEMPLATE_IDS = { 57, 58 }; void enter() { createDynamicInstance(TEMPLATE_IDS[0]); } }");
		write("scripts/instances/CastleDungeon/CastleDungeon.java", "class CastleDungeon { static { CASTLE_DUNGEON.put(36403, 13); SKILLS.put(1, new SkillHolder(200, 1)); } void enter() { createDynamicInstance(id); } }");
		write("scripts/quests/Q00511_AwlUnderFoot/Q00511_AwlUnderFoot.java", "class Q00511_AwlUnderFoot { static { _fortDungeons.put(35666, new FortDungeon(22)); } void enter() { createDynamicInstance(dungeon.getInstanceId()); } }");
		// Comments do not count, a price is not a template, 104_000 is not 104, and an array made with new int[] counts.
		write("scripts/instances/Fake/Fake.java", """
			class Fake
			{
				// private static final int TEMPLATE_ID = 101;
				/* private static final int TEMPLATE_ID_HARD = 102; */
				private static final int[] TEMPLATE_IDS = new int[] { 105, 106 };
				static { PRICES.put(36403, 103); CASTLE_DUNGEON.put(36404, 104_000); }
				void enter() { createDynamicInstance(id); }
			}
			""");
		// A comment marker inside a string literal opens no comment.
		write("scripts/instances/Url/Url.java", "class Url { String u = \"http://a/*\"; private static final int TEMPLATE_ID = 107; String v = \"*/\"; }");
		write("scripts/ai/Unrelated.java", "class Unrelated { static { PRICES.put(1, 200); } private static final int INSTANCEPENALTY = 24; void check() { if (INSTANCEID == 131) {} } }");
		Files.createDirectories(_core);
		Files.writeString(_core.resolve("OlympiadGameManager.java"), "class OlympiadGameManager { int i = InstanceManager.getInstance().createDynamicInstance(147); }");
		Files.writeString(_core.resolve("DefaultPageHandler.java"), "class DefaultPageHandler { public static final DefaultPageHandler INSTANCE = new DefaultPageHandler(2); }");
		write("stats/npcs/25000-25099.xml", """
			<?xml version="1.0"?>
			<list>
				<npc id="25001" level="23" type="RaidBoss" name="Greyclaw Kutus" />
				<npc id="25002" level="30" type="RaidBoss" name="Shadow, of the Dark" />
				<npc id="25003" level="40" type="RaidBoss" name="Forgotten" />
				<npc id="25004" level="50" type="RaidBoss" name="Anakazel" />
				<npc id="25005" level="60" type="RaidBoss" name="Coordinate" />
				<npc id="25006" level="60" type="RaidBoss" name="Positive coordinate" />
				<npc id="25007" level="60" type="RaidBoss" name="Commented in a script" />
				<npc id="25008" level="60" type="RaidBoss" name="Commented spawn" />
				<npc id="25009" level="60" type="RaidBoss" name="Commented rift room" />
				<npc id="25010" level="60" type="RaidBoss" name="Inline row one" />
				<npc id="25011" level="60" type="RaidBoss" name="Inline row two" />
				<npc id="20001" level="5" type="Monster" name="Gremlin" />
			</list>
			""");
		write("stats/npcs/29000-29099.xml", """
			<?xml version="1.0"?>
			<list>
				<npc id="29001" level="40" type="GrandBoss" name="Queen Ant" />
				<npc id="29019" level="79" type="GrandBoss" name="Antharas" />
			</list>
			""");
		write("spawns/Others/23_20.xml", "<list><spawn><npc id=\"25001\" x=\"1\" y=\"2\" z=\"3\" /></spawn></list>");
		write("spawns/Others/old.xml", "<list><!-- <spawn><npc id=\"25008\" x=\"1\" y=\"2\" z=\"3\" /></spawn> --></list>");
		write("DimensionalRift.xml", "<rift><room><spawn mobId=\"25004\" count=\"1\" /><!-- <spawn mobId=\"25009\" count=\"1\" /> --></room></rift>");
		Files.createDirectories(_sql);
		Files.writeString(_sql.resolve("raidboss_spawnlist.sql"), "INSERT INTO raidboss_spawnlist VALUES\n(25002,1,2,3),\n-- (25003,1,2,3), -- not spawned anymore\n(25999,1,2,3);\nINSERT INTO raidboss_spawnlist VALUES (25010,1,2,3), (25011,1,2,3);\n");
		Files.writeString(_sql.resolve("grandboss_data.sql"), "INSERT INTO grandboss_data VALUES\n(29001,1,2,3);\n/* (29019,1,2,3) */\n");
		write("scripts/ai/bosses/Antharas/Antharas.java", "class Antharas { private static final int ANTHARAS = 29019; }");
		write("scripts/ai/Boats.java", "class Boats { static final Location L = new Location(-25005, 3, 4); static final Location M = new Location(25006, 1, 2); // 25007 is gone\n }");
		Files.writeString(_systems, "# a comment\nseven_signs | Seven Signs | mechanics/sevensigns | \nfestival | Festival of Darkness | mechanics/sevensigns | known incomplete\n");
	}

	private void write(String path, String text) throws IOException
	{
		final Path file = _data.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
	}

	private Map<String, List<Map<String, String>>> generate()
	{
		return Inventory.generate(_data, _sql, List.of(_core), _systems);
	}

	private static Map<String, Map<String, String>> byKey(List<Map<String, String>> rows, String key)
	{
		return rows.stream().collect(Collectors.toMap(r -> r.get(key), r -> r));
	}

	@Test
	void questsAreTheQuestClassesWhereverTheyLive()
	{
		final Map<String, Map<String, String>> quests = byKey(generate().get("quests"), "id");
		assertEquals(List.of("1", "255", "511", "57", "717"), quests.keySet().stream().sorted().toList(), "classes in shared directories too; not the abstract base");
		assertEquals("LettersOfLove", quests.get("1").get("name"));
		assertEquals("GENERATED", quests.get("1").get("source"));
		assertEquals("UNKNOWN", quests.get("1").get("status"));
		assertEquals("placeholder quest", quests.get("57").get("finding"));
		assertEquals("", quests.get("717").get("finding"));
	}

	@Test
	void anInstanceIsNamedByAConstantAnArrayAMapPutOrALiteral()
	{
		final Map<String, Map<String, String>> instances = byKey(generate().get("instances"), "id");
		assertEquals("data/scripts/instances/Mansion/Mansion.java", instances.get("9").get("scripts"), "a TEMPLATE_ID constant");
		assertEquals("data/scripts/instances/Kamaloka/Kamaloka.java", instances.get("57").get("scripts"), "an int array");
		assertEquals("data/scripts/instances/Kamaloka/Kamaloka.java", instances.get("58").get("scripts"), "an int array");
		assertEquals("data/scripts/instances/CastleDungeon/CastleDungeon.java", instances.get("13").get("scripts"), "a map put of a bare id in a source that creates instances");
		assertEquals("data/scripts/quests/Q00511_AwlUnderFoot/Q00511_AwlUnderFoot.java", instances.get("22").get("scripts"), "a map put of a dungeon holder");
		assertEquals("core/OlympiadGameManager.java", instances.get("147").get("scripts"), "a literal in a core class");
		assertEquals("data/scripts/instances/Fake/Fake.java", instances.get("105").get("scripts"), "an array made with new int[]");
		assertEquals("data/scripts/instances/Fake/Fake.java", instances.get("106").get("scripts"), "an array made with new int[]");
		assertEquals("data/scripts/instances/Url/Url.java", instances.get("107").get("scripts"), "a comment marker in a string opens no comment");
	}

	@Test
	void namesThatMerelyContainTheWordsAreNotReferences()
	{
		final Map<String, Map<String, String>> instances = byKey(generate().get("instances"), "id");
		for (String id : List.of("2", "17", "24", "101", "102", "103", "104", "131", "200"))
		{
			assertEquals("", instances.get(id).get("scripts"), "template " + id + ": a singleton, a level bound, a penalty, a line or block comment, a price put, 104_000, a comparison, an unrelated holder or put");
			assertEquals("no source names this template", instances.get(id).get("finding"));
		}
	}

	@Test
	void bossesAreRaidAndGrandBossesWithTheirLivePlacements()
	{
		final Map<String, Map<String, String>> bosses = byKey(generate().get("bosses"), "id");
		assertEquals(List.of("25001", "25002", "25003", "25004", "25005", "25006", "25007", "25008", "25009", "25010", "25011", "29001", "29019"), bosses.keySet().stream().sorted().toList(), "monsters are not bosses");
		assertEquals("spawns", bosses.get("25001").get("placed_by"));
		assertEquals("raidboss_spawnlist", bosses.get("25002").get("placed_by"));
		assertEquals("dimensional_rift", bosses.get("25004").get("placed_by"));
		assertEquals("grandboss_data", bosses.get("29001").get("placed_by"));
		assertEquals("scripts", bosses.get("29019").get("placed_by"), "a commented-out table row does not count, the script naming it does");
		assertEquals("raidboss_spawnlist", bosses.get("25010").get("placed_by"), "a row inline after VALUES");
		assertEquals("raidboss_spawnlist", bosses.get("25011").get("placed_by"), "a second row on the same line");
		for (String id : List.of("25003", "25005", "25006", "25007", "25008", "25009"))
		{
			assertEquals("", bosses.get(id).get("placed_by"), "boss " + id + ": a commented-out row, a negative or positive coordinate, a script comment, a commented spawn or rift room");
			assertEquals("placed by no spawn, table or script", bosses.get(id).get("finding"));
		}
		assertEquals("Shadow, of the Dark", bosses.get("25002").get("name"));
	}

	@Test
	void systemsAreTheListedLinesWithTheirNoteAsFinding()
	{
		final List<Map<String, String>> systems = generate().get("systems");
		assertEquals(List.of("seven_signs", "festival"), systems.stream().map(r -> r.get("key")).toList());
		assertEquals("known incomplete", systems.get(1).get("finding"));
	}

	@Test
	void aMalformedSystemsLineIsRefused() throws IOException
	{
		Files.writeString(_systems, "seven_signs | Seven Signs\n");
		assertThrows(IllegalArgumentException.class, this::generate);
	}

	private void writeCase(String id, String text) throws IOException
	{
		Files.writeString(_cases.resolve(id + ".yaml"), text);
	}

	private Map<String, List<Inventory.Judgment>> judged(String table)
	{
		return Inventory.judgments(_cases).getOrDefault(table, Map.of());
	}

	@Test
	void aMergeRefreshesFindingsKeepsNotesAndAddsNewRowsUnknown()
	{
		final List<Map<String, String>> committed = List.of(row("1", "old finding", "GENERATED", "UNKNOWN", "", "a person's note"));
		final List<Map<String, String>> generated = List.of(row("1", "new finding", "GENERATED", "UNKNOWN", "", ""), row("2", "", "GENERATED", "UNKNOWN", "", ""));
		final Map<String, Map<String, String>> merged = byKey(Inventory.merge(committed, generated, "id", Map.of()), "id");
		assertEquals("new finding", merged.get("1").get("finding"), "the generator's column refreshed");
		assertEquals("a person's note", merged.get("1").get("notes"));
		assertEquals("UNKNOWN", merged.get("2").get("status"));
	}

	@Test
	void statusAndCasesComeFromTheCaseFilesNeverFromTheCsv() throws IOException
	{
		writeCase("RT-CONTENT-0001", "# a case\nid: RT-CONTENT-0001\nstatus: BROKEN   # judged at a commit\ninventory: [instances:13, quests:511]\n");
		final List<Map<String, String>> committed = List.of(row("9", "", "GENERATED", "EXACT", "", "an invented judgment"), row("13", "", "GENERATED", "EXACT", "RT-CONTENT-0099", "a note"));
		final List<Map<String, String>> generated = List.of(row("9", "", "GENERATED", "UNKNOWN", "", ""), row("13", "", "GENERATED", "UNKNOWN", "", ""));
		final Map<String, Map<String, String>> merged = byKey(Inventory.merge(committed, generated, "id", judged("instances")), "id");
		assertEquals("UNKNOWN", merged.get("9").get("status"), "a status no case gives is not kept");
		assertEquals("", merged.get("9").get("cases"));
		assertEquals("BROKEN", merged.get("13").get("status"), "the status of the case that names the row");
		assertEquals("RT-CONTENT-0001", merged.get("13").get("cases"), "a case reference that resolves to no case file is not kept");
		assertEquals("a note", merged.get("13").get("notes"));
	}

	@Test
	void aRowSeveralCasesJudgeShowsTheMostSevere() throws IOException
	{
		writeCase("RT-CONTENT-0002", "id: RT-CONTENT-0002\nstatus: EXACT\ninventory:\n  - quests:1\n");
		writeCase("RT-CONTENT-0003", "id: RT-CONTENT-0003\nstatus: BROKEN\ninventory:\n  - quests:1\nnotes: x\n");
		writeCase("RT-CONTENT-0004", "id: RT-CONTENT-0004\nstatus: UNKNOWN\ninventory: [quests:2]\n");
		writeCase("RT-CONTENT-0005", "id: RT-CONTENT-0005\nstatus: FUNCTIONAL\ninventory: [quests:2]\n");
		final List<Map<String, String>> generated = List.of(row("1", "", "GENERATED", "UNKNOWN", "", ""), row("2", "", "GENERATED", "UNKNOWN", "", ""));
		final Map<String, Map<String, String>> merged = byKey(Inventory.merge(List.of(), generated, "id", judged("quests")), "id");
		assertEquals("BROKEN", merged.get("1").get("status"));
		assertEquals("RT-CONTENT-0002;RT-CONTENT-0003", merged.get("1").get("cases"));
		assertEquals("UNKNOWN", merged.get("2").get("status"), "a row is not FUNCTIONAL while one of its cases is UNKNOWN");
	}

	@Test
	void aCaseNamingARowThatIsGoneIsRefused() throws IOException
	{
		writeCase("RT-CONTENT-0006", "id: RT-CONTENT-0006\nstatus: BROKEN\ninventory: [instances:13]\n");
		final List<Map<String, String>> generated = List.of(row("9", "", "GENERATED", "UNKNOWN", "", ""));
		assertThrows(IllegalStateException.class, () -> Inventory.merge(List.of(row("13", "", "GENERATED", "BROKEN", "RT-CONTENT-0006", "")), generated, "id", judged("instances")), "content and row gone together");
		assertThrows(IllegalStateException.class, () -> Inventory.merge(List.of(), generated, "id", judged("instances")), "the row deleted from the csv first");
		writeCase("RT-CONTENT-0006", "id: RT-CONTENT-0006\nstatus: UNKNOWN\ninventory: [instances:13]\n");
		assertThrows(IllegalStateException.class, () -> Inventory.merge(List.of(), generated, "id", judged("instances")), "even an unknown case keeps its row");
		assertEquals(List.of("9"), Inventory.merge(List.of(row("13", "", "GENERATED", "UNKNOWN", "", "")), generated, "id", Map.of()).stream().map(r -> r.get("id")).toList(), "a row no case names may go");
	}

	@Test
	void handRowsAreMissingByTheirCasesAndCannotBeDropped() throws IOException
	{
		writeCase("RT-CONTENT-0007", "id: RT-CONTENT-0007\nstatus: MISSING\ninventory: [quests:3]\n");
		final List<Map<String, String>> generated = List.of(row("1", "", "GENERATED", "UNKNOWN", "", ""), row("5", "", "GENERATED", "UNKNOWN", "", ""));
		final List<Map<String, String>> merged = Inventory.merge(List.of(row("3", "", "HAND", "MISSING", "RT-CONTENT-0007", "retail only")), generated, "id", judged("quests"));
		assertEquals(List.of("1", "3", "5"), merged.stream().map(r -> r.get("id")).toList());
		assertEquals("MISSING", merged.get(1).get("status"));
		assertThrows(IllegalStateException.class, () -> Inventory.merge(List.of(), generated, "id", judged("quests")), "a hand row deleted while its case names it");
		assertThrows(IllegalStateException.class, () -> Inventory.merge(List.of(row("4", "", "HAND", "MISSING", "RT-CONTENT-0007", "")), generated, "id", Map.of()), "a hand row no case names");
		writeCase("RT-CONTENT-0007", "id: RT-CONTENT-0007\nstatus: BROKEN\ninventory: [quests:3]\n");
		assertThrows(IllegalStateException.class, () -> Inventory.merge(List.of(row("3", "", "HAND", "MISSING", "RT-CONTENT-0007", "")), generated, "id", judged("quests")), "a hand row its cases do not judge MISSING");
	}

	@Test
	void textualKeysAreKeptInKeyOrderToo() throws IOException
	{
		writeCase("RT-WORLD-0001", "id: RT-WORLD-0001\nstatus: MISSING\ninventory: [systems:m]\n");
		final List<Map<String, String>> generated = List.of(system("a", "GENERATED"), system("z", "GENERATED"));
		final List<Map<String, String>> merged = Inventory.merge(List.of(system("m", "HAND")), generated, "key", judged("systems"));
		assertEquals(List.of("a", "m", "z"), merged.stream().map(r -> r.get("key")).toList());
	}

	@Test
	void caseFilesAreRefusedWhenTheyCannotBeResolved() throws IOException
	{
		writeCase("RT-CONTENT-0008", "id: RT-CONTENT-0009\nstatus: BROKEN\ninventory: [quests:1]\n");
		assertThrows(IllegalStateException.class, () -> Inventory.judgments(_cases), "an id that is not the file's name");
		writeCase("RT-CONTENT-0008", "id: RT-CONTENT-0008\nstatus: Broken\ninventory: [quests:1]\n");
		assertThrows(IllegalStateException.class, () -> Inventory.judgments(_cases), "a status outside the six");
		writeCase("RT-CONTENT-0008", "id: RT-CONTENT-0008\nstatus: BROKEN\ninventory: [skills:1]\n");
		assertThrows(IllegalStateException.class, () -> Inventory.judgments(_cases), "a table the inventory does not have");
		writeCase("RT-CONTENT-0008", "id: RT-CONTENT-0008\nstatus: BROKEN\nevidence: []\n");
		assertEquals(Map.of(), Inventory.judgments(_cases), "a case that names no row judges none");
	}

	@Test
	void aMergeRefusesAnUnknownStatusOrSourceAndADuplicateKey()
	{
		final List<Map<String, String>> generated = List.of(row("2", "", "GENERATED", "UNKNOWN", "", ""));
		assertThrows(IllegalStateException.class, () -> Inventory.merge(List.of(row("2", "", "GENERATED", "Exact", "", "")), generated, "id", Map.of()), "a status outside the six");
		assertThrows(IllegalStateException.class, () -> Inventory.merge(List.of(row("2", "", "MANUAL", "UNKNOWN", "", "")), generated, "id", Map.of()), "a source neither GENERATED nor HAND");
		assertThrows(IllegalStateException.class, () -> Inventory.merge(List.of(), List.of(row("2", "", "GENERATED", "UNKNOWN", "", ""), row("2", "", "GENERATED", "UNKNOWN", "", "")), "id", Map.of()), "a duplicate generated key");
	}

	@Test
	void csvRoundTripsCommasAndQuotesAndIgnoresATrailingBlankLine() throws IOException
	{
		final Path csv = _root.resolve("t.csv");
		final List<Map<String, String>> rows = List.of(row("25002", "", "GENERATED", "UNKNOWN", "", "a \"quoted\", note"));
		Inventory.write(rows, csv);
		assertEquals(rows, Inventory.read(csv));
		Files.writeString(csv, Files.readString(csv) + "\n\n");
		assertEquals(rows, Inventory.read(csv), "blank lines are not rows");
	}

	@Test
	void csvCellsFollowTheHeaderWhateverTheRowsOrder() throws IOException
	{
		final Path csv = _root.resolve("t.csv");
		final Map<String, String> reordered = new LinkedHashMap<>();
		reordered.put("notes", "n");
		reordered.put("id", "7");
		reordered.put("cases", "");
		reordered.put("status", "UNKNOWN");
		reordered.put("source", "GENERATED");
		reordered.put("finding", "f");
		Inventory.write(List.of(row("6", "", "GENERATED", "UNKNOWN", "", ""), reordered), csv);
		assertEquals("n", Inventory.read(csv).get(1).get("notes"));
		assertEquals("7", Inventory.read(csv).get(1).get("id"));
	}

	private static Map<String, String> system(String key, String source)
	{
		final Map<String, String> row = new LinkedHashMap<>();
		row.put("key", key);
		row.put("finding", "");
		row.put("source", source);
		row.put("status", "HAND".equals(source) ? "MISSING" : "UNKNOWN");
		row.put("cases", "");
		row.put("notes", "");
		return row;
	}

	private static Map<String, String> row(String id, String finding, String source, String status, String cases, String notes)
	{
		final Map<String, String> row = new LinkedHashMap<>();
		row.put("id", id);
		row.put("finding", finding);
		row.put("source", source);
		row.put("status", status);
		row.put("cases", cases);
		row.put("notes", notes);
		return row;
	}
}
