package org.l2jmobius.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Living Chronicle's schema migrations, {@code sql/migrations/V<number>__<name>.sql}, applied in version order on top of the stock schema.
 * <p>
 * A migration holds one statement per {@code ;} at the end of a line, and {@code --} comments on lines of their own.
 * </p>
 */
public final class Migrations
{
	private static final String DIR_PROPERTY = "lc.migrations.dir";
	private static final Pattern NAME = Pattern.compile("V(\\d+)__[a-z0-9_]+\\.sql");

	private Migrations()
	{
	}

	/**
	 * @return every migration, in the order it applies; refuses a file in the directory whose name is not a migration's, and two migrations with one version.
	 */
	public static List<Path> all() throws IOException
	{
		final String dir = System.getProperty(DIR_PROPERTY);
		if (dir == null)
		{
			throw new IllegalStateException("system property " + DIR_PROPERTY + " is not set");
		}
		final List<Path> migrations = new ArrayList<>();
		try (Stream<Path> files = Files.list(Path.of(dir)))
		{
			for (Path file : files.toList())
			{
				if (!NAME.matcher(file.getFileName().toString()).matches())
				{
					throw new IllegalStateException("not a migration: " + file);
				}
				migrations.add(file);
			}
		}
		migrations.sort(Comparator.comparingInt(Migrations::version));
		for (int i = 1; i < migrations.size(); i++)
		{
			if (version(migrations.get(i)) == version(migrations.get(i - 1)))
			{
				throw new IllegalStateException("two migrations with version " + version(migrations.get(i)));
			}
		}
		return migrations;
	}

	/**
	 * Applies every migration to the server's own database once, as the operator does before a server starts
	 * (platform.md §4.3): a test that counts existence needs Living Chronicle's tables.
	 */
	public static void ensure() throws IOException, SQLException
	{
		try (Connection connection = org.l2jmobius.commons.database.DatabaseFactory.getConnection();
			java.sql.ResultSet tables = connection.getMetaData().getTables(null, null, "lc_custody", null))
		{
			if (!tables.next())
			{
				applyAll(connection);
			}
		}
	}

	/**
	 * Applies every migration, in order.
	 */
	public static void applyAll(Connection connection) throws IOException, SQLException
	{
		for (Path migration : all())
		{
			apply(connection, migration);
		}
	}

	public static void apply(Connection connection, Path migration) throws IOException, SQLException
	{
		try (Statement statement = connection.createStatement())
		{
			for (String sql : statements(Files.readString(migration)))
			{
				try
				{
					statement.execute(sql);
				}
				catch (SQLException e)
				{
					throw new SQLException(migration.getFileName() + ": " + e.getMessage() + "\n" + sql, e.getSQLState(), e.getErrorCode(), e);
				}
			}
		}
	}

	static List<String> statements(String text)
	{
		final List<String> statements = new ArrayList<>();
		final StringBuilder current = new StringBuilder();
		for (String line : text.split("\n"))
		{
			final String trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith("--"))
			{
				continue;
			}
			current.append(line).append('\n');
			if (trimmed.endsWith(";"))
			{
				final String sql = current.toString().strip();
				statements.add(sql.substring(0, sql.length() - 1));
				current.setLength(0);
			}
		}
		if (!current.toString().isBlank())
		{
			throw new IllegalStateException("a statement does not end with ';': " + current);
		}
		return statements;
	}

	private static int version(Path migration)
	{
		final Matcher matcher = NAME.matcher(migration.getFileName().toString());
		if (!matcher.matches())
		{
			throw new IllegalStateException("not a migration: " + migration);
		}
		return Integer.parseInt(matcher.group(1));
	}
}
