package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.gameserver.network.holders.MailMessage;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * Refunds (docs/spec/asset-service.md §3.2 {@code ABORTED → REFUNDED}, §7, ASSET-R22, R28): an operation that aborts after
 * taking assets is followed by its refund operation, which returns each whole object to its holder as service mail
 * (unsaved state kept), clears the pending mark and is final: a retry of the aborted id returns ALREADY_REFUNDED.
 * AT-ASSET-02 (refunded attempts), AT-ASSET-22 (abort causes).
 */
class RefundTest
{
	private static final int SHORT_SWORD = 1;
	private static AssetFixtures _f;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), Map.of("TEST", (connection, ref) -> true), id -> MailManager.getInstance().adopt(id));
	}

	private static Player inWorld()
	{
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 40);
		if (World.getPlayer(player.getObjectId()) == null)
		{
			World.addObject(player);
		}
		return player;
	}

	private static long refundMails(Player player) throws Exception
	{
		return _f.number("SELECT COUNT(*) FROM lc_mail_delivery WHERE receiver_char = " + player.getObjectId() + " AND reason = 'REFUND'");
	}

	@Test
	void anAbortAfterTheTakeReturnsTheObjectByServiceMail() throws Exception
	{
		final Player player = inWorld();
		final Item sword = player.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, player, null);
		sword.setEnchantLevel(4); // unsaved: the refund keeps it
		final int custody = _f.open("TEST");
		try (AssetTx first = _f.service.begin(_f.op("SETTLE"), "TEST", "SETTLE", custody))
		{
			first.settlementKey("TEST", "refund-1");
			first.commit();
		}
		final OpId op = _f.op("FUND");
		try (AssetTx tx = _f.service.begin(op, "TEST", "FUND", custody))
		{
			tx.settlementKey("TEST", "refund-1"); // already settled: the operation aborts after taking the sword
			tx.fund(custody, Holder.player(player.getObjectId()), List.of(new Asset(SHORT_SWORD, 1, sword.getObjectId())));
			assertEquals(AssetAbort.Reason.ALREADY_SETTLED, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		assertNull(player.getInventory().getItemByObjectId(sword.getObjectId()), "it is not put back into a live container");
		assertEquals(1, refundMails(player));
		final int message = (int) _f.number("SELECT message_id FROM lc_mail_delivery WHERE receiver_char = " + player.getObjectId() + " AND reason = 'REFUND'");
		assertEquals("-1|MAIL|" + message + "|4", _f.text("SELECT CONCAT_WS('|', owner_id, loc, loc_data, enchant_level) FROM items WHERE object_id = " + sword.getObjectId()));
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_asset_op WHERE op_id = UNHEX('" + op.refund().hex() + "') AND action = 'REFUND'"));
		assertEquals(0, _f.number("SELECT COUNT(*) FROM lc_asset_op WHERE op_id = UNHEX('" + op.hex() + "')"), "the operation itself never committed");
		assertEquals(0, _f.number("SELECT COUNT(*) FROM lc_asset_pending WHERE char_id = " + player.getObjectId()), "the mark went with the refund");
		assertEquals(0, _f.held(custody, SHORT_SWORD));
		final MailMessage mail = MailManager.getInstance().getMessage(message);
		assertNotNull(mail, "the refund mail is in the player's mailbox");
		assertEquals(sword.getObjectId(), mail.getAttachments().getItems().iterator().next().getObjectId());
		try (AssetTx retry = _f.service.begin(op, "TEST", "FUND", custody))
		{
			assertEquals(Outcome.ALREADY_REFUNDED, retry.commit(), "a refunded attempt is final");
		}
	}

	@Test
	void anIneligibleSecondObjectRefundsTheFirst() throws Exception
	{
		final Player player = inWorld();
		final Item first = player.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, player, null);
		final Item equipped = player.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, player, null);
		player.getInventory().equipItem(equipped);
		final int custody = _f.open("TEST");
		try (AssetTx tx = _f.service.begin(_f.op("FUND"), "TEST", "FUND", custody))
		{
			tx.fund(custody, Holder.player(player.getObjectId()), List.of(new Asset(SHORT_SWORD, 1, first.getObjectId()), new Asset(SHORT_SWORD, 1, equipped.getObjectId())));
			assertEquals(AssetAbort.Reason.INELIGIBLE, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		assertEquals(1, refundMails(player), "the first, already taken, comes back by mail");
		assertTrue(first.isRetired());
		assertSame(equipped, player.getInventory().getItemByObjectId(equipped.getObjectId()), "the refused one never moved");
		assertTrue(equipped.isEquipped());
		assertEquals(0, _f.number("SELECT COUNT(*) FROM lc_asset_pending WHERE char_id = " + player.getObjectId()));
	}
}
