package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Debits of part of a stack (docs/spec/asset-service.md §3.3 step 2, §4.3 {@code fund}, {@code charge}): taken atomically
 * under the stack's monitor and write lock, held to {@code DONE}, never its last unit; the stack's row written from memory
 * in the operation's transaction (inserted if it never existed); concurrent retail transfers lose nothing.
 * AT-ASSET-05, AT-ASSET-06 (stack debits), AT-ASSET-01 (charge).
 */
class StackDebitTest
{
	private static AssetFixtures _f;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), Map.of("TEST", (connection, ref) -> true), id -> MailManager.getInstance().adopt(id));
	}

	private static Player inWorld(long adena)
	{
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 40);
		if (World.getPlayer(player.getObjectId()) == null)
		{
			World.addObject(player);
		}
		player.getInventory().addItem(ItemProcessType.NONE, ADENA, adena, player, null);
		return player;
	}

	private static Outcome fundAdena(int custody, Player player, long amount) throws Exception
	{
		try (AssetTx tx = _f.service.begin(_f.op("FUND"), "TEST", "FUND", custody))
		{
			tx.fund(custody, Holder.player(player.getObjectId()), List.of(new Asset(ADENA, amount, null)));
			return tx.commit();
		}
	}

	private static long adenaRow(Player player) throws Exception
	{
		return _f.number("SELECT COALESCE(SUM(count), 0) FROM items WHERE owner_id = " + player.getObjectId() + " AND item_id = " + ADENA + " AND loc = 'INVENTORY'");
	}

	@Test
	void anAmountIsTakenFromTheStackAndItsRowWrittenFromMemory() throws Exception
	{
		final Player player = inWorld(10_000);
		player.getInventory().getAdenaInstance().updateDatabase(true);
		final int custody = _f.open("TEST");
		assertEquals(Outcome.COMMITTED, fundAdena(custody, player, 3_000));
		assertEquals(7_000, player.getAdena());
		assertEquals(7_000, adenaRow(player), "the stack's row says what memory says");
		assertEquals(3_000, _f.held(custody, ADENA));
		assertEquals(3_000, _f.posted(custody, ADENA));
		assertEquals(3_000, _f.number("SELECT qty FROM lc_ledger_posting WHERE account_kind = 'PLAYER' AND account_id = " + player.getObjectId() + " AND direction = 'DEBIT'"));
		assertEquals(0, _f.number("SELECT COUNT(*) FROM lc_asset_pending WHERE char_id = " + player.getObjectId()));
	}

	@Test
	void theLastUnitIsNeverTakenAsAnAmount() throws Exception
	{
		final Player player = inWorld(500);
		final int custody = _f.open("TEST");
		assertEquals(AssetAbort.Reason.INSUFFICIENT, assertThrows(AssetAbort.class, () -> fundAdena(custody, player, 500)).reason(), "a whole stack is a whole-object debit");
		assertEquals(500, player.getAdena());
		assertEquals(0, _f.number("SELECT COUNT(*) FROM lc_asset_pending WHERE char_id = " + player.getObjectId()));
	}

	@Test
	void aStackNeverSavedGetsItsRowInTheOperationAndMobiusKnowsIt() throws Exception
	{
		final Player player = inWorld(2_000); // added in memory, not yet stored
		final Item stack = player.getInventory().getAdenaInstance();
		final int custody = _f.open("TEST");
		fundAdena(custody, player, 600);
		assertEquals(1_400, adenaRow(player), "the row exists now, with the debited count");
		stack.changeCount(ItemProcessType.NONE, 100, null, null);
		stack.updateDatabase(true);
		assertEquals(1_500, adenaRow(player), "Mobius updates the row it now knows exists, never inserting a second");
	}

	@Test
	void aFeeIsChargedIntoItsSink() throws Exception
	{
		final Player player = inWorld(1_000);
		final OpId op = _f.op("CHARGE");
		try (AssetTx tx = _f.service.begin(op, "PACT", "CHARGE", 1))
		{
			tx.charge(Holder.player(player.getObjectId()), 250, SinkCode.FEE_NOTARIUS);
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		assertEquals(750, player.getAdena());
		assertEquals(250, _f.number("SELECT qty FROM lc_ledger_posting WHERE op_id = UNHEX('" + op.hex() + "') AND account_kind = 'SINK' AND code = 'FEE_NOTARIUS'"));
	}

	@Test
	void debitsRacingRetailTransfersLoseNothing() throws Exception
	{
		final Player player = inWorld(100_000);
		final int custody = _f.open("TEST");
		final AtomicInteger failures = new AtomicInteger();
		final Thread retail = new Thread(() ->
		{
			for (int i = 0; i < 2_000; i++)
			{
				final Item adena = player.getInventory().getAdenaInstance();
				if ((adena == null) || (player.getInventory().transferItem(ItemProcessType.TRANSFER, adena.getObjectId(), 1, player.getWarehouse(), player, null) == null))
				{
					failures.incrementAndGet();
				}
			}
		});
		retail.start();
		for (int i = 0; i < 20; i++)
		{
			fundAdena(custody, player, 10);
		}
		retail.join();
		final Item stored = player.getWarehouse().getItemByItemId(ADENA);
		final long warehouse = stored == null ? 0 : stored.getCount();
		assertEquals(100_000, player.getAdena() + warehouse + _f.held(custody, ADENA), "every unit is somewhere, exactly once");
		assertEquals(0, failures.get(), "no retail transfer was refused or lost");
		assertEquals(200, _f.held(custody, ADENA));
	}
}
