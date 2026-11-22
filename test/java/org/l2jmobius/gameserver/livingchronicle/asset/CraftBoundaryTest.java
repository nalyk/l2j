package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.data.xml.RecipeData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.data.holders.RecipeHolder;
import org.l2jmobius.gameserver.entity.item.recipe.RecipeList;
import org.l2jmobius.gameserver.livingchronicle.rng.KeyedRandom;
import org.l2jmobius.gameserver.managers.RecipeManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * The crafting boundary (docs/spec/asset-service.md §11.34, CP-0033). {@code retailCraftIsUnchanged} pins retail's
 * crafting, recorded on upstream before the refactor: the same draws in the same order give the same outcome.
 */
class CraftBoundaryTest
{
	private static final long START = 1_794_216_090_000L;
	private static final long SEED = 33;
	/** Wooden Arrow: always succeeds, no rare product. */
	private static final int ARROWS = 1;
	/** Adamantite Earring: succeeds 70 %, its rare product another item (rarity 4). */
	private static final int EARRING = 328;
	private static TestServer _server;
	private static Player _crafter;

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.bootPaused(START, SEED);
		_crafter = TestPlayers.create(PlayerClass.FIGHTER, 60);
		if (World.getPlayer(_crafter.getObjectId()) == null)
		{
			World.addObject(_crafter);
		}
		_crafter.addSkill(SkillData.getInstance().getSkill(172, 6), false); // Create Item, level 6
		_crafter.registerDwarvenRecipeList(RecipeData.getInstance().getRecipeList(ARROWS), false);
		_crafter.registerDwarvenRecipeList(RecipeData.getInstance().getRecipeList(EARRING), false);
	}

	private static long held(int itemId)
	{
		long count = 0;
		for (Item item : _crafter.getInventory().getAllItemsByItemId(itemId))
		{
			count += item.getCount();
		}
		return count;
	}

	/** One retail craft on its own keyed stream. @return what it made, "id x count" or "-" */
	private static String craft(int recipeId, long key)
	{
		final RecipeList recipe = RecipeData.getInstance().getRecipeList(recipeId);
		for (RecipeHolder ingredient : recipe.getRecipes())
		{
			_crafter.getInventory().addItem(ItemProcessType.NONE, ingredient.getItemId(), ingredient.getQuantity(), _crafter, null);
		}
		_crafter.setCurrentMp(_crafter.getMaxMp());
		final int product = recipe.getItemId();
		final int rare = recipe.getRareItemId();
		final long before = held(product);
		final long rareBefore = rare > 0 ? held(rare) : 0;
		_server.withRandom(KeyedRandom.of(SEED, "craft", (recipeId * 1000L) + key), () -> RecipeManager.getInstance().requestMakeItem(_crafter, recipeId));
		final long made = held(product) - before;
		final long madeRare = rare > 0 ? held(rare) - rareBefore : 0;
		for (RecipeHolder ingredient : recipe.getRecipes())
		{
			if (held(ingredient.getItemId()) != 0)
			{
				return "materials left of " + ingredient.getItemId();
			}
		}
		return (made > 0 ? product + "x" + made : "") + (madeRare > 0 ? rare + "x" + madeRare : "") + (((made == 0) && (madeRare == 0)) ? "-" : "") + "@" + (int) (_crafter.getMaxMp() - _crafter.getCurrentMp());
	}

	@Test
	void retailCraftIsUnchanged()
	{
		final StringBuilder outcomes = new StringBuilder();
		for (int key = 1; key <= 10; key++)
		{
			outcomes.append(craft(ARROWS, key)).append(' ');
		}
		for (int key = 1; key <= 100; key++)
		{
			outcomes.append(craft(EARRING, key)).append(' ');
		}
		assertEquals(RECORDED, outcomes.toString().trim(), "retail's craft, recorded on upstream (CP-0033 must not change it)");
	}

	/** Recorded on upstream c9ca4c31's RecipeManager. */
	private static final String RECORDED = "17x500@30 17x500@30 17x500@30 17x500@30 17x500@30 17x500@30 17x500@30 17x500@30 " +
		"17x500@30 17x500@30 856x1@174 856x1@174 -@174 856x1@174 856x1@174 856x1@174 " +
		"856x1@174 856x1@174 -@174 -@174 -@174 856x1@174 856x1@174 -@174 " +
		"856x1@174 856x1@174 856x1@174 856x1@174 -@174 856x1@174 -@174 856x1@174 " +
		"-@174 856x1@174 856x1@174 12710x1@174 -@174 856x1@174 856x1@174 -@174 " +
		"856x1@174 856x1@174 856x1@174 856x1@174 856x1@174 -@174 -@174 856x1@174 " +
		"856x1@174 856x1@174 856x1@174 856x1@174 856x1@174 -@174 856x1@174 856x1@174 " +
		"856x1@174 856x1@174 856x1@174 -@174 -@174 856x1@174 -@174 -@174 " +
		"856x1@174 856x1@174 856x1@174 -@174 856x1@174 856x1@174 856x1@174 856x1@174 " +
		"856x1@174 856x1@174 856x1@174 -@174 -@174 -@174 856x1@174 856x1@174 " +
		"-@174 -@174 856x1@174 856x1@174 856x1@174 856x1@174 856x1@174 856x1@174 " +
		"856x1@174 856x1@174 -@174 856x1@174 856x1@174 12710x1@174 12710x1@174 -@174 " +
		"856x1@174 856x1@174 856x1@174 856x1@174 856x1@174 856x1@174 -@174 856x1@174 " +
		"856x1@174 856x1@174 856x1@174 856x1@174 856x1@174 856x1@174";
}
