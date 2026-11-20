package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Sinks from a custody (docs/spec/asset-service.md §2.5: CROWN_UPKEEP, ROAD_LOSS, CRAFT_INPUT), corrective admin operations
 * and unfreezing (§3.1 FROZEN transitions, §4.5, §4.2 rule 7). AT-ASSET-15 for custodies.
 */
class SinkAndCorrectionTest
{
	private static AssetFixtures _f;

	@BeforeAll
	static void start() throws Exception
	{
		final HoldingPolicy always = (connection, ref) -> true;
		_f = new AssetFixtures(700_000_000, Map.of("TEST", always, "COLD", always, "BROKEN", always, "SHUT", always, "SHUT2", always, "TWICE", always));
	}

	@Test
	void aSinkRemovesTheAssetAndPostsItsCode() throws Exception
	{
		final int custody = _f.open("TEST");
		_f.seed(custody, ADENA, 100, false);
		final OpId op = _f.op("UPKEEP");
		try (AssetTx tx = _f.service.begin(op, "CROWN", "UPKEEP", 7))
		{
			tx.sink(custody, List.of(new Asset(ADENA, 30, null)), SinkCode.CROWN_UPKEEP, 7);
			tx.commit();
		}
		assertEquals(70, _f.held(custody, ADENA));
		assertEquals(70, _f.posted(custody, ADENA));
		assertEquals(30, _f.number("SELECT qty FROM lc_ledger_posting WHERE op_id = UNHEX('" + op.hex() + "') AND account_kind = 'SINK' AND code = 'CROWN_UPKEEP' AND account_id = 7 AND direction = 'CREDIT'"));
		assertEquals(1, _f.facts("FeeCharged", custody));
	}

	@Test
	void aFrozenModuleRefusesAllButCorrections() throws Exception
	{
		final int custody = _f.open("COLD");
		final int other = _f.open("TEST");
		_f.seed(custody, ADENA, 100, false);
		try (Connection connection = _f.database.connect())
		{
			ModuleState.freeze(connection, "COLD", "test", null);
		}
		try (AssetTx tx = _f.service.begin(_f.op("MOVE"), "TEST", "MOVE", custody))
		{
			tx.move(custody, other, List.of(new Asset(ADENA, 1, null)));
			assertEquals(AssetAbort.Reason.MODULE_FROZEN, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		try (AssetTx tx = _f.service.begin(_f.op("UPKEEP"), "CROWN", "UPKEEP", 1))
		{
			tx.sink(custody, List.of(new Asset(ADENA, 1, null)), SinkCode.CROWN_UPKEEP, 1);
			assertEquals(AssetAbort.Reason.MODULE_FROZEN, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		try (AssetTx tx = _f.service.begin(_f.op("CORRECT"), "ASSET", "CORRECT", custody))
		{
			tx.correct(custody, List.of(), List.of(new Asset(ADENA, 1, null)), Reconcile.NONE, 9001, "test removal", 268_000_001);
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		assertEquals(99, _f.held(custody, ADENA));
	}

	@Test
	void aCorrectionIntoACustodyIsAuditedWithBeforeAndAfter() throws Exception
	{
		final int custody = _f.open("TEST");
		_f.seed(custody, ADENA, 10, false);
		final OpId op = _f.op("CORRECT");
		try (AssetTx tx = _f.service.begin(op, "ASSET", "CORRECT", custody))
		{
			tx.correct(custody, List.of(new Asset(ADENA, 500, null)), List.of(), Reconcile.NONE, 4242, "owed by an incident", 268_000_001);
			tx.commit();
		}
		assertEquals(510, _f.held(custody, ADENA));
		assertEquals(510, _f.posted(custody, ADENA));
		assertEquals(500, _f.number("SELECT qty FROM lc_ledger_posting WHERE op_id = UNHEX('" + op.hex() + "') AND account_kind = 'SOURCE' AND code = 'ADMIN_CORRECTION' AND account_id = 4242"));
		final String payload = _f.lastPayload("AdminCorrection", custody);
		assertTrue(payload.contains("\"admin\":268000001") && payload.contains("\"violation\":4242") && payload.contains("\"reason\":\"owed by an incident\""), payload);
		assertTrue(payload.contains("\"before\":{\"57\":10}") && payload.contains("\"after\":{\"57\":510}"), payload);
	}

	@Test
	void aTamperedCustodyStaysFrozenUntilACorrectionRecognisesItsRowsThenUnfreezes() throws Exception
	{
		final int custody = _f.open("BROKEN");
		final int other = _f.open("TEST");
		final int row = _f.seed(custody, ADENA, 100, false);
		_f.execute("UPDATE items SET count = 130 WHERE object_id = " + row);
		try (AssetTx tx = _f.service.begin(_f.op("MOVE"), "TEST", "MOVE", custody))
		{
			tx.move(custody, other, List.of(new Asset(ADENA, 1, null)));
			assertEquals(AssetAbort.Reason.UNBALANCED, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		assertTrue(_f.frozen("BROKEN"));
		assertEquals(IllegalStateException.class, assertThrows(IllegalStateException.class, () -> _f.service.unfreeze("BROKEN", 268_000_001, "corrected")).getClass(), "an invariant is still broken");
		try (AssetTx tx = _f.service.begin(_f.op("CORRECT"), "ASSET", "CORRECT", custody))
		{
			tx.correct(custody, List.of(), List.of(), Reconcile.RECOGNIZE_ROWS, 77, "rows edited by hand; accepted as found", 268_000_001);
			tx.commit();
		}
		assertEquals(130, _f.held(custody, ADENA), "rows are never changed by recognising them");
		assertEquals(130, _f.posted(custody, ADENA));
		_f.service.unfreeze("BROKEN", 268_000_001, "rows recognised by correction 77");
		assertFalse(_f.frozen("BROKEN"));
		assertEquals("OPEN", _f.text("SELECT state FROM lc_custody WHERE custody_id = " + custody));
		try (AssetTx tx = _f.service.begin(_f.op("MOVE"), "TEST", "MOVE", custody))
		{
			tx.move(custody, other, List.of(new Asset(ADENA, 30, null)));
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
	}

	@Test
	void aCorrectionNeedsAReasonAndAViolationAndCannotTouchAClosedCustody() throws Exception
	{
		final int custody = _f.open("TEST");
		try (AssetTx tx = _f.service.begin(_f.op("CORRECT"), "ASSET", "CORRECT", custody))
		{
			assertThrows(IllegalArgumentException.class, () -> tx.correct(custody, List.of(), List.of(), Reconcile.NONE, 1, " ", 268_000_001));
			assertThrows(IllegalArgumentException.class, () -> tx.correct(custody, List.of(), List.of(), Reconcile.NONE, 0, "why", 268_000_001));
		}
		try (AssetTx tx = _f.service.begin(_f.op("CLOSE"), "TEST", "CLOSE", custody))
		{
			tx.close(custody);
			tx.commit();
		}
		try (AssetTx tx = _f.service.begin(_f.op("CORRECT"), "ASSET", "CORRECT", custody))
		{
			tx.correct(custody, List.of(new Asset(ADENA, 1, null)), List.of(), Reconcile.NONE, 1, "why", 268_000_001);
			assertEquals(AssetAbort.Reason.CUSTODY_NOT_OPEN, assertThrows(AssetAbort.class, tx::commit).reason());
		}
	}

	@Test
	void aSinkOrACorrectionOfAWholeObjectDeletesItsRow() throws Exception
	{
		final int custody = _f.open("TEST");
		final int sword = _f.seed(custody, AssetFixtures.SWORD, 1, true);
		final int other = _f.seed(custody, AssetFixtures.SWORD, 1, true);
		try (AssetTx tx = _f.service.begin(_f.op("CRAFT"), "PACT", "CRAFT", 1))
		{
			tx.sink(custody, List.of(new Asset(AssetFixtures.SWORD, 1, sword)), SinkCode.CRAFT_INPUT, 1);
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		assertEquals(0, _f.number("SELECT COUNT(*) FROM items WHERE object_id = " + sword));
		try (AssetTx tx = _f.service.begin(_f.op("CORRECT"), "ASSET", "CORRECT", custody))
		{
			tx.correct(custody, List.of(), List.of(new Asset(AssetFixtures.SWORD, 1, other)), Reconcile.NONE, 5, "removed", 268_000_001);
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		assertEquals(0, _f.number("SELECT COUNT(*) FROM items WHERE object_id = " + other));
		assertFalse(_f.frozen("TEST"), "a valid operation never freezes its owner");
	}

	@Test
	void unfreezeChecksClosedCustodiesToo() throws Exception
	{
		final int custody = _f.open("SHUT");
		try (AssetTx tx = _f.service.begin(_f.op("CLOSE"), "SHUT", "CLOSE", custody))
		{
			tx.close(custody);
			tx.commit();
		}
		_f.execute("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data) VALUES (-1, 799999001, 57, 3, 0, 'ESCROW', " + custody + ")");
		try (Connection connection = _f.database.connect())
		{
			ModuleState.freeze(connection, "SHUT", "INV04", null);
		}
		assertThrows(IllegalStateException.class, () -> _f.service.unfreeze("SHUT", 268_000_001, "try"), "a closed custody holding rows keeps its module frozen");
	}

	@Test
	void rowsUnderAClosedCustodyOrNoCustodyAreTakenOutByACorrectionThatLeavesItClosed() throws Exception
	{
		final int custody = _f.open("SHUT2");
		try (AssetTx tx = _f.service.begin(_f.op("CLOSE"), "SHUT2", "CLOSE", custody))
		{
			tx.close(custody);
			tx.commit();
		}
		_f.execute("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data) VALUES (-1, 799999101, 57, 9, 0, 'ESCROW', " + custody + ")");
		try (AssetTx tx = _f.service.begin(_f.op("CORRECT"), "ASSET", "CORRECT", custody))
		{
			tx.correct(custody, List.of(new Asset(ADENA, 1, null)), List.of(), Reconcile.NONE, 11, "nothing goes into a closed custody", 268_000_001);
			assertEquals(AssetAbort.Reason.CUSTODY_NOT_OPEN, assertThrows(AssetAbort.class, tx::commit).reason());
		}
		try (AssetTx tx = _f.service.begin(_f.op("CORRECT"), "ASSET", "CORRECT", custody))
		{
			tx.correct(custody, List.of(), List.of(new Asset(ADENA, 9, null)), Reconcile.RECOGNIZE_ROWS, 11, "rows found under a closed custody", 268_000_001);
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		assertEquals(0, _f.held(custody, ADENA));
		assertEquals(0, _f.posted(custody, ADENA), "recognised, then taken out: the ledger shows both");
		assertEquals("CLOSED", _f.text("SELECT state FROM lc_custody WHERE custody_id = " + custody));
		final int nowhere = 2_000_000_100;
		_f.execute("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data) VALUES (-1, 799999102, 57, 4, 0, 'ESCROW', " + nowhere + ")");
		try (AssetTx tx = _f.service.begin(_f.op("CORRECT"), "ASSET", "CORRECT", nowhere))
		{
			tx.correct(nowhere, List.of(), List.of(new Asset(ADENA, 4, null)), Reconcile.RECOGNIZE_ROWS, 12, "rows under no custody", 268_000_001);
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		assertEquals(0, _f.held(nowhere, ADENA));
	}

	@Test
	void unfreezeRefusesAnEscrowObjectThatIsAlsoOnTheGround() throws Exception
	{
		final int custody = _f.open("TWICE");
		final int sword = _f.seed(custody, AssetFixtures.SWORD, 1, true);
		_f.execute("INSERT INTO itemsonground (object_id, item_id, count, x, y, z) VALUES (" + sword + ", " + AssetFixtures.SWORD + ", 1, 0, 0, 0)");
		try (Connection connection = _f.database.connect())
		{
			ModuleState.freeze(connection, "TWICE", "INV02", null);
		}
		final IllegalStateException refused = assertThrows(IllegalStateException.class, () -> _f.service.unfreeze("TWICE", 268_000_001, "try"));
		assertTrue(refused.getMessage().contains("INV02"), refused.getMessage());
	}
}
