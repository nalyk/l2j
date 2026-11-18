package org.l2jmobius.testing.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.testing.agent.MethodRedirector.Target;

/**
 * Java agent for test JVMs: makes Mobius read time from {@code GameClock} instead of the system.
 * <p>
 * Mobius reads the clock in several hundred places across roughly 250 files, core and datapack scripts. Editing them would make every upstream intake a conflict, so they are not edited. Instead, as each Mobius class is loaded in a test JVM, its calls to the JDK's "what time is it" methods are pointed at the equivalent {@code GameClock} method (see {@link MethodRedirector}). {@code GameClock} follows the system until a test takes control of it, so loading this agent changes nothing by itself. Production never loads it and runs the classes exactly as compiled.
 * </p>
 * <p>
 * The agent also keeps the books a test needs to trust it: how many classes it patched, every class it failed on, and every way of asking the time that it saw but cannot redirect. A silent miss here would be a hole in every time-dependent golden.
 * </p>
 */
public final class TruthAgent implements ClassFileTransformer
{
	private static final String CLOCK = "org/l2jmobius/gameserver/livingchronicle/clock/GameClock";
	private static final String SCRIPT_LOADER = "org.l2jmobius.gameserver.scripting.engine.ScriptClassLoader";

	/** Never patched: the clock itself (it must reach the real system clock) and test support code (its timeouts are real time). */
	private static final String[] OUT_OF_SCOPE =
	{
		"org/l2jmobius/gameserver/livingchronicle/clock/",
		"org/l2jmobius/testing/"
	};

	private static final Map<String, Target> REDIRECTS = Map.of(
		"java/lang/System.currentTimeMillis()J", new Target(CLOCK, "millis"),
		"java/lang/System.nanoTime()J", new Target(CLOCK, "nanos"),
		"java/util/Calendar.getInstance()Ljava/util/Calendar;", new Target(CLOCK, "calendar"),
		"java/util/Calendar.getInstance(Ljava/util/TimeZone;)Ljava/util/Calendar;", new Target(CLOCK, "calendar"),
		"java/time/LocalDateTime.now()Ljava/time/LocalDateTime;", new Target(CLOCK, "localDateTime"));

	/** Constructors that capture "now" without being handed an instant. They cannot be redirected, only reported. */
	private static final Set<String> NOW_CONSTRUCTORS = Set.of(
		"java/util/Date.<init>()V",
		"java/util/GregorianCalendar.<init>()V",
		"java/util/GregorianCalendar.<init>(Ljava/util/TimeZone;)V",
		"java/util/GregorianCalendar.<init>(Ljava/util/Locale;)V",
		"java/util/GregorianCalendar.<init>(Ljava/util/TimeZone;Ljava/util/Locale;)V");

	private static final MethodRedirector REDIRECTOR = new MethodRedirector(REDIRECTS, TruthAgent::readsTheSystemClock);

	private static final AtomicInteger PATCHED_CORE_CLASSES = new AtomicInteger();
	private static final AtomicInteger PATCHED_SCRIPT_CLASSES = new AtomicInteger();
	private static final Set<String> FAILURES = new ConcurrentSkipListSet<>();
	private static final Set<String> UNREDIRECTED = new ConcurrentSkipListSet<>();

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
		for (String prefix : OUT_OF_SCOPE)
		{
			if (className.startsWith(prefix))
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
	 * @return {@code class: reason} for every class the agent could not process. Such a class ran unpatched.
	 */
	public static Set<String> failures()
	{
		return Set.copyOf(FAILURES);
	}

	/**
	 * @return {@code class -> reference} for every read of the system clock seen in a loaded class that the agent cannot redirect.
	 */
	public static Set<String> unredirectedReads()
	{
		return new java.util.TreeSet<>(UNREDIRECTED);
	}

	/**
	 * Every JDK entry point that answers "what time is it now" and is not in {@link #REDIRECTS}.
	 */
	private static boolean readsTheSystemClock(String reference)
	{
		return NOW_CONSTRUCTORS.contains(reference) //
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

			if (result.classFile() == classFile)
			{
				return null;
			}

			(script ? PATCHED_SCRIPT_CLASSES : PATCHED_CORE_CLASSES).incrementAndGet();
			return result.classFile();
		}
		catch (Throwable t)
		{
			FAILURES.add(className + ": " + t);
			return null;
		}
	}
}
