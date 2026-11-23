package org.l2jmobius.gameserver.livingchronicle.asset;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.EtcItem;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.managers.ScriptManager;
import org.l2jmobius.gameserver.mechanics.olympiad.Hero;
import org.l2jmobius.gameserver.mechanics.script.LongTimeEvent;
import org.l2jmobius.gameserver.mechanics.script.Quest;

/**
 * Which items may enter a custody (docs/spec/asset-service.md §4.4): what Mobius's own availability check allows with
 * Adena allowed and non-tradeable items refused, never time-limited or shadow items, and never an item id that one of
 * Mobius's blanket deletions by item id reaches (hero items, Hero.java:81; an event's items on its end,
 * LongTimeEvent.java:557). Those two lists are read from Mobius itself, so they cannot drift from what it deletes; a
 * renamed field fails loudly instead of letting such an item in.
 */
final class Eligibility
{
	private static final Pattern NUMBER = Pattern.compile("\\d+");

	private Eligibility()
	{
	}

	/** @return why the item may not enter a custody from this character, or null if it may */
	static String refusal(Player player, Item item)
	{
		if (!item.isAvailable(player, true, false))
		{
			return "not available: equipped, a quest item, the summoned pet's collar, being enchanted, consumed by a skill, or not tradeable";
		}
		if (item.isTimeLimitedItem() || item.isShadowItem())
		{
			return "time-limited or shadow item";
		}
		if (blanketDeleted().contains(item.getId()))
		{
			return "an item id Mobius deletes by item id (hero or event item)";
		}
		return null;
	}

	/** For a clan warehouse (§4.4): the same checks without the player-bound ones. */
	static String clanRefusal(Item item)
	{
		if (item.isEquipped() || item.isQuestItem() || !item.isTradeable())
		{
			return "equipped, a quest item, or not tradeable";
		}
		if (item.isTimeLimitedItem() || item.isShadowItem())
		{
			return "time-limited or shadow item";
		}
		if (blanketDeleted().contains(item.getId()))
		{
			return "an item id Mobius deletes by item id (hero or event item)";
		}
		return null;
	}

	/**
	 * For an item a source creates straight into a custody (§4.4): the checks that need no holder, on its template.
	 * @return why it may not, or null if it may
	 */
	static String templateRefusal(ItemTemplate template)
	{
		if (template == null)
		{
			return "no such item";
		}
		if (!template.isTradeable() || template.isQuestItem())
		{
			return "a quest item, or not tradeable";
		}
		if ((template.getTime() > 0) || (template.getDuration() >= 0))
		{
			return "time-limited or shadow item";
		}
		if (blanketDeleted().contains(template.getId()))
		{
			return "an item id Mobius deletes by item id (hero or event item)";
		}
		return null;
	}

	/**
	 * {@code issue}'s representation test (§4.3): a parchment or a seal is non-stackable, cannot be traded, dropped, sold,
	 * deposited or crystallized ({@code RequestCrystallizeItem} pays crystals for any crystallizable item, whatever its
	 * price or flags), has no reference price, no extractable items and no item skills (either would make something on
	 * use), and no item handler but {@code Bypass}, which opens a page (Bypass.java:41-52).
	 * @return why the template is not a representation, or null if it is
	 */
	static String representationRefusal(ItemTemplate template)
	{
		if (template == null)
		{
			return "no such item";
		}
		if (template.isStackable())
		{
			return "stackable";
		}
		if (template.isTradeable() || template.isDropable() || template.isSellable() || template.isDepositable())
		{
			return "tradable, droppable, sellable or depositable";
		}
		if (template.isCrystallizable())
		{
			return "crystallizable";
		}
		if (template.getReferencePrice() != 0)
		{
			return "priced";
		}
		if (template.hasSkills())
		{
			return "with item skills";
		}
		if (template instanceof EtcItem etc)
		{
			if ((etc.getExtractableItems() != null) && !etc.getExtractableItems().isEmpty())
			{
				return "extractable";
			}
			if ((etc.getHandlerName() != null) && !etc.getHandlerName().isEmpty() && !"Bypass".equals(etc.getHandlerName()))
			{
				return "a handler other than Bypass";
			}
		}
		return null;
	}

	/** The item ids Mobius deletes from every owner at once: hero items and the loaded events' items. */
	static Set<Integer> blanketDeleted()
	{
		final Set<Integer> ids = new HashSet<>(heroItems());
		for (Quest script : loadedScripts())
		{
			if (script instanceof LongTimeEvent event)
			{
				ids.addAll(eventItems(event));
			}
		}
		return ids;
	}

	private static Set<Integer> heroItems()
	{
		final Set<Integer> ids = new HashSet<>();
		final Matcher numbers = NUMBER.matcher((String) read(Hero.class, null, "DELETE_ITEMS"));
		while (numbers.find())
		{
			ids.add(Integer.parseInt(numbers.group()));
		}
		if (ids.isEmpty())
		{
			throw new IllegalStateException("Hero.DELETE_ITEMS names no item: the hero item deletion changed upstream");
		}
		return ids;
	}

	@SuppressWarnings("unchecked")
	private static List<Integer> eventItems(LongTimeEvent event)
	{
		return (List<Integer>) read(LongTimeEvent.class, event, "_destroyItemsOnEnd");
	}

	@SuppressWarnings("unchecked")
	private static Collection<Quest> loadedScripts()
	{
		final Set<Quest> scripts = new HashSet<>(((Map<String, Quest>) read(ScriptManager.class, ScriptManager.getInstance(), "_quests")).values());
		scripts.addAll(((Map<String, Quest>) read(ScriptManager.class, ScriptManager.getInstance(), "_scripts")).values());
		return scripts;
	}

	private static Object read(Class<?> owner, Object instance, String name)
	{
		try
		{
			final Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return field.get(instance);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(owner.getSimpleName() + "." + name + " is gone: §4.4's blanket-deletion list cannot be read", e);
		}
	}
}
