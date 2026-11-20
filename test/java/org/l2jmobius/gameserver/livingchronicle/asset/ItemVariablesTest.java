package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.mechanics.variables.AbstractVariables;
import org.l2jmobius.gameserver.mechanics.variables.ItemVariables;
import org.l2jmobius.testing.TestServer;

/**
 * CP-0013 (docs/spec/asset-service.md §3.3, §11.12): a variable is tracked and set in one step under the save lock, so
 * a capture (the Asset Service's, or a save) never sees a change tracked without its value; a retired variables object
 * refuses writes and never saves, so an escrowed item's variables are written only by the Asset Service's transaction.
 */
class ItemVariablesTest
{
	private static TestServer _server;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.boot();
	}

	/** Pauses the first change right after it has been tracked, to give a capture the chance to run in between. */
	static final class PausingVariables extends AbstractVariables
	{
		final CountDownLatch tracked = new CountDownLatch(1);
		final CountDownLatch resume = new CountDownLatch(1);
		private boolean _paused;

		@Override
		protected void trackChange(String name)
		{
			super.trackChange(name);
			if (!_paused)
			{
				_paused = true;
				tracked.countDown();
				try
				{
					resume.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
				}
			}
		}

		/** What a save reads: whether the variable is tracked as changed, and its value, under the save lock. */
		String capture() throws InterruptedException
		{
			if (!_saveLock.tryLock(500, TimeUnit.MILLISECONDS))
			{
				return "LOCKED"; // the change is in progress under the lock: no capture can fall between its two steps
			}
			try
			{
				final boolean isTracked = _added.contains("k") || _modified.contains("k");
				return isTracked + "=" + getString("k", "ABSENT");
			}
			finally
			{
				_saveLock.unlock();
			}
		}
	}

	@Test
	void aCaptureNeverSeesATrackedChangeWithoutItsValue() throws Exception
	{
		final PausingVariables variables = new PausingVariables();
		final Thread setter = new Thread(() -> variables.set("k", "new"));
		setter.start();
		assertTrue(variables.tracked.await(5, TimeUnit.SECONDS));
		final String seen = variables.capture();
		variables.resume.countDown();
		setter.join();
		assertFalse(seen.equals("true=ABSENT"), "a capture saw the change tracked but its value not yet set: " + seen);
		assertEquals("new", variables.getString("k"));
	}

	@Test
	void aRetiredVariablesObjectRefusesWritesAndNeverSaves() throws Exception
	{
		final int objectId = IdManager.getInstance().getNextId();
		final ItemVariables variables = new ItemVariables(objectId);
		variables.set("kept", 1);
		variables.retire();
		variables.set("refused", 2);
		variables.remove("kept");
		assertFalse(variables.hasVariable("refused"), "a write after retirement is refused");
		assertTrue(variables.hasVariable("kept"), "a removal after retirement is refused");
		assertFalse(variables.saveNow(), "a retired object never saves");
		try (Connection connection = _server.database().connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM item_variables WHERE id = " + objectId))
		{
			result.next();
			assertEquals(0, result.getLong(1), "nothing written: the Asset Service writes these rows itself");
		}
	}
}
