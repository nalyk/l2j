package org.l2jmobius.gameserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.livingchronicle.rng.KeyedRandom;
import org.l2jmobius.testing.RealTime;
import org.l2jmobius.testing.agent.TruthAgent;

/**
 * In a test JVM, code in Mobius packages reads the game clock through the plain JDK calls it was written with.
 * <p>
 * This class is in a Mobius package, so the agent patches it like any upstream class: every JDK time call below is, at run time, a call into {@link GameClock}. That is the whole mechanism, observed from the inside.
 * </p>
 */
class TruthAgentTest
{
	/** 2026-11-09T09:21:30Z, the fork point. */
	private static final long INSTANT = 1_794_216_090_000L;

	@AfterEach
	void release()
	{
		GameClock.followSystem();
	}

	@Test
	void jdkTimeCallsReadTheControlledClock()
	{
		GameClock.set(INSTANT);

		assertEquals(INSTANT, System.currentTimeMillis());
		assertEquals(INSTANT * 1_000_000L, System.nanoTime());
		assertEquals(INSTANT, Calendar.getInstance().getTimeInMillis());
		assertEquals(INSTANT, Calendar.getInstance(TimeZone.getTimeZone("Europe/Chisinau")).getTimeInMillis());
		assertEquals(LocalDateTime.of(2026, 11, 9, 9, 21, 30), LocalDateTime.now());
		assertEquals(INSTANT, new Date(System.currentTimeMillis()).getTime());

		final LongSupplier reference = System::currentTimeMillis;
		assertEquals(INSTANT, reference.getAsLong());

		GameClock.advance(60_000);
		assertEquals(INSTANT + 60_000, System.currentTimeMillis());
	}

	@Test
	void withoutControlTheSameCallsReadTheSystemClock()
	{
		final long before = RealTime.millis();
		final long read = System.currentTimeMillis();
		final long after = RealTime.millis();
		assertTrue((before <= read) && (read <= after), () -> "read " + read + " outside [" + before + ", " + after + "]");
	}

	/**
	 * The JDK's own random draws, which Rnd cannot bind, are made through Rnd: with Rnd bound to a stream, Collections.shuffle(List) makes exactly the swaps the JDK's shuffle makes with that stream, and Math.random() is that stream's next double.
	 */
	@Test
	void jdkRandomDrawsComeFromRnd()
	{
		final List<Integer> shuffled = new ArrayList<>();
		for (int i = 0; i < 20; i++)
		{
			shuffled.add(i);
		}
		final List<Integer> expected = new ArrayList<>(shuffled);
		final RandomGenerator reference = KeyedRandom.of(1, "agent.draws", 0);
		Collections.shuffle(expected, reference);

		Rnd.bind(KeyedRandom.of(1, "agent.draws", 0));
		try
		{
			Collections.shuffle(shuffled);
			assertEquals(expected, shuffled);
			assertEquals(reference.nextDouble(), Math.random());
		}
		finally
		{
			Rnd.unbind();
		}
	}

	@Test
	void theAgentProcessedEveryClassItWasHanded()
	{
		assertEquals(Set.of(), TruthAgent.failures());
		assertTrue(TruthAgent.patchedCoreClasses() > 0, "this class alone should have been patched");
	}
}
