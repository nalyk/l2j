package org.l2jmobius.gameserver.livingchronicle.clock;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;

/**
 * The one way to run something later, for Living Chronicle code and for Mobius code in test JVMs.
 * <p>
 * By default every method hands its task to Mobius's {@link ThreadPool}, unchanged. A JVM started with {@code -Dlc.clock.controllable=true} may take control, and then nothing runs by itself any more: tasks wait in a queue ordered by when they are due on the {@link GameClock}, and {@link #advance(long)} runs them, one after another on the calling thread, moving the clock to each task's due time as it goes.
 * </p>
 * <p>
 * Under control the server is a discrete-event simulation. The same tasks run in the same order every time, on one thread, so a world booted with a fixed clock and a bound random stream evolves identically on every run. That is what a behaviour lock of anything that takes time (regeneration, respawns, AI, sieges) is recorded against. It is one legal schedule of the real thread pool, not a different semantics: tasks never overlap, as if the pool had one very fast thread.
 * </p>
 * <p>
 * Like {@link GameClock}, the methods mirror the {@link ThreadPool} signatures they stand in for, because test JVMs point Mobius's calls here as classes load.
 * </p>
 */
public final class GameScheduler
{
	private static final Logger LOGGER = Logger.getLogger(GameScheduler.class.getName());

	private static final boolean CONTROLLABLE = Boolean.getBoolean("lc.clock.controllable");

	/** The clamp ThreadPool applies to delays. */
	private static final long MAX_DELAY_MILLIS = 3155695200000L;

	private static final int MAX_RUNS_PER_INSTANT = 1_000_000;

	private static final ConcurrentSkipListSet<Task> QUEUE = new ConcurrentSkipListSet<>();
	private static final AtomicLong SEQUENCE = new AtomicLong();
	private static final ConcurrentLinkedQueue<String> FAILURES = new ConcurrentLinkedQueue<>();

	private static volatile boolean _controlled;

	private GameScheduler()
	{
	}

	/**
	 * As {@link ThreadPool#schedule(Runnable, long)}.
	 * @param runnable the task.
	 * @param delay milliseconds from now.
	 * @return a handle to cancel the task.
	 */
	public static ScheduledFuture<?> schedule(Runnable runnable, long delay)
	{
		if (CONTROLLABLE && _controlled)
		{
			return enqueue(runnable, delay, 0);
		}
		return ThreadPool.schedule(runnable, delay);
	}

	/**
	 * As {@link ThreadPool#scheduleAtFixedRate(Runnable, long, long)}.
	 * @param runnable the task.
	 * @param initialDelay milliseconds from now to the first run.
	 * @param period milliseconds between runs.
	 * @return a handle to cancel the task.
	 */
	public static ScheduledFuture<?> scheduleAtFixedRate(Runnable runnable, long initialDelay, long period)
	{
		if (CONTROLLABLE && _controlled)
		{
			return enqueue(runnable, initialDelay, clamp(period));
		}
		return ThreadPool.scheduleAtFixedRate(runnable, initialDelay, period);
	}

	/**
	 * As {@link ThreadPool#schedulePriorityTaskAtFixedRate(Runnable, long, long)}. Under control there is one thread, so priority has no meaning.
	 * @param runnable the task.
	 * @param initialDelay milliseconds from now to the first run.
	 * @param period milliseconds between runs.
	 * @return a handle to cancel the task.
	 */
	public static ScheduledFuture<?> schedulePriorityTaskAtFixedRate(Runnable runnable, long initialDelay, long period)
	{
		if (CONTROLLABLE && _controlled)
		{
			return enqueue(runnable, initialDelay, clamp(period));
		}
		return ThreadPool.schedulePriorityTaskAtFixedRate(runnable, initialDelay, period);
	}

	/**
	 * As {@link ThreadPool#execute(Runnable)}. Under control the task is due immediately and runs at the next {@link #advance(long)}, after everything already due.
	 * @param runnable the task.
	 */
	public static void execute(Runnable runnable)
	{
		if (CONTROLLABLE && _controlled)
		{
			enqueue(runnable, 0, 0);
			return;
		}
		ThreadPool.execute(runnable);
	}

	/**
	 * Takes control: from now on tasks wait for {@link #advance(long)} instead of being handed to the thread pool. Tasks already handed over are unaffected, so take control before the server boots.
	 * @throws IllegalStateException in a JVM that was not started as controllable, or while the clock is not controlled: a queue ordered by time needs a clock that only moves when told.
	 */
	public static synchronized void takeControl()
	{
		if (!CONTROLLABLE)
		{
			throw new IllegalStateException("GameScheduler is not controllable: this JVM was started without -Dlc.clock.controllable=true");
		}
		if (!GameClock.isControlled())
		{
			throw new IllegalStateException("set the GameClock before taking control of the scheduler");
		}
		_controlled = true;
	}

	/**
	 * @return whether tasks are being queued rather than handed to the thread pool.
	 */
	public static boolean isControlled()
	{
		return CONTROLLABLE && _controlled;
	}

	/**
	 * Lets {@code millis} of game time pass: runs every task that falls due in that span, in order of due time and then of scheduling, on the calling thread. The clock reads each task's due time while it runs and {@code now + millis} at the end. Tasks may schedule more tasks; those run too if they fall inside the span.
	 * @param millis how much game time passes; 0 runs only what is already due.
	 * @return how many task runs were executed.
	 */
	public static synchronized int advance(long millis)
	{
		if (!isControlled())
		{
			throw new IllegalStateException("the scheduler is not controlled");
		}
		if (millis < 0)
		{
			throw new IllegalArgumentException("cannot advance by a negative amount: " + millis);
		}

		final long end = Math.addExact(GameClock.millis(), millis);
		int runs = 0;
		int runsAtThisInstant = 0;
		while (true)
		{
			final Task task = QUEUE.pollFirst();
			if (task == null)
			{
				break;
			}
			if (task._dueMillis > end)
			{
				QUEUE.add(task);
				break;
			}

			// A task scheduled in the past (the clock was moved directly) runs now; game time never goes back.
			if (task._dueMillis > GameClock.millis())
			{
				GameClock.set(task._dueMillis);
				runsAtThisInstant = 0;
			}

			// Tasks that keep scheduling each other with no delay would spin a real pool forever; here they would hang the caller.
			if (++runsAtThisInstant > MAX_RUNS_PER_INSTANT)
			{
				QUEUE.add(task);
				throw new IllegalStateException("more than " + MAX_RUNS_PER_INSTANT + " task runs without game time moving; last was " + task._runnable.getClass().getName());
			}

			task.run();
			runs++;
		}

		GameClock.set(end);
		return runs;
	}

	/**
	 * Releases control and discards every task still waiting. New tasks go to the thread pool again.
	 */
	public static synchronized void releaseControl()
	{
		_controlled = false;
		QUEUE.clear();
		FAILURES.clear();
	}

	/**
	 * @return how many tasks are waiting.
	 */
	public static int pendingTasks()
	{
		return QUEUE.size();
	}

	/**
	 * @return every task that threw while running under control, as {@code task class: throwable}. The thread pool would have logged these and carried on; so does the queue, but a deterministic run should also be able to assert there were none.
	 */
	public static List<String> failures()
	{
		return List.copyOf(FAILURES);
	}

	private static Task enqueue(Runnable runnable, long delay, long period)
	{
		final Task task = new Task(runnable, Math.addExact(GameClock.millis(), clamp(delay)), period, SEQUENCE.getAndIncrement());
		QUEUE.add(task);
		return task;
	}

	private static long clamp(long delay)
	{
		return Math.clamp(delay, 0, MAX_DELAY_MILLIS);
	}

	/**
	 * A queued task. Ordered by due time, then by the order it was scheduled, which makes the order total and repeatable.
	 */
	private static final class Task implements ScheduledFuture<Object>, Runnable
	{
		private final Runnable _runnable;
		private final long _periodMillis;
		private final long _sequence;
		private volatile long _dueMillis;
		private volatile boolean _cancelled;
		private volatile boolean _done;

		Task(Runnable runnable, long dueMillis, long periodMillis, long sequence)
		{
			_runnable = runnable;
			_dueMillis = dueMillis;
			_periodMillis = periodMillis;
			_sequence = sequence;
		}

		@Override
		public void run()
		{
			if (_cancelled)
			{
				return;
			}

			try
			{
				_runnable.run();
			}
			catch (Throwable t)
			{
				FAILURES.add(_runnable.getClass().getName() + ": " + t);
				LOGGER.log(Level.WARNING, "GameScheduler: task " + _runnable.getClass().getName() + " failed.", t);
			}

			if ((_periodMillis > 0) && !_cancelled)
			{
				_dueMillis += _periodMillis;
				QUEUE.add(this);
			}
			else
			{
				_done = true;
			}
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning)
		{
			if (_done || _cancelled)
			{
				return false;
			}
			_cancelled = true;
			QUEUE.remove(this);
			return true;
		}

		@Override
		public boolean isCancelled()
		{
			return _cancelled;
		}

		@Override
		public boolean isDone()
		{
			return _done || _cancelled;
		}

		@Override
		public long getDelay(TimeUnit unit)
		{
			return unit.convert(_dueMillis - GameClock.millis(), TimeUnit.MILLISECONDS);
		}

		/**
		 * Nothing can wait for a queued task: the thread that would run it is the one asking.
		 */
		@Override
		public Object get()
		{
			if (_cancelled)
			{
				throw new CancellationException();
			}
			if (_done)
			{
				return null;
			}
			throw new IllegalStateException("a task queued under GameScheduler control cannot be waited for; advance the scheduler instead");
		}

		@Override
		public Object get(long timeout, TimeUnit unit)
		{
			return get();
		}

		@Override
		public int compareTo(Delayed other)
		{
			if (other instanceof Task task)
			{
				final int byDue = Long.compare(_dueMillis, task._dueMillis);
				return byDue != 0 ? byDue : Long.compare(_sequence, task._sequence);
			}
			return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
		}
	}
}
