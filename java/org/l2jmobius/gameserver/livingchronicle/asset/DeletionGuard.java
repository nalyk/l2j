package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.l2jmobius.commons.database.DatabaseFactory;

/**
 * The deletion guard (docs/spec/asset-service.md §2.1, §11.21, ASSET-R27): before a character is marked for deletion, or
 * deleted at once, one transaction locks its row, refuses while it has unclaimed service mail or a pending mark, and
 * otherwise marks it. Service mail and pending marks are written under the same row lock, so a payout, a debit with the
 * refund it may need, and a deletion never interleave. When Living Chronicle is not running ({@code truth}), it marks the
 * character exactly as retail does.
 */
public final class DeletionGuard
{
	private DeletionGuard()
	{
	}

	/**
	 * @param deleteTime the {@code deletetime} to set: when the character is to be deleted
	 * @return whether the character was marked; {@code false} when something is still owed to or taken from it
	 */
	public static boolean markForDeletion(int charId, long deleteTime) throws SQLException
	{
		try (Connection connection = DatabaseFactory.getConnection())
		{
			connection.setAutoCommit(false);
			try
			{
				try (PreparedStatement lock = connection.prepareStatement("SELECT charId FROM characters WHERE charId = ? FOR UPDATE"))
				{
					lock.setInt(1, charId);
					try (ResultSet result = lock.executeQuery())
					{
						if (!result.next())
						{
							connection.rollback();
							return false;
						}
					}
				}
				if (ServiceMail.isActive() && (owed(connection, "SELECT COUNT(*) FROM lc_mail_delivery WHERE receiver_char = ? AND claimed_at IS NULL", charId) || owed(connection, "SELECT COUNT(*) FROM lc_asset_pending WHERE char_id = ?", charId)))
				{
					connection.rollback();
					return false;
				}
				try (PreparedStatement mark = connection.prepareStatement("UPDATE characters SET deletetime = ? WHERE charId = ?"))
				{
					mark.setLong(1, deleteTime);
					mark.setInt(2, charId);
					mark.executeUpdate();
				}
				connection.commit();
				return true;
			}
			catch (SQLException e)
			{
				connection.rollback();
				throw e;
			}
			finally
			{
				connection.setAutoCommit(true);
			}
		}
	}

	private static boolean owed(Connection connection, String sql, int charId) throws SQLException
	{
		try (PreparedStatement read = connection.prepareStatement(sql))
		{
			read.setInt(1, charId);
			try (ResultSet result = read.executeQuery())
			{
				result.next();
				return result.getLong(1) > 0;
			}
		}
	}
}
