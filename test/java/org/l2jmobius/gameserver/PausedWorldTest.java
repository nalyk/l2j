package org.l2jmobius.gameserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.livingchronicle.clock.GameScheduler;
import org.l2jmobius.gameserver.livingchronicle.rng.KeyedRandom;
import org.l2jmobius.testing.Golden;
import org.l2jmobius.testing.TestServer;
import org.l2jmobius.testing.WorldSummary;

/**
 * LOCK-WORLD: the world is a function of the code, the datapack, the instant it boots at and its seed.
 * <p>
 * The server boots standing still (clock stopped, every task queued, one random stream) and is then advanced through game time by hand. What the world looks like at boot and after ten minutes is recorded in goldens. If two runs of this test disagree, something in the server still depends on real time, real threads or unseeded randomness, and no golden of anything that moves could be trusted.
 * </p>
 * <p>
 * It also locks behaviour in the large: spawn placement, respawn and AI scheduling, the festival and siege schedulers and every periodic manager all feed these summaries, so a change to any of them shows up here even before a narrower lock exists for it.
 * </p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PausedWorldTest
{
	/** 2026-11-09T09:21:30Z, the fork point. Day in game time, so the game clock does not flip to night while booting. */
	private static final long START = 1_794_216_090_000L;
	private static final long SEED = 1;

	private static final long TEN_MINUTES = 600_000;

	private static TestServer _server;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.bootPaused(START, SEED);
	}

	/**
	 * The suite's JVM flag for constant identity hashes is experimental. If a JDK ever drops it, say so here instead of letting worlds quietly stop being reproducible.
	 */
	@Test
	@Order(0)
	void identityHashesAreConstantInTheSuite()
	{
		assertEquals(System.identityHashCode(new Object()), System.identityHashCode(new Object()), "the suite must run with -XX:hashCode=2");
	}

	@Test
	@Order(1)
	void theWorldAtBootIsTheRecordedOne()
	{
		assertEquals(START, GameClock.millis(), "booting must not move game time");

		final List<String> summary = new ArrayList<>();
		summary.add("# LOCK-WORLD-0001: the world as booted at " + START + " with seed " + SEED + ", before any scheduled task has run.");
		summary.add("pending-tasks\t" + GameScheduler.pendingTasks());
		summary.addAll(WorldSummary.of());
		Golden.repository().writeForDiagnosis("world/at-boot.detail.tsv", WorldSummary.detail());
		Golden.repository().assertMatches("world/at-boot.tsv", summary);
	}

	/**
	 * LOCK-BOOT: everything the server reports while booting, in order. Each loader says what it loaded and how much, so this is an inventory of the datapack as the server understood it, and the schedule of everything dated (sieges, events, festivals) as computed for the boot instant.
	 */
	@Test
	@Order(1)
	void theBootReportIsTheRecordedOne()
	{
		final List<String> report = new ArrayList<>();
		report.add("# LOCK-BOOT-0001: the server's boot log at " + START + ", level and message. Figures that describe the machine rather than the game are masked.");
		for (TestServer.LogLine line : _server.bootLog())
		{
			report.add(line.level() + "\t" + maskMachineFigures(line.message()).replace("\n", "\\n"));
		}
		Golden.repository().assertMatches("boot/report.tsv", report);
	}

	/**
	 * A few boot lines report the host rather than the game: memory in use, how long loading took, thread pool sizes derived from the core count. Exactly those figures are replaced, so the golden holds on any machine and everything else stays exact.
	 */
	private static String maskMachineFigures(String message)
	{
		String masked = message.replace(java.nio.file.Path.of("").toAbsolutePath() + "/", "");
		for (Pattern figure : MACHINE_FIGURES)
		{
			final Matcher matcher = figure.matcher(masked);
			if (matcher.matches())
			{
				final StringBuilder result = new StringBuilder(masked);
				for (int group = matcher.groupCount(); group >= 1; group--)
				{
					result.replace(matcher.start(group), matcher.end(group), "#");
				}
				masked = result.toString();
			}
		}
		return masked;
	}

	/** Each group is a figure that depends on the machine. */
	private static final List<Pattern> MACHINE_FIGURES = List.of( //
		Pattern.compile("GameServer: Started, using (\\d+) of (\\d+) MB total memory\\."), //
		Pattern.compile("GameServer: Server loaded in (\\d+) seconds\\."), //
		Pattern.compile("\\.\\.\\.scheduled pool executor with (\\d+) (?:high priority|total) threads\\."), //
		Pattern.compile("\\.\\.\\.instant pool executor with (\\d+) total threads\\."), //
		Pattern.compile("DatabaseIdManager: Cleaned \\d+ elements in (\\d+) seconds\\."));

	@Test
	@Order(2)
	void tenMinutesLaterTheWorldIsTheRecordedOne()
	{
		final int startupRuns = GameScheduler.advance(0);
		final int runs = GameScheduler.advance(TEN_MINUTES);
		assertEquals(START + TEN_MINUTES, GameClock.millis());

		final List<String> summary = new ArrayList<>();
		summary.add("# LOCK-WORLD-0002: the same world after " + TEN_MINUTES + " ms of game time.");
		summary.add("task-runs-due-at-boot\t" + startupRuns);
		summary.add("task-runs-in-ten-minutes\t" + runs);
		summary.add("pending-tasks\t" + GameScheduler.pendingTasks());
		summary.addAll(WorldSummary.of());
		Golden.repository().writeForDiagnosis("world/after-ten-minutes.detail.tsv", WorldSummary.detail());
		Golden.repository().assertMatches("world/after-ten-minutes.tsv", summary);
	}

	@Test
	@Order(3)
	void noScheduledTaskFailed()
	{
		assertEquals(List.of(), GameScheduler.failures());
	}

	/**
	 * A lock case draws on its own stream only for what it sets going (see CaseStreams): one that sets nothing going, open while the world goes on by itself for ten minutes, draws nothing at all. If this fails, some world task draws on whatever case is open, and every case that advances time depends on the rest of the world.
	 */
	@Test
	@Order(4)
	void anEmptyCaseDrawsNothingWhileTheWorldGoesOn()
	{
		final KeyedRandom stream = KeyedRandom.of(SEED, "lock.world.empty-case", 0);
		final AtomicInteger draws = new AtomicInteger();
		final RandomGenerator counting = () ->
		{
			draws.incrementAndGet();
			return stream.nextLong();
		};
		_server.withRandom(counting, () -> GameScheduler.advance(TEN_MINUTES));
		assertEquals(0, draws.get());
	}
}
