/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * Every operation names a non-stackable item's lifecycle (docs/spec/asset-service.md §2.2, §11.35, ASSET-R07, AT-ASSET-07):
 * a whole-object leg begins it or binds to it, an amount of a non-stackable item in a custody moves as its whole objects,
 * a created non-stackable begins its own, a sink of a custody row ends it {@code DESTROYED}; a stack has none.
 */
class LifecycleLegTest
{
	private static final int SHORT_SWORD = 1;
	private static final int ADENA = 57;
	private static final int STEEL = 1880;
	private static AssetFixtures _f;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), Map.of("TEST", (connection, ref) -> true), id -> MailManager.getInstance().adopt(id));
	}

	@AfterEach
	void uninstall()
	{
		LifecycleWriter.install(null, false);
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

	private static Item sword(Player player)
	{
		final Item sword = player.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, player, null);
		sword.updateDatabase(true);
		return sword;
	}

	private static OpId fund(int custody, Player player, Asset asset) throws Exception
	{
		final OpId op = _f.op("FUND");
		try (AssetTx tx = _f.service.begin(op, "TEST", "FUND", custody))
		{
			tx.fund(custody, Holder.player(player.getObjectId()), List.of(asset));
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		return op;
	}

	private static OpId move(int from, int to, Asset asset) throws Exception
	{
		final OpId op = _f.op("MOVE");
		try (AssetTx tx = _f.service.begin(op, "TEST", "MOVE", from))
		{
			tx.move(from, to, List.of(asset));
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		return op;
	}

	/** {@code reason|began_op is this op|ended_op is this op} per lifecycle of the object, oldest first. */
	private static String lifecycles(int objectId, OpId began, OpId ended) throws Exception
	{
		return _f.text("SELECT GROUP_CONCAT(CONCAT_WS('|', IFNULL(end_reason, 'LIVE'), IFNULL(began_op = UNHEX('" + (began == null ? "00" : began.hex()) + "'), 0), IFNULL(ended_op = UNHEX('" + (ended == null ? "00" : ended.hex()) + "'), 0)) ORDER BY lifecycle_id SEPARATOR ';') FROM lc_item_lifecycle WHERE object_id = " + objectId);
	}

	private static long live(int objectId) throws Exception
	{
		return _f.number("SELECT IFNULL(MAX(lifecycle_id), 0) FROM lc_item_lifecycle WHERE object_id = " + objectId + " AND ended_at IS NULL");
	}

	private static long legsNaming(int objectId, long lifecycle) throws Exception
	{
		return _f.number("SELECT COUNT(*) FROM lc_ledger_posting WHERE object_id = " + objectId + " AND lifecycle_id = " + lifecycle);
	}

	@Test
	void aFundBeginsANonStackablesLifecycleNamedByBothLegsAndItsFact() throws Exception
	{
		final Player player = inWorld();
		final Item sword = sword(player);
		final int custody = _f.open("TEST");
		final OpId funded = fund(custody, player, new Asset(SHORT_SWORD, 1, sword.getObjectId()));
		assertEquals("LIVE|1|0", lifecycles(sword.getObjectId(), funded, null), "begun by the fund");
		final long lifecycle = live(sword.getObjectId());
		assertEquals(2, legsNaming(sword.getObjectId(), lifecycle), "the debit and the credit name it");
		assertTrue(_f.lastPayload("EscrowFunded", custody).contains("\"lifecycle\":" + lifecycle), _f.lastPayload("EscrowFunded", custody));
	}

	@Test
	void aMoveAndAReleaseKeepTheLifecycle() throws Exception
	{
		final Player player = inWorld();
		final Item sword = sword(player);
		final int first = _f.open("TEST");
		final int second = _f.open("TEST");
		final OpId funded = fund(first, player, new Asset(SHORT_SWORD, 1, sword.getObjectId()));
		final long lifecycle = live(sword.getObjectId());
		move(first, second, new Asset(SHORT_SWORD, 1, sword.getObjectId()));
		try (AssetTx tx = _f.service.begin(_f.op("RELEASE"), "TEST", "RELEASE", second))
		{
			tx.release(second, player.getObjectId(), List.of(new Asset(SHORT_SWORD, 1, sword.getObjectId())), MailReason.PAYOUT, "Notarius of Giran", "Your contract's escrow.");
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		assertEquals("LIVE|1|0", lifecycles(sword.getObjectId(), funded, null), "one lifecycle, still the fund's");
		assertEquals(6, legsNaming(sword.getObjectId(), lifecycle), "fund, move and release: two legs each");
	}

	@Test
	void anAmountOfANonStackableInACustodyMovesAsItsWholeObjects() throws Exception
	{
		final int first = _f.open("TEST");
		final int second = _f.open("TEST");
		final int a = _f.seed(first, SHORT_SWORD, 1, true);
		final int b = _f.seed(first, SHORT_SWORD, 1, true);
		final OpId moved = move(first, second, new Asset(SHORT_SWORD, 2, null));
		assertEquals(second + "|" + second, _f.text("SELECT GROUP_CONCAT(loc_data ORDER BY object_id SEPARATOR '|') FROM items WHERE object_id IN (" + a + ", " + b + ")"), "the same objects, now in the second custody");
		assertEquals(0, _f.number("SELECT COUNT(*) FROM items WHERE loc = 'ESCROW' AND loc_data = " + second + " AND object_id NOT IN (" + a + ", " + b + ")"), "no new object");
		assertEquals("LIVE|1|0", lifecycles(a, moved, null));
		assertEquals("LIVE|1|0", lifecycles(b, moved, null));
	}

	@Test
	void aPartOfAStackNamesNoLifecycle() throws Exception
	{
		final Player player = inWorld();
		player.getInventory().addItem(ItemProcessType.NONE, ADENA, 1000, player, null);
		final int custody = _f.open("TEST");
		final long before = _f.number("SELECT COUNT(*) FROM lc_item_lifecycle WHERE item_id = " + ADENA);
		fund(custody, player, new Asset(ADENA, 100, null));
		assertEquals(before, _f.number("SELECT COUNT(*) FROM lc_item_lifecycle WHERE item_id = " + ADENA));
		assertEquals(0, _f.number("SELECT COUNT(*) FROM lc_ledger_posting WHERE account_kind = 'CUSTODY' AND account_id = " + custody + " AND lifecycle_id IS NOT NULL"));
	}

	@Test
	void aCraftInputEndsItsLifecycleAndItsProductBeginsOne() throws Exception
	{
		final Player player = inWorld();
		final Item sword = sword(player);
		final int custody = _f.open("TEST");
		fund(custody, player, new Asset(SHORT_SWORD, 1, sword.getObjectId()));
		_f.seed(custody, STEEL, 10, false);
		final OpId crafted = _f.op("CRAFT");
		try (AssetTx tx = _f.service.begin(crafted, "TEST", "CRAFT", custody))
		{
			tx.craft(custody, 1, List.of(new Asset(SHORT_SWORD, 1, null), new Asset(STEEL, 10, null)), player.getObjectId(), new ItemHolder(SHORT_SWORD, 1), "Notarius of Giran", "Your contract's craft.");
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		assertEquals("DESTROYED|0|1", lifecycles(sword.getObjectId(), crafted, crafted), "the material's lifecycle ends with its row");
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = 'Asset.LifecycleEnded' AND JSON_VALUE(o.payload, '$.object') = " + sword.getObjectId()));
		final int product = (int) _f.number("SELECT p.object_id FROM lc_ledger_posting p WHERE p.op_id = UNHEX('" + crafted.hex() + "') AND p.code = 'CRAFT_OUTPUT'");
		assertEquals("LIVE|1|0", lifecycles(product, crafted, null), "the product begins its own");
	}

	@Test
	void aCaptureAndAnOperationOfOneObjectShareOneLifecycle() throws Exception
	{
		final LifecycleWriter writer = new LifecycleWriter(_f.service, 1, LifecycleWriter.DEFAULT_CAPACITY);
		LifecycleWriter.install(writer, true);
		final Player player = inWorld();
		final Item sword = sword(player);
		assertTrue(LifecycleWriter.capture(sword, ItemProcessType.PICKUP, 0));
		for (int i = 0; (i < 400) && !writer.idle(); i++)
		{
			Thread.sleep(25);
		}
		final long tracked = live(sword.getObjectId());
		assertTrue(tracked > 0, "begun by the capture");
		final int custody = _f.open("TEST");
		fund(custody, player, new Asset(SHORT_SWORD, 1, sword.getObjectId()));
		assertEquals(tracked, live(sword.getObjectId()), "the fund binds to it");
		assertEquals(2, legsNaming(sword.getObjectId(), tracked));
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_item_lifecycle WHERE object_id = " + sword.getObjectId()));
	}
}
