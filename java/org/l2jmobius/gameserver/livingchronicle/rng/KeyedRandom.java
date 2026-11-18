package org.l2jmobius.gameserver.livingchronicle.rng;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.random.RandomGenerator;

/**
 * A deterministic random stream identified by {@code (seed, stream, key)}.
 * <p>
 * Two streams with different identities are statistically independent, and a stream's values depend on nothing but its identity and how many values were drawn from it. Adding a new consumer of randomness therefore never shifts the values any existing consumer sees, which is what keeps behaviour-lock goldens and simulations stable as the code grows.
 * </p>
 * <p>
 * <b>The output is a contract.</b> Goldens are recorded against it, so every method below is specified here, bit for bit, and none of it is delegated to JDK algorithms that a later Java release is free to change. {@code KeyedRandomTest} holds known answers computed by an independent implementation of this specification.
 * </p>
 * <ul>
 * <li>State: 64 bits. Initial state = the first 8 bytes, big-endian, of {@code SHA-256(seed as 8 bytes BE || key as 8 bytes BE || stream as UTF-8)}.</li>
 * <li>{@link #nextLong()}: SplitMix64 (Steele, Lea, Flood 2014), increment {@code 0x9E3779B97F4A7C15}, Stafford mix 13.</li>
 * <li>A value below {@code bound}: the high 64 bits of the unsigned 128-bit product {@code nextLong() * bound}. One draw per value, bias below 2<sup>-64</sup> &times; bound.</li>
 * <li>{@link #nextDouble()}: the top 53 bits of a draw &times; 2<sup>-53</sup>. {@link #nextFloat()}: the top 24 bits &times; 2<sup>-24</sup>.</li>
 * <li>{@link #nextGaussian()}: Box-Muller on two draws, evaluated with {@link StrictMath}.</li>
 * </ul>
 * Not thread-safe: a stream belongs to one consumer.
 */
public final class KeyedRandom implements RandomGenerator
{
	private static final long INCREMENT = 0x9E3779B97F4A7C15L;
	private static final double DOUBLE_UNIT = 0x1.0p-53;
	private static final float FLOAT_UNIT = 0x1.0p-24f;

	private long _state;

	KeyedRandom(long state)
	{
		_state = state;
	}

	/**
	 * @param seed the run's master seed
	 * @param stream what the randomness is for, e.g. {@code "lock.combat.critical"}
	 * @param key which instance of that purpose, e.g. a case number or an entity id
	 * @return the stream with that identity, positioned at its first value
	 */
	public static KeyedRandom of(long seed, String stream, long key)
	{
		final MessageDigest sha256;
		try
		{
			sha256 = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("every Java platform is required to provide SHA-256", e);
		}

		// Fixed-width fields first, so no two identities share an encoding.
		sha256.update(ByteBuffer.allocate(2 * Long.BYTES).putLong(seed).putLong(key).array());
		sha256.update(stream.getBytes(StandardCharsets.UTF_8));
		return new KeyedRandom(ByteBuffer.wrap(sha256.digest()).getLong());
	}

	@Override
	public long nextLong()
	{
		long z = (_state += INCREMENT);
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	@Override
	public int nextInt()
	{
		return (int) (nextLong() >>> 32);
	}

	@Override
	public int nextInt(int bound)
	{
		if (bound <= 0)
		{
			throw new IllegalArgumentException("bound must be positive: " + bound);
		}
		return (int) below(bound);
	}

	@Override
	public int nextInt(int origin, int bound)
	{
		if (origin >= bound)
		{
			throw new IllegalArgumentException("origin " + origin + " must be below bound " + bound);
		}
		return (int) (origin + below((long) bound - origin));
	}

	@Override
	public long nextLong(long bound)
	{
		if (bound <= 0)
		{
			throw new IllegalArgumentException("bound must be positive: " + bound);
		}
		return below(bound);
	}

	@Override
	public long nextLong(long origin, long bound)
	{
		if (origin >= bound)
		{
			throw new IllegalArgumentException("origin " + origin + " must be below bound " + bound);
		}

		final long range = bound - origin;
		if (range > 0)
		{
			return origin + below(range);
		}

		// The range does not fit in 63 bits, so more than half of all draws are inside it.
		while (true)
		{
			final long value = nextLong();
			if ((value >= origin) && (value < bound))
			{
				return value;
			}
		}
	}

	@Override
	public boolean nextBoolean()
	{
		return nextLong() < 0;
	}

	@Override
	public double nextDouble()
	{
		return (nextLong() >>> 11) * DOUBLE_UNIT;
	}

	@Override
	public double nextDouble(double bound)
	{
		if (!((bound > 0) && (bound < Double.POSITIVE_INFINITY)))
		{
			throw new IllegalArgumentException("bound must be finite and positive: " + bound);
		}

		final double value = nextDouble() * bound;
		return value < bound ? value : Math.nextDown(bound); // Rounding may land on the bound itself.
	}

	@Override
	public double nextDouble(double origin, double bound)
	{
		if (!((origin < bound) && ((bound - origin) < Double.POSITIVE_INFINITY)))
		{
			throw new IllegalArgumentException("need a finite range with origin " + origin + " below bound " + bound);
		}

		final double value = origin + (nextDouble() * (bound - origin));
		return value < bound ? value : Math.nextDown(bound);
	}

	@Override
	public float nextFloat()
	{
		return (nextLong() >>> 40) * FLOAT_UNIT;
	}

	@Override
	public float nextFloat(float bound)
	{
		if (!((bound > 0) && (bound < Float.POSITIVE_INFINITY)))
		{
			throw new IllegalArgumentException("bound must be finite and positive: " + bound);
		}

		final float value = nextFloat() * bound;
		return value < bound ? value : Math.nextDown(bound);
	}

	@Override
	public float nextFloat(float origin, float bound)
	{
		if (!((origin < bound) && ((bound - origin) < Float.POSITIVE_INFINITY)))
		{
			throw new IllegalArgumentException("need a finite range with origin " + origin + " below bound " + bound);
		}

		final float value = origin + (nextFloat() * (bound - origin));
		return value < bound ? value : Math.nextDown(bound);
	}

	@Override
	public void nextBytes(byte[] bytes)
	{
		// Each draw supplies up to eight bytes, least significant first.
		for (int i = 0; i < bytes.length;)
		{
			long draw = nextLong();
			for (int n = Math.min(bytes.length - i, Long.BYTES); n > 0; n--)
			{
				bytes[i++] = (byte) draw;
				draw >>>= Byte.SIZE;
			}
		}
	}

	@Override
	public double nextGaussian()
	{
		// The first uniform is in (0, 1] so its logarithm is finite.
		final double radius = StrictMath.sqrt(-2 * StrictMath.log(((nextLong() >>> 11) + 1) * DOUBLE_UNIT));
		return radius * StrictMath.cos(2 * StrictMath.PI * nextDouble());
	}

	/**
	 * Unspecified here, and the JDK's algorithm is not a contract.
	 */
	@Override
	public double nextExponential()
	{
		throw new UnsupportedOperationException("KeyedRandom does not specify an exponential distribution");
	}

	/**
	 * @param bound in {@code [1, 2^63)}
	 * @return a value in {@code [0, bound)}
	 */
	private long below(long bound)
	{
		return Math.unsignedMultiplyHigh(nextLong(), bound);
	}
}
