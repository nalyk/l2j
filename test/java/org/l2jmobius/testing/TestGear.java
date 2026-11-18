package org.l2jmobius.testing;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;

/**
 * The gear lock and truth cases dress characters in: the plain Vesper set, the S84 grade a level 85 character of every class can use without penalty, with no special ability on any piece.
 */
public final class TestGear
{
	/**
	 * Some count of one item.
	 */
	public record Piece(int itemId, long count)
	{
		Piece(int itemId)
		{
			this(itemId, 1);
		}
	}

	/**
	 * A weapon, with the ammunition it fires if it fires any.
	 */
	public record Weapon(String name, boolean oneHanded, List<Piece> pieces)
	{
	}

	/**
	 * Something worn: an armour set with its jewels, or a shield or sigil.
	 */
	public record Wear(String name, List<Piece> pieces)
	{
	}

	/** Every weapon type of the game, in the order lock cases try them. A bow comes with Shining Arrows, a crossbow with Shining Bolts. */
	public static final List<Weapon> WEAPONS = List.of( //
		new Weapon("sword", true, List.of(new Piece(13457))), // Vesper Cutter
		new Weapon("two-handed sword", false, List.of(new Piece(13458))), // Vesper Slasher
		new Weapon("blunt", true, List.of(new Piece(13463))), // Vesper Avenger
		new Weapon("two-handed blunt", false, List.of(new Piece(13464))), // Vesper Retributer
		new Weapon("dagger", true, List.of(new Piece(13460))), // Vesper Shaper
		new Weapon("dual daggers", false, List.of(new Piece(13884))), // Vesper Dual Daggers
		new Weapon("dual swords", false, List.of(new Piece(52))), // Vesper Dual Sword
		new Weapon("fists", false, List.of(new Piece(13461))), // Vesper Fighter
		new Weapon("polearm", false, List.of(new Piece(13462))), // Vesper Stormer
		new Weapon("bow", false, List.of(new Piece(13467), new Piece(1345, 1000))), // Vesper Thrower, Shining Arrow
		new Weapon("crossbow", false, List.of(new Piece(13469), new Piece(9637, 1000))), // Vesper Shooter, Shining Bolt
		new Weapon("rapier", true, List.of(new Piece(13468))), // Vesper Pincer
		new Weapon("ancient sword", false, List.of(new Piece(13470)))); // Vesper Nagan

	public static final Wear SHIELD = new Wear("shield", List.of(new Piece(13471))); // Vesper Shield
	public static final Wear SIGIL = new Wear("sigil", List.of(new Piece(12813))); // Vesper Sigil

	private static final List<Piece> JEWELS = List.of(new Piece(14163), new Piece(14163), new Piece(14164), new Piece(14165), new Piece(14165)); // Vesper Earring x2, Necklace, Ring x2

	/** Helmet, chest, legs, gloves and boots of the Vesper heavy, light and robe sets (armorsets 143, 144, 145), with the Vesper jewels. */
	public static final Wear HEAVY = new Wear("heavy", withJewels(13137, 13432, 13438, 13439, 13440));
	public static final Wear LIGHT = new Wear("light", withJewels(13138, 13433, 13441, 13442, 13443));
	public static final Wear ROBE = new Wear("robe", withJewels(13139, 13434, 13444, 13445, 13446));

	private TestGear()
	{
	}

	private static List<Piece> withJewels(int... armour)
	{
		final List<Piece> pieces = new ArrayList<>();
		for (int itemId : armour)
		{
			pieces.add(new Piece(itemId));
		}
		pieces.addAll(JEWELS);
		return List.copyOf(pieces);
	}

	/**
	 * Takes off everything {@code player} wears and puts on exactly {@code pieces}, in order, through the inventory's own equip, so set bonuses, item skills and mastery conditions follow as they do in play. Items it lacks are added to its inventory first.
	 */
	public static void wear(Player player, List<Piece> pieces)
	{
		final Inventory inventory = player.getInventory();
		for (int slot = 0; slot < Inventory.PAPERDOLL_TOTALSLOTS; slot++)
		{
			inventory.unEquipItemInSlot(slot);
		}

		for (Piece piece : pieces)
		{
			Item item = null;
			for (Item candidate : inventory.getAllItemsByItemId(piece.itemId()))
			{
				if (!candidate.isEquipped())
				{
					item = candidate;
					break;
				}
			}
			if (item == null)
			{
				item = inventory.addItem(ItemProcessType.NONE, piece.itemId(), piece.count(), player, null);
			}
			inventory.equipItem(item);
		}
	}
}
