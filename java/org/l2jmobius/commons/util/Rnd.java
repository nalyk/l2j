/*
 * Copyright (c) 2013 L2jMobius
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.commons.util;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * @author Mobius
 * @since September 15th 2018
 */
public class Rnd
{
	private static final int MINIMUM_POSITIVE_INT = 1;
	private static final long MINIMUM_POSITIVE_LONG = 1L;
	private static final float MINIMUM_POSITIVE_FLOAT = Float.intBitsToFloat(0x1);
	private static final double MINIMUM_POSITIVE_DOUBLE = Double.longBitsToDouble(0x1L);
	
	/**
	 * Living Chronicle: test and simulation JVMs start with {@code -Dlc.rng.bindable=true} and may bind a deterministic generator to a thread.<br>
	 * Production starts without it, the constant folds to false, and every draw comes from ThreadLocalRandom exactly as before.
	 */
	private static final boolean BINDABLE = Boolean.getBoolean("lc.rng.bindable");
	private static final ThreadLocal<RandomGenerator> BOUND = new ThreadLocal<>();
	
	/**
	 * Makes every draw on the calling thread come from the given generator until {@link #unbind()}.
	 * @param generator the generator to draw from.
	 * @throws IllegalStateException in a JVM that was not started as bindable, so production randomness can never be made predictable.
	 */
	public static void bind(RandomGenerator generator)
	{
		if (!BINDABLE)
		{
			throw new IllegalStateException("Rnd is not bindable: this JVM was started without -Dlc.rng.bindable=true");
		}
		BOUND.set(generator);
	}
	
	/**
	 * Returns the calling thread to ThreadLocalRandom.
	 */
	public static void unbind()
	{
		BOUND.remove();
	}
	
	private static RandomGenerator generator()
	{
		if (BINDABLE)
		{
			final RandomGenerator bound = BOUND.get();
			if (bound != null)
			{
				return bound;
			}
		}
		return ThreadLocalRandom.current();
	}
	
	/**
	 * @return a random boolean value.
	 */
	public static boolean nextBoolean()
	{
		return generator().nextBoolean();
	}
	
	/**
	 * Generates random bytes and places them into a user-supplied byte array. The number of random bytes produced is equal to the length of the byte array.
	 * @param bytes the byte array to fill with random bytes.
	 */
	public static void nextBytes(byte[] bytes)
	{
		generator().nextBytes(bytes);
	}
	
	/**
	 * @param bound (int)
	 * @return a random int value between zero (inclusive) and the specified bound (exclusive).
	 */
	public static int get(int bound)
	{
		return bound <= 0 ? 0 : generator().nextInt(bound);
	}
	
	/**
	 * @param origin (int)
	 * @param bound (int)
	 * @return a random int value between the specified origin (inclusive) and the specified bound (inclusive).
	 */
	public static int get(int origin, int bound)
	{
		return origin >= bound ? origin : generator().nextInt(origin, bound == Integer.MAX_VALUE ? bound : bound + MINIMUM_POSITIVE_INT);
	}
	
	/**
	 * @return a random int value.
	 */
	public static int nextInt()
	{
		return generator().nextInt();
	}
	
	/**
	 * @param bound (long)
	 * @return a random long value between zero (inclusive) and the specified bound (exclusive).
	 */
	public static long get(long bound)
	{
		return bound <= 0 ? 0 : generator().nextLong(bound);
	}
	
	/**
	 * @param origin (long)
	 * @param bound (long)
	 * @return a random long value between the specified origin (inclusive) and the specified bound (inclusive).
	 */
	public static long get(long origin, long bound)
	{
		return origin >= bound ? origin : generator().nextLong(origin, bound == Long.MAX_VALUE ? bound : bound + MINIMUM_POSITIVE_LONG);
	}
	
	/**
	 * @return a random long value.
	 */
	public static long nextLong()
	{
		return generator().nextLong();
	}
	
	/**
	 * @param bound (float)
	 * @return a random float value between zero (inclusive) and the specified bound (exclusive).
	 */
	public static float get(float bound)
	{
		return bound <= 0 ? 0 : generator().nextFloat(bound);
	}
	
	/**
	 * @param origin (float)
	 * @param bound (float)
	 * @return a random float value between the specified origin (inclusive) and the specified bound (inclusive).
	 */
	public static float get(float origin, float bound)
	{
		return origin >= bound ? origin : generator().nextFloat(origin, bound == Float.MAX_VALUE ? bound : bound + MINIMUM_POSITIVE_FLOAT);
	}
	
	/**
	 * @return a random float value between zero (inclusive) and one (exclusive).
	 */
	public static float nextFloat()
	{
		return generator().nextFloat();
	}
	
	/**
	 * @param bound (double)
	 * @return a random double value between zero (inclusive) and the specified bound (exclusive).
	 */
	public static double get(double bound)
	{
		return bound <= 0 ? 0 : generator().nextDouble(bound);
	}
	
	/**
	 * @param origin (double)
	 * @param bound (double)
	 * @return a random double value between the specified origin (inclusive) and the specified bound (inclusive).
	 */
	public static double get(double origin, double bound)
	{
		return origin >= bound ? origin : generator().nextDouble(origin, bound == Double.MAX_VALUE ? bound : bound + MINIMUM_POSITIVE_DOUBLE);
	}
	
	/**
	 * @return a random double value between zero (inclusive) and one (exclusive).
	 */
	public static double nextDouble()
	{
		return generator().nextDouble();
	}
	
	/**
	 * @return the next random, Gaussian ("normally") distributed double value with mean 0.0 and standard deviation 1.0 from this random number generator's sequence.
	 */
	public static double nextGaussian()
	{
		return generator().nextGaussian();
	}
}
