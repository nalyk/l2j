package org.l2jmobius.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A throwaway MariaDB holding the stock Mobius schema.
 * <p>
 * The schema is installed by the database's own client from upstream's SQL files, in the image pinned by toolchain.lock, into a RAM-backed data directory. Nothing is shared between test JVMs.
 * </p>
 * <p>
 * The container is deliberately never stopped by the test: the game server's shutdown hook still needs its database when the JVM exits. Testcontainers' reaper removes the container once the JVM is gone.
 * </p>
 */
public final class TestDatabase
{
	/** The schema name upstream's stock configuration expects. */
	public static final String SCHEMA = "l2jmobiush5";

	private static final String IMAGE_VARIABLE = "LC_MARIADB_IMAGE";
	private static final String SQL_DIR_PROPERTY = "lc.sql.dir";
	private static final String INIT_DIR = "/docker-entrypoint-initdb.d";
	private static final int PORT = 3306;

	/**
	 * The image entrypoint runs init files in name order and aborts on the first error, so a table created by a file that sorts last proves every file before it succeeded.
	 */
	private static final String SENTINEL = "zzzz_lc_schema_installed";

	private static final Set<PosixFilePermission> WORLD_READABLE_DIR = PosixFilePermissions.fromString("rwxr-xr-x");
	private static final Set<PosixFilePermission> WORLD_READABLE_FILE = PosixFilePermissions.fromString("rw-r--r--");

	private final String _host;
	private final int _port;

	private TestDatabase(String host, int port)
	{
		_host = host;
		_port = port;
	}

	public String host()
	{
		return _host;
	}

	public int port()
	{
		return _port;
	}

	public Connection connect() throws SQLException
	{
		return DriverManager.getConnection("jdbc:mysql://" + _host + ":" + _port + "/" + SCHEMA + "?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
	}

	/**
	 * Connects as another user of the test database, to test what a restricted role can read.
	 */
	public Connection connectAs(String user, String password) throws SQLException
	{
		return DriverManager.getConnection("jdbc:mysql://" + _host + ":" + _port + "/" + SCHEMA + "?useSSL=false&allowPublicKeyRetrieval=true", user, password);
	}

	/**
	 * Starts the database and installs the schema. Returns only when every upstream SQL file has been applied.
	 */
	public static TestDatabase start() throws IOException, SQLException
	{
		final String image = System.getenv(IMAGE_VARIABLE);
		if ((image == null) || image.isBlank())
		{
			throw new IllegalStateException(IMAGE_VARIABLE + " is not set; run the suite through ./build.sh so the image pinned in toolchain.lock is used");
		}

		final String sqlDir = System.getProperty(SQL_DIR_PROPERTY);
		if (sqlDir == null)
		{
			throw new IllegalStateException("system property " + SQL_DIR_PROPERTY + " is not set");
		}

		final Path init = collectInitScripts(Path.of(sqlDir));

		// Stock Mobius connects as root with an empty password; the test database matches so only the host has to change.
		final GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image));
		container.withEnv("MARIADB_ALLOW_EMPTY_ROOT_PASSWORD", "1");
		container.withEnv("MARIADB_DATABASE", SCHEMA);
		container.withTmpFs(Map.of("/var/lib/mysql", "rw"));
		container.withExposedPorts(PORT);
		container.withCopyFileToContainer(MountableFile.forHostPath(init), INIT_DIR);

		container.waitingFor(new SchemaInstalled());
		container.start();

		final TestDatabase database = new TestDatabase(container.getHost(), container.getMappedPort(PORT));
		database.requireSentinel();
		return database;
	}

	/**
	 * Ready means the image's own health check passes over TCP.
	 * <p>
	 * While init scripts run, the server listens on a socket only, so a TCP health check cannot pass before the schema is in. A plain "is the port open" wait does not work here: Docker's port proxy accepts connections long before the database does.
	 * </p>
	 */
	private static final class SchemaInstalled implements WaitStrategy
	{
		private static final long POLL_MILLIS = 250;
		
		private Duration _timeout = Duration.ofMinutes(2);
		
		@Override
		public void waitUntilReady(WaitStrategyTarget target)
		{
			final long deadline = System.nanoTime() + _timeout.toNanos();
			while (true)
			{
				// A failing init script makes the entrypoint exit; say why instead of timing out.
				if (!target.isRunning())
				{
					throw new ContainerLaunchException("database container exited while installing the schema:\n" + target.getLogs());
				}
				
				try
				{
					if (target.execInContainer("healthcheck.sh", "--connect", "--innodb_initialized").getExitCode() == 0)
					{
						return;
					}
					
					if (System.nanoTime() > deadline)
					{
						throw new ContainerLaunchException("database not ready after " + _timeout + ":\n" + target.getLogs());
					}
					
					Thread.sleep(POLL_MILLIS);
				}
				catch (IOException e)
				{
					throw new ContainerLaunchException("cannot run the database health check", e);
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					throw new ContainerLaunchException("interrupted while waiting for the database", e);
				}
			}
		}
		
		@Override
		public WaitStrategy withStartupTimeout(Duration startupTimeout)
		{
			_timeout = startupTimeout;
			return this;
		}
	}
	
	/**
	 * Flattens upstream's {@code login} and {@code game} SQL directories into one init directory, the same two sets the stock installer applies to a single schema.
	 */
	private static Path collectInitScripts(Path sqlDir) throws IOException
	{
		final Path init = Files.createTempDirectory("lc-db-init");
		// The database reads these as its own unprivileged user; a temp directory is private by default.
		Files.setPosixFilePermissions(init, WORLD_READABLE_DIR);
		for (String set : List.of("login", "game"))
		{
			try (Stream<Path> files = Files.list(sqlDir.resolve(set)))
			{
				for (Path file : files.filter(path -> path.toString().endsWith(".sql")).toList())
				{
					// No REPLACE_EXISTING: two sets defining the same file is an upstream change we must see, not paper over.
					Files.setPosixFilePermissions(Files.copy(file, init.resolve(file.getFileName())), WORLD_READABLE_FILE);
				}
			}
		}

		final Path sentinel = Files.writeString(init.resolve(SENTINEL + ".sql"), "CREATE TABLE `" + SENTINEL + "` (`id` INT NOT NULL PRIMARY KEY);\n");
		Files.setPosixFilePermissions(sentinel, WORLD_READABLE_FILE);
		return init;
	}

	private void requireSentinel() throws SQLException
	{
		try (Connection connection = connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA + "' AND table_name = '" + SENTINEL + "'"))
		{
			result.next();
			if (result.getInt(1) != 1)
			{
				throw new IllegalStateException("database accepted connections before the schema finished installing");
			}
		}
	}
}
