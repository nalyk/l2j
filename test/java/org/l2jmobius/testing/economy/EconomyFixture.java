package org.l2jmobius.testing.economy;

import java.util.function.Consumer;

import org.l2jmobius.gameserver.entity.item.enums.ItemLocation;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.livingchronicle.asset.EconomyCounter;
import org.l2jmobius.gameserver.managers.ItemManager;

/**
 * Never run: compiled only so {@link SiteScanTest} can find one site of each kind with its file and member.
 */
final class EconomyFixture
{
	private static final String PURGE = "DELETE FROM items WHERE item_id = ?";

	private EconomyFixture()
	{
	}

	static Item mints()
	{
		return new Item(1, 57);
	}

	static void creates()
	{
		ItemManager.createItem(ItemProcessType.NONE, 57, 1, null, null);
	}

	static void destroys(Item item)
	{
		ItemManager.destroyItem(ItemProcessType.NONE, item, null, null);
	}

	static Consumer<Item> grows()
	{
		return item -> item.changeCount(ItemProcessType.NONE, 1, null, null); // in a lambda: still the member "grows"
	}

	static void sets(Item item)
	{
		item.setCount(5);
		item.setCount(6); // two calls of one kind in one member: one site
	}

	static String purges()
	{
		return PURGE; // an inlined constant: an SQL site of this member
	}

	static String inserts()
	{
		return "INSERT INTO items (owner_id,item_id,count) VALUES (?,?,?)";
	}

	static String treasury()
	{
		return "UPDATE castle SET treasury = ? WHERE id = ?";
	}

	static String bids()
	{
		return "DELETE FROM auction_bid WHERE auctionId=?";
	}

	static String ground()
	{
		return "DELETE FROM itemsonground";
	}

	static String notACensusTable()
	{
		return "UPDATE castle SET taxPercent = ? WHERE id = ?"; // the castle row, but not its treasury: no site
	}

	static void hooks()
	{
		EconomyCounter.post(57, 1); // a counter hook: a classified site
	}

	static boolean onlyAsks()
	{
		return EconomyCounter.isActive(); // asking is not counting: no site
	}

	static void relocates(Item item)
	{
		item.setItemLocation(ItemLocation.VOID); // a location change can enter or leave the counted holdings
	}

	static void harmless(Item item)
	{
		item.getCount();
	}
}
