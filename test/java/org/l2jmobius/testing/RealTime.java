package org.l2jmobius.testing;

/**
 * The real system clock, for tests in packages where the test agent redirects {@code System.currentTimeMillis()} to the game clock.
 * <p>
 * This package is outside the agent's scope, so the call below is never redirected.
 * </p>
 */
public final class RealTime
{
	private RealTime()
	{
	}

	public static long millis()
	{
		return System.currentTimeMillis();
	}
}
