package org.l2jmobius.commons.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.ProductionJvm;

/**
 * A JVM started the way production is started cannot have its randomness bound.
 * <p>
 * Predictable randomness on a live server is an exploit, so the refusal is tested in a real JVM without the test flag rather than assumed from reading the code.
 * </p>
 */
class RndProductionModeTest
{
	/**
	 * Runs in the child JVM.
	 */
	public static final class Probe
	{
		private Probe()
		{
		}

		public static void main(String[] args)
		{
			try
			{
				Rnd.bind(ThreadLocalRandom.current());
				System.out.print("BOUND");
			}
			catch (IllegalStateException e)
			{
				// Still usable after the refusal.
				Rnd.get(10);
				Rnd.unbind();
				System.out.print("REFUSED");
			}
		}
	}

	@Test
	void bindingIsRefusedWithoutTheFlag() throws Exception
	{
		assertEquals("REFUSED", ProductionJvm.run(Probe.class));
	}
}
