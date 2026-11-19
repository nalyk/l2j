package org.l2jmobius.gameserver.livingchronicle.rng;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Known answers for {@link KeyedRandom}.
 * <p>
 * Behaviour-lock goldens are recorded against these streams, so their output may never drift. The expected values below were not produced by the class under test: the SplitMix64 vector is the published reference output, and the rest come from an independent implementation of the specification in the class comment (Python, arbitrary-precision integers, hashlib).
 * </p>
 */
class KeyedRandomTest
{
	private static KeyedRandom stream()
	{
		return KeyedRandom.of(1, "lock.test", 42);
	}

	@Test
	void coreGeneratorIsSplitMix64()
	{
		// Reference output of SplitMix64 for seed 1234567, as unsigned decimals.
		final String[] expected =
		{
			"6457827717110365317",
			"3203168211198807973",
			"9817491932198370423",
			"4593380528125082431",
			"16408922859458223821"
		};

		final KeyedRandom random = new KeyedRandom(1234567);
		for (String value : expected)
		{
			assertEquals(value, Long.toUnsignedString(random.nextLong()));
		}
	}

	@Test
	void identityDeterminesTheStream()
	{
		final KeyedRandom random = stream();
		assertEquals(-8790779790180134212L, random.nextLong());
		assertEquals(2139721685946361042L, random.nextLong());
		assertEquals(-6145453056763602834L, random.nextLong());
	}

	@Test
	void everyPartOfTheIdentityMatters()
	{
		assertEquals(-157740960150900694L, KeyedRandom.of(1, "a", 1).nextLong());
		assertEquals(-8776700152572040618L, KeyedRandom.of(1, "a", 2).nextLong());
		assertEquals(-3448075248911011879L, KeyedRandom.of(1, "b", 1).nextLong());
		assertEquals(-4053310479130270868L, KeyedRandom.of(2, "a", 1).nextLong());
	}

	/**
	 * One stream drawn through every specified method in a fixed order, so the draw accounting of each method is pinned as well as its values.
	 */
	@Test
	void everyMethodMatchesTheSpecification()
	{
		final KeyedRandom random = stream();

		assertArrayEquals(new int[]
		{
			52,
			11,
			66,
			1,
			98,
			58,
			42,
			80
		}, draw(8, () -> random.nextInt(100)));

		assertArrayEquals(new int[]
		{
			6,
			5,
			5,
			5,
			5,
			7,
			7,
			7
		}, draw(8, () -> random.nextInt(5, 9)));

		assertEquals(557330495259L, random.nextLong(1_000_000_000_000L));
		assertEquals(192961110782L, random.nextLong(1_000_000_000_000L));
		assertEquals(644907825908L, random.nextLong(0L, 1_000_000_000_000L));

		assertEquals(-1695394932, random.nextInt());
		assertEquals(-1586048961, random.nextInt());
		assertEquals(-2141875951, random.nextInt());

		assertEquals(0x1.cb58f59c16478p-2, random.nextDouble());
		assertEquals(0x1.fee15aea1c6d0p-2, random.nextDouble());
		assertEquals(0x1.b4f7a2f775360p-6, random.nextDouble());

		assertEquals(0.19332945346832275f, random.nextFloat());
		assertEquals(0.49158626794815063f, random.nextFloat());
		assertEquals(0.38817739486694336f, random.nextFloat());

		final boolean[] booleans = new boolean[8];
		for (int i = 0; i < booleans.length; i++)
		{
			booleans[i] = random.nextBoolean();
		}
		assertArrayEquals(new boolean[]
		{
			false,
			false,
			false,
			false,
			true,
			true,
			true,
			false
		}, booleans);

		final byte[] bytes = new byte[11];
		random.nextBytes(bytes);
		assertArrayEquals(new byte[]
		{
			35,
			25,
			53,
			-78,
			63,
			29,
			126,
			92,
			-98,
			-16,
			-97
		}, bytes);

		// The reference used the platform math library, StrictMath is fdlibm: they may differ in the last bit.
		assertEquals(-0.384152802334469, random.nextGaussian(), 1e-15);
		assertEquals(0.9279298612697838, random.nextGaussian(), 1e-15);
	}

	@Test
	void boundedValuesStayInRange()
	{
		final KeyedRandom random = stream();
		final Set<Integer> seen = new HashSet<>();
		for (int i = 0; i < 100_000; i++)
		{
			final int ranged = random.nextInt(-3, 4);
			assertTrue((ranged >= -3) && (ranged < 4), () -> "nextInt(-3, 4) returned " + ranged);
			seen.add(ranged);

			final long wide = random.nextLong(Long.MIN_VALUE, Long.MAX_VALUE);
			assertNotEquals(Long.MAX_VALUE, wide);

			final long narrow = random.nextLong(Long.MAX_VALUE - 2, Long.MAX_VALUE);
			assertTrue(narrow >= (Long.MAX_VALUE - 2), () -> "nextLong near the maximum returned " + narrow);

			final double unit = random.nextDouble();
			assertTrue((unit >= 0) && (unit < 1), () -> "nextDouble() returned " + unit);

			final double scaled = random.nextDouble(2.5, 2.75);
			assertTrue((scaled >= 2.5) && (scaled < 2.75), () -> "nextDouble(2.5, 2.75) returned " + scaled);

			final float single = random.nextFloat(10f);
			assertTrue((single >= 0f) && (single < 10f), () -> "nextFloat(10) returned " + single);
		}
		assertEquals(Set.of(-3, -2, -1, 0, 1, 2, 3), seen);
	}

	@Test
	void aBoundIsNeverReturnedEvenWhenRoundingLandsOnIt()
	{
		final KeyedRandom random = stream();
		for (int i = 0; i < 1000; i++)
		{
			assertEquals(0d, random.nextDouble(Double.MIN_VALUE));
			assertEquals(0f, random.nextFloat(Float.MIN_VALUE));
		}
	}

	@Test
	void invalidRangesAreRejected()
	{
		final KeyedRandom random = stream();
		assertThrows(IllegalArgumentException.class, () -> random.nextInt(0));
		assertThrows(IllegalArgumentException.class, () -> random.nextInt(5, 5));
		assertThrows(IllegalArgumentException.class, () -> random.nextLong(-1));
		assertThrows(IllegalArgumentException.class, () -> random.nextLong(7, 3));
		assertThrows(IllegalArgumentException.class, () -> random.nextDouble(Double.NaN));
		assertThrows(IllegalArgumentException.class, () -> random.nextDouble(0, Double.POSITIVE_INFINITY));
		assertThrows(IllegalArgumentException.class, () -> random.nextFloat(0f));
		assertThrows(IllegalArgumentException.class, () -> random.nextFloat(2f, 1f));
	}

	@Test
	void unspecifiedDistributionsFailInsteadOfBorrowingTheJdks()
	{
		assertThrows(UnsupportedOperationException.class, () -> stream().nextExponential());
	}

	private static int[] draw(int count, java.util.function.IntSupplier source)
	{
		final int[] values = new int[count];
		for (int i = 0; i < count; i++)
		{
			values[i] = source.getAsInt();
		}
		return values;
	}
}
