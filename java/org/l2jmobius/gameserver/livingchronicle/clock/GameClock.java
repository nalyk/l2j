package org.l2jmobius.gameserver.livingchronicle.clock;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * The one source of "now" for Living Chronicle code, and for Mobius code in test JVMs.
 * <p>
 * By default it is the system clock. A JVM started with {@code -Dlc.clock.controllable=true} may take control: the clock then stands still at a set instant and moves only when told to. Production starts without the flag; the constant folds to false, every read is the system clock, and taking control throws.
 * </p>
 * <p>
 * Living Chronicle code calls these methods directly. Mobius code is not edited to call them: test JVMs load an agent that points its {@code System.currentTimeMillis()}, {@code System.nanoTime()}, {@code Calendar.getInstance()} and {@code LocalDateTime.now()} calls here, which is why each method mirrors the signature of the JDK method it stands in for.
 * </p>
 */
public final class GameClock
{
	private static final boolean CONTROLLABLE = Boolean.getBoolean("lc.clock.controllable");
	private static final long NANOS_PER_MILLI = 1_000_000L;

	/** Marks the clock as following the system; no real instant is this far in the past. */
	private static final long FOLLOWING_SYSTEM = Long.MIN_VALUE;

	private static volatile long _controlledMillis = FOLLOWING_SYSTEM;

	private GameClock()
	{
	}

	/**
	 * @return the current time in epoch milliseconds, as {@link System#currentTimeMillis()}.
	 */
	public static long millis()
	{
		if (CONTROLLABLE)
		{
			final long controlled = _controlledMillis;
			if (controlled != FOLLOWING_SYSTEM)
			{
				return controlled;
			}
		}
		return System.currentTimeMillis();
	}

	/**
	 * @return a monotonic nanosecond reading, as {@link System#nanoTime()}. Under control it is the controlled instant in nanoseconds, so it advances exactly with {@link #millis()}.
	 */
	public static long nanos()
	{
		if (CONTROLLABLE)
		{
			final long controlled = _controlledMillis;
			if (controlled != FOLLOWING_SYSTEM)
			{
				return controlled * NANOS_PER_MILLI;
			}
		}
		return System.nanoTime();
	}

	/**
	 * @return a calendar set to now in the default time zone, as {@link Calendar#getInstance()}.
	 */
	public static Calendar calendar()
	{
		return at(Calendar.getInstance());
	}

	/**
	 * @param zone the time zone of the calendar.
	 * @return a calendar set to now in the given time zone, as {@link Calendar#getInstance(TimeZone)}.
	 */
	public static Calendar calendar(TimeZone zone)
	{
		return at(Calendar.getInstance(zone));
	}

	/**
	 * @return now in the default time zone, as {@link LocalDateTime#now()}.
	 */
	public static LocalDateTime localDateTime()
	{
		if (CONTROLLABLE)
		{
			final long controlled = _controlledMillis;
			if (controlled != FOLLOWING_SYSTEM)
			{
				return LocalDateTime.ofInstant(Instant.ofEpochMilli(controlled), ZoneId.systemDefault());
			}
		}
		return LocalDateTime.now();
	}

	private static Calendar at(Calendar calendar)
	{
		if (CONTROLLABLE)
		{
			final long controlled = _controlledMillis;
			if (controlled != FOLLOWING_SYSTEM)
			{
				calendar.setTimeInMillis(controlled);
			}
		}
		return calendar;
	}

	/**
	 * Takes control: the clock reads {@code epochMillis} until it is set, advanced or released.
	 * @param epochMillis the instant to stand at.
	 * @throws IllegalStateException in a JVM that was not started as controllable, so production time can never be moved.
	 */
	public static synchronized void set(long epochMillis)
	{
		requireControllable();
		if (epochMillis == FOLLOWING_SYSTEM)
		{
			throw new IllegalArgumentException("not a representable instant: " + epochMillis);
		}
		_controlledMillis = epochMillis;
	}

	/**
	 * Moves a controlled clock forward.
	 * @param millis how far, never negative: game time does not run backwards.
	 */
	public static synchronized void advance(long millis)
	{
		requireControllable();
		if (_controlledMillis == FOLLOWING_SYSTEM)
		{
			throw new IllegalStateException("the clock is following the system; set it before advancing it");
		}
		if (millis < 0)
		{
			throw new IllegalArgumentException("cannot advance by a negative amount: " + millis);
		}
		_controlledMillis = Math.addExact(_controlledMillis, millis);
	}

	/**
	 * Releases control: the clock follows the system again.
	 */
	public static synchronized void followSystem()
	{
		_controlledMillis = FOLLOWING_SYSTEM;
	}

	/**
	 * @return whether the clock is standing at a controlled instant.
	 */
	public static boolean isControlled()
	{
		return CONTROLLABLE && (_controlledMillis != FOLLOWING_SYSTEM);
	}

	private static void requireControllable()
	{
		if (!CONTROLLABLE)
		{
			throw new IllegalStateException("GameClock is not controllable: this JVM was started without -Dlc.clock.controllable=true");
		}
	}
}
