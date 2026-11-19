package org.l2jmobius.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.item.Armor;
import org.l2jmobius.gameserver.entity.item.EtcItem;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.enums.BodyPart;
import org.l2jmobius.gameserver.entity.item.type.ArmorType;
import org.l2jmobius.gameserver.entity.item.type.CrystalType;
import org.l2jmobius.gameserver.entity.item.type.EtcItemType;
import org.l2jmobius.gameserver.entity.item.type.WeaponType;
import org.l2jmobius.testing.TestGear.Piece;
import org.l2jmobius.testing.TestGear.Weapon;
import org.l2jmobius.testing.TestGear.Wear;

/**
 * The plain gear of every grade, taken from the datapack itself rather than listed by hand: per armour type the lowest armour set of the grade, per kind of weapon the lowest plain weapon of the grade (no special ability, no PvP, no shadow or common copy, nothing that expires), with the grade's arrows or bolts for a bow or crossbow.
 * <p>
 * A datapack change to what the plainest gear of a grade is shows in the locks that wear it.
 * </p>
 */
public final class GradeGear
{
	/** The grades a character's expertise reaches, lowest first, with the level it reaches each at. */
	public static final Map<CrystalType, Integer> FIRST_LEVEL = new LinkedHashMap<>();
	static
	{
		FIRST_LEVEL.put(CrystalType.D, 20);
		FIRST_LEVEL.put(CrystalType.C, 40);
		FIRST_LEVEL.put(CrystalType.B, 52);
		FIRST_LEVEL.put(CrystalType.A, 61);
		FIRST_LEVEL.put(CrystalType.S, 76);
		FIRST_LEVEL.put(CrystalType.S80, 80);
		FIRST_LEVEL.put(CrystalType.S84, 84);
	}

	private static final Path ARMOR_SETS = Path.of("data", "stats", "armorsets");

	/** Set files that hold copies or clan and event sets rather than a grade's own. */
	private static final List<String> NOT_A_GRADES_OWN = List.of("pvp", "clan", "friendship", "special", "no_grade");

	private GradeGear()
	{
	}

	/**
	 * @return per armour type of {@code grade} ("heavy", "light", "robe"), the pieces of its lowest set: chest, then the first legs, head, gloves and feet the set names.
	 */
	public static Map<String, Wear> armours(CrystalType grade) throws IOException
	{
		final Map<ArmorType, TreeMap<Integer, List<Piece>>> byType = new EnumMap<>(ArmorType.class);
		try (var files = Files.list(ARMOR_SETS))
		{
			for (Path file : files.filter(path -> path.toString().endsWith(".xml")).sorted().toList())
			{
				final String name = file.getFileName().toString();
				if (NOT_A_GRADES_OWN.stream().anyMatch(name::contains))
				{
					continue;
				}
				final Document document;
				try
				{
					document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
				}
				catch (Exception e)
				{
					throw new IOException("cannot read " + file, e);
				}
				final NodeList sets = document.getElementsByTagName("set");
				for (int i = 0; i < sets.getLength(); i++)
				{
					final Element set = (Element) sets.item(i);
					final List<Piece> pieces = new ArrayList<>();
					for (String part : List.of("chest", "legs", "head", "gloves", "feet"))
					{
						final Node first = set.getElementsByTagName(part).item(0);
						if (first != null)
						{
							pieces.add(new Piece(Integer.parseInt(((Element) first).getAttribute("id")), 1));
						}
					}
					if (pieces.isEmpty() || !(ItemData.getInstance().getTemplate(pieces.get(0).itemId()) instanceof Armor chest) || (chest.getCrystalType() != grade))
					{
						continue;
					}
					byType.computeIfAbsent(chest.getItemType(), type -> new TreeMap<>()).putIfAbsent(Integer.parseInt(set.getAttribute("id")), List.copyOf(pieces));
				}
			}
		}

		final Map<String, Wear> armours = new LinkedHashMap<>();
		for (Map.Entry<ArmorType, String> type : Map.of(ArmorType.HEAVY, "heavy", ArmorType.LIGHT, "light", ArmorType.MAGIC, "robe").entrySet().stream().sorted(Map.Entry.comparingByValue()).toList())
		{
			final TreeMap<Integer, List<Piece>> sets = byType.get(type.getKey());
			if (sets != null)
			{
				armours.put(type.getValue(), new Wear(type.getValue(), sets.firstEntry().getValue()));
			}
		}
		return armours;
	}

	/**
	 * @return per kind of weapon of {@code grade}, by name ("sword", "two-handed sword", ...), its lowest plain weapon, with ammunition when it fires any.
	 */
	public static Map<String, Weapon> weapons(CrystalType grade)
	{
		final Map<String, Weapon> weapons = new TreeMap<>();
		int arrow = 0;
		int bolt = 0;
		for (ItemTemplate item : ItemData.getInstance().getAllItems())
		{
			if ((item == null) || (item.getCrystalType() != grade))
			{
				continue;
			}
			if (item instanceof EtcItem ammunition)
			{
				if ((arrow == 0) && (ammunition.getItemType() == EtcItemType.ARROW))
				{
					arrow = item.getId();
				}
				else if ((bolt == 0) && (ammunition.getItemType() == EtcItemType.BOLT))
				{
					bolt = item.getId();
				}
			}
		}
		for (ItemTemplate item : ItemData.getInstance().getAllItems())
		{
			if (!(item instanceof org.l2jmobius.gameserver.entity.item.Weapon weapon) || (item.getCrystalType() != grade) || !plain(item))
			{
				continue;
			}
			final String kind = kind(weapon);
			if ((kind == null) || weapons.containsKey(kind))
			{
				continue;
			}
			final List<Piece> pieces = new ArrayList<>();
			pieces.add(new Piece(item.getId(), 1));
			if ((weapon.getItemType() == WeaponType.BOW) && (arrow != 0))
			{
				pieces.add(new Piece(arrow, 1000));
			}
			else if ((weapon.getItemType() == WeaponType.CROSSBOW) && (bolt != 0))
			{
				pieces.add(new Piece(bolt, 1000));
			}
			weapons.put(kind, new Weapon(kind, weapon.getBodyPart() == BodyPart.R_HAND, List.copyOf(pieces)));
		}
		return weapons;
	}

	/**
	 * A weapon a player buys or crafts as it is: not a copy with a special ability, for PvP, with a shadow's mana, common or timed, nor a quest item.
	 */
	private static boolean plain(ItemTemplate item)
	{
		final String name = item.getName();
		return !name.contains(" - ") && !name.contains("PvP") && !name.contains("Shadow") && !name.startsWith("Common") && !item.isPvpItem() && !item.isQuestItem() && (item.getDuration() <= 0) && (item.getTime() <= 0);
	}

	/**
	 * @return the kind of {@code weapon} as the locks name it, {@code null} for none a player fights with.
	 */
	private static String kind(org.l2jmobius.gameserver.entity.item.Weapon weapon)
	{
		final boolean twoHanded = weapon.getBodyPart() == BodyPart.LR_HAND;
		return switch (weapon.getItemType())
		{
			case SWORD -> twoHanded ? "two-handed sword" : "sword";
			case BLUNT -> twoHanded ? "two-handed blunt" : "blunt";
			case DAGGER -> "dagger";
			case DUALDAGGER -> "dual daggers";
			case DUAL -> "dual swords";
			case DUALFIST -> "fists";
			case POLE -> "polearm";
			case BOW -> "bow";
			case CROSSBOW -> "crossbow";
			case RAPIER -> "rapier";
			case ANCIENTSWORD -> "ancient sword";
			default -> null;
		};
	}
}
