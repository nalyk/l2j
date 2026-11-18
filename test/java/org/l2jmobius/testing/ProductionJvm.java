package org.l2jmobius.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Runs a probe in a JVM started the way production starts one: same classes, no test agent, no test flags.
 * <p>
 * The test facilities (bindable randomness, controllable clock) are switched on by JVM flags the suite passes to itself. That they are really off without those flags is a security property, and it is checked in a real JVM rather than argued from the source.
 * </p>
 */
public final class ProductionJvm
{
	private ProductionJvm()
	{
	}

	/**
	 * @param probe a class with a {@code main} method
	 * @return everything the probe printed, once it has exited with status 0
	 * @throws IllegalStateException if the probe exits with any other status
	 */
	public static String run(Class<?> probe) throws IOException, InterruptedException
	{
		final String java = ProcessHandle.current().info().command().orElseThrow();
		final Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), probe.getName()).redirectErrorStream(true).start();
		final String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

		final int status = child.waitFor();
		if (status != 0)
		{
			throw new IllegalStateException(probe.getName() + " exited with status " + status + ":\n" + output);
		}
		return output;
	}
}
