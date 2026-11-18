package org.l2jmobius.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.GameServer;
import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.livingchronicle.clock.GameScheduler;
import org.l2jmobius.gameserver.livingchronicle.rng.KeyedRandom;

/**
 * The real game server, booted once inside the test JVM.
 * <p>
 * Nothing is mocked: this runs upstream's own {@code GameServer.main} from a staged copy of {@code dist/game}, against a {@link TestDatabase}. The only things changed in the staged copy are where the database is and a static network configuration, so that a test run neither depends on nor talks to anything outside the build.
 * </p>
 * <p>
 * Mobius is static singletons throughout, so a server cannot be booted twice in one JVM. The suite runs one JVM per test class; within a class, {@link #boot()} is idempotent.
 * </p>
 */
public final class TestServer
{
	private static final Path DATABASE_CONFIG = Path.of("config", "Database.ini");
	private static final Path DEFAULT_NETWORK_CONFIG = Path.of("config", "default-ipconfig.xml");
	private static final Path NETWORK_CONFIG = Path.of("config", "ipconfig.xml");
	private static final Path SERVER_LOG = Path.of("log", "java0.log");

	private static final Pattern DATABASE_HOST = Pattern.compile("(?m)^(URL\\s*=\\s*jdbc:mysql://)[^/]+(/" + TestDatabase.SCHEMA + "\\?)");

	/** One record as written by upstream's FileLogFormatter: date, level, thread, logger, message. */
	private static final Pattern LOG_RECORD = Pattern.compile("^\\d{4}\\.\\d{2}\\.\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3}\\t([A-Z]+)\\t\\d+\\t([^\\t]*)\\t(.*)$");

	private static final String BOOT_COMPLETE = "GameServer: Server loaded in ";

	/** Threads Mobius starts itself whose loops are part of the world: the one that turns game time, day and night. */
	private static final Set<String> COOPERATIVE_THREADS = Set.of("GameTimeTaskManager");

	private static TestServer _instance;

	private final TestDatabase _database;
	private final List<LogLine> _bootLog;
	private SimulationRandom _random;

	/**
	 * One line of the server's own log file.
	 */
	public record LogLine(Level level, String logger, String message)
	{
		@Override
		public String toString()
		{
			return level + " " + logger + ": " + message;
		}
	}

	private TestServer(TestDatabase database, List<LogLine> bootLog)
	{
		_database = database;
		_bootLog = bootLog;
	}

	public TestDatabase database()
	{
		return _database;
	}

	/**
	 * While {@code action} runs, every draw in the simulation comes from {@code generator}: on the calling thread, and on the scheduler threads running whatever {@code action} advances. Afterwards draws come from where they came from before. For a lock case that needs randomness of its own, independent of how much the world or earlier cases have drawn.
	 * <p>
	 * World tasks that fall due while {@code action} advances the scheduler draw from {@code generator} too; a case that advances should keep its span short.
	 * </p>
	 */
	public void withRandom(RandomGenerator generator, Runnable action)
	{
		if (_random == null)
		{
			throw new IllegalStateException("only a paused world has a stream to return to");
		}

		final RandomGenerator previous = _random.source();
		_random.use(generator);
		try
		{
			action.run();
		}
		finally
		{
			_random.use(previous);
		}
	}

	/**
	 * Everything the server logged from start until it reported itself loaded, read back from the log file production writes.
	 */
	public List<LogLine> bootLog()
	{
		return _bootLog;
	}

	/**
	 * Boots a world that is standing still: the game clock stopped at {@code epochMillis}, every scheduled task queued instead of run, and all of it drawing from the keyed stream {@code (seed, "world", 0)}.
	 * <p>
	 * Such a world is a function of the code, the datapack, the instant and the seed. Nothing in it moves until the test advances {@link GameScheduler}.
	 * </p>
	 */
	public static synchronized TestServer bootPaused(long epochMillis, long seed) throws Exception
	{
		if (_instance != null)
		{
			throw new IllegalStateException("the server is already booted in this JVM");
		}

		// One stream for the whole world. It is shared by every thread of the simulation, which is safe because only one of them runs at a time.
		final SimulationRandom random = new SimulationRandom(KeyedRandom.of(seed, "world", 0));
		GameClock.set(epochMillis);
		GameScheduler.takeControl(() -> Rnd.bind(random), COOPERATIVE_THREADS);
		Rnd.bind(random);

		final TestServer server = boot();
		server._random = random;

		// Until its first sleep the game-time thread still runs beside us; from here on it only runs when the simulation wakes it.
		GameScheduler.awaitCooperativeThreads(10_000);
		return server;
	}

	/**
	 * Boots the server as production would run it: real time, real thread pool, unseeded randomness.
	 */
	public static synchronized TestServer boot() throws Exception
	{
		if (_instance != null)
		{
			return _instance;
		}

		if (!Files.isRegularFile(DATABASE_CONFIG) || !Files.isDirectory(Path.of("data")))
		{
			throw new IllegalStateException("working directory " + Path.of("").toAbsolutePath() + " is not a staged game runtime; run the suite through ./build.sh");
		}

		final TestDatabase database = TestDatabase.start();
		pointServerAt(database);

		// Without ipconfig.xml the server asks an external web service for its public address at boot.
		Files.copy(DEFAULT_NETWORK_CONFIG, NETWORK_CONFIG, StandardCopyOption.REPLACE_EXISTING);

		GameServer.main(new String[0]);

		_instance = new TestServer(database, readBootLog());
		return _instance;
	}

	private static void pointServerAt(TestDatabase database) throws IOException
	{
		final String config = Files.readString(DATABASE_CONFIG, StandardCharsets.UTF_8);
		final Matcher matcher = DATABASE_HOST.matcher(config);
		if (!matcher.find())
		{
			throw new IllegalStateException(DATABASE_CONFIG + " no longer has the stock 'URL = jdbc:mysql://<host>/" + TestDatabase.SCHEMA + "?...' line");
		}

		Files.writeString(DATABASE_CONFIG, matcher.replaceFirst("$1" + Matcher.quoteReplacement(database.host() + ":" + database.port()) + "$2"), StandardCharsets.UTF_8);
	}

	private static List<LogLine> readBootLog() throws IOException
	{
		final List<LogLine> lines = new ArrayList<>();
		boolean complete = false;
		for (String raw : Files.readAllLines(SERVER_LOG, StandardCharsets.UTF_8))
		{
			final Matcher matcher = LOG_RECORD.matcher(raw);
			if (matcher.matches())
			{
				lines.add(new LogLine(Level.parse(matcher.group(1)), matcher.group(2), matcher.group(3)));
				if (matcher.group(3).startsWith(BOOT_COMPLETE))
				{
					complete = true;
					break;
				}
			}
			else if (!lines.isEmpty())
			{
				// A message that spans lines: keep it attached to its record.
				final LogLine last = lines.removeLast();
				lines.add(new LogLine(last.level(), last.logger(), last.message() + "\n" + raw));
			}
		}

		if (!complete)
		{
			throw new IllegalStateException(SERVER_LOG + " has no '" + BOOT_COMPLETE + "' record; the server did not finish booting");
		}
		return List.copyOf(lines);
	}
}
