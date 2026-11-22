package org.l2jmobius.gameserver.livingchronicle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * CP-0027's seams sit where asset-service.md §11.25 puts them, read from the compiled core: the startup census right
 * after the database opens and before the thread pools; recovery after the punishment manager and before the shutdown hook
 * and the offline traders' restore; the boundary after Mobius's last save and before the database closes.
 */
class SeamsTest
{
	/** @return every call ("Owner.name") in the method of {@code type} that makes {@code call}, in order */
	private static List<String> callsAround(String type, String call) throws Exception
	{
		final Path file = Path.of(System.getProperty("lc.classes.dir")).resolve(type + ".class");
		final ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
		for (MethodModel method : model.methods())
		{
			final List<String> calls = new ArrayList<>();
			method.code().ifPresent(code ->
			{
				for (CodeElement element : code)
				{
					if (element instanceof InvokeInstruction invoke)
					{
						final String owner = invoke.owner().asInternalName();
						calls.add(owner.substring(owner.lastIndexOf('/') + 1) + "." + invoke.name().stringValue());
					}
				}
			});
			if (calls.contains(call))
			{
				return calls;
			}
		}
		throw new AssertionError(type + " never calls " + call);
	}

	private static void inOrder(List<String> calls, String... order)
	{
		int at = -1;
		for (String call : order)
		{
			final int next = calls.subList(at + 1, calls.size()).indexOf(call);
			assertTrue(next >= 0, call + " after " + String.join(", ", List.of(order).subList(0, List.of(order).indexOf(call))) + " in " + calls);
			at += next + 1;
		}
	}

	private static void adjacent(List<String> calls, String first, String second)
	{
		final int at = calls.indexOf(first);
		assertTrue((at >= 0) && ((at + 1) < calls.size()) && calls.get(at + 1).equals(second), second + " right after " + first + " in " + calls);
	}

	@Test
	void theStartupCensusComesRightAfterTheDatabaseOpens() throws Exception
	{
		final List<String> calls = callsAround("org/l2jmobius/gameserver/GameServer", "LivingChronicle.atDatabase");
		adjacent(calls, "DatabaseFactory.init", "LivingChronicle.atDatabase");
		inOrder(calls, "LivingChronicle.atDatabase", "ThreadPool.init", "IdManager.getInstance");
	}

	@Test
	void recoveryComesBeforeAnyCharacterLoads() throws Exception
	{
		final List<String> calls = callsAround("org/l2jmobius/gameserver/GameServer", "LivingChronicle.beforeLogin");
		inOrder(calls, "MailManager.getInstance", "PunishmentManager.getInstance", "LivingChronicle.beforeLogin", "Runtime.addShutdownHook", "OfflineTraderTable.getInstance");
	}

	@Test
	void theBoundaryFollowsTheLastSaveAndPrecedesTheDatabaseClosing() throws Exception
	{
		final List<String> calls = callsAround("org/l2jmobius/gameserver/Shutdown", "LivingChronicle.atShutdown");
		adjacent(calls, "Shutdown.saveData", "LivingChronicle.atShutdown");
		inOrder(calls, "LivingChronicle.atShutdown", "DatabaseFactory.close");
	}

	@Test
	void theBoundaryWaitsForTheTaskPoolsMobiusStopped() throws Exception
	{
		inOrder(callsAround("org/l2jmobius/gameserver/Shutdown", "LivingChronicle.atShutdown"), "ThreadPool.shutdown", "LivingChronicle.atShutdown");
		assertTrue(callsAround("org/l2jmobius/gameserver/livingchronicle/LivingChronicle", "ThreadPool.awaitTermination").contains("ThreadPool.awaitTermination"), "shutdownNow only interrupts; the boundary waits for termination");
	}
}
