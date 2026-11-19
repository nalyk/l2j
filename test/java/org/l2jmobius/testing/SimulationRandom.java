package org.l2jmobius.testing;

import java.util.random.RandomGenerator;

/**
 * The one generator every thread of a paused world is bound to, whose source a test can switch.
 * <p>
 * {@code Rnd} binds a generator per thread, and a paused world runs on several: the test's own and the scheduler threads that run whatever an advance makes due. Binding all of them to this object, once, lets a lock case move the whole simulation onto a stream of its own and back, which a per-thread bind on the test's thread alone could not do for a skill whose hit lands on a scheduler thread.
 * </p>
 * <p>
 * Every method forwards to the current source, so drawing through this object is draw for draw the same as drawing from the source directly. Only one simulation thread runs at a time, so a switch made between advances is seen by all of them.
 * </p>
 */
final class SimulationRandom implements RandomGenerator
{
	private volatile RandomGenerator _source;

	SimulationRandom(RandomGenerator source)
	{
		_source = source;
	}

	RandomGenerator source()
	{
		return _source;
	}

	void use(RandomGenerator source)
	{
		_source = source;
	}

	@Override
	public long nextLong()
	{
		return _source.nextLong();
	}

	@Override
	public long nextLong(long bound)
	{
		return _source.nextLong(bound);
	}

	@Override
	public long nextLong(long origin, long bound)
	{
		return _source.nextLong(origin, bound);
	}

	@Override
	public int nextInt()
	{
		return _source.nextInt();
	}

	@Override
	public int nextInt(int bound)
	{
		return _source.nextInt(bound);
	}

	@Override
	public int nextInt(int origin, int bound)
	{
		return _source.nextInt(origin, bound);
	}

	@Override
	public boolean nextBoolean()
	{
		return _source.nextBoolean();
	}

	@Override
	public double nextDouble()
	{
		return _source.nextDouble();
	}

	@Override
	public double nextDouble(double bound)
	{
		return _source.nextDouble(bound);
	}

	@Override
	public double nextDouble(double origin, double bound)
	{
		return _source.nextDouble(origin, bound);
	}

	@Override
	public float nextFloat()
	{
		return _source.nextFloat();
	}

	@Override
	public float nextFloat(float bound)
	{
		return _source.nextFloat(bound);
	}

	@Override
	public float nextFloat(float origin, float bound)
	{
		return _source.nextFloat(origin, bound);
	}

	@Override
	public void nextBytes(byte[] bytes)
	{
		_source.nextBytes(bytes);
	}

	@Override
	public double nextGaussian()
	{
		return _source.nextGaussian();
	}

	@Override
	public double nextGaussian(double mean, double stddev)
	{
		return _source.nextGaussian(mean, stddev);
	}

	@Override
	public double nextExponential()
	{
		return _source.nextExponential();
	}
}
