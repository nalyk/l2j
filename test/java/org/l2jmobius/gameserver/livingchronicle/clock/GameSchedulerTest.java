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
