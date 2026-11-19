package org.l2jmobius.gameserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.testing.TestServer;
import org.l2jmobius.testing.agent.TruthAgent;

/**
 * The stock server boots on the pinned toolchain against the stock schema, and complains about exactly what it complained about at the fork point.
 * <p>
 * The list of boot complaints is pinned, not merely bounded: a new warning from a loader is a regression, and a warning that disappears is a change someone should have meant to make. Either way this turns red and the list is edited deliberately.
 * </p>
 */
class GameServerBootTest
{
	private static final int ADENA = 57;

	/**
	 * Everything upstream logs at WARNING or above while booting, at the fork point. Paths are relative to the server's working directory.
	 */
	private static final List<String> KNOWN_BOOT_COMPLAINTS = List.of(
		// Upstream reads an optional directory of custom buylists that its repository does not contain.
		"WARNING org.l2jmobius.commons.util.IXmlReader: Directory not found: ./data/buylists/custom");

	private static TestServer _server;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.boot();
	}

	@Test
	void bootComplaintsAreExactlyTheKnownOnes()
	{
		final String workingDirectory = Path.of("").toAbsolutePath() + "/";
		final List<String> complaints = _server.bootLog().stream().filter(line -> line.level().intValue() >= Level.WARNING.intValue()).map(line -> line.toString().replace(workingDirectory, "")).toList();
		assertEquals(KNOWN_BOOT_COMPLAINTS, complaints);
	}

	/**
	 * Booting loads and compiles every datapack script, so this is the moment the agent has seen all of them.
	 */
	@Test
	void everyLoadedClassReadsTimeThroughTheGameClock()
	{
		assertEquals(Set.of(), TruthAgent.failures());
		assertTrue(TruthAgent.patchedScriptClasses() > 0, "the agent never patched a script class, so it is not seeing the script loader");
		assertEquals(Set.of(), TruthAgent.unredirectedReads());
	}

	/**
	 * The server's many-actor loops (moving, thinking, decaying, respawning) tell their actors apart in a test JVM: the agent found every call it sends to the test's ActorStreams. Every one is loaded here whether or not the boot happened to run it.
	 */
	@Test
	void theManyActorLoopsTellTheirActorsApart() throws ClassNotFoundException
	{
		final Set<String> loops = Set.of("org/l2jmobius/gameserver/taskmanagers/MovementTaskManager$Movement", "org/l2jmobius/gameserver/taskmanagers/AttackableThinkTaskManager$AttackableThink", "org/l2jmobius/gameserver/entity/spawns/Spawn", "org/l2jmobius/gameserver/taskmanagers/RespawnTaskManager", "org/l2jmobius/gameserver/entity/actor/Npc", "org/l2jmobius/gameserver/taskmanagers/DecayTaskManager");
		for (String loop : loops)
		{
			Class.forName(loop.replace('/', '.'));
		}
		assertEquals(Set.of(), TruthAgent.failures());
		assertEquals(loops, TruthAgent.dispatchedClasses());
	}

	@Test
	void theDatapackIsLoaded()
	{
		assertEquals("Adena", ItemData.getInstance().getTemplate(ADENA).getName());
		assertNotNull(SkillData.getInstance().getSkill(1, 1));
		assertTrue(World.getVisibleObjectsCount() > 10_000, () -> "only " + World.getVisibleObjectsCount() + " objects were spawned");
	}
}
