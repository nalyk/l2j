package org.l2jmobius.testing.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * No compiled core class reads the system clock in a way the test agent cannot redirect.
 * <p>
 * A test run loads only part of the core, so "the agent saw nothing it could not handle" is not enough. This scans every compiled class with the agent's own rules. When upstream starts asking the time in a new way, this turns red, and the fix is a redirect in {@link TruthAgent} or, for constructors, a ledgered core patch that passes the instant explicitly (CORE_PATCHES.md CP-0002).
 * </p>
 */
class CoreClockCoverageTest
{
	private static final String CLASSES_DIR_PROPERTY = "lc.classes.dir";

	@Test
	void everyCoreClassReadsTimeThroughSomethingRedirectable() throws IOException
	{
		final String classesDir = System.getProperty(CLASSES_DIR_PROPERTY);
		assertTrue(classesDir != null, "system property " + CLASSES_DIR_PROPERTY + " is not set; run the suite through ./build.sh");

		final Path root = Path.of(classesDir);
		final List<Path> classFiles;
		try (Stream<Path> files = Files.walk(root))
		{
			classFiles = files.filter(path -> path.toString().endsWith(".class")).toList();
		}

		int scanned = 0;
		final Set<String> unredirected = new TreeSet<>();
		for (Path classFile : classFiles)
		{
			final String name = root.relativize(classFile).toString().replace('\\', '/').replaceAll("\\.class$", "");
			if (TruthAgent.inScope(name))
			{
				scanned++;
				for (String reference : TruthAgent.unredirectedReadsIn(Files.readAllBytes(classFile)))
				{
					unredirected.add(name + " -> " + reference);
				}
			}
		}

		assertTrue(scanned > 1900, () -> "expected the whole core, found only " + classFiles.size() + " class files under " + root);
		assertEquals(Set.of(), unredirected);
	}
}
