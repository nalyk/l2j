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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * {@code raid} (docs/spec/asset-service.md §4.3, §2.5 {@code RAID_SHARE}, {@code ROAD_LOSS}, ASSET-R25, CP-0035; AT-ASSET-27,
 * part of AT-ASSET-31): share and rest exactly the manifest, every row deleted, the share dropped once after a known
 * commit at the Master's place, a non-stackable keeping its id, state and lifecycle, a stackable's share one new object.
 */
class RaidTest
{
	private static final int SHORT_SWORD = 1;
	private static final int STEEL = 1880;
	private static AssetFixtures _f;
	private static Monster _master;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), Map.of("TEST", (connection, ref) -> true), id -> MailManager.getInstance().adopt(id));
		final List<NpcTemplate> monsters = new ArrayList<>(NpcData.getInstance().getTemplates(t -> t.isType("Monster")));
		monsters.sort(Comparator.comparingInt(NpcTemplate::getId));
		_master = new Monster(monsters.get(0));
		_master.setXYZ(-84318, 244579, -3730); // Talking Island
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

	/** Four swords of one player funded (each with its lifecycle, one enchanted +3 with a variable), 100 steel in two rows. */
	private static int[] manifest(int custody, Player player) throws Exception
	{
		final int[] swords = new int[4];
		for (int i = 0; i < 4; i++)
		{
			final Item sword = player.getInventory().addItem(ItemProcessType.NONE, SHORT_SWORD, 1, player, null);
			sword.updateDatabase(true);
			if (i == 0)
			{
				sword.setEnchantLevel(3);
				sword.getVariables().set("lc_test_mark", "raided");
			}
			swords[i] = sword.getObjectId();
			try (AssetTx tx = _f.service.begin(_f.op("FUND"), "TEST", "FUND", custody))
			{
				tx.fund(custody, Holder.player(player.getObjectId()), List.of(new Asset(SHORT_SWORD, 1, sword.getObjectId())));
				assertEquals(Outcome.COMMITTED, tx.commit());
			}
		}
		_f.seed(custody, STEEL, 60, false);
		_f.seed(custody, STEEL, 40, false);
		return swords;
	}

	private static OpId raid(int custody, List<Asset> share, List<Asset> rest) throws Exception
	{
		final OpId op = _f.op("RAID");
		try (AssetTx tx = _f.service.begin(op, "TEST", "RAID", custody))
		{
			tx.raid(custody, share, rest, _master);
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		return op;
	}

	private static long rows(int custody) throws Exception
	{
		return _f.number("SELECT COUNT(*) FROM items WHERE owner_id = -1 AND loc = 'ESCROW' AND loc_data = " + custody);
	}

	@Test
	void aRaidDropsItsShareOnceAfterTheCommitAndLosesTheRestToTheRoad() throws Exception
	{
		final Player player = inWorld();
		final int custody = _f.open("TEST");
		final int[] swords = manifest(custody, player);
		final long lifecycle = _f.number("SELECT lifecycle_id FROM lc_item_lifecycle WHERE object_id = " + swords[0] + " AND ended_at IS NULL");
		// 300 per mille: 30 of 100 steel, 1 of 4 swords (the lowest id), as aden-remembers.md §4.6 computes it
		final List<Asset> share = List.of(new Asset(STEEL, 30, null), new Asset(SHORT_SWORD, 1, swords[0]));
		final List<Asset> rest = List.of(new Asset(STEEL, 70, null), new Asset(SHORT_SWORD, 1, swords[1]), new Asset(SHORT_SWORD, 1, swords[2]), new Asset(SHORT_SWORD, 1, swords[3]));
		raid(custody, share, rest);
		assertEquals(0, rows(custody), "every manifest row is gone");
		final WorldObject dropped = World.findObject(swords[0]);
		assertTrue((dropped instanceof Item sword) && sword.isSpawned(), "the share's sword lies on the ground, as itself");
		final Item sword = (Item) dropped;
		assertEquals(3, sword.getEnchantLevel(), "with its enchant");
		assertEquals("raided", sword.getVariables().getString("lc_test_mark", null), "and its variables");
		assertTrue(Math.abs(sword.getX() - _master.getX()) <= 100, "at the Master's place");
		assertEquals(lifecycle, _f.number("SELECT lifecycle_id FROM lc_item_lifecycle WHERE object_id = " + swords[0] + " AND ended_at IS NULL"), "its lifecycle stays live");
		for (int i = 1; i < 4; i++)
		{
			assertEquals("DESTROYED", _f.text("SELECT end_reason FROM lc_item_lifecycle WHERE object_id = " + swords[i]), "the rest is lost to the road");
			assertNull(World.findObject(swords[i]));
		}
		assertEquals(30, steelOnTheGround(), "the steel's share is one new object of 30");
		assertEquals(2, _f.number("SELECT COUNT(*) FROM lc_ledger_posting p JOIN lc_asset_op o ON o.op_id = p.op_id WHERE o.action = 'RAID' AND p.code = 'RAID_SHARE' AND p.account_kind = 'SINK' AND p.op_id = (SELECT op_id FROM lc_ledger_posting WHERE object_id = " + swords[0] + " AND code = 'RAID_SHARE')"));
		final Item picked = player.getInventory().addItem(ItemProcessType.PICKUP, sword, player, null);
		picked.updateDatabase(true);
		assertEquals(player.getObjectId() + "|3", _f.text("SELECT CONCAT_WS('|', owner_id, enchant_level) FROM items WHERE object_id = " + swords[0]), "its pickup saves it as a new row");
		assertEquals(0, _f.number("SELECT COUNT(*) FROM item_variables WHERE id = " + swords[0]), "its variables stay in memory, as a retail drop's do (Mobius deletes a dropped item's variable rows)");
	}

	private static long steelOnTheGround()
	{
		long steel = 0;
		for (WorldObject object : World.getVisibleObjects())
		{
			if ((object instanceof Item item) && (item.getId() == STEEL) && item.isSpawned() && (Math.abs(item.getX() - _master.getX()) <= 100))
			{
				steel += item.getCount();
			}
		}
		return steel;
	}

	@Test
	void aRaidThatIsNotExactlyTheManifestIsRefusedAndDropsNothing() throws Exception
	{
		final Player player = inWorld();
		final int custody = _f.open("TEST");
		final int[] swords = manifest(custody, player);
		final List<Asset> share = List.of(new Asset(STEEL, 30, null), new Asset(SHORT_SWORD, 1, swords[0]));
		final List<Asset> missingOne = List.of(new Asset(STEEL, 70, null), new Asset(SHORT_SWORD, 1, swords[1]), new Asset(SHORT_SWORD, 1, swords[2]));
		assertEquals(AssetAbort.Reason.INSUFFICIENT, assertThrows(AssetAbort.class, () -> raid(custody, share, missingOne)).reason());
		final List<Asset> steelTwice = List.of(new Asset(STEEL, 71, null), new Asset(SHORT_SWORD, 1, swords[1]), new Asset(SHORT_SWORD, 1, swords[2]), new Asset(SHORT_SWORD, 1, swords[3]));
		assertEquals(AssetAbort.Reason.INSUFFICIENT, assertThrows(AssetAbort.class, () -> raid(custody, share, steelTwice)).reason());
		final List<Asset> swordByAmount = List.of(new Asset(STEEL, 70, null), new Asset(SHORT_SWORD, 3, null));
		assertEquals(AssetAbort.Reason.INSUFFICIENT, assertThrows(AssetAbort.class, () -> raid(custody, share, swordByAmount)).reason());
		assertEquals(6, rows(custody), "nothing moved");
		assertNull(World.findObject(swords[0]), "nothing dropped: the rollback built nothing");
	}
}
