package org.l2jmobius.gameserver.livingchronicle.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.ProductionJvm;

/**
 * {@link GameClock} follows the system until a controllable JVM takes control, then moves only when told to.
 * <p>
 * This package is outside the test agent's scope, so {@code System.currentTimeMillis()} here is the real system clock.
 * </p>
 */
class GameClockTest
{
	/** 2026-11-09T09:21:30Z, the fork point. */
	private static final long INSTANT = 1_794_216_090_000L;

	@AfterEach
	void release()
	{
		GameClock.followSystem();
	}

	@Test
	void followsTheSystemUntilControlled()
	{
		assertFalse(GameClock.isControlled());

		final long before = System.currentTimeMillis();
		final long read = GameClock.millis();
		final long after = System.currentTimeMillis();
		assertTrue((before <= read) && (read <= after), () -> "read " + read + " outside [" + before + ", " + after + "]");

		final long nanosBefore = System.nanoTime();
		final long nanos = GameClock.nanos();
		assertTrue((nanos - nanosBefore) >= 0, "nanos() must be the monotonic system reading");
	}

	@Test
	void standsStillOnceSet() throws InterruptedException
	{
		GameClock.set(INSTANT);
		assertTrue(GameClock.isControlled());

		Thread.sleep(20);
		assertEquals(INSTANT, GameClock.millis());
		assertEquals(INSTANT * 1_000_000L, GameClock.nanos());
		assertEquals(INSTANT, GameClock.calendar().getTimeInMillis());
		assertEquals(INSTANT, GameClock.calendar(TimeZone.getTimeZone("Asia/Tokyo")).getTimeInMillis());
		assertEquals(TimeZone.getTimeZone("Asia/Tokyo"), GameClock.calendar(TimeZone.getTimeZone("Asia/Tokyo")).getTimeZone());
		assertEquals(LocalDateTime.of(2026, 11, 9, 9, 21, 30), GameClock.localDateTime());
		assertEquals(Calendar.MONDAY, GameClock.calendar().get(Calendar.DAY_OF_WEEK));
	}

	@Test
	void movesOnlyWhenAdvanced()
	{
		GameClock.set(INSTANT);
		GameClock.advance(0);
		assertEquals(INSTANT, GameClock.millis());

		GameClock.advance(1500);
		assertEquals(INSTANT + 1500, GameClock.millis());
		assertEquals((INSTANT + 1500) * 1_000_000L, GameClock.nanos());
		assertEquals(LocalDateTime.of(2026, 11, 9, 9, 21, 31, 500_000_000), GameClock.localDateTime());
	}

	@Test
	void neverRunsBackwardsAndNeverAdvancesUncontrolled()
	{
		assertThrows(IllegalStateException.class, () -> GameClock.advance(1));

		GameClock.set(INSTANT);
		assertThrows(IllegalArgumentException.class, () -> GameClock.advance(-1));
		assertEquals(INSTANT, GameClock.millis());
	}

	@Test
	void followsTheSystemAgainOnceReleased()
	{
		GameClock.set(0);
		GameClock.followSystem();
		assertFalse(GameClock.isControlled());
		assertTrue(GameClock.millis() > INSTANT, "after release the clock must be back in the present");
	}

	@Test
	void theDefaultZoneOfTheSuiteIsUtc()
	{
		// Goldens that print dates depend on it; the build pins it.
		assertEquals(ZoneOffset.UTC.getTotalSeconds() * 1000, TimeZone.getDefault().getRawOffset());
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
			final StringBuilder result = new StringBuilder();
			try
			{
				GameClock.set(0);
				result.append("SET");
			}
			catch (IllegalStateException e)
			{
				result.append("REFUSED");
			}

			try
			{
				GameClock.advance(1);
				result.append(" ADVANCED");
			}
			catch (IllegalStateException e)
			{
				result.append(" REFUSED");
			}

			GameClock.followSystem();
			result.append(GameClock.isControlled() ? " CONTROLLED" : " SYSTEM");
			result.append(Math.abs(GameClock.millis() - System.currentTimeMillis()) < 60_000 ? " PRESENT" : " ELSEWHEN");
			System.out.print(result);
		}
	}

	@Test
	void controlIsRefusedWithoutTheFlag() throws Exception
	{
		assertEquals("REFUSED REFUSED SYSTEM PRESENT", ProductionJvm.run(Probe.class));
	}
}
