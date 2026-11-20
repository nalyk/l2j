package org.l2jmobius.gameserver.livingchronicle.asset;

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

	private ServiceMail()
	{
	}
}
