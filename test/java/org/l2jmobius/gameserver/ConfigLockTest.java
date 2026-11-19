package org.l2jmobius.gameserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.Golden;
import org.l2jmobius.testing.TruthProfile;

/**
 * LOCK-CONFIG: every setting the behaviour lock boots the server with, in the truth profile.
 * <p>
 * The other locks are recorded in a world made by these settings, so a default that changes upstream (a rate, a range, a custom module now shipped switched on) changes them all; this lock names it. It reads the staged runtime as {@link TruthProfile} leaves it, without booting.
 * </p>
 */
class ConfigLockTest
{
	/** Settings that describe the machine or the test database rather than the game. */
	private static final Set<String> MACHINE_SETTINGS = Set.of("Database.ini");

	@Test
	void everySettingIsTheRecordedOne() throws IOException
	{
		final Path root = Path.of("");
		TruthProfile.apply(root);

		final List<Path> files;
		try (var walk = Files.walk(root.resolve("config")))
		{
			files = walk.filter(path -> path.toString().endsWith(".ini")).filter(path -> !MACHINE_SETTINGS.contains(path.getFileName().toString())).sorted().toList();
		}

		final List<String> lines = new ArrayList<>();
		lines.add("# LOCK-CONFIG-0001: every setting of the server's .ini files as the truth profile boots with them (blueprint 11 §3), Database.ini left out: it names the test database.");
		lines.add("# A value continued over several lines with a trailing backslash is joined.");
		lines.add("file\tsetting\tvalue");
		for (Path file : files)
		{
			final String name = root.resolve("config").relativize(file).toString();
			String pending = null;
			for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8))
			{
				final String line = raw.strip();
				if (pending != null)
				{
					pending += line.endsWith("\\") ? line.substring(0, line.length() - 1) : line;
					if (!line.endsWith("\\"))
					{
						lines.add(setting(name, pending));
						pending = null;
					}
					continue;
				}
				if (line.isEmpty() || line.startsWith("#") || !line.contains("="))
				{
					continue;
				}
				if (line.endsWith("\\"))
				{
					pending = line.substring(0, line.length() - 1);
					continue;
				}
				lines.add(setting(name, line));
			}
			if (pending != null)
			{
				lines.add(setting(name, pending));
			}
		}
		Golden.repository().assertMatches("config/settings.tsv", lines);
	}

	private static String setting(String file, String line)
	{
		final int equals = line.indexOf('=');
		return file + "\t" + line.substring(0, equals).strip() + "\t" + line.substring(equals + 1).strip();
	}
}
