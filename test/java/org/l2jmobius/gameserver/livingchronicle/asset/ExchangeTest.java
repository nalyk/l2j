package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

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
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * {@code exchange} (docs/spec/asset-service.md §4.3; the Guild's buying and selling, aden-remembers.md §4.6): one
 * operation in which a character's goods enter one custody and another custody pays the character by service mail;
 * refused whole if either side cannot be met.
 */
class ExchangeTest
{
	private static final int SHORT_SWORD = 1;
	private static AssetFixtures _f;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), Map.of("GUILD", (connection, ref) -> true), id -> MailManager.getInstance().adopt(id));
	}

	private static Player seller()
	{
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 40);
		if (World.getPlayer(player.getObjectId()) == null)
		{
			World.addObject(player);
		}
		return player;
	}

	@Test
	void theCharactersGoodsGoInAndThePaymentComesOutTogether() throws Exception
	{
		final Player seller = seller();
		final Item sword = seller.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, seller, null);
		final int warehouse = _f.open("GUILD");
		final int pool = _f.open("GUILD");
		_f.seed(pool, ADENA, 1_000, false);
		final OpId op = _f.op("EXCHANGE");
		try (AssetTx tx = _f.service.begin(op, "GUILD", "EXCHANGE", seller.getObjectId()))
		{
			tx.exchange(seller.getObjectId(), List.of(new Asset(SHORT_SWORD, 1, sword.getObjectId())), warehouse, List.of(new Asset(ADENA, 600, null)), pool, "Merchant Guild of Giran", "Bought: Short Sword.");
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		assertEquals(1, _f.held(warehouse, SHORT_SWORD));
		assertEquals(400, _f.held(pool, ADENA));
		assertEquals(600, _f.number("SELECT SUM(i.count) FROM items i JOIN lc_mail_delivery d ON d.message_id = i.loc_data WHERE d.op_id = UNHEX('" + op.hex() + "') AND i.loc = 'MAIL'"), "paid by service mail");
		assertEquals(1, _f.facts("Exchanged", warehouse));
	}

	@Test
	void aPoolThatCannotPayRefusesTheWholeExchange() throws Exception
	{
		final Player seller = seller();
		final Item sword = seller.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, seller, null);
		final int warehouse = _f.open("GUILD");
		final int pool = _f.open("GUILD");
		_f.seed(pool, ADENA, 100, false);
		try (AssetTx tx = _f.service.begin(_f.op("EXCHANGE"), "GUILD", "EXCHANGE", seller.getObjectId()))
		{
			tx.exchange(seller.getObjectId(), List.of(new Asset(SHORT_SWORD, 1, sword.getObjectId())), warehouse, List.of(new Asset(ADENA, 600, null)), pool, "Merchant Guild of Giran", "Bought: Short Sword.");
			assertEquals(AssetAbort.Reason.INSUFFICIENT, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		assertEquals(0, _f.held(warehouse, SHORT_SWORD), "the goods did not go in");
		assertEquals(100, _f.held(pool, ADENA), "nothing was paid");
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_mail_delivery WHERE receiver_char = " + seller.getObjectId() + " AND reason = 'REFUND'"), "the sword, already taken, came back by mail");
	}
}
