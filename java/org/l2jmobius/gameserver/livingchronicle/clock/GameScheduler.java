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

	/** Cooperative threads that have queued their first wake: from then on they run only when the event loop wakes them. */
	private static final Set<String> READY = ConcurrentHashMap.newKeySet();

	/** How long releasing control waits, in real time, for the scheduler threads of the session it ends to finish. */
	private static final long RELEASE_WAIT_MILLIS = 10_000;

	private static final ThreadLocal<SimThread> CURRENT = new ThreadLocal<>();

	/** Released by the simulation when an advance has reached its target. */
	private static final Semaphore CONTROLLER_GATE = new Semaphore(0);

	private static final TaskContext NO_CONTEXT = new TaskContext()
	{
		@Override
		public Object capture()
		{
			return null;
		}

		@Override
		public void restore(Object context)
		{
		}
	};

	private static volatile boolean _controlled;
	/** Which control session this is. Threads and tasks of an earlier session are let go and never act in a later one. */
	private static volatile int _currentSession;
	private static volatile Thread _controller;
	private static volatile Runnable _simThreadSetup;
	private static volatile TaskContext _taskContext = NO_CONTEXT;
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
	 * What a task carries from the code that scheduled it into its runs, and a simulation thread across its sleeps: for a test, the random stream it draws from, so that what one case sets going draws on that case's stream and the rest of the world on its own. Only one thread of the simulation runs at a time, so a context that is one setting for the whole simulation stays right as long as it is put back every time a thread takes the baton, which the scheduler does.
	 */
	public interface TaskContext
	{
		/**
		 * @return the context the code running now is in.
		 */
		Object capture();

		/**
		 * Puts the simulation in {@code context}, for the code about to run.
		 * @param context one {@link #capture()} returned.
		 */
		void restore(Object context);

		/**
		 * @param runnable a task about to run.
		 * @param scheduled the context it was scheduled in.
		 * @return the context it runs in: by default the one it was scheduled in.
		 */
		default Object forRun(Runnable runnable, Object scheduled)
		{
			return scheduled;
		}
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
			return enqueuePeriodic(runnable, initialDelay, period, "recurring task");
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
			return enqueuePeriodic(runnable, initialDelay, period, "high priority task");
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
		if ((self != null) && (self._session != _currentSession))
		{
			// A thread of a session that has ended is no longer part of the simulation.
			CURRENT.remove();
			self = null;
		}
		final boolean holdsBaton = self != null;
		final boolean joining = self == null;
		if (joining)
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

		// As Thread.sleep with the interrupt status set: the status is cleared and the sleep does not happen.
		if (self._interrupted)
		{
			self._interrupted = false;
			throw new InterruptedException("sleep interrupted");
		}

		final Object resumeIn = _taskContext.capture();
		final Wake wake = new Wake(self, Math.addExact(GameClock.millis(), clamp(millis)), SEQUENCE.getAndIncrement());
		self._wake = wake;
		QUEUE.add(wake);
		if (joining)
		{
			// Only now, with its wake queued, does the thread run only when the event loop wakes it.
			READY.add(self._name);
		}
		if (holdsBaton)
		{
			// The world goes on while this thread sleeps.
			passBatonToAScheduler();
		}

		// Parked until the event loop reaches our wake time, or an interrupt moves it to now, and hands the baton to us.
		self._resume.acquireUninterruptibly();
		self._wake = null;
		_taskContext.restore(resumeIn);
		if (self._interrupted && (self._session == _currentSession))
		{
			self._interrupted = false;
			throw new InterruptedException("sleep interrupted");
		}
	}

	/**
	 * Takes control: from now on tasks wait for {@link #advance(long)} instead of being handed to the thread pool. Tasks already handed over are unaffected, so take control before the server boots.
	 * @param simThreadSetup run once on every thread that becomes part of the simulation, before it runs anything else; this is where a test binds the shared random stream.
	 * @param cooperativeThreads names of threads Mobius starts itself that are part of the simulation: their sleeps become game-time sleeps.
	 * @throws IllegalStateException in a JVM that was not started as controllable, or while the clock is not controlled: a queue ordered by time needs a clock that only moves when told.
	 */
	public static void takeControl(Runnable simThreadSetup, Set<String> cooperativeThreads)
	{
		takeControl(simThreadSetup, cooperativeThreads, NO_CONTEXT);
	}

	/**
	 * Takes control, every task carrying the {@link TaskContext} it was scheduled in.
	 * @param simThreadSetup as {@link #takeControl(Runnable, Set)}.
	 * @param cooperativeThreads as {@link #takeControl(Runnable, Set)}.
	 * @param context what tasks carry from where they were scheduled.
	 */
	public static synchronized void takeControl(Runnable simThreadSetup, Set<String> cooperativeThreads, TaskContext context)
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
		_taskContext = context;
		_controller = Thread.currentThread();
		_abort = null;
		READY.clear();
		_currentSession++;
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
		final Object resumeIn = _taskContext.capture();
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
		finally
		{
			_taskContext.restore(resumeIn);
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
			missing.removeAll(READY);
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
		_currentSession++;
		QUEUE.clear();
		FAILURES.clear();
		READY.clear();
		_cooperativeThreads = Set.of();
		_taskContext = NO_CONTEXT;

		synchronized (IDLE)
		{
			IDLE.clear();
		}
		final List<SimThread> released = List.copyOf(SIM_THREADS);
		for (SimThread thread : released)
		{
			thread._resume.release();
		}
		SIM_THREADS.clear();
		CONTROLLER_GATE.drainPermits();

		// A scheduler thread finishes the task it is in, unparked, and ends. None of this session may act in the next, so wait for them.
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RELEASE_WAIT_MILLIS);
		final List<String> stillRunning = new java.util.ArrayList<>();
		for (SimThread thread : released)
		{
			final Thread running = thread._thread;
			if ((running == null) || !thread._scheduler || (running == Thread.currentThread()))
			{
				continue;
			}
			try
			{
				running.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return;
			}
			if (running.isAlive())
			{
				stillRunning.add(thread._name);
			}
		}
		if (!stillRunning.isEmpty())
		{
			throw new IllegalStateException("scheduler threads of the released session did not end within " + RELEASE_WAIT_MILLIS + " ms: " + stillRunning);
		}
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

	/**
	 * As the thread pool's fixed-rate methods: a period that is not positive is refused with a warning and no task, as the pool's executor refuses it.
	 */
	private static Task enqueuePeriodic(Runnable runnable, long initialDelay, long period, String kind)
	{
		if (clamp(period) <= 0)
		{
			LOGGER.warning("GameScheduler: Failed to schedule " + kind + " " + runnable.getClass().getSimpleName() + " with initial delay " + initialDelay + "ms and period " + period + "ms: the period must be positive.");
			return null;
		}
		return enqueue(runnable, initialDelay, clamp(period));
	}

	private static Task enqueue(Runnable runnable, long delay, long period)
	{
		final Task task = new Task(runnable, Math.addExact(GameClock.millis(), clamp(delay)), period, SEQUENCE.getAndIncrement(), _taskContext.capture());
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
		final SimThread self = new SimThread(name, Thread.currentThread(), false);
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
				final SimThread[] started = new SimThread[1];
				final Thread thread = new Thread(() -> runScheduler(started[0]), name);
				started[0] = new SimThread(name, thread, true);
				thread.setDaemon(true);
				thread.start();
				next = started[0];
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
		if (!inSession(self))
		{
			return;
		}
		_simThreadSetup.run();

		int runsAtThisInstant = 0;
		final Map<String, Integer> tally = new HashMap<>();
		while (inSession(self))
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
			final Task task = (Task) event;
			_taskContext.restore(_taskContext.forRun(task._runnable, task._scheduledIn));
			task._runner = self;
			try
			{
				task.run();
			}
			finally
			{
				task._runner = null;
				// As a pool thread between tasks: an interrupt meant for one task does not reach the next.
				self._interrupted = false;
			}
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
		return inSession(self);
	}

	/**
	 * @return whether {@code thread} belongs to the session that is controlling now.
	 */
	private static boolean inSession(SimThread thread)
	{
		return _controlled && (thread._session == _currentSession);
	}

	/**
	 * Interrupts the thread running a task, as a pool interrupts the thread of a task cancelled with {@code mayInterruptIfRunning}: its sleep, now or the next one, ends at once with an {@link InterruptedException}.
	 */
	private static void interrupt(SimThread runner)
	{
		runner._interrupted = true;
		final Wake asleep = runner._wake;
		if ((asleep != null) && QUEUE.remove(asleep))
		{
			final Wake now = new Wake(runner, GameClock.millis(), SEQUENCE.getAndIncrement());
			runner._wake = now;
			QUEUE.add(now);
		}
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

		/** The thread itself, so that releasing control can wait for it. */
		final Thread _thread;

		/** Whether it is one of the scheduler's own threads, rather than a cooperative thread Mobius started. */
		final boolean _scheduler;

		/** The control session it belongs to. */
		final int _session = _currentSession;

		/** Released to hand this thread the baton. */
		final Semaphore _resume = new Semaphore(0);

		/** Its wake-up while it sleeps in game time, null otherwise. */
		volatile Wake _wake;

		/** Its interrupt status, as {@link Thread#interrupted()} has it for a pool thread. */
		volatile boolean _interrupted;

		SimThread(String name, Thread thread, boolean scheduler)
		{
			_name = name;
			_thread = thread;
			_scheduler = scheduler;
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
		/** The {@link TaskContext} it was scheduled in. */
		private final Object _scheduledIn;
		/** The control session it was scheduled in; it never runs again in another. */
		private final int _session = _currentSession;
		/** The thread running it, while it runs. */
		volatile SimThread _runner;
		private volatile boolean _cancelled;
		private volatile boolean _done;

		Task(Runnable runnable, long dueMillis, long periodMillis, long sequence, Object scheduledIn)
		{
			super(dueMillis, sequence);
			_runnable = runnable;
			_periodMillis = periodMillis;
			_scheduledIn = scheduledIn;
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

			if ((_periodMillis > 0) && !_cancelled && _controlled && (_session == _currentSession))
			{
				try
				{
					_dueMillis = Math.addExact(_dueMillis, _periodMillis);
					QUEUE.add(this);
				}
				catch (ArithmeticException e)
				{
					FAILURES.add(_runnable.getClass().getName() + ": its next run would be past the last instant the clock can hold");
					_done = true;
				}
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
			final SimThread runner = _runner;
			if (mayInterruptIfRunning && (runner != null))
			{
				interrupt(runner);
			}
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
