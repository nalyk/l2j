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

import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.managers.ItemManager;
import org.l2jmobius.gameserver.taskmanagers.ItemsAutoDestroyTaskManager;

/**
 * A raided share, dropped once after its operation's commit is known (docs/spec/asset-service.md §4.3 {@code raid},
 * ASSET-R25): each object is built new, unsaved and uncounted from what its custody row held ({@link Item#rebuilt},
 * CP-0035) and falls at the Master's place and instance as the raid found them, the way a kill's drop falls there
 * ({@code Npc.dropItemAt}): validated from that place, refused by an instance that is gone (then destroyed at once),
 * given to the auto-destroy task as retail's drops are. The ground and its pickup count it as any drop (§2.6).
 */
final class RaidShare
{
	/** What one share object's row held: a non-stackable's whole state, or a stackable's item id and quantity. */
	record Snapshot(int objectId, int itemId, long count, int enchantLevel, int customType1, int customType2, int manaLeft, long time, int augmentation, int[][] elementals, Map<String, String> variables)
	{
		static Snapshot amount(int objectId, int itemId, long count)
		{
			return new Snapshot(objectId, itemId, count, 0, 0, 0, -1, -1, -1, new int[0][], Map.of());
		}
	}

	/** The spread of a share around the Master's place, a fixed ring (no draw: Living Chronicle code draws only keyed). */
	private static final int[][] RING =
	{
		{ 0, 0 }, { 40, 0 }, { 0, 40 }, { -40, 0 }, { 0, -40 }, { 30, 30 }, { -30, 30 }, { -30, -30 }, { 30, -30 }
	};

	private RaidShare()
	{
	}

	/** Drops the share: called once, by the raid's own thread, only when its commit is known. */
	static void drop(List<Snapshot> share, Npc master, int x, int y, int z, int instanceId)
	{
		for (int i = 0; i < share.size(); i++)
		{
			final Snapshot snapshot = share.get(i);
			final Item item = Item.rebuilt(snapshot.objectId(), snapshot.itemId(), snapshot.count(), snapshot.enchantLevel(), snapshot.customType1(), snapshot.customType2(), snapshot.manaLeft(), snapshot.time(), snapshot.augmentation(), snapshot.elementals());
			if (item == null)
			{
				continue;
			}
			for (Map.Entry<String, String> variable : snapshot.variables().entrySet())
			{
				item.getVariables().set(variable.getKey(), variable.getValue(), false); // in memory, as a retail drop keeps them (its rows deleted, Item.java:1763)
			}
			World.addObject(item);
			final int[] offset = RING[i % RING.length];
			item.dropMeFrom(master, x, y, z, instanceId, true, x + offset[0], y + offset[1], z + 20);
			if (!item.isSpawned())
			{
				ItemManager.destroyItem(ItemProcessType.LOOT, item, null, master); // its instance is gone, as a held kill's drop
				continue;
			}
			if (!GeneralConfig.LIST_PROTECTED_ITEMS.contains(item.getId()) && (((GeneralConfig.AUTODESTROY_ITEM_AFTER > 0) && !item.getTemplate().hasExImmediateEffect()) || ((GeneralConfig.HERB_AUTO_DESTROY_TIME > 0) && item.getTemplate().hasExImmediateEffect())))
			{
				ItemsAutoDestroyTaskManager.getInstance().addItem(item);
			}
			item.setProtected(false);
		}
	}
}
