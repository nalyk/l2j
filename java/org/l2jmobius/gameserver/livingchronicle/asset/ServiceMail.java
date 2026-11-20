package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.network.enums.MessageSenderType;

/**
 * Service mail (docs/spec/asset-service.md §2.1): how what an operation owes a character arrives, as a retail system
 * mail written in the operation's transaction.
 */
public final class ServiceMail
{
	/** The sender of every service mail, as of all of Mobius's system mail; startup cleanup keeps its attachments. */
	public static final int SENDER = -1;
	/** {@code MessageSenderType.NEWS}: nonzero, so the mail cannot be rejected back to a sender. */
	public static final int SEND_BY_SYSTEM = MessageSenderType.NEWS.ordinal();
	/**
	 * 2038-01-19 03:14:07 UTC, the latest instant the client can show (the mail lists send expiry as {@code int} epoch
	 * seconds). The deletion task never schedules a message with this expiration, so service mail never expires.
	 */
	public static final long EXPIRATION = Integer.MAX_VALUE * 1000L;

	private static final Logger LOGGER = Logger.getLogger(ServiceMail.class.getName());

	/** Set when Living Chronicle runs (never in the {@code truth} profile, where no module starts). */
	private static volatile boolean _active;

	private ServiceMail()
	{
	}

	/** Living Chronicle runs: claims of service mail are recorded from now on. */
	public static void activate()
	{
		_active = true;
	}

	/**
	 * Retail cleared a system message's attachments (CP-0015, called by {@code MailMessage.removeAttachments}): if it is a
	 * service mail, its delivery is now claimed (§2.1, §11.23). A message with no delivery changes nothing; a failure is
	 * logged and repaired by the census, which sets {@code claimed_at} for a message whose attachments are gone.
	 */
	public static void attachmentsCleared(int messageId)
	{
		if (!_active)
		{
			return;
		}

		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement claim = connection.prepareStatement("UPDATE lc_mail_delivery SET claimed_at = NOW(3) WHERE message_id = ? AND claimed_at IS NULL"))
		{
			claim.setInt(1, messageId);
			claim.executeUpdate();
		}
		catch (SQLException e)
		{
			LOGGER.log(Level.WARNING, "Could not record the claim of service mail " + messageId + "; the census will", e);
		}
	}
}
