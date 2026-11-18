package org.l2jmobius.gameserver.livingchronicle.clock;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;

/**
 * The one way to run something later, for Living Chronicle code and for Mobius code in test JVMs.
 * <p>
 * By default every method hands its task to Mobius's {@link ThreadPool}, unchanged, and {@link #sleep(long)} is {@link Thread#sleep(long)}. A JVM started with {@code -Dlc.clock.controllable=true} may take control, and then nothing runs by itself any more: tasks wait in a queue ordered by when they are due on the {@link GameClock}, and {@link #advance(long)} runs them in that order, moving the clock to each task's due time as it goes.
 * </p>
 * <p>
 * Under control the server is a discrete-event simulation. The same tasks run in the same order every time, never two at once, so a world booted with a fixed clock and a bound random stream evolves identically on every run. That is what a behaviour lock of anything that takes time (regeneration, respawns, AI, sieges) is recorded against. It is one legal schedule of the real thread pool, not a different semantics.
 * </p>
 * <p>
 * <b>One thread runs at a time.</b> Simulation code runs on scheduler threads, and exactly one of them holds the baton. A task that sleeps does not stop the world: its thread parks, the baton passes to another scheduler thread which keeps running events, and when the sleeper's wake time comes the baton is handed back to it. Because of that, a task may sleep for an hour of game time inside a loop, as several Mobius tasks do, at no real-time cost. Threads Mobius starts itself can be named as cooperative when taking control; they join the same scheme the first time they sleep. Every other thread is outside the simulation and sleeps for real.
 * </p>
 * <p>
 * Like {@link GameClock}, the methods mirror the signatures they stand in for, because test JVMs point Mobius's calls here as classes load.
 * </p>
 */
public final class GameScheduler
{
	private static final Logger LOGGER = Logger.getLogger(GameScheduler.class.getName());

	private static final boolean CONTROLLABLE = Boolean.getBoolean("lc.clock.controllable");

	/** The clamp ThreadPool applies to delays. */
	private static final long MAX_DELAY_MILLIS = 3155695200000L;

	private static final int MAX_RUNS_PER_INSTANT = 1_000_000;

	/** How many of the last runs before that limit are tallied, so the failure names the tasks that kept the instant from ending. */
	private static final int TALLIED_RUNS = 10_000;

	/** How long, in real time, an advance may take before it is declared stuck. */
	private static final long STUCK_AFTER_SECONDS = Long.getLong("lc.scheduler.stuckAfterSeconds", 600);

	private static final ConcurrentSkipListSet<Event> QUEUE = new ConcurrentSkipListSet<>(GameScheduler::compare);
	private static final AtomicLong SEQUENCE = new AtomicLong();
	private static final ConcurrentLinkedQueue<String> FAILURES = new ConcurrentLinkedQueue<>();

	/** Scheduler threads without the baton and without a sleeping task. Guarded by itself. */
	private static final ArrayDeque<SimThread> IDLE = new ArrayDeque<>();

	/** Every thread that takes part in the simulation, so releasing control can let them all go. */
	private static final Set<SimThread> SIM_THREADS = ConcurrentHashMap.newKeySet();

	private static final ThreadLocal<SimThread> CURRENT = new ThreadLocal<>();

	/** Released by the simulation when an advance has reached its target. */
	private static final Semaphore CONTROLLER_GATE = new Semaphore(0);

	private static volatile boolean _controlled;
	private static volatile Thread _controller;
	private static volatile Runnable _simThreadSetup;
	private static volatile Set<String> _cooperativeThreads = Set.of();
	private static volatile long _targetMillis;
	private static volatile RuntimeException _abort;
	private static volatile int _runs;
	private static int _schedulerThreadCount;
	private static boolean _releaseOnExit;

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
	 * As {@link ThreadPool#schedulePriorityTaskAtFixedRate(Runnable, long, long)}. Under control one thread runs at a time, so priority has no meaning.
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
	 * As {@link Thread#sleep(long)}. Under control, a thread that is part of the simulation sleeps in game time: it parks until the clock reaches its wake time while other events keep running. Any other thread sleeps for real.
	 * @param millis how long to sleep.
	 * @throws InterruptedException as {@link Thread#sleep(long)} when sleeping for real.
	 */
	public static void sleep(long millis) throws InterruptedException
	{
		if (!(CONTROLLABLE && _controlled))
		{
			Thread.sleep(millis);
			return;
		}
		if (millis < 0)
		{
			throw new IllegalArgumentException("timeout value is negative"); // As Thread.sleep.
		}

		SimThread self = CURRENT.get();
		final boolean holdsBaton = self != null;
		if (self == null)
		{
			// A thread Mobius started itself. It joins the simulation only if it was named as cooperative.
			final Thread thread = Thread.currentThread();
			if (!_cooperativeThreads.contains(thread.getName()))
			{
				Thread.sleep(millis);
				return;
			}
			self = join(thread.getName());
		}

		QUEUE.add(new Wake(self, Math.addExact(GameClock.millis(), clamp(millis)), SEQUENCE.getAndIncrement()));
		if (holdsBaton)
		{
			// The world goes on while this thread sleeps.
			passBatonToAScheduler();
		}

		// Parked until the event loop reaches our wake time and hands the baton to us.
		self._resume.acquireUninterruptibly();
	}

	/**
	 * Takes control: from now on tasks wait for {@link #advance(long)} instead of being handed to the thread pool. Tasks already handed over are unaffected, so take control before the server boots.
	 * @param simThreadSetup run once on every thread that becomes part of the simulation, before it runs anything else; this is where a test binds the shared random stream.
	 * @param cooperativeThreads names of threads Mobius starts itself that are part of the simulation: their sleeps become game-time sleeps.
	 * @throws IllegalStateException in a JVM that was not started as controllable, or while the clock is not controlled: a queue ordered by time needs a clock that only moves when told.
	 */
	public static synchronized void takeControl(Runnable simThreadSetup, Set<String> cooperativeThreads)
	{
		if (!CONTROLLABLE)
		{
			throw new IllegalStateException("GameScheduler is not controllable: this JVM was started without -Dlc.clock.controllable=true");
		}
		if (!GameClock.isControlled())
		{
			throw new IllegalStateException("set the GameClock before taking control of the scheduler");
		}
		if (_controlled)
		{
			throw new IllegalStateException("the scheduler is already controlled");
		}

		_simThreadSetup = simThreadSetup;
		_cooperativeThreads = Set.copyOf(cooperativeThreads);
		_controller = Thread.currentThread();
		_abort = null;
		_controlled = true;

		// A JVM that exits while controlled must not leave a shutdown hook asleep in game time.
		if (!_releaseOnExit)
		{
			_releaseOnExit = true;
			Runtime.getRuntime().addShutdownHook(new Thread(GameScheduler::releaseControl, "GameScheduler-release"));
		}
	}

	/**
	 * Takes control with no thread setup and no cooperative threads.
	 */
	public static void takeControl()
	{
		takeControl(() ->
		{
		}, Set.of());
	}

	/**
	 * @return whether tasks are being queued rather than handed to the thread pool.
	 */
	public static boolean isControlled()
	{
		return CONTROLLABLE && _controlled;
	}

	/**
	 * Lets {@code millis} of game time pass: runs every task that falls due in that span, in order of due time and then of scheduling. The clock reads each task's due time while it runs and {@code now + millis} at the end. Tasks may schedule more tasks; those run too if they fall inside the span.
	 * @param millis how much game time passes; 0 runs only what is already due.
	 * @return how many task runs were executed.
	 */
	public static int advance(long millis)
	{
		// Checked before taking the lock: a task that calls this would otherwise wait forever for the advance it is running in.
		if (CURRENT.get() != null)
		{
			throw new IllegalStateException("advance() called from inside the simulation");
		}
		synchronized (GameScheduler.class)
		{
			return advanceFromOutside(millis);
		}
	}

	private static int advanceFromOutside(long millis)
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
		_targetMillis = end;
		_runs = 0;
		passBatonToAScheduler();

		try
		{
			if (!CONTROLLER_GATE.tryAcquire(STUCK_AFTER_SECONDS, TimeUnit.SECONDS))
			{
				throw new IllegalStateException("advance(" + millis + ") did not finish within " + STUCK_AFTER_SECONDS + " s of real time; a task is blocked on something that is not game time:\n" + describeSimThreads());
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while the simulation was running", e);
		}

		final RuntimeException abort = _abort;
		if (abort != null)
		{
			_abort = null;
			throw abort;
		}

		GameClock.set(end);
		return _runs;
	}

	/**
	 * Blocks, in real time, until every cooperative thread named when taking control has started and reached its first sleep. Until then such a thread still runs beside the caller; afterwards it only runs when the event loop wakes it.
	 * @param timeoutMillis how long to wait, in real time.
	 */
	public static void awaitCooperativeThreads(long timeoutMillis) throws InterruptedException
	{
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		while (true)
		{
			final Set<String> missing = new java.util.TreeSet<>(_cooperativeThreads);
			for (SimThread thread : SIM_THREADS)
			{
				missing.remove(thread._name);
			}
			if (missing.isEmpty())
			{
				return;
			}
			if (System.nanoTime() > deadline)
			{
				throw new IllegalStateException("cooperative threads never reached a sleep: " + missing);
			}
			Thread.sleep(10);
		}
	}

	/**
	 * Releases control and discards every task still waiting. Sleeping simulation threads wake at once; new tasks go to the thread pool again.
	 */
	public static void releaseControl()
	{
		_controlled = false;
		QUEUE.clear();
		FAILURES.clear();
		_cooperativeThreads = Set.of();

		synchronized (IDLE)
		{
			IDLE.clear();
		}
		for (SimThread thread : SIM_THREADS)
		{
			thread._resume.release();
		}
		SIM_THREADS.clear();
		CONTROLLER_GATE.drainPermits();
	}

	/**
	 * @return how many tasks and sleepers are waiting.
	 */
	public static int pendingTasks()
	{
		return QUEUE.size();
	}

	/**
	 * When the next thing will happen: advancing by {@code nextDue() - GameClock.millis()} runs exactly the earliest waiting task or wake-up, and whatever else is due at that same instant. This is how a test steps through a process one event at a time, and learns how long it took, without guessing a step size.
	 * @return the game time the earliest waiting task or sleeper is due, or {@link Long#MAX_VALUE} if nothing is waiting.
	 */
	public static long nextDue()
	{
		try
		{
			return QUEUE.first()._dueMillis;
		}
		catch (NoSuchElementException e)
		{
			return Long.MAX_VALUE;
		}
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
	 * The order events run in: by due time, then cooperative wake-ups by thread name, then by creation.
	 */
	private static int compare(Event event, Event other)
	{
		final int byDue = Long.compare(event._dueMillis, other._dueMillis);
		if (byDue != 0)
		{
			return byDue;
		}

		final String name = event.cooperativeName();
		final String otherName = other.cooperativeName();
		if ((name != null) || (otherName != null))
		{
			if (name == null)
			{
				return 1;
			}
			if (otherName == null)
			{
				return -1;
			}
			final int byName = name.compareTo(otherName);
			if (byName != 0)
			{
				return byName;
			}
		}
		return Long.compare(event._sequence, other._sequence);
	}

	/**
	 * Makes the calling thread part of the simulation.
	 */
	private static SimThread join(String name)
	{
		final SimThread self = new SimThread(name);
		CURRENT.set(self);
		_simThreadSetup.run();
		SIM_THREADS.add(self);
		return self;
	}

	/**
	 * Hands the baton to an idle scheduler thread, starting one if none is idle. The caller must stop running simulation code right after.
	 */
	private static void passBatonToAScheduler()
	{
		SimThread next;
		synchronized (IDLE)
		{
			next = IDLE.pollFirst();
			if (next == null)
			{
				final String name = "GameScheduler-" + (++_schedulerThreadCount);
				final SimThread started = new SimThread(name);
				final Thread thread = new Thread(() -> runScheduler(started), name);
				thread.setDaemon(true);
				thread.start();
				next = started;
			}
		}
		next._resume.release();
	}

	/**
	 * A scheduler thread: waits for the baton, then runs events until the target is reached or the baton goes to a sleeper.
	 */
	private static void runScheduler(SimThread self)
	{
		CURRENT.set(self);
		SIM_THREADS.add(self);
		self._resume.acquireUninterruptibly();
		if (!_controlled)
		{
			return;
		}
		_simThreadSetup.run();

		int runsAtThisInstant = 0;
		final Map<String, Integer> tally = new HashMap<>();
		while (_controlled)
		{
			final Event event = QUEUE.pollFirst();
			if ((event == null) || (event._dueMillis > _targetMillis) || (_abort != null))
			{
				if (event != null)
				{
					QUEUE.add(event);
				}

				// Target reached: the baton goes back to whoever called advance().
				if (!idleUntilResumed(self, CONTROLLER_GATE))
				{
					return;
				}
				runsAtThisInstant = 0;
				continue;
			}

			// An event that became overdue because the clock was moved directly runs now; game time never goes back.
			if (event._dueMillis > GameClock.millis())
			{
				GameClock.set(event._dueMillis);
				runsAtThisInstant = 0;
			}

			if (event instanceof Wake wake)
			{
				// The sleeper carries on from where it slept and becomes the running thread.
				if (!idleUntilResumed(self, wake._sleeper._resume))
				{
					return;
				}
				runsAtThisInstant = 0;
				continue;
			}

			// Tasks that keep scheduling each other with no delay would spin a real pool forever; here they would hang the caller.
			if (++runsAtThisInstant > MAX_RUNS_PER_INSTANT)
			{
				QUEUE.add(event);
				_abort = new IllegalStateException("more than " + MAX_RUNS_PER_INSTANT + " task runs without game time moving at " + GameClock.millis() + "; of the last " + TALLIED_RUNS + ", " + mostRun(tally));
				tally.clear();
				continue;
			}
			if (runsAtThisInstant > (MAX_RUNS_PER_INSTANT - TALLIED_RUNS))
			{
				tally.merge(((Task) event)._runnable.getClass().getName(), 1, Integer::sum);
			}
			else if (!tally.isEmpty())
			{
				tally.clear();
			}

			_runs++;
			((Task) event).run();
		}
	}

	/**
	 * The tasks of {@code tally} that ran most, most first, as "name (runs)".
	 */
	private static String mostRun(Map<String, Integer> tally)
	{
		final StringJoiner names = new StringJoiner(", ");
		tally.entrySet().stream().sorted(Map.Entry.<String, Integer> comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(5).forEach(entry -> names.add(entry.getKey() + " (" + entry.getValue() + ")"));
		return names.toString();
	}

	/**
	 * Gives the baton away through {@code handover} and parks as an idle scheduler until it comes back.
	 * @return false if control was released meanwhile and the thread should end.
	 */
	private static boolean idleUntilResumed(SimThread self, Semaphore handover)
	{
		synchronized (IDLE)
		{
			IDLE.addFirst(self);
		}
		handover.release();
		self._resume.acquireUninterruptibly();
		return _controlled;
	}

	private static String describeSimThreads()
	{
		final StringBuilder description = new StringBuilder();
		for (Thread thread : Thread.getAllStackTraces().keySet())
		{
			for (SimThread sim : SIM_THREADS)
			{
				if (sim._name.equals(thread.getName()))
				{
					description.append(thread.getName()).append(" (").append(thread.getState()).append(")\n");
					for (StackTraceElement frame : thread.getStackTrace())
					{
						description.append("    at ").append(frame).append('\n');
					}
				}
			}
		}
		return description.toString();
	}

	/**
	 * A thread that takes part in the simulation: a scheduler thread, or a cooperative thread Mobius started.
	 */
	private static final class SimThread
	{
		final String _name;

		/** Released to hand this thread the baton. */
		final Semaphore _resume = new Semaphore(0);

		SimThread(String name)
		{
			_name = name;
		}
	}

	/**
	 * Something due at a point in game time. Ordered by due time, then by the order it was created, which makes the order total and repeatable.
	 */
	private abstract static class Event
	{
		final long _sequence;
		volatile long _dueMillis;

		Event(long dueMillis, long sequence)
		{
			_dueMillis = dueMillis;
			_sequence = sequence;
		}

		/**
		 * Cooperative threads start beside the booting thread, so the order in which their first sleeps are created is a race. Their wake-ups are ordered by thread name, ahead of everything else due at the same instant, to keep the order repeatable.
		 */
		String cooperativeName()
		{
			return null;
		}

	}

	/**
	 * The end of a game-time sleep.
	 */
	private static final class Wake extends Event
	{
		final SimThread _sleeper;

		Wake(SimThread sleeper, long dueMillis, long sequence)
		{
			super(dueMillis, sequence);
			_sleeper = sleeper;
		}

		@Override
		String cooperativeName()
		{
			return _sleeper._name.startsWith("GameScheduler-") ? null : _sleeper._name;
		}
	}

	/**
	 * A queued task.
	 */
	private static final class Task extends Event implements ScheduledFuture<Object>
	{
		private final Runnable _runnable;
		private final long _periodMillis;
		private volatile boolean _cancelled;
		private volatile boolean _done;

		Task(Runnable runnable, long dueMillis, long periodMillis, long sequence)
		{
			super(dueMillis, sequence);
			_runnable = runnable;
			_periodMillis = periodMillis;
		}

		void run()
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

			if ((_periodMillis > 0) && !_cancelled && _controlled)
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
			if (other instanceof Event event)
			{
				return compare(this, event);
			}
			return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
		}
	}
}
