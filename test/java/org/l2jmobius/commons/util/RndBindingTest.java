package org.l2jmobius.commons.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.livingchronicle.rng.KeyedRandom;

/**
 * In a bindable JVM a thread can make {@link Rnd} deterministic for itself, and only for itself.
 */
class RndBindingTest
{
	@AfterEach
	void unbind()
	{
		Rnd.unbind();
	}

	/**
	 * One draw through every public method of Rnd.
	 */
	private static List<Object> drawEverything()
	{
		final byte[] bytes = new byte[5];
		Rnd.nextBytes(bytes);

		final List<Object> values = new ArrayList<>();
		for (byte value : bytes)
		{
			values.add(value);
		}
		values.add(Rnd.nextBoolean());
		values.add(Rnd.get(1000));
		values.add(Rnd.get(-50, 50));
		values.add(Rnd.nextInt());
		values.add(Rnd.get(1_000_000_000_000L));
		values.add(Rnd.get(-5L, 5L));
		values.add(Rnd.nextLong());
		values.add(Rnd.get(10f));
		values.add(Rnd.get(1f, 2f));
		values.add(Rnd.nextFloat());
		values.add(Rnd.get(10d));
		values.add(Rnd.get(1d, 2d));
		values.add(Rnd.nextDouble());
		values.add(Rnd.nextGaussian());
		return values;
	}

	@Test
	void theSameStreamGivesTheSameDraws()
	{
		Rnd.bind(KeyedRandom.of(7, "test.rnd", 1));
		final List<Object> first = drawEverything();

		Rnd.bind(KeyedRandom.of(7, "test.rnd", 1));
		final List<Object> second = drawEverything();

		Rnd.bind(KeyedRandom.of(7, "test.rnd", 2));
		final List<Object> other = drawEverything();

		assertEquals(first, second);
		assertTrue(!first.equals(other), "a different key must give a different stream");
	}

	@Test
	void theRangeContractHoldsOnABoundStream()
	{
		Rnd.bind(KeyedRandom.of(7, "test.rnd.contract", 0));

		boolean sawOrigin = false;
		boolean sawBound = false;
		for (int i = 0; i < 100_000; i++)
		{
			final int exclusive = Rnd.get(4);
			assertTrue((exclusive >= 0) && (exclusive < 4), () -> "Rnd.get(4) returned " + exclusive);

			final int inclusive = Rnd.get(5, 8);
			assertTrue((inclusive >= 5) && (inclusive <= 8), () -> "Rnd.get(5, 8) returned " + inclusive);
			sawOrigin |= inclusive == 5;
			sawBound |= inclusive == 8;

			final long wide = Rnd.get(Long.MAX_VALUE - 1, Long.MAX_VALUE);
			assertTrue(wide >= (Long.MAX_VALUE - 1), () -> "Rnd.get near Long.MAX_VALUE returned " + wide);
		}
		assertTrue(sawOrigin && sawBound, "Rnd.get(5, 8) must reach both 5 and 8");

		assertEquals(0, Rnd.get(0));
		assertEquals(9, Rnd.get(9, 3));
	}

	@Test
	void aBindingBelongsToItsThread() throws InterruptedException
	{
		final CountingGenerator counting = new CountingGenerator();
		Rnd.bind(counting);

		final Thread other = new Thread(RndBindingTest::drawEverything);
		other.start();
		other.join();
		assertEquals(0, counting.draws(), "another thread drew from this thread's stream");

		Rnd.get(10);
		assertTrue(counting.draws() > 0, "the bound thread did not draw from its stream");
	}

	@Test
	void unbindingReturnsTheThreadToOrdinaryRandomness()
	{
		final CountingGenerator counting = new CountingGenerator();
		Rnd.bind(counting);
		Rnd.unbind();

		drawEverything();
		assertEquals(0, counting.draws());
	}

	/**
	 * Counts how often it is asked for anything; every other method of the interface funnels into {@link #nextLong()}.
	 */
	private static final class CountingGenerator implements RandomGenerator
	{
		private final AtomicLong _draws = new AtomicLong();

		long draws()
		{
			return _draws.get();
		}

		@Override
		public long nextLong()
		{
			return _draws.incrementAndGet();
		}
	}
}
