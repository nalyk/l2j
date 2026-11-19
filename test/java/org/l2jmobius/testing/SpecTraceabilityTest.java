package org.l2jmobius.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The N1 specs and the traceability matrix stay consistent (docs/spec/README.md, blueprint 30 §3, 02 §9): every MUST names the acceptance tests that prove it, every acceptance test it names is defined, every requirement is reached from a blueprint sub-feature, a row lists every test its requirements name, and the matrix cites only requirements, tests, specs and blueprint documents that exist.
 */
class SpecTraceabilityTest
{
	private static final Pattern REQUIREMENT = Pattern.compile("^\\| ([A-Z]+-R\\d+) \\| (.*) \\| ([^|]*) \\|$", Pattern.MULTILINE);
	private static final Pattern TEST = Pattern.compile("^\\| (AT-[A-Z]+-\\d+) \\| ([a-z, ]+) \\| ", Pattern.MULTILINE);
	private static final Pattern TEST_ID = Pattern.compile("AT-[A-Z]+-\\d+");
	private static final Set<String> KINDS = Set.of("unit", "integration", "fault", "restart", "sim", "human");
	private static final Set<String> STATUSES = Set.of("blueprint", "specified", "reviewed", "implemented");
	/** A blueprint section reference: a two-digit document number followed by a section sign, as in "12 §8" or "90 §2". */
	private static final Pattern BLUEPRINT_REFERENCE = Pattern.compile("\\b(\\d\\d) §");
	/** The two-digit numbers of the blueprint's documents. */
	private static final Set<String> BLUEPRINT_DOCUMENTS = new HashSet<>();

	/** Requirement id → the acceptance tests it names. */
	private static final Map<String, List<String>> REQUIREMENTS = new LinkedHashMap<>();
	/** Acceptance test id → its spec file. */
	private static final Map<String, String> TESTS = new HashMap<>();
	private static final List<Map<String, String>> MATRIX = new ArrayList<>();
	private static final Set<String> SPEC_FILES = new HashSet<>();

	@BeforeAll
	static void read() throws IOException
	{
		final Path docs = Path.of(System.getProperty("lc.docs.dir"));
		try (Stream<Path> files = Files.list(docs.resolve("spec")))
		{
			for (Path spec : files.filter(f -> f.toString().endsWith(".md") && !f.getFileName().toString().equals("README.md")).sorted().toList())
			{
				SPEC_FILES.add(spec.getFileName().toString());
				final String text = Files.readString(spec);
				final Matcher requirement = REQUIREMENT.matcher(text);
				while (requirement.find())
				{
					final List<String> tests = new ArrayList<>();
					final Matcher test = TEST_ID.matcher(requirement.group(3));
					while (test.find())
					{
						tests.add(test.group());
					}
					assertTrue(REQUIREMENTS.put(requirement.group(1), tests) == null, "requirement defined twice: " + requirement.group(1));
				}
				final Matcher test = TEST.matcher(text);
				while (test.find())
				{
					assertTrue(TESTS.put(test.group(1), spec.getFileName().toString()) == null, "acceptance test defined twice: " + test.group(1));
					for (String kind : test.group(2).split(", "))
					{
						assertTrue(KINDS.contains(kind), test.group(1) + " has an unknown kind: " + kind);
					}
				}
			}
		}

		try (Stream<Path> documents = Files.list(docs.resolve("blueprint")))
		{
			documents.map(d -> d.getFileName().toString()).filter(n -> n.matches("\\d\\d-.*\\.md")).forEach(n -> BLUEPRINT_DOCUMENTS.add(n.substring(0, 2)));
		}

		final List<String> lines = Files.readAllLines(docs.resolve("blueprint/traceability.csv"));
		final List<String> header = csv(lines.get(0));
		for (String line : lines.subList(1, lines.size()))
		{
			final List<String> cells = csv(line);
			assertEquals(header.size(), cells.size(), "a matrix row with the wrong number of cells: " + line);
			final Map<String, String> row = new LinkedHashMap<>();
			for (int i = 0; i < header.size(); i++)
			{
				row.put(header.get(i), cells.get(i));
			}
			MATRIX.add(row);
		}
	}

	@Test
	void everyRequirementNamesDefinedTests()
	{
		assertTrue(!REQUIREMENTS.isEmpty(), "no requirement found: the pattern no longer matches the specs");
		for (Map.Entry<String, List<String>> requirement : REQUIREMENTS.entrySet())
		{
			assertTrue(!requirement.getValue().isEmpty(), requirement.getKey() + " names no acceptance test");
			for (String test : requirement.getValue())
			{
				assertTrue(TESTS.containsKey(test), requirement.getKey() + " names " + test + ", which no spec defines");
			}
		}
	}

	@Test
	void everyRequirementIsReachedFromTheBlueprint()
	{
		final Set<String> reached = new TreeSet<>();
		for (Map<String, String> row : MATRIX)
		{
			reached.addAll(split(row.get("requirements")));
		}
		final Set<String> unreached = new TreeSet<>(REQUIREMENTS.keySet());
		unreached.removeAll(reached);
		assertEquals(Set.of(), unreached, "requirements no blueprint sub-feature leads to");
	}

	@Test
	void theMatrixCitesOnlyWhatExists()
	{
		final Set<String> ids = new HashSet<>();
		for (Map<String, String> row : MATRIX)
		{
			final String id = row.get("id");
			assertTrue(ids.add(id), "matrix row id twice: " + id);
			assertTrue(STATUSES.contains(row.get("status")), id + " has an unknown status: " + row.get("status"));
			final List<String> rowTests = split(row.get("tests"));
			for (String requirement : split(row.get("requirements")))
			{
				assertTrue(REQUIREMENTS.containsKey(requirement), id + " cites " + requirement + ", which no spec defines");
				for (String test : REQUIREMENTS.get(requirement))
				{
					assertTrue(rowTests.contains(test), id + " cites " + requirement + " but not " + test + ", which proves it");
				}
			}
			final Matcher document = BLUEPRINT_REFERENCE.matcher(row.get("blueprint") + " " + row.get("source"));
			while (document.find())
			{
				assertTrue(BLUEPRINT_DOCUMENTS.contains(document.group(1)), id + " refers to blueprint " + document.group(1) + ", which does not exist");
			}
			for (String test : split(row.get("tests")))
			{
				assertTrue(TESTS.containsKey(test), id + " cites " + test + ", which no spec defines");
			}
			for (String spec : split(row.get("spec")))
			{
				assertTrue(SPEC_FILES.contains(spec), id + " cites " + spec + ", which is not a spec");
			}
			final boolean specified = !"blueprint".equals(row.get("status"));
			assertEquals(specified, !row.get("requirements").isEmpty(), id + ": a row is specified exactly when it names requirements");
		}
	}

	private static List<String> split(String cell)
	{
		final List<String> parts = new ArrayList<>();
		for (String part : cell.split(";"))
		{
			if (!part.isBlank())
			{
				parts.add(part.strip());
			}
		}
		return parts;
	}

	/** One CSV line: commas separate cells, double quotes enclose a cell, a doubled quote inside is one quote. */
	private static List<String> csv(String line)
	{
		final List<String> cells = new ArrayList<>();
		final StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++)
		{
			final char c = line.charAt(i);
			if (quoted)
			{
				if ((c == '"') && ((i + 1) < line.length()) && (line.charAt(i + 1) == '"'))
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
				cells.add(cell.toString());
				cell.setLength(0);
			}
			else
			{
				cell.append(c);
			}
		}
		cells.add(cell.toString());
		return cells;
	}
}
