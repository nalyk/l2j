package org.l2jmobius.testing.truth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The H5 content inventory of docs/spec/retail-truth.md §4.5: quests, instances, bosses and world systems, generated from a datapack, with the judged columns ({@link #JUDGED}) kept across regenerations by {@link #merge}.
 */
public final class Inventory
{
	/** The columns the generator never decides: kept across regenerations. */
	public static final List<String> JUDGED = List.of("status", "cases", "notes");
	/** The statuses of docs/spec/retail-truth.md §3. */
	public static final Set<String> STATUSES = Set.of("EXACT", "FUNCTIONAL", "APPROXIMATE", "BROKEN", "MISSING", "UNKNOWN");

	private static final Pattern QUEST = Pattern.compile("Q(\\d{5})_(\\w+)\\.java");
	/** An int or int[] constant whose name is a template or instance id (TEMPLATE_ID, INSTANCE_IDS, TEMPLATE_ID_HARD...). */
	private static final Pattern CONSTANT = Pattern.compile("\\bfinal\\s+int(?:\\s*\\[\\s*\\])?\\s+\\w*(?:TEMPLATE|INSTANCE)_?IDS?(?:_\\w+)?\\s*=\\s*(\\{[^}]*\\}|\\d+)\\s*;");
	/** A map put of a template id, bare or in a dungeon holder: .put(npc, 13), .put(npc, new FortDungeon(22)). */
	private static final Pattern MAP_PUT = Pattern.compile("\\.put\\(\\s*\\d+\\s*,\\s*(?:new\\s+\\w*Dungeon\\(\\s*)?(\\d+)");
	/** A number standing alone: not part of a coordinate (-25152), a decimal or a longer token. */
	private static final Pattern TOKEN = Pattern.compile("(?<![-\\w.])(\\d+)(?![\\w.])");
	private static final Pattern SQL_COMMENT = Pattern.compile("--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
	private static final Pattern SQL_ROW = Pattern.compile("(?m)^\\s*\\((\\d+),");
	private static final Pattern LITERAL = Pattern.compile("createDynamicInstance\\(\\s*(\\d+)");
	private static final Pattern NUMBER = Pattern.compile("\\b\\d+\\b");

	private Inventory()
	{
	}

	/**
	 * Generates the four tables (quests, instances, bosses, systems) from a datapack.
	 * @param data the datapack's {@code data} directory (holding {@code scripts}, {@code instances}, {@code stats/npcs}, {@code spawns})
	 * @param sql the directory of the game's install SQL ({@code raidboss_spawnlist.sql}, {@code grandboss_data.sql})
	 * @param sources further Java source roots to scan for instance and boss references (the core)
	 * @param systems the hand-kept {@code systems.in}
	 * @return each table's rows by table name, each row an ordered map of column to value; paths relative to the common ancestor of {@code data} and {@code sources}
	 */
	public static Map<String, List<Map<String, String>>> generate(Path data, Path sql, List<Path> sources, Path systems)
	{
		Path base = data.toAbsolutePath().normalize();
		for (Path source : sources)
		{
			base = commonAncestor(base, source.toAbsolutePath().normalize());
		}
		final Map<Path, String> scripts = javaSources(data.resolve("scripts"));
		final Map<Path, String> all = new LinkedHashMap<>(scripts);
		for (Path source : sources)
		{
			all.putAll(javaSources(source));
		}
		final Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>();
		tables.put("quests", quests(data, base));
		tables.put("instances", instances(data, all, base));
		tables.put("bosses", bosses(data, sql, scripts));
		tables.put("systems", systems(systems));
		return tables;
	}

	private static List<Map<String, String>> quests(Path data, Path base)
	{
		final TreeMap<Integer, Map<String, String>> rows = new TreeMap<>();
		for (Path file : walk(data.resolve("scripts/quests"), ".java"))
		{
			final Matcher m = QUEST.matcher(file.getFileName().toString());
			if (!m.matches())
			{
				continue;
			}
			final int id = Integer.parseInt(m.group(1));
			final Map<String, String> row = new LinkedHashMap<>();
			row.put("id", String.valueOf(id));
			row.put("name", m.group(2));
			row.put("script", relative(base, file));
			if (rows.put(id, generated(row, file.getParent().getFileName().toString().equals("Dummy") ? "placeholder quest" : "")) != null)
			{
				throw new IllegalStateException("quest " + id + " is declared twice in " + data);
			}
		}
		return new ArrayList<>(rows.values());
	}

	private static List<Map<String, String>> instances(Path data, Map<Path, String> sources, Path base)
	{
		final Map<String, Set<String>> references = new LinkedHashMap<>();
		sources.forEach((path, text) ->
		{
			final List<String> ids = new ArrayList<>();
			final Matcher constant = CONSTANT.matcher(text);
			while (constant.find())
			{
				final Matcher number = NUMBER.matcher(constant.group(1));
				while (number.find())
				{
					ids.add(number.group());
				}
			}
			if (text.contains("createDynamicInstance") || text.contains("enterInstance"))
			{
				final Matcher put = MAP_PUT.matcher(text);
				while (put.find())
				{
					ids.add(put.group(1));
				}
			}
			final Matcher literal = LITERAL.matcher(text);
			while (literal.find())
			{
				ids.add(literal.group(1));
			}
			for (String id : ids)
			{
				references.computeIfAbsent(id, k -> new TreeSet<>()).add(relative(base, path));
			}
		});
		final TreeMap<Integer, Map<String, String>> rows = new TreeMap<>();
		for (Path template : walk(data.resolve("instances"), ".xml"))
		{
			final Element root = document(template);
			final String id = root.getAttribute("id");
			if (!root.getTagName().equals("instance"))
			{
				continue;
			}
			if (id.isEmpty())
			{
				throw new IllegalStateException("an instance template without an id: " + template);
			}
			final Set<String> named = references.getOrDefault(id, Set.of());
			final Map<String, String> row = new LinkedHashMap<>();
			row.put("id", id);
			row.put("template", relative(base, template));
			row.put("scripts", String.join(";", named));
			if (rows.put(number(id, template), generated(row, named.isEmpty() ? "no source names this template" : "")) != null)
			{
				throw new IllegalStateException("instance template " + id + " is declared twice (" + template + ")");
			}
		}
		return new ArrayList<>(rows.values());
	}

	private static List<Map<String, String>> bosses(Path data, Path sql, Map<Path, String> scripts)
	{
		final Set<String> spawned = new TreeSet<>();
		for (Path spawn : walk(data.resolve("spawns"), ".xml"))
		{
			collect(Pattern.compile("<npc\\s+id=\"(\\d+)\""), text(spawn), spawned);
		}
		final Set<String> raidRows = new TreeSet<>();
		collect(SQL_ROW, SQL_COMMENT.matcher(text(sql.resolve("raidboss_spawnlist.sql"))).replaceAll(""), raidRows);
		final Set<String> grandRows = new TreeSet<>();
		collect(SQL_ROW, SQL_COMMENT.matcher(text(sql.resolve("grandboss_data.sql"))).replaceAll(""), grandRows);
		final Set<String> rift = new TreeSet<>();
		final Path riftFile = data.resolve("DimensionalRift.xml");
		if (Files.exists(riftFile))
		{
			collect(Pattern.compile("mobId=\"(\\d+)\""), text(riftFile), rift);
		}
		final Set<String> named = new TreeSet<>();
		for (String source : scripts.values())
		{
			collect(TOKEN, source, named);
		}
		final TreeMap<Integer, Map<String, String>> rows = new TreeMap<>();
		for (Path stats : walk(data.resolve("stats/npcs"), ".xml"))
		{
			final NodeList npcs = document(stats).getElementsByTagName("npc");
			for (int i = 0; i < npcs.getLength(); i++)
			{
				final Element npc = (Element) npcs.item(i);
				final String type = npc.getAttribute("type");
				if (!type.equals("RaidBoss") && !type.equals("GrandBoss"))
				{
					continue;
				}
				final String id = npc.getAttribute("id");
				final List<String> placed = new ArrayList<>();
				if (spawned.contains(id))
				{
					placed.add("spawns");
				}
				if (raidRows.contains(id))
				{
					placed.add("raidboss_spawnlist");
				}
				if (grandRows.contains(id))
				{
					placed.add("grandboss_data");
				}
				if (rift.contains(id))
				{
					placed.add("dimensional_rift");
				}
				if (named.contains(id))
				{
					placed.add("scripts");
				}
				final Map<String, String> row = new LinkedHashMap<>();
				row.put("id", id);
				row.put("name", npc.getAttribute("name"));
				row.put("level", npc.getAttribute("level"));
				row.put("type", type);
				row.put("placed_by", String.join(";", placed));
				if (rows.put(number(id, stats), generated(row, placed.isEmpty() ? "placed by no spawn, table or script" : "")) != null)
				{
					throw new IllegalStateException("boss " + id + " is declared twice (" + stats + ")");
				}
			}
		}
		return new ArrayList<>(rows.values());
	}

	private static void collect(Pattern pattern, String text, Set<String> into)
	{
		final Matcher m = pattern.matcher(text);
		while (m.find())
		{
			into.add(m.group(1));
		}
	}

	private static int number(String id, Path file)
	{
		try
		{
			return Integer.parseInt(id);
		}
		catch (NumberFormatException e)
		{
			throw new IllegalStateException("a non-numeric id '" + id + "' in " + file, e);
		}
	}

	private static List<Map<String, String>> systems(Path systems)
	{
		final List<Map<String, String>> rows = new ArrayList<>();
		for (String line : text(systems).split("\n"))
		{
			final String trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith("#"))
			{
				continue;
			}
			final String[] fields = trimmed.split("\\|", -1);
			if (fields.length != 4)
			{
				throw new IllegalArgumentException("systems.in: four fields, separated by |, expected: " + line);
			}
			final Map<String, String> row = new LinkedHashMap<>();
			row.put("key", fields[0].strip());
			row.put("name", fields[1].strip());
			row.put("where", fields[2].strip());
			rows.add(generated(row, fields[3].strip()));
		}
		return rows;
	}

	private static Map<String, String> generated(Map<String, String> row, String finding)
	{
		row.put("finding", finding);
		row.put("source", "GENERATED");
		row.put("status", "UNKNOWN");
		row.put("cases", "");
		row.put("notes", "");
		return row;
	}

	/**
	 * Merges a regeneration into the committed rows: judged columns kept, new rows as generated, hand rows kept.
	 * @throws IllegalStateException when a generated row a case judged would disappear
	 */
	public static List<Map<String, String>> merge(List<Map<String, String>> committed, List<Map<String, String>> generated, String key)
	{
		final Map<String, Map<String, String>> old = index(committed, key, "committed");
		index(generated, key, "generated");
		for (Map<String, String> row : committed)
		{
			if (!STATUSES.contains(row.get("status")))
			{
				throw new IllegalStateException(key + " " + row.get(key) + " has the status '" + row.get("status") + "', not one of " + STATUSES);
			}
			if ("HAND".equals(row.get("source")) && (!"MISSING".equals(row.get("status")) || row.getOrDefault("cases", "").isBlank()))
			{
				throw new IllegalStateException("hand row " + row.get(key) + " must be MISSING with the case that proves it");
			}
		}
		final List<Map<String, String>> merged = new ArrayList<>();
		final Set<String> seen = new TreeSet<>();
		for (Map<String, String> row : generated)
		{
			final Map<String, String> result = new LinkedHashMap<>(row);
			final Map<String, String> previous = old.get(row.get(key));
			if (previous != null)
			{
				for (String column : JUDGED)
				{
					result.put(column, previous.getOrDefault(column, result.get(column)));
				}
			}
			merged.add(result);
			seen.add(row.get(key));
		}
		for (Map<String, String> row : committed)
		{
			if (seen.contains(row.get(key)))
			{
				continue;
			}
			if ("HAND".equals(row.get("source")))
			{
				merged.add(row);
			}
			else if (!"UNKNOWN".equals(row.get("status")))
			{
				throw new IllegalStateException(key + " " + row.get(key) + " was judged " + row.get("status") + " and is no longer in the datapack: resolve the finding, do not drop the row");
			}
		}
		if (merged.stream().allMatch(r -> r.get(key).matches("\\d+")))
		{
			merged.sort(Comparator.comparingLong(r -> Long.parseLong(r.get(key))));
		}
		return merged;
	}

	private static Map<String, Map<String, String>> index(List<Map<String, String>> rows, String key, String what)
	{
		final Map<String, Map<String, String>> byKey = new LinkedHashMap<>();
		for (Map<String, String> row : rows)
		{
			if (byKey.put(row.get(key), row) != null)
			{
				throw new IllegalStateException("the " + what + " rows hold " + key + " " + row.get(key) + " twice");
			}
		}
		return byKey;
	}

	public static List<Map<String, String>> read(Path csv)
	{
		final List<List<String>> records = parse(text(csv));
		final List<Map<String, String>> rows = new ArrayList<>();
		if (records.isEmpty())
		{
			return rows;
		}
		final List<String> header = records.get(0);
		for (List<String> record : records.subList(1, records.size()))
		{
			if (record.size() == 1 && record.get(0).isEmpty())
			{
				continue; // a blank line is not a row
			}
			final Map<String, String> row = new LinkedHashMap<>();
			for (int i = 0; i < header.size(); i++)
			{
				row.put(header.get(i), i < record.size() ? record.get(i) : "");
			}
			rows.add(row);
		}
		return rows;
	}

	public static void write(List<Map<String, String>> rows, Path csv)
	{
		final StringBuilder out = new StringBuilder();
		if (!rows.isEmpty())
		{
			// The header is every column in first-seen order, and each row's cells follow it by name.
			final Set<String> header = new java.util.LinkedHashSet<>();
			rows.forEach(r -> header.addAll(r.keySet()));
			out.append(line(new ArrayList<>(header)));
			for (Map<String, String> row : rows)
			{
				final List<String> cells = new ArrayList<>();
				header.forEach(column -> cells.add(row.getOrDefault(column, "")));
				out.append(line(cells));
			}
		}
		try
		{
			Files.writeString(csv, out.toString(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	private static String line(List<String> values)
	{
		final List<String> cells = new ArrayList<>();
		for (String value : values)
		{
			cells.add(value.contains(",") || value.contains("\"") || value.contains("\n") ? "\"" + value.replace("\"", "\"\"") + "\"" : value);
		}
		return String.join(",", cells) + "\n";
	}

	private static List<List<String>> parse(String text)
	{
		final List<List<String>> records = new ArrayList<>();
		List<String> record = new ArrayList<>();
		final StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < text.length(); i++)
		{
			final char c = text.charAt(i);
			if (quoted)
			{
				if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"')
				{
					cell.append('"');
					i++;
				}
				else if (c == '"')
				{
					quoted = false;
				}
				else
				{
					cell.append(c);
				}
			}
			else if (c == '"')
			{
				quoted = true;
			}
			else if (c == ',')
			{
				record.add(cell.toString());
				cell.setLength(0);
			}
			else if (c == '\n')
			{
				record.add(cell.toString());
				cell.setLength(0);
				records.add(record);
				record = new ArrayList<>();
			}
			else if (c != '\r')
			{
				cell.append(c);
			}
		}
		if (cell.length() > 0 || !record.isEmpty())
		{
			record.add(cell.toString());
			records.add(record);
		}
		return records;
	}

	private static Path commonAncestor(Path a, Path b)
	{
		Path ancestor = a;
		while (ancestor != null && !b.startsWith(ancestor))
		{
			ancestor = ancestor.getParent();
		}
		return ancestor == null ? a.getRoot() : ancestor;
	}

	private static String relative(Path base, Path path)
	{
		return base.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
	}

	private static Map<Path, String> javaSources(Path root)
	{
		final Map<Path, String> sources = new TreeMap<>();
		for (Path file : walk(root, ".java"))
		{
			sources.put(file, text(file));
		}
		return sources;
	}

	private static List<Path> list(Path dir)
	{
		if (!Files.isDirectory(dir))
		{
			return List.of();
		}
		try (Stream<Path> entries = Files.list(dir))
		{
			return entries.sorted().toList();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	private static List<Path> walk(Path dir, String suffix)
	{
		if (!Files.isDirectory(dir))
		{
			return List.of();
		}
		try (Stream<Path> files = Files.walk(dir))
		{
			return files.filter(f -> Files.isRegularFile(f) && f.toString().endsWith(suffix)).sorted(Comparator.naturalOrder()).toList();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	private static String text(Path file)
	{
		try
		{
			// Lenient: a datapack file with a stray non-UTF-8 byte is still read (the byte becomes U+FFFD).
			return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	private static Element document(Path xml)
	{
		try
		{
			final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(false);
			factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			return factory.newDocumentBuilder().parse(xml.toFile()).getDocumentElement();
		}
		catch (Exception e)
		{
			throw new IllegalStateException("cannot parse " + xml, e);
		}
	}
}
