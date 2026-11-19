package org.l2jmobius.testing.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.testing.agent.MethodRedirector.Target;

/**
 * Java agent for test JVMs: makes Mobius read time from {@code GameClock} and schedule work through {@code GameScheduler}.
 * <p>
 * Mobius reads the clock in several hundred places across roughly 250 files, core and datapack scripts, and hands work to its thread pool in several hundred more. Editing them would make every upstream intake a conflict, so they are not edited. Instead, as each Mobius class is loaded in a test JVM, its calls to the JDK's "what time is it" methods and to {@code ThreadPool} are pointed at the equivalent {@code GameClock} and {@code GameScheduler} methods (see {@link MethodRedirector}). Both pass straight through until a test takes control of them, so loading this agent changes nothing by itself. Production never loads it and runs the classes exactly as compiled.
 * </p>
 * <p>
 * The loops of the server that serve many actors in one task (moving, thinking, decaying, respawning) have the calls that are one actor's turn sent through the test's {@code ActorStreams}, so a test can tell whose turn it is (see {@link ActorDispatch}).
 * </p>
 * <p>
 * The agent also keeps the books a test needs to trust it: how many classes it patched, every class it failed on, and every way of asking the time that it saw but cannot redirect. A silent miss here would be a hole in every time-dependent golden.
 * </p>
 */
public final class TruthAgent implements ClassFileTransformer
{
	private static final String CLOCK = "org/l2jmobius/gameserver/livingchronicle/clock/GameClock";
	private static final String SCRIPT_LOADER = "org.l2jmobius.gameserver.scripting.engine.ScriptClassLoader";

	/** Never patched: the clock itself (it must reach the real system clock), Rnd (the one place randomness is bound, whose unbound path is the JDK's on purpose) and test support code (its timeouts are real time). */
	private static final String[] OUT_OF_SCOPE =
	{
		"org/l2jmobius/gameserver/livingchronicle/clock/",
		"org/l2jmobius/commons/util/Rnd",
		"org/l2jmobius/testing/"
	};

	private static final String SCHEDULER = "org/l2jmobius/gameserver/livingchronicle/clock/GameScheduler";
	private static final String DRAWS = "org/l2jmobius/gameserver/livingchronicle/rng/RndDraws";
	private static final String THREAD_POOL = "org/l2jmobius/commons/threads/ThreadPool.";
	private static final String FUTURE = "Ljava/util/concurrent/ScheduledFuture;";

	private static final Map<String, Target> REDIRECTS = Map.ofEntries(
		// What time is it.
		Map.entry("java/lang/System.currentTimeMillis()J", new Target(CLOCK, "millis")),
		Map.entry("java/lang/System.nanoTime()J", new Target(CLOCK, "nanos")),
		Map.entry("java/util/Calendar.getInstance()Ljava/util/Calendar;", new Target(CLOCK, "calendar")),
		Map.entry("java/util/Calendar.getInstance(Ljava/util/TimeZone;)Ljava/util/Calendar;", new Target(CLOCK, "calendar")),
		Map.entry("java/time/LocalDateTime.now()Ljava/time/LocalDateTime;", new Target(CLOCK, "localDateTime")),
		// Run this later.
		Map.entry(THREAD_POOL + "schedule(Ljava/lang/Runnable;J)" + FUTURE, new Target(SCHEDULER, "schedule")),
		Map.entry(THREAD_POOL + "scheduleAtFixedRate(Ljava/lang/Runnable;JJ)" + FUTURE, new Target(SCHEDULER, "scheduleAtFixedRate")),
		Map.entry(THREAD_POOL + "schedulePriorityTaskAtFixedRate(Ljava/lang/Runnable;JJ)" + FUTURE, new Target(SCHEDULER, "schedulePriorityTaskAtFixedRate")),
		Map.entry(THREAD_POOL + "execute(Ljava/lang/Runnable;)V", new Target(SCHEDULER, "execute")),
		// Wait this long.
		Map.entry("java/lang/Thread.sleep(J)V", new Target(SCHEDULER, "sleep")),
		// Draw at random with a generator Rnd cannot bind.
		Map.entry("java/util/Collections.shuffle(Ljava/util/List;)V", new Target(DRAWS, "shuffle")),
		Map.entry("java/lang/Math.random()D", new Target(DRAWS, "random")));

	/** Constructors that capture "now" without being handed an instant. They cannot be redirected, only reported. */
	private static final Set<String> NOW_CONSTRUCTORS = Set.of(
		"java/util/Date.<init>()V",
		"java/util/GregorianCalendar.<init>()V",
		"java/util/GregorianCalendar.<init>(Ljava/util/TimeZone;)V",
		"java/util/GregorianCalendar.<init>(Ljava/util/Locale;)V",
		"java/util/GregorianCalendar.<init>(Ljava/util/TimeZone;Ljava/util/Locale;)V");

	/** Ways of waiting for real time to pass that are not redirected. An untimed wait() is not here: it waits for another thread, not for time. */
	private static final Set<String> REAL_TIME_WAITS = Set.of(
		"java/lang/Object.wait(J)V",
		"java/lang/Object.wait(JI)V",
		"java/lang/Thread.sleep(JI)V",
		"java/lang/Thread.sleep(Ljava/time/Duration;)V",
		"java/util/concurrent/TimeUnit.sleep(J)V");

	private static final MethodRedirector REDIRECTOR = new MethodRedirector(REDIRECTS, TruthAgent::escapesTheSimulation);

	private static final String CREATURE = "org/l2jmobius/gameserver/entity/actor/Creature";
	private static final String CREATURE_AI = "org/l2jmobius/gameserver/ai/CreatureAI";
	private static final String AI = "L" + CREATURE_AI + ";";
	private static final String NPC = "Lorg/l2jmobius/gameserver/entity/actor/Npc;";
	private static final String DECAY = "org/l2jmobius/gameserver/taskmanagers/DecayTaskManager";

	/** The loops of the server that serve many actors in one task, and the calls in them that are one actor's turn. See {@link ActorDispatch}; the test's {@code ActorStreams} receives them. */
	private static final ActorDispatch DISPATCH = new ActorDispatch(Map.of( //
		"org/l2jmobius/gameserver/taskmanagers/MovementTaskManager$Movement", new ActorDispatch.Site("run", List.of( //
			new ActorDispatch.Call(CREATURE, "updatePosition", "()Z"), //
			new ActorDispatch.Call(CREATURE, "getAI", "()" + AI), //
			new ActorDispatch.Call(CREATURE_AI, "notifyActionArrived", "()V"))), //
		"org/l2jmobius/gameserver/taskmanagers/AttackableThinkTaskManager$AttackableThink", new ActorDispatch.Site("run", List.of( //
			new ActorDispatch.Call("org/l2jmobius/gameserver/entity/actor/Attackable", "getAI", "()" + AI), //
			new ActorDispatch.Call(CREATURE_AI, "notifyActionThink", "()V"))), //
		// A respawn is the turn of the NPC that died: it draws on the stream its death was on.
		"org/l2jmobius/gameserver/entity/spawns/Spawn", new ActorDispatch.Site("decreaseCount", List.of( //
			new ActorDispatch.Call("org/l2jmobius/gameserver/taskmanagers/RespawnTaskManager", "add", "(" + NPC + "J)V"))), //
		"org/l2jmobius/gameserver/taskmanagers/RespawnTaskManager", new ActorDispatch.Site("run", List.of( //
			new ActorDispatch.Call("org/l2jmobius/gameserver/entity/spawns/Spawn", "respawnNpc", "(" + NPC + ")V"))), //
		// A dead NPC decays, and so comes back, on the stream it died on: the decay manager serves the whole world.
		"org/l2jmobius/gameserver/entity/actor/Npc", new ActorDispatch.Site("doDie", List.of( //
			new ActorDispatch.Call(DECAY, "add", "(L" + CREATURE + ";)V"))), //
		DECAY, new ActorDispatch.Site("run", List.of( //
			new ActorDispatch.Call(CREATURE, "onDecay", "()V")))), //
		"org/l2jmobius/testing/ActorStreams");

	private static final AtomicInteger PATCHED_CORE_CLASSES = new AtomicInteger();
	private static final AtomicInteger PATCHED_SCRIPT_CLASSES = new AtomicInteger();
	private static final Set<String> FAILURES = new ConcurrentSkipListSet<>();
	private static final Set<String> UNREDIRECTED = new ConcurrentSkipListSet<>();
	private static final Set<String> DISPATCHED_CLASSES = new ConcurrentSkipListSet<>();

	private TruthAgent()
	{
	}

	public static void premain(String arguments, Instrumentation instrumentation)
	{
		instrumentation.addTransformer(new TruthAgent());
	}

	/**
	 * @return the redirector the agent applies, for checking classes that a test run never loads.
	 */
	public static Set<String> unredirectedReadsIn(byte[] classFile)
	{
		return REDIRECTOR.apply(classFile).sightings();
	}

	/**
	 * @param className internal name, {@code org/l2jmobius/...}
	 * @return whether the agent patches a class of that name when it is not loaded by the script loader.
	 */
	public static boolean inScope(String className)
	{
		for (String excluded : OUT_OF_SCOPE)
		{
			// A name ending in / is a package; any other is a class, with the classes nested in it.
			if (excluded.endsWith("/") ? className.startsWith(excluded) : (className.equals(excluded) || className.startsWith(excluded + "$")))
			{
				return false;
			}
		}
		return className.startsWith("org/l2jmobius/");
	}

	public static int patchedCoreClasses()
	{
		return PATCHED_CORE_CLASSES.get();
	}

	public static int patchedScriptClasses()
	{
		return PATCHED_SCRIPT_CLASSES.get();
	}

	/**
	 * @return the loaded classes whose many-actor loops tell their actors apart (see {@link ActorDispatch}), by internal name.
	 */
	public static Set<String> dispatchedClasses()
	{
		return Set.copyOf(DISPATCHED_CLASSES);
	}

	/**
	 * @return {@code class: reason} for every class the agent could not process. Such a class ran unpatched.
	 */
	public static Set<String> failures()
	{
		return Set.copyOf(FAILURES);
	}

	/**
	 * @return {@code class -> reference} for every read of the system clock, wait for real time or draw outside Rnd seen in a loaded class that the agent cannot redirect.
	 */
	public static Set<String> unredirectedReads()
	{
		return new java.util.TreeSet<>(UNREDIRECTED);
	}

	/**
	 * Every JDK entry point that answers "what time is it now", waits for real time to pass, or draws at random with a generator of its own, and is not in {@link #REDIRECTS}: a class that uses one makes a paused world depend on something besides its clock and seed.
	 */
	private static boolean escapesTheSimulation(String reference)
	{
		return dependsOnRealTime(reference) || drawsOutsideRnd(reference);
	}

	private static boolean drawsOutsideRnd(String reference)
	{
		return reference.startsWith("java/util/Random.<init>(") //
			|| reference.startsWith("java/util/SplittableRandom.<init>(") //
			|| reference.startsWith("java/security/SecureRandom.") //
			|| reference.startsWith("java/util/concurrent/ThreadLocalRandom.") //
			|| reference.startsWith("java/util/random/RandomGenerator.getDefault(") //
			|| reference.startsWith("java/util/random/RandomGenerator.of(") //
			|| reference.startsWith("java/util/UUID.randomUUID(") //
			|| reference.startsWith("java/util/Collections.shuffle(") //
			|| reference.startsWith("java/lang/Math.random(") //
			|| reference.startsWith("java/lang/StrictMath.random(");
	}

	private static boolean dependsOnRealTime(String reference)
	{
		return NOW_CONSTRUCTORS.contains(reference) //
			|| REAL_TIME_WAITS.contains(reference) //
			|| reference.startsWith("java/lang/System.currentTimeMillis(") //
			|| reference.startsWith("java/lang/System.nanoTime(") //
			|| reference.startsWith("java/util/Calendar.getInstance(") //
			|| reference.startsWith("java/time/Clock.system") //
			|| (reference.startsWith("java/time/") && reference.contains(".now("));
	}

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classFile)
	{
		if ((loader == null) || (className == null))
		{
			return null;
		}

		final boolean script = loader.getClass().getName().equals(SCRIPT_LOADER);
		if (!script && !inScope(className))
		{
			return null;
		}

		// The JVM discards anything thrown from here and loads the class unpatched, so failures are recorded, never thrown.
		try
		{
			final MethodRedirector.Result result = REDIRECTOR.apply(classFile);
			for (String reference : result.sightings())
			{
				UNREDIRECTED.add(className + " -> " + reference);
			}

			final byte[] patched = script ? result.classFile() : DISPATCH.apply(className, result.classFile(), loader);
			if (patched == classFile)
			{
				return null;
			}

			(script ? PATCHED_SCRIPT_CLASSES : PATCHED_CORE_CLASSES).incrementAndGet();
			if (DISPATCH.covers(className))
			{
				DISPATCHED_CLASSES.add(className);
			}
			return patched;
		}
		catch (Throwable t)
		{
			FAILURES.add(className + ": " + t);
			return null;
		}
	}
}
