package org.l2jmobius.gameserver.livingchronicle.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.testing.ProductionJvm;

/**
 * Under control, {@link GameScheduler} runs exactly the tasks that fall due, in a fixed order, at their due time on the game clock.
 */
class GameSchedulerTest
{
	private static final long START = 1_794_216_090_000L;

	private final List<String> _log = new ArrayList<>();

	@BeforeEach
	void takeControl()
	{
		GameClock.set(START);
		GameScheduler.takeControl();
	}

	@AfterEach
	void release()
	{
		GameScheduler.releaseControl();
		GameClock.followSystem();
	}

	/**
	 * A task that records its name and the game time it ran at, relative to the start.
	 */
	private Runnable task(String name)
	{
		return () -> _log.add(name + "@" + (GameClock.millis() - START));
	}

	@Test
	void nothingRunsUntilTimeIsAdvanced()
	{
		GameScheduler.schedule(task("later"), 100);
		GameScheduler.execute(task("now"));
		assertEquals(List.of(), _log);
		assertEquals(2, GameScheduler.pendingTasks());
	}

	@Test
	void tasksRunInOrderOfDueTimeThenOfScheduling()
	{
		GameScheduler.schedule(task("c"), 300);
		GameScheduler.schedule(task("a"), 100);
		GameScheduler.schedule(task("b1"), 200);
		GameScheduler.schedule(task("b2"), 200);
		GameScheduler.execute(task("now"));

		assertEquals(5, GameScheduler.advance(1000));
		assertEquals(List.of("now@0", "a@100", "b1@200", "b2@200", "c@300"), _log);
		assertEquals(START + 1000, GameClock.millis());
		assertEquals(0, GameScheduler.pendingTasks());
	}

	@Test
	void stepsGoFromEventToEvent()
	{
		assertEquals(Long.MAX_VALUE, GameScheduler.nextDue());

		GameScheduler.schedule(task("b"), 250);
		final ScheduledFuture<?> cancelled = GameScheduler.schedule(task("never"), 100);
		GameScheduler.schedule(task("a"), 150);
		cancelled.cancel(false);
		assertEquals(START + 150, GameScheduler.nextDue());

		GameScheduler.advance(GameScheduler.nextDue() - GameClock.millis());
		assertEquals(List.of("a@150"), _log);
		assertEquals(START + 250, GameScheduler.nextDue());

		GameScheduler.advance(GameScheduler.nextDue() - GameClock.millis());
		assertEquals(List.of("a@150", "b@250"), _log);
		assertEquals(Long.MAX_VALUE, GameScheduler.nextDue());
	}

	@Test
	void onlyWhatFallsDueInTheSpanRuns()
	{
		GameScheduler.schedule(task("in"), 500);
		GameScheduler.schedule(task("edge"), 1000);
		GameScheduler.schedule(task("out"), 1001);

		GameScheduler.advance(1000);
		assertEquals(List.of("in@500", "edge@1000"), _log);

		GameScheduler.advance(1);
		assertEquals(List.of("in@500", "edge@1000", "out@1001"), _log);
	}

	@Test
	void advancingByZeroRunsWhatIsAlreadyDue()
	{
		GameScheduler.execute(task("now"));
		GameScheduler.schedule(task("negative"), -50);
		GameScheduler.schedule(task("later"), 1);

		GameScheduler.advance(0);
		assertEquals(List.of("now@0", "negative@0"), _log);
		assertEquals(START, GameClock.millis());
	}

	@Test
	void aFixedRateTaskRunsEveryPeriodFromItsInitialDelay()
	{
		GameScheduler.scheduleAtFixedRate(task("tick"), 100, 250);
		GameScheduler.schedulePriorityTaskAtFixedRate(task("fast"), 0, 400);

		GameScheduler.advance(900);
		assertEquals(List.of("fast@0", "tick@100", "tick@350", "fast@400", "tick@600", "fast@800", "tick@850"), _log);
		assertEquals(2, GameScheduler.pendingTasks());
	}

	@Test
	void tasksScheduledByTasksRunInTheSameAdvance()
	{
		GameScheduler.schedule(() ->
		{
			task("parent").run();
			GameScheduler.schedule(task("child"), 50);
			GameScheduler.execute(task("immediate"));
			GameScheduler.schedule(task("grandchild-out"), 5000);
		}, 100);

		GameScheduler.advance(1000);
		assertEquals(List.of("parent@100", "immediate@100", "child@150"), _log);
		assertEquals(1, GameScheduler.pendingTasks());
	}

	@Test
	void aCancelledTaskNeverRuns()
	{
		final ScheduledFuture<?> once = GameScheduler.schedule(task("once"), 100);
		final ScheduledFuture<?> ticking = GameScheduler.scheduleAtFixedRate(task("tick"), 100, 100);

		GameScheduler.advance(250);
		assertTrue(ticking.cancel(false));
		assertFalse(once.cancel(false), "a finished task cannot be cancelled");
		GameScheduler.advance(1000);

		assertEquals(List.of("once@100", "tick@100", "tick@200"), _log);
		assertTrue(ticking.isCancelled() && ticking.isDone());
		assertTrue(once.isDone() && !once.isCancelled());
		assertEquals(0, GameScheduler.pendingTasks());
	}

	@Test
	void aFixedRateTaskCanCancelItselfWhileRunning()
	{
		final AtomicReference<ScheduledFuture<?>> self = new AtomicReference<>();
		self.set(GameScheduler.scheduleAtFixedRate(() ->
		{
			task("tick").run();
			if (_log.size() == 3)
			{
				self.get().cancel(false);
			}
		}, 10, 10));

		GameScheduler.advance(1000);
		assertEquals(List.of("tick@10", "tick@20", "tick@30"), _log);
		assertEquals(0, GameScheduler.pendingTasks());
	}

	@Test
	void aFailingTaskIsRecordedAndDoesNotStopTheOthers()
	{
		GameScheduler.schedule(() ->
		{
			throw new IllegalStateException("boom");
		}, 10);
		GameScheduler.schedule(task("after"), 20);

		GameScheduler.advance(100);
		assertEquals(List.of("after@20"), _log);
		assertEquals(1, GameScheduler.failures().size());
		assertTrue(GameScheduler.failures().get(0).contains("IllegalStateException: boom"), GameScheduler.failures().toString());
	}

	@Test
	void aHandleReportsItsDelayAndCannotBeWaitedFor()
	{
		final ScheduledFuture<?> handle = GameScheduler.schedule(task("x"), 1500);
		assertEquals(1500, handle.getDelay(TimeUnit.MILLISECONDS));
		assertEquals(1, handle.getDelay(TimeUnit.SECONDS));
		assertThrows(IllegalStateException.class, handle::get);

		GameScheduler.advance(1000);
		assertEquals(500, handle.getDelay(TimeUnit.MILLISECONDS));

		GameScheduler.advance(500);
		assertNull(assertNoThrow(handle));

		final ScheduledFuture<?> cancelled = GameScheduler.schedule(task("y"), 10);
		cancelled.cancel(false);
		assertThrows(CancellationException.class, cancelled::get);
	}

	private static Object assertNoThrow(ScheduledFuture<?> handle)
	{
		try
		{
			return handle.get();
		}
		catch (Exception e)
		{
			throw new AssertionError("a finished task must be gettable", e);
		}
	}

	@Test
	void tasksThatNeverLetTimeMoveAreStoppedNotSpunForever()
	{
		final Runnable[] spinner = new Runnable[1];
		spinner[0] = () -> GameScheduler.execute(spinner[0]);
		GameScheduler.execute(spinner[0]);

		final IllegalStateException stopped = assertThrows(IllegalStateException.class, () -> GameScheduler.advance(0));
		assertTrue(stopped.getMessage().contains("without game time moving"), stopped.getMessage());
	}

	@Test
	void gameTimeNeverRunsBackwards()
	{
		assertThrows(IllegalArgumentException.class, () -> GameScheduler.advance(-1));

		// A task that became overdue because the clock was moved directly still runs, at the present.
		GameScheduler.schedule(task("overdue"), 100);
		GameClock.advance(500);
		GameScheduler.advance(0);
		assertEquals(List.of("overdue@500"), _log);
	}

	@Test
	void controlNeedsAControlledClock()
	{
		GameScheduler.releaseControl();
		GameClock.followSystem();
		assertThrows(IllegalStateException.class, GameScheduler::takeControl);
		assertThrows(IllegalStateException.class, () -> GameScheduler.advance(1));
	}

	@Test
	void withoutControlTasksGoToTheThreadPool() throws InterruptedException
	{
		GameScheduler.releaseControl();
		GameClock.followSystem();
		ThreadPool.init();

		final CountDownLatch ran = new CountDownLatch(3);
		final AtomicReference<String> thread = new AtomicReference<>();
		GameScheduler.execute(() ->
		{
			thread.set(Thread.currentThread().getName());
			ran.countDown();
		});
		GameScheduler.schedule(ran::countDown, 10);
		final ScheduledFuture<?> ticking = GameScheduler.scheduleAtFixedRate(ran::countDown, 10, 10);

		assertTrue(ran.await(10, TimeUnit.SECONDS), "the thread pool did not run the tasks");
		ticking.cancel(false);
		assertTrue(thread.get().startsWith("L2jMobius"), () -> "ran on " + thread.get());
		assertEquals(0, GameScheduler.pendingTasks());
	}

	/**
	 * A task that pauses in game time between two records, as several Mobius tasks do with Thread.sleep.
	 */
	private Runnable sleeper(String name, long millis)
	{
		return () ->
		{
			task(name + "-before").run();
			try
			{
				GameScheduler.sleep(millis);
			}
			catch (InterruptedException e)
			{
				throw new IllegalStateException(e);
			}
			task(name + "-after").run();
		};
	}

	@Test
	void aSleepingTaskDoesNotStopTheWorld()
	{
		GameScheduler.schedule(sleeper("s", 1000), 100);
		GameScheduler.schedule(task("other"), 500);

		GameScheduler.advance(2000);
		assertEquals(List.of("s-before@100", "other@500", "s-after@1100"), _log);
		assertEquals(0, GameScheduler.pendingTasks());
	}

	@Test
	void aSleepSpansAdvances()
	{
		GameScheduler.schedule(sleeper("s", 1000), 100);

		GameScheduler.advance(600);
		assertEquals(List.of("s-before@100"), _log);
		assertEquals(1, GameScheduler.pendingTasks());
		assertEquals(START + 600, GameClock.millis());

		GameScheduler.advance(600);
		assertEquals(List.of("s-before@100", "s-after@1100"), _log);
		assertEquals(START + 1200, GameClock.millis());
	}

	@Test
	void sleepersWakeInOrderOfTheirWakeTimes()
	{
		GameScheduler.schedule(sleeper("long", 900), 0);
		GameScheduler.schedule(sleeper("short", 100), 0);
		GameScheduler.schedule(() ->
		{
			for (int i = 0; i < 3; i++)
			{
				sleeper("loop" + i, 200).run();
			}
		}, 50);

		GameScheduler.advance(5000);
		assertEquals(List.of("long-before@0", "short-before@0", "loop0-before@50", "short-after@100", "loop0-after@250", "loop1-before@250", "loop1-after@450", "loop2-before@450", "loop2-after@650", "long-after@900"), _log);
	}

	@Test
	void aWakeAndATaskDueTogetherRunInOrderOfCreation()
	{
		GameScheduler.schedule(task("scheduled-first"), 300);
		GameScheduler.schedule(sleeper("s", 200), 100);
		GameScheduler.schedule(() -> GameScheduler.schedule(task("scheduled-last"), 100), 200);

		GameScheduler.advance(1000);
		assertEquals(List.of("s-before@100", "scheduled-first@300", "s-after@300", "scheduled-last@300"), _log);
	}

	@Test
	void aSleepingPeriodicTaskKeepsItsRate()
	{
		final ScheduledFuture<?> ticking = GameScheduler.scheduleAtFixedRate(sleeper("t", 30), 0, 100);
		GameScheduler.advance(250);
		ticking.cancel(false);
		assertEquals(List.of("t-before@0", "t-after@30", "t-before@100", "t-after@130", "t-before@200", "t-after@230"), _log);
	}

	@Test
	void everySimulationThreadIsSetUpBeforeItRunsAnything()
	{
		GameScheduler.releaseControl();
		final List<String> setUp = java.util.Collections.synchronizedList(new ArrayList<>());
		GameScheduler.takeControl(() -> setUp.add(Thread.currentThread().getName()), java.util.Set.of());

		// Two sleepers at once need a second scheduler thread to keep the world going.
		GameScheduler.schedule(sleeper("a", 500), 0);
		GameScheduler.schedule(sleeper("b", 500), 0);
		GameScheduler.schedule(() -> _log.add("ran on " + Thread.currentThread().getName()), 100);
		GameScheduler.advance(1000);

		assertTrue(setUp.size() >= 3, () -> "expected three scheduler threads, set up: " + setUp);
		assertEquals(setUp.size(), setUp.stream().distinct().count(), "a thread was set up twice");
		assertTrue(_log.stream().filter(line -> line.startsWith("ran on ")).allMatch(line -> setUp.contains(line.substring("ran on ".length()))), () -> _log + " vs " + setUp);
	}

	@Test
	void aCooperativeThreadSleepsInGameTime() throws InterruptedException
	{
		GameScheduler.releaseControl();
		GameScheduler.takeControl(() ->
		{
		}, java.util.Set.of("cooperative"));

		final List<String> ticks = java.util.Collections.synchronizedList(new ArrayList<>());
		final Thread thread = new Thread(() ->
		{
			try
			{
				while (GameScheduler.isControlled())
				{
					ticks.add("tick@" + (GameClock.millis() - START));
					GameScheduler.sleep(100);
				}
			}
			catch (InterruptedException e)
			{
				// Ends the thread.
			}
		}, "cooperative");
		thread.setDaemon(true);
		thread.start();
		GameScheduler.awaitCooperativeThreads(10_000);

		// It ran once on its own, reached its sleep, and now waits for game time.
		Thread.sleep(50);
		assertEquals(List.of("tick@0"), ticks);

		GameScheduler.schedule(task("task"), 100);
		GameScheduler.advance(350);
		assertEquals(List.of("tick@0", "tick@100", "tick@200", "tick@300"), ticks);

		// Due at the same instant, the cooperative thread goes first: its order must not depend on a start-up race.
		assertEquals(List.of("task@100"), _log);
	}

	@Test
	void aThreadOutsideTheSimulationSleepsForReal() throws InterruptedException
	{
		final long[] elapsedNanos = new long[1];
		final Thread outsider = new Thread(() ->
		{
			final long began = System.nanoTime();
			try
			{
				GameScheduler.sleep(60);
			}
			catch (InterruptedException e)
			{
				// Leaves elapsed at zero and fails the test.
			}
			elapsedNanos[0] = System.nanoTime() - began;
		}, "outsider");
		outsider.start();
		outsider.join(10_000);

		assertTrue(elapsedNanos[0] >= TimeUnit.MILLISECONDS.toNanos(60), () -> "slept only " + elapsedNanos[0] + " ns");
		assertEquals(START, GameClock.millis());
		assertEquals(0, GameScheduler.pendingTasks());
	}

	@Test
	void advancingFromInsideTheSimulationIsRefused()
	{
		GameScheduler.schedule(() -> GameScheduler.advance(10), 0);
		GameScheduler.advance(100);
		assertEquals(1, GameScheduler.failures().size());
		assertTrue(GameScheduler.failures().get(0).contains("from inside the simulation"), GameScheduler.failures().toString());
	}

	/**
	 * Runs in a JVM without the flag.
	 */
	public static final class Probe
	{
		private Probe()
		{
		}

		public static void main(String[] args)
		{
			try
			{
				GameScheduler.takeControl();
				System.out.print("CONTROLLED");
			}
			catch (IllegalStateException e)
			{
				System.out.print(GameScheduler.isControlled() ? "CONTROLLED" : "REFUSED");
			}
		}
	}

	@Test
	void controlIsRefusedWithoutTheFlag() throws Exception
	{
		assertEquals("REFUSED", ProductionJvm.run(Probe.class));
	}
}
