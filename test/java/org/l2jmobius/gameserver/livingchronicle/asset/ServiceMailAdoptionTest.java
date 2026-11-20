package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.gameserver.network.enums.MessageSenderType;
import org.l2jmobius.gameserver.network.holders.MailMessage;
import org.l2jmobius.gameserver.taskmanagers.MessageDeletionTaskManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * CP-0014 (docs/spec/asset-service.md §2.1, §11.19): Mobius adopts a service mail the Asset Service committed on its own
 * connection, once, and never schedules its expiry; player mail still expires as in retail.
 */
class ServiceMailAdoptionTest
{
	private static TestServer _server;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.boot();
	}

	@SuppressWarnings("unchecked")
	private static Map<Integer, Long> scheduled() throws Exception
	{
		final Field field = MessageDeletionTaskManager.class.getDeclaredField("PENDING_MESSAGES");
		field.setAccessible(true);
		return (Map<Integer, Long>) field.get(null);
	}

	/** A service mail as the Asset Service's transaction writes it, committed behind MailManager's back. */
	private static int committedServiceMail(Player receiver) throws Exception
	{
		final int messageId = IdManager.getInstance().getNextId();
		try (Connection connection = _server.database().connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO messages (messageId, senderId, receiverId, subject, content, expiration, reqAdena, hasAttachments, isUnread, isDeletedBySender, isDeletedByReceiver, sendBySystem, isReturned) VALUES (" + messageId + ", -1, " + receiver.getObjectId() + ", 'Notarius of Giran', 'Your contract is settled.', " + ServiceMail.EXPIRATION + ", 0, 'true', 'true', 'true', 'false', " + ServiceMail.SEND_BY_SYSTEM + ", 'false')");
		}
		return messageId;
	}

	@Test
	void aCommittedMessageIsAdoptedOnce() throws Exception
	{
		final Player receiver = TestPlayers.create(PlayerClass.FIGHTER, 20);
		final int messageId = committedServiceMail(receiver);
		MailManager.getInstance().adopt(messageId);
		final MailMessage adopted = MailManager.getInstance().getMessage(messageId);
		assertNotNull(adopted);
		assertEquals(receiver.getObjectId(), adopted.getReceiverId());
		assertEquals(-1, adopted.getSenderId());
		MailManager.getInstance().adopt(messageId);
		assertSame(adopted, MailManager.getInstance().getMessage(messageId), "adopting again changes nothing");
		assertTrue(MailManager.getInstance().getInbox(receiver.getObjectId()).contains(adopted));
	}

	@Test
	void aServiceMailIsNeverScheduledForExpiry() throws Exception
	{
		final Player receiver = TestPlayers.create(PlayerClass.FIGHTER, 20);
		final int messageId = committedServiceMail(receiver);
		MailManager.getInstance().adopt(messageId);
		assertFalse(scheduled().containsKey(messageId), "adopted: not scheduled");
		MessageDeletionTaskManager.getInstance().add(messageId, ServiceMail.EXPIRATION);
		assertFalse(scheduled().containsKey(messageId), "loaded after a restart (MailManager.load adds every message): not scheduled either");
	}

	@Test
	void retailMailStillExpires() throws Exception
	{
		final Player receiver = TestPlayers.create(PlayerClass.FIGHTER, 20);
		final MailMessage mail = new MailMessage(receiver.getObjectId(), "Hello", "A system notice.", MessageSenderType.NEWS);
		MailManager.getInstance().sendMessage(mail);
		assertEquals(mail.getExpiration(), scheduled().get(mail.getId()));
	}
}
