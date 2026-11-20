package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

import org.l2jmobius.gameserver.entity.item.holders.Elementals;
import org.l2jmobius.gameserver.entity.item.instance.Item;

/**
 * Writes a taken whole object's row and its metadata rows from memory, in the operation's transaction
 * (docs/spec/asset-service.md §2.3, §3.3): every column as it was in memory when the operation took it, unsaved changes
 * included, so nothing Mobius would have written later is lost (ASSET-R07).
 */
final class ItemRows
{
	private ItemRows()
	{
	}

	/** A stack's row as Mobius would write it now (§3.3 step 2): where it is and its count in memory, inserted if absent. */
	static void writeStack(Connection connection, Item stack) throws SQLException
	{
		writeRow(connection, stack, stack.getOwnerId(), stack.getItemLocation().name(), stack.getLocationSlot());
	}

	static void write(Connection connection, Item item, int ownerId, String loc, int locData, Map<String, String> variables) throws SQLException
	{
		writeRow(connection, item, ownerId, loc, locData);
		writeMetadata(connection, item, variables);
	}

	private static void writeRow(Connection connection, Item item, int ownerId, String loc, int locData) throws SQLException
	{
		try (PreparedStatement row = connection.prepareStatement("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data, custom_type1, custom_type2, mana_left, time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE owner_id = VALUES(owner_id), item_id = VALUES(item_id), count = VALUES(count), enchant_level = VALUES(enchant_level), loc = VALUES(loc), loc_data = VALUES(loc_data), custom_type1 = VALUES(custom_type1), custom_type2 = VALUES(custom_type2), mana_left = VALUES(mana_left), time = VALUES(time)"))
		{
			row.setInt(1, ownerId);
			row.setInt(2, item.getObjectId());
			row.setInt(3, item.getId());
			row.setLong(4, item.getCount());
			row.setInt(5, item.getEnchantLevel());
			row.setString(6, loc);
			row.setInt(7, locData);
			row.setInt(8, item.getCustomType1());
			row.setInt(9, item.getCustomType2());
			row.setInt(10, item.getMana());
			row.setLong(11, item.getTime());
			row.executeUpdate();
		}
	}

	private static void writeMetadata(Connection connection, Item item, Map<String, String> variables) throws SQLException
	{
		delete(connection, "item_attributes", "itemId", item.getObjectId());
		if (item.getAugmentation() != null)
		{
			try (PreparedStatement attributes = connection.prepareStatement("INSERT INTO item_attributes (itemId, augAttributes) VALUES (?, ?)"))
			{
				attributes.setInt(1, item.getObjectId());
				attributes.setInt(2, item.getAugmentation().getAugmentationId());
				attributes.executeUpdate();
			}
		}
		delete(connection, "item_elementals", "itemId", item.getObjectId());
		if (item.getElementals() != null)
		{
			try (PreparedStatement elementals = connection.prepareStatement("INSERT INTO item_elementals (itemId, elemType, elemValue) VALUES (?, ?, ?)"))
			{
				for (Elementals elemental : item.getElementals())
				{
					elementals.setInt(1, item.getObjectId());
					elementals.setByte(2, elemental.getElement());
					elementals.setInt(3, elemental.getValue());
					elementals.executeUpdate();
				}
			}
		}
		delete(connection, "item_variables", "id", item.getObjectId());
		if (!variables.isEmpty())
		{
			try (PreparedStatement values = connection.prepareStatement("INSERT INTO item_variables (id, var, val) VALUES (?, ?, ?)"))
			{
				for (Map.Entry<String, String> variable : variables.entrySet())
				{
					values.setInt(1, item.getObjectId());
					values.setString(2, variable.getKey());
					values.setString(3, variable.getValue());
					values.executeUpdate();
				}
			}
		}
	}

	private static void delete(Connection connection, String table, String column, int objectId) throws SQLException
	{
		try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE " + column + " = ?"))
		{
			delete.setInt(1, objectId);
			delete.executeUpdate();
		}
	}
}
