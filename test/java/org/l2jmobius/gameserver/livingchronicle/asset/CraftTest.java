package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * {@code craft} and owner rows (docs/spec/asset-service.md §4.2 rule 10, §4.3, §2.5 {@code CRAFT_INPUT},
 * {@code CRAFT_OUTPUT}; pact.md §4.3): a contractual craft sinks its materials from the contract's custody and sends its
 * product, if any, straight to the receiver by service mail, in one operation with the owner's outcome row.
 */
class CraftTest
{
	private static final int STEEL = 1880; // a material
	private static final int VARNISH = 1865;
	private static final int SHORT_SWORD = 1;
	private static AssetFixtures _f;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), Map.of("TEST", (connection, ref) -> true), id -> MailManager.getInstance().adopt(id));
		_f.execute("CREATE TABLE IF NOT EXISTS lc_test_outcome (attempt INT PRIMARY KEY, success BOOLEAN NOT NULL)");
		EconomyCounter.activate(Map.of());
	}

	@AfterAll
	static void stop() throws Exception
	{
		EconomyCounter.deactivate();
		_f.execute("DROP TABLE IF EXISTS lc_test_outcome");
	}

	private static Player receiver()
	{
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 20);
		if (World.getPlayer(player.getObjectId()) == null)
		{
			World.addObject(player);
		}
		return player;
	}

	private static long counted(int itemId)
	{
		return EconomyCounter.snapshot().getOrDefault(itemId, 0L);
	}

	private static int custodyWithMaterials() throws Exception
	{
		final int custody = _f.open("TEST");
		_f.seed(custody, STEEL, 10, false);
		_f.seed(custody, VARNISH, 4, false);
		return custody;
	}

	private static Outcome craft(int custody, int receiver, ItemHolder product, int attempt) throws Exception
	{
		try (AssetTx tx = _f.service.begin(_f.op("CRAFT"), "TEST", "CRAFT", custody))
		{
			tx.craft(custody, List.of(new Asset(STEEL, 10, null), new Asset(VARNISH, 4, null)), receiver, product, "Notarius of Giran", "Your contract's craft.");
			final Map<String, Object> outcome = new LinkedHashMap<>();
			outcome.put("attempt", attempt);
			outcome.put("success", product != null);
			tx.ownerRow("lc_test_outcome", outcome);
			return tx.commit();
		}
	}

	@Test
	void aSuccessfulCraftSinksTheMaterialsAndMailsTheProduct() throws Exception
	{
		final Player crafter = receiver();
		final int custody = custodyWithMaterials();
		final long steel = counted(STEEL);
		final long swords = counted(SHORT_SWORD);
		assertEquals(Outcome.COMMITTED, craft(custody, crafter.getObjectId(), new ItemHolder(SHORT_SWORD, 1), 1));
		assertEquals(0, _f.held(custody, STEEL), "the materials are consumed");
		assertEquals(0, _f.held(custody, VARNISH));
		final long contract = _f.number("SELECT owner_ref FROM lc_custody WHERE custody_id = " + custody);
		assertEquals(14, _f.number("SELECT SUM(qty) FROM lc_ledger_posting WHERE code = 'CRAFT_INPUT' AND account_kind = 'SINK' AND account_id = " + contract), "a CRAFT_INPUT sink leg per material, referenced by the contract");
		assertEquals(1, _f.number("SELECT COUNT(*) FROM items i JOIN messages m ON m.messageId = i.loc_data WHERE i.loc = 'MAIL' AND m.receiverId = " + crafter.getObjectId() + " AND i.item_id = " + SHORT_SWORD), "the product by service mail, never into the custody");
		assertEquals(1, _f.number("SELECT SUM(qty) FROM lc_ledger_posting WHERE code = 'CRAFT_OUTPUT' AND account_kind = 'SOURCE' AND account_id = " + contract), "the CRAFT_OUTPUT source, referenced by the contract");
		assertEquals(steel - 10, counted(STEEL));
		assertEquals(swords + 1, counted(SHORT_SWORD));
		assertEquals(1, _f.facts("Crafted", custody));
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_test_outcome WHERE attempt = 1 AND success"), "the outcome row in the same operation");
	}

	@Test
	void aFailedCraftOnlySinksTheMaterials() throws Exception
	{
		final Player crafter = receiver();
		final int custody = custodyWithMaterials();
		assertEquals(Outcome.COMMITTED, craft(custody, crafter.getObjectId(), null, 2));
		assertEquals(0, _f.held(custody, STEEL));
		assertEquals(0, _f.number("SELECT COUNT(*) FROM messages WHERE receiverId = " + crafter.getObjectId()), "nothing made, nothing mailed");
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_test_outcome WHERE attempt = 2 AND NOT success"));
	}

	@Test
	void anAbortedCraftWritesNoOutcomeRow() throws Exception
	{
		final int custody = custodyWithMaterials();
		assertEquals(AssetAbort.Reason.RECEIVER_GONE, assertThrows(AssetAbort.class, () -> craft(custody, 999_999_999, new ItemHolder(SHORT_SWORD, 1), 3)).reason());
		assertEquals(10, _f.held(custody, STEEL), "nothing consumed");
		assertEquals(0, _f.number("SELECT COUNT(*) FROM lc_test_outcome WHERE attempt = 3"), "the outcome row went with the operation");
	}

	@Test
	void anotherModulesTableIsRefused() throws Exception
	{
		final int custody = custodyWithMaterials();
		try (AssetTx tx = _f.service.begin(_f.op("CRAFT"), "TEST", "CRAFT", custody))
		{
			assertThrows(IllegalArgumentException.class, () -> tx.ownerRow("lc_asset_op", Map.of("module", "TEST")), "only the operation module's own tables");
			assertThrows(IllegalArgumentException.class, () -> tx.ownerRow("items", Map.of("count", 1)), "never Mobius's");
		}
	}
}
