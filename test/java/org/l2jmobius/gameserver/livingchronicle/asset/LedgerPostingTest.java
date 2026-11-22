package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * The ledger's own postings to the existence counters (docs/spec/asset-service.md §2.6): a committed operation posts what
 * its legs created or destroyed (a sink, a fee charged to a holder, a correction's quantities in and out); a move, a
 * release, a reconciliation that recognises rows as they are, and an aborted operation post nothing.
 */
class LedgerPostingTest
{
	private static final int SOULSHOT = 1835;
	private static AssetFixtures _f;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), Map.of("TEST", (connection, ref) -> true), id -> MailManager.getInstance().adopt(id));
		EconomyCounter.activate(Map.of());
	}

	@AfterAll
	static void stop()
	{
		EconomyCounter.deactivate();
	}

	private interface Work
	{
		void run() throws Exception;
	}

	private static Map<Integer, Long> change(Work work) throws Exception
	{
		final Map<Integer, Long> before = EconomyCounter.snapshot();
		work.run();
		final Map<Integer, Long> after = EconomyCounter.snapshot();
		final Set<Integer> ids = new HashSet<>(before.keySet());
		ids.addAll(after.keySet());
		final Map<Integer, Long> change = new TreeMap<>();
		for (int id : ids)
		{
			final long delta = after.getOrDefault(id, 0L) - before.getOrDefault(id, 0L);
			if (delta != 0)
			{
				change.put(id, delta);
			}
		}
		return change;
	}

	@Test
	void aSinkPostsWhatLeftExistence() throws Exception
	{
		final int custody = _f.open("TEST");
		_f.seed(custody, ADENA, 100, false);
		assertEquals(Map.of(ADENA, -30L), change(() ->
		{
			try (AssetTx tx = _f.service.begin(_f.op("UPKEEP"), "CROWN", "UPKEEP", 7))
			{
				tx.sink(custody, List.of(new Asset(ADENA, 30, null)), SinkCode.CROWN_UPKEEP, 7);
				assertEquals(Outcome.COMMITTED, tx.commit());
			}
		}));
	}

	@Test
	void aFeeChargedToAHolderPostsOnlyTheFee() throws Exception
	{
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 40);
		if (World.getPlayer(player.getObjectId()) == null)
		{
			World.addObject(player);
		}
		player.getInventory().addItem(ItemProcessType.NONE, ADENA, 1_000, player, null);
		assertEquals(Map.of(ADENA, -100L), change(() ->
		{
			try (AssetTx tx = _f.service.begin(_f.op("FEE"), "TEST", "FEE", player.getObjectId()))
			{
				tx.charge(Holder.player(player.getObjectId()), 100, SinkCode.FEE_NOTARIUS);
				assertEquals(Outcome.COMMITTED, tx.commit());
			}
		}), "the stack's decrease is the custody's, the sink is the fee");
		assertEquals(900, player.getAdena());
	}

	@Test
	void aCorrectionPostsItsQuantitiesInAndOut() throws Exception
	{
		final int custody = _f.open("TEST");
		_f.seed(custody, ADENA, 100, false);
		assertEquals(Map.of(ADENA, -2L, SOULSHOT, 5L), change(() ->
		{
			try (AssetTx tx = _f.service.begin(_f.op("CORRECT"), "ASSET", "CORRECT", custody))
			{
				tx.correct(custody, List.of(new Asset(SOULSHOT, 5, null)), List.of(new Asset(ADENA, 2, null)), Reconcile.NONE, 9002, "test", 268_000_001);
				assertEquals(Outcome.COMMITTED, tx.commit());
			}
		}));
	}

	@Test
	void recognisingRowsAsTheyArePostsNothing() throws Exception
	{
		final int custody = _f.open("TEST");
		final int row = _f.seed(custody, ADENA, 100, false);
		_f.execute("UPDATE items SET count = 130 WHERE object_id = " + row);
		assertEquals(Map.of(), change(() ->
		{
			try (AssetTx tx = _f.service.begin(_f.op("CORRECT"), "ASSET", "CORRECT", custody))
			{
				tx.correct(custody, List.of(), List.of(), Reconcile.RECOGNIZE_ROWS, 77, "accepted as found", 268_000_001);
				assertEquals(Outcome.COMMITTED, tx.commit());
			}
		}), "the rows were in the census already; only the postings change");
	}

	@Test
	void aMoveAndAReleasePostNothing() throws Exception
	{
		final int from = _f.open("TEST");
		final int to = _f.open("TEST");
		_f.seed(from, ADENA, 100, false);
		final Player receiver = TestPlayers.create(PlayerClass.FIGHTER, 40);
		assertEquals(Map.of(), change(() ->
		{
			try (AssetTx tx = _f.service.begin(_f.op("MOVE"), "TEST", "MOVE", from))
			{
				tx.move(from, to, List.of(new Asset(ADENA, 40, null)));
				tx.release(to, receiver.getObjectId(), List.of(new Asset(ADENA, 10, null)), MailReason.PAYOUT, "Test", "A test payout.");
				assertEquals(Outcome.COMMITTED, tx.commit());
			}
		}));
	}

	@Test
	void anAbortedOperationPostsNothing() throws Exception
	{
		final int custody = _f.open("TEST");
		_f.seed(custody, ADENA, 10, false);
		assertEquals(Map.of(), change(() ->
		{
			try (AssetTx tx = _f.service.begin(_f.op("UPKEEP"), "CROWN", "UPKEEP", 7))
			{
				tx.sink(custody, List.of(new Asset(ADENA, 30, null)), SinkCode.CROWN_UPKEEP, 7);
				assertEquals(AssetAbort.Reason.INSUFFICIENT, assertThrows(AssetAbort.class, tx::commit).reason());
			}
		}));
	}
}
