package org.l2jmobius.commons.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the range contract of {@link Rnd} that 127 upstream files depend on.
 * <p>
 * These are the easy-to-get-wrong parts: the one-argument form excludes its bound, the two-argument form includes it, and a degenerate range answers without drawing. Any randomness seam must keep every one of them.
 * </p>
 */
class RndContractTest
{
	private static final int DRAWS = 200_000;

	@Test
	void singleBoundIsExclusive()
	{
		boolean sawZero = false;
		boolean sawTop = false;
		for (int i = 0; i < DRAWS; i++)
		{
			final int value = Rnd.get(4);
			assertTrue((value >= 0) && (value < 4), () -> "Rnd.get(4) returned " + value);
			sawZero |= value == 0;
			sawTop |= value == 3;
		}
		assertTrue(sawZero && sawTop, "Rnd.get(4) must reach both 0 and 3");
	}

	@Test
	void originBoundIsInclusiveOnBothEnds()
	{
		boolean sawOrigin = false;
		boolean sawBound = false;
		for (int i = 0; i < DRAWS; i++)
		{
			final int value = Rnd.get(5, 8);
			assertTrue((value >= 5) && (value <= 8), () -> "Rnd.get(5, 8) returned " + value);
			sawOrigin |= value == 5;
			sawBound |= value == 8;
		}
		assertTrue(sawOrigin && sawBound, "Rnd.get(5, 8) must reach both 5 and 8");
	}

	@Test
	void longOriginBoundIsInclusiveOnBothEnds()
	{
		boolean sawOrigin = false;
		boolean sawBound = false;
		for (int i = 0; i < DRAWS; i++)
		{
			final long value = Rnd.get(5L, 8L);
			assertTrue((value >= 5L) && (value <= 8L), () -> "Rnd.get(5L, 8L) returned " + value);
			sawOrigin |= value == 5L;
			sawBound |= value == 8L;
		}
		assertTrue(sawOrigin && sawBound, "Rnd.get(5L, 8L) must reach both 5 and 8");
	}

	@Test
	void degenerateRangesAnswerWithoutDrawing()
	{
		assertEquals(0, Rnd.get(0));
		assertEquals(0, Rnd.get(-7));
		assertEquals(0L, Rnd.get(0L));
		assertEquals(0L, Rnd.get(-7L));
		assertEquals(0f, Rnd.get(0f));
		assertEquals(0d, Rnd.get(-1d));

		assertEquals(9, Rnd.get(9, 9));
		assertEquals(9, Rnd.get(9, 3));
		assertEquals(9L, Rnd.get(9L, 3L));
		assertEquals(9f, Rnd.get(9f, 3f));
		assertEquals(9d, Rnd.get(9d, 3d));
	}

	@Test
	void maximumBoundDoesNotOverflow()
	{
		for (int i = 0; i < 1000; i++)
		{
			assertTrue(Rnd.get(Integer.MAX_VALUE - 1, Integer.MAX_VALUE) >= (Integer.MAX_VALUE - 1));
			assertTrue(Rnd.get(Long.MAX_VALUE - 1, Long.MAX_VALUE) >= (Long.MAX_VALUE - 1));
		}
	}

	@Test
	void floatingRangesStayInsideTheirBounds()
	{
		for (int i = 0; i < DRAWS; i++)
		{
			final double unit = Rnd.nextDouble();
			assertTrue((unit >= 0d) && (unit < 1d), () -> "Rnd.nextDouble() returned " + unit);

			final double bounded = Rnd.get(100d);
			assertTrue((bounded >= 0d) && (bounded < 100d), () -> "Rnd.get(100d) returned " + bounded);

			final double ranged = Rnd.get(2d, 3d);
			assertTrue((ranged >= 2d) && (ranged <= 3d), () -> "Rnd.get(2d, 3d) returned " + ranged);
		}
	}
}
