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

	private static RecipeList recipe(int id)
	{
		return RecipeData.getInstance().getRecipeList(id);
	}

	@Test
	void theRatesAreRetails()
	{
		assertEquals(70, RecipeManager.Craft.successRate(recipe(EARRING)));
		final double rate = org.l2jmobius.gameserver.config.PlayerConfig.CRAFT_MASTERWORK_CHANCE_RATE;
		assertEquals(Math.min(100, (int) Math.ceil(4 * rate)) * 10_000, RecipeManager.Craft.masterworkRatePpm(recipe(EARRING)), "rarity 4: retail's draw Rnd.get(100) < 4 x rate");
		assertEquals(0, RecipeManager.Craft.masterworkRatePpm(recipe(ARROWS)), "no rare product");
		final boolean masterwork = org.l2jmobius.gameserver.config.PlayerConfig.CRAFT_MASTERWORK;
		org.l2jmobius.gameserver.config.PlayerConfig.CRAFT_MASTERWORK = false;
		try
		{
			assertEquals(0, RecipeManager.Craft.masterworkRatePpm(recipe(EARRING)), "a rare product of another item needs CraftMasterwork");
			assertEquals(Math.min(100, (int) Math.ceil(20 * rate)) * 10_000, RecipeManager.Craft.masterworkRatePpm(recipe(44)), "a rare product of the same item does not");
		}
		finally
		{
			org.l2jmobius.gameserver.config.PlayerConfig.CRAFT_MASTERWORK = masterwork;
		}
	}

	@Test
	void materialsAndProductAreRetails()
	{
		assertEquals(java.util.List.of("1864x4", "1869x2"), RecipeManager.Craft.materials(recipe(ARROWS)).stream().map(h -> h.getId() + "x" + h.getCount()).toList());
		assertEquals("17x500", text(RecipeManager.Craft.product(recipe(ARROWS), false)));
		assertEquals("17x500", text(RecipeManager.Craft.product(recipe(ARROWS), true)), "no rare product: a masterwork roll changes nothing");
		assertEquals("856x1", text(RecipeManager.Craft.product(recipe(EARRING), false)));
		assertEquals("12710x1", text(RecipeManager.Craft.product(recipe(EARRING), true)));
	}

	private static String text(org.l2jmobius.gameserver.entity.item.holders.ItemHolder holder)
	{
		return holder.getId() + "x" + holder.getCount();
	}

	@Test
	void refusalMatchesWhatRetailRefuses()
	{
		_crafter.setCurrentMp(_crafter.getMaxMp());
		assertEquals(null, RecipeManager.Craft.refusal(_crafter, recipe(EARRING)), "retail would start it");
		assertEquals(RecipeManager.Craft.Refusal.NOT_IN_BOOK, RecipeManager.Craft.refusal(_crafter, recipe(44)));
		_crafter.setCurrentMp(0);
		assertEquals(RecipeManager.Craft.Refusal.NOT_ENOUGH_MP, RecipeManager.Craft.refusal(_crafter, recipe(EARRING)));
		_crafter.setCurrentMp(_crafter.getMaxMp());
		final Player novice = TestPlayers.create(PlayerClass.FIGHTER, 60);
		novice.addSkill(SkillData.getInstance().getSkill(172, 1), false);
		novice.registerDwarvenRecipeList(recipe(EARRING), false);
		novice.setCurrentMp(novice.getMaxMp());
		assertEquals(RecipeManager.Craft.Refusal.LOW_SKILL, RecipeManager.Craft.refusal(novice, recipe(EARRING)), "level 6 needs Create Item 6");
	}

	@Test
	void consumeStatsTakesWhatRetailTakes()
	{
		_crafter.setCurrentMp(_crafter.getMaxMp());
		RecipeManager.Craft.consumeStats(_crafter, recipe(EARRING));
		assertEquals(174, (int) (_crafter.getMaxMp() - _crafter.getCurrentMp()), "the earring's MP, as the recorded crafts cost");
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
