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
 * The H5 content inventory of docs/spec/retail-truth.md §4.5: quests, instances, bosses and world systems, generated from a datapack, each row's status and cases derived from the case files that name it ({@link #judgments}) and its notes kept across regenerations by {@link #merge}.
 */
public final class Inventory
{
	/** The column people write: kept across regenerations. {@code status} and {@code cases} are derived from the case files. */
	public static final List<String> KEPT = List.of("notes");
	/** The statuses of docs/spec/retail-truth.md §3, most severe first: a row several cases judge shows the first any of them has. */
	public static final List<String> SEVERITY = List.of("MISSING", "BROKEN", "APPROXIMATE", "UNKNOWN", "FUNCTIONAL", "EXACT");
	/** The statuses of docs/spec/retail-truth.md §3. */
	public static final Set<String> STATUSES = Set.copyOf(SEVERITY);
	/** The inventory's tables, which a case's {@code inventory} entries name as {@code <table>:<key>}. */
	public static final Set<String> TABLES = Set.of("quests", "instances", "bosses", "systems");

	/** One case's judgment of an inventory row. */
	public record Judgment(String caseId, String status)
	{
	}

	private static final Pattern QUEST = Pattern.compile("Q(\\d{5})_(\\w+)\\.java");
	/** An int or int[] constant whose name is a template or instance id (TEMPLATE_ID, INSTANCE_IDS, TEMPLATE_ID_HARD...). */
	private static final Pattern CONSTANT = Pattern.compile("\\bfinal\\s+int(?:\\s*\\[\\s*\\])?\\s+\\w*(?:TEMPLATE|INSTANCE)_?IDS?(?:_\\w+)?\\s*=\\s*(?:new\\s+int\\s*\\[\\s*\\]\\s*)?(\\{[^}]*\\}|\\d+)\\s*;");
	/** A put of a template id, bare or in a dungeon holder, into a map named for dungeons, instances or templates: CASTLE_DUNGEON.put(npc, 13), _fortDungeons.put(npc, new FortDungeon(22)). */
	private static final Pattern MAP_PUT = Pattern.compile("\\b\\w*(?i:dungeon|instance|template)\\w*\\s*\\.put\\(\\s*\\d+\\s*,\\s*(?:new\\s+\\w*Dungeon\\(\\s*)?(\\d+)(?![\\w.])");
	/** A number standing alone: not part of a coordinate (-25152), a decimal or a longer token (101_000). */
	private static final Pattern TOKEN = Pattern.compile("(?<![-\\w.])(\\d+)(?![\\w.])");
	/** The arguments of a location constructor: coordinates, never an id. */
	private static final Pattern LOCATION = Pattern.compile("new\\s+\\w*Location\\s*\\([^()]*\\)");
	private static final Pattern SQL_COMMENT = Pattern.compile("--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
	/** A table row's first value: a tuple opening after VALUES or after the comma that ends the previous tuple, on any line. */
	private static final Pattern SQL_ROW = Pattern.compile("(?i)(?:\\bVALUES\\s*|\\)\\s*,\\s*)\\(\\s*(\\d+)\\s*,");
	private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
	private static final Pattern LITERAL = Pattern.compile("createDynamicInstance\\(\\s*(\\d+)(?![\\w.])");
	private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])\\d+(?![\\w.])");

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
			collect(Pattern.compile("<npc\\s+id=\"(\\d+)\""), XML_COMMENT.matcher(text(spawn)).replaceAll(""), spawned);
		}
		final Set<String> raidRows = new TreeSet<>();
		collect(SQL_ROW, SQL_COMMENT.matcher(text(sql.resolve("raidboss_spawnlist.sql"))).replaceAll(""), raidRows);
		final Set<String> grandRows = new TreeSet<>();
		collect(SQL_ROW, SQL_COMMENT.matcher(text(sql.resolve("grandboss_data.sql"))).replaceAll(""), grandRows);
		final Set<String> rift = new TreeSet<>();
		final Path riftFile = data.resolve("DimensionalRift.xml");
		if (Files.exists(riftFile))
		{
			collect(Pattern.compile("mobId=\"(\\d+)\""), XML_COMMENT.matcher(text(riftFile)).replaceAll(""), rift);
		}
		final Set<String> named = new TreeSet<>();
		for (String source : scripts.values())
		{
			collect(TOKEN, LOCATION.matcher(source).replaceAll(" "), named);
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
	 * Reads every case's judgments of inventory rows: its {@code status} and the rows its {@code inventory} entries name ({@code <table>:<key>}, as a flow list {@code [a, b]} or a block list of {@code - a}).
	 * @param cases the directory of case files ({@code truth/cases}); absent, no case judges anything
	 * @return by table, by row key, the judgments in case-id order
	 * @throws IllegalStateException when a case's id is not its file's name, its status is not one of the six, or an entry names a table the inventory does not have
	 */
	public static Map<String, Map<String, List<Judgment>>> judgments(Path cases)
	{
		final Map<String, Map<String, List<Judgment>>> judged = new TreeMap<>();
		for (Path file : walk(cases, ".yaml"))
		{
			final String name = file.getFileName().toString().replaceFirst("\\.yaml$", "");
			String id = null;
			String status = null;
			final List<String> rows = new ArrayList<>();
			boolean inInventory = false;
			for (String raw : text(file).split("\n"))
			{
				final String line = raw.replaceFirst("\\s+#.*$", "").replaceFirst("^#.*$", "").stripTrailing();
				if (line.isEmpty())
				{
					continue;
				}
				final Matcher entry = Pattern.compile("^\\s+-\\s+(\\S+)$").matcher(line);
				if (inInventory && entry.matches())
				{
					rows.add(entry.group(1));
					continue;
				}
				inInventory = false;
				final Matcher field = Pattern.compile("^(\\w+):\\s*(.*)$").matcher(line);
				if (!field.matches())
				{
					continue; // a nested line of another field
				}
				final String value = field.group(2).strip();
				switch (field.group(1))
				{
					case "id" -> id = value;
					case "status" -> status = value;
					case "inventory" ->
					{
						if (value.isEmpty())
						{
							inInventory = true;
						}
						else if (value.startsWith("[") && value.endsWith("]"))
						{
							for (String item : value.substring(1, value.length() - 1).split(","))
							{
								if (!item.isBlank())
								{
									rows.add(item.strip());
								}
							}
						}
						else
						{
							throw new IllegalStateException(file + ": inventory must be a list of <table>:<key>, not '" + value + "'");
						}
					}
					default ->
					{
					}
				}
			}
			if (!name.equals(id))
			{
				throw new IllegalStateException(file + ": its id '" + id + "' is not its file's name");
			}
			if (!STATUSES.contains(status))
			{
				throw new IllegalStateException(file + ": the status '" + status + "' is not one of " + SEVERITY);
			}
			for (String row : rows)
			{
				final int colon = row.indexOf(':');
				final String table = colon < 0 ? row : row.substring(0, colon);
				if (colon < 0 || !TABLES.contains(table) || colon == row.length() - 1)
				{
					throw new IllegalStateException(file + ": '" + row + "' does not name a row as <table>:<key>, the table one of " + TABLES);
				}
				judged.computeIfAbsent(table, k -> new TreeMap<>()).computeIfAbsent(row.substring(colon + 1), k -> new ArrayList<>()).add(new Judgment(id, status));
			}
		}
		return judged;
	}

	/**
	 * Merges a regeneration into the committed rows. The generator's columns come from the regeneration; {@code notes} is kept; {@code status} and {@code cases} are derived from the cases that name the row, never kept from the committed file; {@code HAND} rows are kept; every row is in key order.
	 * @param judged this table's judgments by row key ({@link #judgments})
	 * @throws IllegalStateException when a committed row has an unknown status or source, a key is held twice, a case names a row that is in neither the regeneration nor the committed hand rows, or a hand row is not MISSING by its cases
	 */
	public static List<Map<String, String>> merge(List<Map<String, String>> committed, List<Map<String, String>> generated, String key, Map<String, List<Judgment>> judged)
	{
		final Map<String, Map<String, String>> old = index(committed, key, "committed");
		index(generated, key, "generated");
		for (Map<String, String> row : committed)
		{
			if (!STATUSES.contains(row.get("status")))
			{
				throw new IllegalStateException(key + " " + row.get(key) + " has the status '" + row.get("status") + "', not one of " + SEVERITY);
			}
			if (!"GENERATED".equals(row.get("source")) && !"HAND".equals(row.get("source")))
			{
				throw new IllegalStateException(key + " " + row.get(key) + " has the source '" + row.get("source") + "', neither GENERATED nor HAND");
			}
		}
		final List<Map<String, String>> merged = new ArrayList<>();
		for (Map<String, String> row : generated)
		{
			final Map<String, String> result = new LinkedHashMap<>(row);
			final Map<String, String> previous = old.get(row.get(key));
			if (previous != null && "GENERATED".equals(previous.get("source")))
			{
				for (String column : KEPT)
				{
					result.put(column, previous.getOrDefault(column, result.get(column)));
				}
			}
			merged.add(result);
		}
		final Set<String> generatedKeys = new TreeSet<>(generated.stream().map(r -> r.get(key)).toList());
		for (Map<String, String> row : committed)
		{
			if ("HAND".equals(row.get("source")))
			{
				if (generatedKeys.contains(row.get(key)))
				{
					throw new IllegalStateException("hand row " + row.get(key) + " is now in the datapack: the content is no longer missing; judge the generated row again and remove the hand row");
				}
				merged.add(new LinkedHashMap<>(row));
			}
		}
		final Set<String> present = new TreeSet<>();
		for (Map<String, String> row : merged)
		{
			present.add(row.get(key));
			final List<Judgment> cases = judged.getOrDefault(row.get(key), List.of());
			row.put("status", cases.stream().map(Judgment::status).min(Comparator.comparingInt(SEVERITY::indexOf)).orElse("UNKNOWN"));
			row.put("cases", String.join(";", cases.stream().map(Judgment::caseId).sorted().toList()));
			if ("HAND".equals(row.get("source")) && (cases.isEmpty() || !"MISSING".equals(row.get("status"))))
			{
				throw new IllegalStateException("hand row " + row.get(key) + " must be judged MISSING by the cases that name it, and is " + (cases.isEmpty() ? "named by none" : row.get("status")));
			}
		}
		for (Map.Entry<String, List<Judgment>> judgment : judged.entrySet())
		{
			if (!present.contains(judgment.getKey()))
			{
				throw new IllegalStateException(judgment.getValue() + " judge " + key + " " + judgment.getKey() + ", which is no longer in the inventory: resolve the finding (re-point or refute the case), do not drop the row");
			}
		}
		final boolean numeric = merged.stream().allMatch(r -> r.get(key).matches("\\d+"));
		merged.sort(numeric ? Comparator.comparingLong(r -> Long.parseLong(r.get(key))) : Comparator.comparing(r -> r.get(key)));
		return merged;
	}

	/**
	 * Java source without its comments: a {@code //} or {@code /* *}{@code /} comment becomes a space, while string, character and text-block literals are kept whole, so a comment marker inside one opens nothing.
	 */
	static String withoutComments(String java)
	{
		final StringBuilder out = new StringBuilder(java.length());
		int i = 0;
		final int n = java.length();
		while (i < n)
		{
			final char c = java.charAt(i);
			if (java.startsWith("\"\"\"", i))
			{
				final int end = java.indexOf("\"\"\"", i + 3);
				final int stop = end < 0 ? n : end + 3;
				out.append(java, i, stop);
				i = stop;
			}
			else if (c == '"' || c == '\'')
			{
				int j = i + 1;
				while (j < n && java.charAt(j) != c && java.charAt(j) != '\n')
				{
					j += java.charAt(j) == '\\' ? 2 : 1;
				}
				final int stop = Math.min(n, j + 1);
				out.append(java, i, stop);
				i = stop;
			}
			else if (java.startsWith("//", i))
			{
				final int end = java.indexOf('\n', i);
				out.append(' ');
				i = end < 0 ? n : end;
			}
			else if (java.startsWith("/*", i))
			{
				final int end = java.indexOf("*/", i + 2);
				out.append(' ');
				i = end < 0 ? n : end + 2;
			}
			else
			{
				out.append(c);
				i++;
			}
		}
		return out.toString();
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
			sources.put(file, withoutComments(text(file)));
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
