package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Mail;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.gameserver.network.enums.MessageSenderType;
import org.l2jmobius.gameserver.network.holders.MailMessage;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * CP-0015 (docs/spec/asset-service.md §2.1, §11.23, INV12): claiming a service mail's attachments, as retail's
 * {@code RequestPostAttachment} does, records {@code claimed_at} on its delivery. Part of AT-ASSET-11.
 */
class ServiceMailClaimTest
{
	private static AssetFixtures _f;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), Map.of("TEST", (connection, ref) -> true), id -> MailManager.getInstance().adopt(id));
		ServiceMail.activate();
	}

	/** The claim exactly as RequestPostAttachment.runImpl performs it (RequestPostAttachment.java:191-232). */
	private static void claim(Player receiver, MailMessage message)
	{
		final Mail attachments = message.getAttachments();
		for (Item item : List.copyOf(attachments.getItems()))
		{
			assertNotNull(attachments.transferItem(ItemProcessType.TRANSFER, item.getObjectId(), item.getCount(), receiver.getInventory(), receiver, null));
		}
		message.removeAttachments();
	}

	@Test
	void claimingTheAttachmentsSetsClaimedAt() throws Exception
	{
		final Player receiver = TestPlayers.create(PlayerClass.FIGHTER, 20);
		final int custody = _f.open("TEST");
		_f.seed(custody, ADENA, 300, false);
		final OpId op = _f.op("RELEASE");
		try (AssetTx tx = _f.service.begin(op, "TEST", "RELEASE", custody))
		{
			tx.release(custody, receiver.getObjectId(), List.of(new Asset(ADENA, 300, null)), MailReason.PAYOUT, "Notarius", "Paid.");
			tx.commit();
		}
		final int message = (int) _f.number("SELECT message_id FROM lc_mail_delivery WHERE op_id = UNHEX('" + op.hex() + "')");
		final long before = receiver.getAdena();
		claim(receiver, MailManager.getInstance().getMessage(message));
		assertEquals(before + 300, receiver.getAdena(), "retail mail delivered the Adena");
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_mail_delivery WHERE message_id = " + message + " AND claimed_at IS NOT NULL"), "the claim is recorded");
		assertEquals(0, _f.number("SELECT COUNT(*) FROM items WHERE loc = 'MAIL' AND loc_data = " + message));
	}

	@Test
	void anotherSystemMailHasNoDeliveryAndNothingChanges() throws Exception
	{
		final Player receiver = TestPlayers.create(PlayerClass.FIGHTER, 20);
		final MailMessage mail = new MailMessage(receiver.getObjectId(), "Event", "A gift.", MessageSenderType.NEWS);
		MailManager.getInstance().sendMessage(mail);
		final long deliveries = _f.number("SELECT COUNT(*) FROM lc_mail_delivery");
		final long claimed = _f.number("SELECT COUNT(*) FROM lc_mail_delivery WHERE claimed_at IS NOT NULL");
		mail.createAttachments();
		mail.removeAttachments();
		assertEquals(deliveries, _f.number("SELECT COUNT(*) FROM lc_mail_delivery"));
		assertEquals(claimed, _f.number("SELECT COUNT(*) FROM lc_mail_delivery WHERE claimed_at IS NOT NULL"));
	}
}
