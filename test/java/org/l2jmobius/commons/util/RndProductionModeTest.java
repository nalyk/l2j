package org.l2jmobius.commons.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;

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
		final String java = ProcessHandle.current().info().command().orElseThrow();
		final Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Probe.class.getName()).redirectErrorStream(true).start();
		final String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

		assertEquals(0, child.waitFor(), output);
		assertEquals("REFUSED", output);
	}
}
