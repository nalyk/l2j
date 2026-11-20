package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.managers.CastleManager;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.mechanics.siege.Castle;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * Clan warehouses and castle treasuries as holders (docs/spec/asset-service.md §2.1, §3.3 step 3, §4.3 {@code fund},
 * {@code commit} / {@code return}, §4.4, CP-0020): a clan warehouse is debited in memory like a character's, its refund
 * mailed to the clan leader, and its script events fire only for operations that happen; a treasury is debited under its
 * write lock with its row written from memory, credited after commit, never past MAX_ADENA.
 */
class ClanAndTreasuryDebitTest
{
	private static final int SHORT_SWORD = 1;
	private static final AtomicInteger NAMES = new AtomicInteger();
	private static AssetFixtures _f;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), Map.of("TEST", (connection, ref) -> true), id -> MailManager.getInstance().adopt(id));
	}

	private static Clan clan()
	{
		final Player leader = TestPlayers.create(PlayerClass.FIGHTER, 40);
		if (World.getPlayer(leader.getObjectId()) == null)
		{
			World.addObject(leader);
		}
		final Clan clan = ClanTable.getInstance().createClan(leader, "Escrow" + NAMES.incrementAndGet());
		assertTrue(clan != null, "the test clan is created");
		return clan;
	}

	@Test
	void aClanWarehouseIsDebitedAndItsRefundGoesToTheLeader() throws Exception
	{
		final Clan clan = clan();
		final Item sword = clan.getWarehouse().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, null, null);
		final Item spare = clan.getWarehouse().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, null, null);
		final int custody = _f.open("TEST");
		try (AssetTx tx = _f.service.begin(_f.op("FUND"), "TEST", "FUND", custody))
		{
			tx.fund(custody, Holder.clanWarehouse(clan.getId()), List.of(new Asset(SHORT_SWORD, 1, sword.getObjectId())));
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		assertNull(clan.getWarehouse().getItemByObjectId(sword.getObjectId()));
		assertEquals("-1|ESCROW|" + custody, _f.text("SELECT CONCAT_WS('|', owner_id, loc, loc_data) FROM items WHERE object_id = " + sword.getObjectId()));
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_ledger_posting WHERE account_kind = 'CLAN_WAREHOUSE' AND account_id = " + clan.getId() + " AND direction = 'DEBIT'"));
		try (AssetTx first = _f.service.begin(_f.op("SETTLE"), "TEST", "SETTLE", custody))
		{
			first.settlementKey("TEST", "clan-" + clan.getId());
			first.commit();
		}
		try (AssetTx tx = _f.service.begin(_f.op("FUND"), "TEST", "FUND", custody))
		{
			tx.settlementKey("TEST", "clan-" + clan.getId());
			tx.fund(custody, Holder.clanWarehouse(clan.getId()), List.of(new Asset(SHORT_SWORD, 1, spare.getObjectId())));
			assertThrows(AssetAbort.class, tx::commit);
		}
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_mail_delivery WHERE receiver_char = " + clan.getLeaderId() + " AND reason = 'REFUND'"), "refunded to the clan leader, named in the operation");
	}

	@Test
	void clanWarehouseEventsFireOnlyForOperationsThatHappen() throws Exception
	{
		final Clan clan = clan();
		final Item sword = clan.getWarehouse().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, null, null);
		final AtomicInteger destroys = new AtomicInteger();
		final ConsumerEventListener listener = new ConsumerEventListener(Containers.Global(), EventType.ON_PLAYER_CLAN_WH_ITEM_DESTROY, event -> destroys.incrementAndGet(), this);
		sword.getTemplate().addListener(listener);
		try
		{
			sword.retire();
			assertNull(clan.getWarehouse().destroyItem(ItemProcessType.NONE, sword, 1, null, null), "refused");
			Thread.sleep(1_000); // events are dispatched asynchronously: give one every chance to arrive
			assertEquals(0, destroys.get(), "no event for an operation that did not happen");
			assertNull(clan.getWarehouse().transferItem(ItemProcessType.NONE, 2_000_000_001, 1, clan.getWarehouse(), null, null), "a missing object: null, not a NullPointerException");
		}
		finally
		{
			sword.getTemplate().removeListener(listener);
		}
	}

	private static Castle ownedCastle(long treasury) throws Exception
	{
		final Castle castle = CastleManager.getInstance().getCastleById(3);
		final Field owner = Castle.class.getDeclaredField("_ownerId");
		owner.setAccessible(true);
		owner.set(castle, 1);
		final Field field = Castle.class.getDeclaredField("_treasury");
		field.setAccessible(true);
		field.set(castle, treasury);
		return castle;
	}

	@Test
	void aTreasuryIsCommittedToACustodyAndReturned() throws Exception
	{
		final Castle castle = ownedCastle(10_000);
		final int custody = _f.open("TEST");
		try (AssetTx tx = _f.service.begin(_f.op("COMMIT"), "CROWN", "COMMIT", 3))
		{
			tx.commitTreasury(castle.getResidenceId(), custody, 4_000);
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		assertEquals(6_000, castle.getTreasury());
		assertEquals(6_000, _f.number("SELECT treasury FROM castle WHERE id = 3"), "the row from memory");
		assertEquals(4_000, _f.held(custody, ADENA));
		try (AssetTx tx = _f.service.begin(_f.op("RETURN"), "CROWN", "RETURN", 3))
		{
			tx.returnToTreasury(custody, castle.getResidenceId(), 1_500);
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		assertEquals(7_500, castle.getTreasury(), "credited after commit");
		assertEquals(7_500, _f.number("SELECT treasury FROM castle WHERE id = 3"));
		assertEquals(2_500, _f.held(custody, ADENA));
		try (AssetTx tx = _f.service.begin(_f.op("COMMIT"), "CROWN", "COMMIT", 3))
		{
			tx.commitTreasury(castle.getResidenceId(), custody, 7_501);
			assertEquals(AssetAbort.Reason.INSUFFICIENT, assertThrows(AssetAbort.class, tx::commit).reason(), "never overdrawn");
		}
		assertEquals(7_500, castle.getTreasury());
	}
}
