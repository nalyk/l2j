package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;

/**
 * Pending marks (docs/spec/asset-service.md §2.2 {@code lc_asset_pending}, §2.4): a character whose items an operation has
 * taken in memory and not yet committed or refunded. The mark is committed on its own before the first debit and removed
 * by the operation's or its refund's transaction; a login waits while one exists, because the character's rows may still
 * change (CP-0018).
 */
public final class PendingMarks
{
	private static final Logger LOGGER = Logger.getLogger(PendingMarks.class.getName());
	private static final long POLL_MILLIS = 50;

	/** How long a login waits for a mark to go: four times the uncertain-commit lookup, the longest a resolution takes. */
	private static volatile long _loginWaitMillis = AssetConfig.defaults().uncertainCommitLookupMillis() * 4L;

	private PendingMarks()
	{
	}

	public static void configure(AssetConfig config)
	{
		_loginWaitMillis = config.uncertainCommitLookupMillis() * 4L;
	}

	/** Marks the character for {@code op}, committed on its own (the connection is left in autocommit). */
	public static void mark(Connection connection, int charId, OpId op) throws SQLException
	{
		final boolean autoCommit = connection.getAutoCommit();
		connection.setAutoCommit(true);
		try (PreparedStatement insert = connection.prepareStatement("INSERT INTO lc_asset_pending (char_id, op_id, since) VALUES (?, ?, ?)"))
		{
			insert.setInt(1, charId);
			insert.setBytes(2, op.bytes());
			insert.setTimestamp(3, Timestamp.from(Instant.now()));
			insert.executeUpdate();
		}
		finally
		{
			connection.setAutoCommit(autoCommit);
		}
	}

	/** Removes {@code op}'s mark of the character in the caller's transaction (the operation's or its refund's). */
	public static void clear(Connection connection, int charId, OpId op) throws SQLException
	{
		try (PreparedStatement delete = connection.prepareStatement("DELETE FROM lc_asset_pending WHERE char_id = ? AND op_id = ?"))
		{
			delete.setInt(1, charId);
			delete.setBytes(2, op.bytes());
			delete.executeUpdate();
		}
	}

	/** Whether any operation has the character marked; a plain read on the game's pool, taking no lock. */
	public static boolean isMarked(int charId)
	{
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement read = connection.prepareStatement("SELECT 1 FROM lc_asset_pending WHERE char_id = ? LIMIT 1"))
		{
			read.setInt(1, charId);
			try (ResultSet result = read.executeQuery())
			{
				return result.next();
			}
		}
		catch (SQLException e)
		{
			LOGGER.log(Level.WARNING, "Could not read the pending marks of character " + charId + "; the login waits", e);
			return true;
		}
	}

	/**
	 * Waits while the character is marked (CP-0018, called before a login restores it); does nothing when Living Chronicle
	 * is not running.
	 * @return whether the character is free to load; {@code false} if a mark outlived the wait
	 */
	public static boolean awaitNone(int charId)
	{
		if (!ServiceMail.isActive())
		{
			return true;
		}

		final long deadline = System.nanoTime() + (_loginWaitMillis * 1_000_000L);
		while (isMarked(charId))
		{
			if (System.nanoTime() >= deadline)
			{
				return false;
			}
			try
			{
				Thread.sleep(POLL_MILLIS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return true;
	}
}
