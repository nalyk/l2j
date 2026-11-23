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
 * crafting, recorded on upstream: the same draws in the same order give the same outcome, and each craft leaves its
 * stream where upstream's did (the next draw is recorded), so a draw added or removed shows.
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
		final int[] next = new int[1];
		_server.withRandom(KeyedRandom.of(SEED, "craft", (recipeId * 1000L) + key), () ->
		{
			RecipeManager.getInstance().requestMakeItem(_crafter, recipeId);
			next[0] = org.l2jmobius.commons.util.Rnd.get(1_000_000); // the stream's next draw: the craft drew as many as upstream
		});
		final long made = held(product) - before;
		final long madeRare = rare > 0 ? held(rare) - rareBefore : 0;
		for (RecipeHolder ingredient : recipe.getRecipes())
		{
			if (held(ingredient.getItemId()) != 0)
			{
				return "materials left of " + ingredient.getItemId();
			}
		}
		return (made > 0 ? product + "x" + made : "") + (madeRare > 0 ? rare + "x" + madeRare : "") + (((made == 0) && (madeRare == 0)) ? "-" : "") + "@" + (int) (_crafter.getMaxMp() - _crafter.getCurrentMp()) + "#" + next[0];
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

	@Test
	void withAltGameCreationTheCallableRulesAreRetailsToo() throws Exception
	{
		final boolean alt = org.l2jmobius.gameserver.config.PlayerConfig.ALT_GAME_CREATION;
		org.l2jmobius.gameserver.config.PlayerConfig.ALT_GAME_CREATION = true;
		final java.lang.reflect.Field makers = RecipeManager.class.getDeclaredField("_activeMakers");
		makers.setAccessible(true);
		@SuppressWarnings("unchecked")
		final java.util.Map<Integer, Object> active = (java.util.Map<Integer, Object>) makers.get(null);
		try
		{
			_crafter.setCurrentMp(_crafter.getMaxMp());
			RecipeManager.Craft.consumeStats(_crafter, recipe(EARRING));
			// 88 materials grabbed 6 at a time (Create Item 6): 15 passes of 174 / 15 = 11 MP
			assertEquals(165, (int) (_crafter.getMaxMp() - _crafter.getCurrentMp()), "retail's per-pass cost, not the recipe's whole");
			active.put(_crafter.getObjectId(), new Object());
			_crafter.setCurrentMp(_crafter.getMaxMp());
			assertEquals(RecipeManager.Craft.Refusal.BUSY, RecipeManager.Craft.refusal(_crafter, recipe(EARRING)), "retail refuses a second craft while one runs");
		}
		finally
		{
			active.remove(_crafter.getObjectId());
			org.l2jmobius.gameserver.config.PlayerConfig.ALT_GAME_CREATION = alt;
		}
	}

	/** Recorded on upstream c9ca4c31's RecipeManager: each craft's outcome, its MP, and its stream's next draw. */
	private static final String RECORDED = "17x500@30#684934 17x500@30#576544 17x500@30#617252 17x500@30#872361 17x500@30#192633 17x500@30#161618 " +
		"17x500@30#200692 17x500@30#979488 17x500@30#843240 17x500@30#313878 856x1@174#213256 856x1@174#684219 " +
		"-@174#686053 856x1@174#149650 856x1@174#592153 856x1@174#26976 856x1@174#98523 856x1@174#623511 " +
		"-@174#859869 -@174#464008 -@174#354700 856x1@174#232355 856x1@174#319416 -@174#718450 " +
		"856x1@174#766623 856x1@174#288371 856x1@174#965310 856x1@174#918914 -@174#403338 856x1@174#409816 " +
		"-@174#896346 856x1@174#74586 -@174#24707 856x1@174#164974 856x1@174#697745 12710x1@174#898531 " +
		"-@174#768667 856x1@174#460406 856x1@174#283090 -@174#347276 856x1@174#464469 856x1@174#252461 " +
		"856x1@174#335767 856x1@174#774459 856x1@174#123273 -@174#149491 -@174#428503 856x1@174#854904 " +
		"856x1@174#287125 856x1@174#857619 856x1@174#924495 856x1@174#202052 856x1@174#158337 -@174#130077 " +
		"856x1@174#154290 856x1@174#17291 856x1@174#801080 856x1@174#431134 856x1@174#401006 -@174#108922 " +
		"-@174#665776 856x1@174#531837 -@174#620610 -@174#100658 856x1@174#550331 856x1@174#896720 " +
		"856x1@174#466253 -@174#478690 856x1@174#339833 856x1@174#678189 856x1@174#277235 856x1@174#894397 " +
		"856x1@174#225977 856x1@174#976017 856x1@174#943388 -@174#547479 -@174#97851 -@174#7062 " +
		"856x1@174#781259 856x1@174#436618 -@174#83155 -@174#13175 856x1@174#325350 856x1@174#550073 " +
		"856x1@174#14169 856x1@174#628057 856x1@174#175336 856x1@174#772536 856x1@174#208832 856x1@174#578423 " +
		"-@174#47409 856x1@174#783792 856x1@174#613686 12710x1@174#893525 12710x1@174#797040 -@174#708081 " +
		"856x1@174#843110 856x1@174#614853 856x1@174#520492 856x1@174#424143 856x1@174#877613 856x1@174#601711 " +
		"-@174#127815 856x1@174#941572 856x1@174#208515 856x1@174#915704 856x1@174#766703 856x1@174#622894 " +
		"856x1@174#747861 856x1@174#622460";
}
