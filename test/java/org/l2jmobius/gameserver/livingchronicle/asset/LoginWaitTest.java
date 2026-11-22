package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.holders.CharacterInfoHolder;
import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * CP-0018 (docs/spec/asset-service.md §2.4, §11.22, ASSET-R26): while an operation that took a character's items is
 * pending, that character's login waits; it goes ahead once the mark is gone, and is refused, the character not loaded,
 * if the mark outlives the bound. Part of AT-ASSET-28.
 */
class LoginWaitTest
{
	private static TestServer _server;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.boot();
		try (var connection = _server.database().connect();
			var tables = connection.getMetaData().getTables(null, null, "lc_custody", null))
		{
			if (!tables.next())
			{
				Migrations.applyAll(connection);
			}
		}
		ServiceMail.activate();
		PendingMarks.configure(new AssetConfig(15, 100, 200, 3)); // a login waits at most 4 x 100 ms here
	}

	/** A character logged out of the world, with a client that has it in slot 0. */
	private static GameClient clientFor(Player character) throws Exception
	{
		character.deleteMe();
		final Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		final Object unsafe = unsafeField.get(null);
		final GameClient client = (GameClient) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, GameClient.class);
		client.setCharSelection(List.of(new CharacterInfoHolder(character.getObjectId(), character.getName())));
		return client;
	}

	private static void mark(Player character, OpId op) throws Exception
	{
		try (var connection = _server.database().connect())
		{
			PendingMarks.mark(connection, character.getObjectId(), op);
		}
	}

	private static void clear(Player character, OpId op) throws Exception
	{
		try (var connection = _server.database().connect();
			var statement = connection.prepareStatement("DELETE FROM lc_asset_pending WHERE char_id = ? AND op_id = ?"))
		{
			statement.setInt(1, character.getObjectId());
			statement.setBytes(2, op.bytes());
			statement.executeUpdate();
		}
	}

	@Test
	void aLoginWaitsWhileAnOperationOfTheCharacterIsPending() throws Exception
	{
		PendingMarks.configure(new AssetConfig(15, 2000, 200, 3)); // up to 8 s: long enough to clear the mark first
		try
		{
			final Player character = TestPlayers.create(PlayerClass.FIGHTER, 20);
			final OpId op = OpId.of("TEST", character.getObjectId(), "FUND", 0);
			mark(character, op);
			final GameClient client = clientFor(character);
			final CompletableFuture<Player> login = CompletableFuture.supplyAsync(() -> client.load(0));
			Thread.sleep(400);
			assertFalse(login.isDone(), "the login waits for the pending operation");
			clear(character, op);
			final Player loaded = login.get(10, TimeUnit.SECONDS);
			assertNotNull(loaded, "it goes ahead once the operation is done");
			assertEquals(character.getObjectId(), loaded.getObjectId());
			loaded.deleteMe();
		}
		finally
		{
			PendingMarks.configure(new AssetConfig(15, 100, 200, 3));
		}
	}

	@Test
	void aLoginIsRefusedIfTheMarkOutlivesTheBound() throws Exception
	{
		final Player character = TestPlayers.create(PlayerClass.FIGHTER, 20);
		mark(character, OpId.of("TEST", character.getObjectId(), "STUCK", 0));
		final GameClient client = clientFor(character);
		final long start = System.nanoTime();
		assertNull(client.load(0), "not loaded: the rows may still change");
		assertTrue(((System.nanoTime() - start) / 1_000_000) >= 350, "after waiting the bound");
	}

	@Test
	void anUnmarkedLoginDoesNotWait() throws Exception
	{
		final Player character = TestPlayers.create(PlayerClass.FIGHTER, 20);
		final GameClient client = clientFor(character);
		final Player loaded = client.load(0);
		assertNotNull(loaded);
		loaded.deleteMe();
	}

	@Test
	void markingCommitsOnItsOwnConnection() throws Exception
	{
		final Player character = TestPlayers.create(PlayerClass.FIGHTER, 20);
		final OpId op = OpId.of("TEST", character.getObjectId(), "SEEN", 0);
		mark(character, op);
		assertTrue(PendingMarks.isMarked(character.getObjectId()), "visible to another connection at once");
		clear(character, op);
		assertFalse(PendingMarks.isMarked(character.getObjectId()));
	}
}
