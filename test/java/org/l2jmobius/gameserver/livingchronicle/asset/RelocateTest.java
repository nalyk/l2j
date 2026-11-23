package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.testing.TestServer;

/**
 * {@code relocate} (docs/spec/asset-service.md §4.3, §4.4, §2.5 {@code RELOCATED_DROP}; aden-remembers.md §4.3.1): a
 * kill's selected drops enter from the {@code RELOCATED_DROP} source, referenced by the kill, each into its own Guild
 * warehouse custody, all in one operation or none.
 */
class RelocateTest
{
	private static final int SHORT_SWORD = 1;
	private static final int DARINS_LETTER = 687;
	private static final AtomicLong KILLS = new AtomicLong((7L << 40) + 1);
	private static AssetFixtures _f;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), Map.of("WORLD", (connection, ref) -> true), id -> MailManager.getInstance().adopt(id));
		EconomyCounter.activate(Map.of());
	}

	@AfterAll
	static void stop()
	{
		EconomyCounter.deactivate();
	}

	private static int warehouse() throws Exception
	{
		return _f.service.open(_f.op("OPEN"), Custody.Kind.POOL, "WORLD", KILLS.incrementAndGet());
	}

	private static Outcome relocate(long kill, Relocation... drops) throws Exception
	{
		try (AssetTx tx = _f.service.begin(_f.op("RELOCATE"), "WORLD", "RELOCATE", kill))
		{
			tx.settlementKey("WORLD", "kill/" + (kill >>> 40) + "/" + (kill & ((1L << 40) - 1)));
			tx.relocate(kill, List.of(drops));
			return tx.commit();
		}
	}

	private static long counted(int itemId)
	{
		return EconomyCounter.snapshot().getOrDefault(itemId, 0L);
	}

	@Test
	void eachDropGoesToItsOwnWarehouseCustody() throws Exception
	{
		final int giran = warehouse();
		final int aden = warehouse();
		final long kill = KILLS.incrementAndGet();
		final long adena = counted(ADENA);
		assertEquals(Outcome.COMMITTED, relocate(kill, new Relocation(ADENA, 100, giran), new Relocation(SHORT_SWORD, 2, aden), new Relocation(ADENA, 50, aden)));
		assertEquals(100, _f.held(giran, ADENA));
		assertEquals(50, _f.held(aden, ADENA));
		assertEquals(2, _f.number("SELECT COUNT(*) FROM items WHERE owner_id = -1 AND loc = 'ESCROW' AND loc_data = " + aden + " AND item_id = " + SHORT_SWORD + " AND count = 1"), "two swords, two objects");
		assertEquals(100, _f.posted(giran, ADENA));
		assertEquals(152, _f.number("SELECT SUM(qty) FROM lc_ledger_posting WHERE code = 'RELOCATED_DROP' AND account_kind = 'SOURCE' AND account_id = " + kill), "each leg referenced by the kill");
		assertEquals(adena + 150, counted(ADENA), "what the kill made exists");
		assertEquals(1, _f.facts("DropRelocated", giran));
		assertEquals(1, _f.facts("DropRelocated", aden));
	}

	@Test
	void anIneligibleDropRefusesTheWholeRelocation() throws Exception
	{
		final int giran = warehouse();
		final AssetAbort abort = assertThrows(AssetAbort.class, () -> relocate(KILLS.incrementAndGet(), new Relocation(ADENA, 100, giran), new Relocation(DARINS_LETTER, 1, giran)));
		assertEquals(AssetAbort.Reason.INELIGIBLE, abort.reason());
		assertEquals(0, _f.held(giran, ADENA), "all or nothing");
	}

	@Test
	void aFrozenOwnerRefusesIt() throws Exception
	{
		final int giran = warehouse();
		try (Connection connection = DatabaseFactory.getConnection())
		{
			ModuleState.freeze(connection, "WORLD", "test", null);
		}
		try
		{
			final AssetAbort abort = assertThrows(AssetAbort.class, () -> relocate(KILLS.incrementAndGet(), new Relocation(ADENA, 100, giran)));
			assertEquals(AssetAbort.Reason.MODULE_FROZEN, abort.reason());
			assertEquals(0, _f.held(giran, ADENA));
		}
		finally
		{
			try (Connection connection = DatabaseFactory.getConnection())
			{
				ModuleState.unfreeze(connection, "WORLD");
			}
		}
	}

	@Test
	void aSecondRelocationOfOneKillAbortsAsSettled() throws Exception
	{
		final int giran = warehouse();
		final long kill = KILLS.incrementAndGet();
		assertEquals(Outcome.COMMITTED, relocate(kill, new Relocation(ADENA, 10, giran)));
		assertEquals(AssetAbort.Reason.ALREADY_SETTLED, assertThrows(AssetAbort.class, () -> relocate(kill, new Relocation(ADENA, 10, giran))).reason());
		assertEquals(10, _f.held(giran, ADENA), "relocated once");
	}
}
