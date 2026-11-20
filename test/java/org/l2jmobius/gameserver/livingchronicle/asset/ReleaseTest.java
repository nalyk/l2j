package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.SWORD;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.gameserver.network.holders.MailMessage;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * {@code release} (docs/spec/asset-service.md §2.1, §4.3, INV12, ASSET-R10/R11): from a custody to a character, as
 * service mail written in the operation's transaction and adopted by Mobius; to a receiver who is gone or marked for
 * deletion, nothing moves. AT-ASSET-10, AT-ASSET-11 (writing) and AT-ASSET-01 for releases.
 */
class ReleaseTest
{
	private static AssetFixtures _f;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		final HoldingPolicy always = (connection, ref) -> true;
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), Map.of("TEST", always), id -> MailManager.getInstance().adopt(id));
	}

	private static int release(int custody, Player receiver, Asset... assets) throws Exception
	{
		final OpId op = _f.op("RELEASE");
		try (AssetTx tx = _f.service.begin(op, "TEST", "RELEASE", custody))
		{
			tx.release(custody, receiver.getObjectId(), List.of(assets), MailReason.PAYOUT, "Notarius of Giran", "Your contract is settled.");
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		return (int) _f.number("SELECT message_id FROM lc_mail_delivery WHERE op_id = UNHEX('" + op.hex() + "')");
	}

	@Test
	void aReleaseWritesAServiceMailWithItsAttachmentsDeliveryAndPostings() throws Exception
	{
		final Player receiver = TestPlayers.create(PlayerClass.FIGHTER, 20);
		final int custody = _f.open("TEST");
		_f.seed(custody, ADENA, 1000, false);
		final int message = release(custody, receiver, new Asset(ADENA, 400, null));
		assertEquals("-1|" + receiver.getObjectId() + "|1|0|true|" + ServiceMail.EXPIRATION, _f.text("SELECT CONCAT_WS('|', senderId, receiverId, sendBySystem, reqAdena, hasAttachments, expiration) FROM messages WHERE messageId = " + message));
		assertEquals(400, _f.number("SELECT SUM(count) FROM items WHERE owner_id = -1 AND loc = 'MAIL' AND loc_data = " + message + " AND item_id = " + ADENA));
		assertEquals(600, _f.held(custody, ADENA));
		assertEquals(600, _f.posted(custody, ADENA));
		assertEquals(400, _f.number("SELECT qty FROM lc_ledger_posting WHERE account_kind = 'MAIL' AND account_id = " + message + " AND direction = 'CREDIT'"));
		assertEquals("PAYOUT|" + receiver.getObjectId(), _f.text("SELECT CONCAT_WS('|', reason, receiver_char) FROM lc_mail_delivery WHERE message_id = " + message + " AND claimed_at IS NULL"));
		assertEquals(1, _f.facts("EscrowReleased", custody));
	}

	@Test
	void aWholeObjectKeepsItsObjectIdAndMobiusAdoptsTheMail() throws Exception
	{
		final Player receiver = TestPlayers.create(PlayerClass.FIGHTER, 20);
		final int custody = _f.open("TEST");
		final int sword = _f.seed(custody, SWORD, 1, true);
		final int message = release(custody, receiver, new Asset(SWORD, 1, sword));
		assertEquals("-1|MAIL|" + message, _f.text("SELECT CONCAT_WS('|', owner_id, loc, loc_data) FROM items WHERE object_id = " + sword));
		final MailMessage mail = MailManager.getInstance().getMessage(message);
		assertNotNull(mail, "adopted after commit");
		assertTrue(MailManager.getInstance().getInbox(receiver.getObjectId()).contains(mail));
		final List<Item> attachments = List.copyOf(mail.getAttachments().getItems());
		assertEquals(1, attachments.size());
		assertEquals(sword, attachments.get(0).getObjectId(), "retail mail loads exactly the attachment rows the release wrote");
	}

	@Test
	void aMarkedOrMissingReceiverAbortsWithNothingMoved() throws Exception
	{
		final Player marked = TestPlayers.create(PlayerClass.FIGHTER, 20);
		_f.execute("UPDATE characters SET deletetime = 1 WHERE charId = " + marked.getObjectId());
		final int custody = _f.open("TEST");
		_f.seed(custody, ADENA, 50, false);
		for (int receiver : new int[] { marked.getObjectId(), 2_100_000_000 })
		{
			try (AssetTx tx = _f.service.begin(_f.op("RELEASE"), "TEST", "RELEASE", custody))
			{
				tx.release(custody, receiver, List.of(new Asset(ADENA, 10, null)), MailReason.PAYOUT, "Notarius", "x");
				assertEquals(AssetAbort.Reason.RECEIVER_GONE, assertThrows(AssetAbort.class, tx::commit).reason());
			}
		}
		assertEquals(50, _f.held(custody, ADENA));
		assertEquals(0, _f.number("SELECT COUNT(*) FROM messages WHERE receiverId IN (" + marked.getObjectId() + ", 2100000000)"));
	}
}
