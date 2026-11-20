package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.l2jmobius.gameserver.entity.itemcontainer.PlayerInventory;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * {@code fund} of whole objects (docs/spec/asset-service.md §3.3 step 1, §4.3, §4.4, ASSET-R07, R20): a live
 * character's item leaves its inventory under Mobius's own locks, is retired, and becomes an {@code ESCROW} row written
 * from memory (unsaved changes included) in the operation's transaction; an ineligible item is refused with nothing
 * moved. AT-ASSET-01 (fund), AT-ASSET-07 (whole objects), AT-ASSET-18.
 */
class FundWholeObjectTest
{
	/** A tradeable weapon: the Squire's Sword (2369) is a starter item, not tradeable, so §4.4 refuses it. */
	private static final int SHORT_SWORD = 1;
	/** A quest item: Darin's Letter (Q00001, is_questitem). */
	private static final int QUEST_ITEM = 687;
	/** A hero weapon, deleted by item id when heroes change (Hero.java:81). */
	private static final int HERO_WEAPON = 6611;
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

	private static Outcome fund(int custody, Player player, Item item) throws Exception
	{
		try (AssetTx tx = _f.service.begin(_f.op("FUND"), "TEST", "FUND", custody))
		{
			tx.fund(custody, Holder.player(player.getObjectId()), List.of(new Asset(item.getId(), item.getCount(), item.getObjectId())));
			return tx.commit();
		}
	}

	@Test
	void aFundedWholeObjectBecomesAnEscrowRowWithItsUnsavedState() throws Exception
	{
		final Player player = inWorld();
		final Item sword = player.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, player, null);
		sword.updateDatabase(true);
		sword.setEnchantLevel(3); // changed in memory, not yet saved
		final int custody = _f.open("TEST");
		assertEquals(Outcome.COMMITTED, fund(custody, player, sword));
		assertNull(player.getInventory().getItemByObjectId(sword.getObjectId()), "it left the inventory");
		assertTrue(sword.isRetired());
		assertEquals("-1|ESCROW|" + custody + "|3", _f.text("SELECT CONCAT_WS('|', owner_id, loc, loc_data, enchant_level) FROM items WHERE object_id = " + sword.getObjectId()));
		assertEquals(1, _f.posted(custody, SHORT_SWORD));
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_ledger_posting WHERE account_kind = 'PLAYER' AND account_id = " + player.getObjectId() + " AND object_id = " + sword.getObjectId() + " AND direction = 'DEBIT'"));
		assertEquals(0, _f.number("SELECT COUNT(*) FROM lc_asset_pending WHERE char_id = " + player.getObjectId()), "the pending mark went with the commit");
		assertEquals(1, _f.facts("EscrowFunded", custody));
		final PlayerInventory reloaded = new PlayerInventory(player);
		reloaded.restore();
		assertNull(reloaded.getItemByObjectId(sword.getObjectId()), "a re-login does not load it");
	}

	@Test
	void ineligibleItemsAreRefusedWithNothingMoved() throws Exception
	{
		final Player player = inWorld();
		final Item equipped = player.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, player, null);
		player.getInventory().equipItem(equipped);
		final Item quest = player.getInventory().addItem(ItemProcessType.NONE, QUEST_ITEM, 1, player, null);
		final Item hero = player.getInventory().addItem(ItemProcessType.NONE, HERO_WEAPON, 1, player, null);
		final int custody = _f.open("TEST");
		for (Item item : List.of(equipped, quest, hero))
		{
			final AssetAbort refused = assertThrows(AssetAbort.class, () -> fund(custody, player, item), item.getName());
			assertEquals(AssetAbort.Reason.INELIGIBLE, refused.reason(), item.getName());
			assertSame(item, player.getInventory().getItemByObjectId(item.getObjectId()), item.getName() + " stays");
			assertFalse(item.isRetired(), item.getName());
		}
		assertTrue(equipped.isEquipped());
		assertEquals(0, _f.held(custody, SHORT_SWORD));
		assertEquals(0, _f.number("SELECT COUNT(*) FROM lc_asset_pending WHERE char_id = " + player.getObjectId()), "no mark left behind");
	}

	@Test
	void aCharacterNotInTheWorldCannotBeDebited() throws Exception
	{
		final Player player = inWorld();
		final Item sword = player.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, player, null);
		World.removeObject(player);
		final int custody = _f.open("TEST");
		assertEquals(AssetAbort.Reason.HOLDER_ABSENT, assertThrows(AssetAbort.class, () -> fund(custody, player, sword)).reason());
		assertFalse(sword.isRetired());
	}
}
