package org.l2jmobius.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.opentest4j.AssertionFailedError;

/**
 * Compares what the server does now with what it was recorded doing (blueprint 11 §2).
 * <p>
 * A golden is a plain text file under {@code test/lock}, one fact per line, written to be read and diffed by a person. A passing comparison proves the behaviour is unchanged. It says nothing about the behaviour being right; that is what Retail Truth cases are for.
 * </p>
 * <p>
 * There is no "update goldens" switch, on purpose. Every comparison writes what it observed to the build directory. Recording a new lock, or accepting a changed one, is a person or agent copying that file into {@code test/lock} and committing it with the reason. The suite itself cannot write goldens: it runs with the checkout mounted read-only.
 * </p>
 */
public final class Golden
{
	private static final String GOLDEN_DIR_PROPERTY = "lc.lock.dir";
	private static final String ACTUAL_DIR_PROPERTY = "lc.lock.actual";
	private static final int REPORTED_DIFFERENCES = 20;

	private final Path _goldenDir;
	private final Path _actualDir;

	public Golden(Path goldenDir, Path actualDir)
	{
		_goldenDir = goldenDir;
		_actualDir = actualDir;
	}

	/**
	 * The goldens of this checkout, as located by the build.
	 */
	public static Golden repository()
	{
		return new Golden(Path.of(required(GOLDEN_DIR_PROPERTY)), Path.of(required(ACTUAL_DIR_PROPERTY)));
	}

	private static String required(String property)
	{
		final String value = System.getProperty(property);
		if (value == null)
		{
			throw new IllegalStateException("system property " + property + " is not set; run the suite through ./build.sh");
		}
		return value;
	}

	/**
	 * Fails unless {@code lines} are exactly the content of the golden {@code name}.
	 * @param name path of the golden relative to the golden directory, e.g. {@code combat/physical.tsv}
	 * @param lines the observed behaviour, one fact per line, in a stable order
	 */
	public void assertMatches(String name, List<String> lines)
	{
		for (String line : lines)
		{
			if (line.indexOf('\n') >= 0)
			{
				throw new IllegalArgumentException("a golden line must not contain a line break: " + line);
			}
		}

		final Path actual = _actualDir.resolve(name);
		final Path golden = _goldenDir.resolve(name);
		final List<String> expected;
		try
		{
			Files.createDirectories(actual.getParent());
			Files.write(actual, lines, StandardCharsets.UTF_8);
			expected = Files.isRegularFile(golden) ? Files.readAllLines(golden, StandardCharsets.UTF_8) : null;
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}

		if (expected == null)
		{
			throw new AssertionFailedError("no golden recorded for " + name + ". If this is a new lock, review " + actual + " and copy it to " + golden + ".");
		}

		if (!expected.equals(lines))
		{
			throw new AssertionFailedError(describeDifference(name, expected, lines, actual));
		}
	}

	/**
	 * Writes detail next to what a comparison observed, for whoever has to work out why a golden changed. It is never compared with anything.
	 * @param name path relative to the directory observed behaviour is written to
	 * @param lines the detail
	 */
	public void writeForDiagnosis(String name, List<String> lines)
	{
		final Path file = _actualDir.resolve(name);
		try
		{
			Files.createDirectories(file.getParent());
			Files.write(file, lines, StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	private static String describeDifference(String name, List<String> expected, List<String> lines, Path actual)
	{
		final StringBuilder report = new StringBuilder();
		report.append("behaviour differs from golden ").append(name).append(" (golden ").append(expected.size()).append(" lines, observed ").append(lines.size()).append(" lines)\n");

		final int common = Math.min(expected.size(), lines.size());
		int differences = 0;
		for (int i = 0; i < common; i++)
		{
			if (!expected.get(i).equals(lines.get(i)))
			{
				if (differences < REPORTED_DIFFERENCES)
				{
					report.append("  line ").append(i + 1).append("\n    golden:   ").append(expected.get(i)).append("\n    observed: ").append(lines.get(i)).append('\n');
				}
				differences++;
			}
		}

		report.append("  ").append(differences).append(" of the first ").append(common).append(" lines differ");
		if (differences > REPORTED_DIFFERENCES)
		{
			report.append(" (first ").append(REPORTED_DIFFERENCES).append(" shown)");
		}
		report.append("\n  observed behaviour written to ").append(actual);
		report.append("\n  A golden changes only with an evidence-backed Retail Truth correction or a scoped DESIGN_OVERRIDE (blueprint 11 §2).");
		return report.toString();
	}
}
