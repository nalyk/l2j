package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.livingchronicle.journal.OutboxStager;

/**
 * The startup custody check, ledger half of recovery (docs/spec/asset-service.md §3.2, §6, §7 "GameServer restarted in any
 * custody state"): INV03, INV04, INV05 and INV11 over every custody, each violation frozen with its evidence, nothing
 * repaired. AT-ASSET-25 for custodies. Every test checks only its own custodies: the check scans the whole database.
 */
class CustodyCheckTest
{
	private static final AtomicBoolean GUILD_ALLOWS = new AtomicBoolean(true);
	private static AssetFixtures _f;
	private static CustodyCheck _check;

	@BeforeAll
	static void start() throws Exception
	{
		final HoldingPolicy always = (connection, ref) -> true;
		final Map<String, HoldingPolicy> owners = Map.of("TEST", always, "CHECKED", always, "CLOSER", always, "GUILD", (connection, ref) -> GUILD_ALLOWS.get());
		_f = new AssetFixtures(800_000_000, owners);
		_check = new CustodyCheck(_f.database::connect, new OutboxStager(Clock.systemUTC(), () -> 0L, () -> 1), owners);
	}

	private static List<String> violationsOf(CustodyCheck.Report report, int custody)
	{
		return report.violations().stream().filter(v -> v.startsWith("custody " + custody + ":")).toList();
	}

	@Test
	void aConsistentCustodyPassesAndRecoveryIsRecorded() throws Exception
	{
		final int custody = _f.open("TEST");
		_f.seed(custody, ADENA, 100, false);
		final CustodyCheck.Report report = _check.run();
		assertEquals(List.of(), violationsOf(report, custody));
		assertTrue(report.custodiesChecked() >= 1);
		assertEquals("OPEN", _f.text("SELECT state FROM lc_custody WHERE custody_id = " + custody));
		assertTrue(_f.number("SELECT COUNT(*) FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = 'Asset.RecoveryCompleted'") >= 1);
	}

	@Test
	void aTamperedEscrowRowFreezesItsCustodyAtStartup() throws Exception
	{
		final int custody = _f.open("CHECKED");
		final int row = _f.seed(custody, ADENA, 100, false);
		_f.execute("UPDATE items SET count = count + 1 WHERE object_id = " + row);
		final List<String> violations = violationsOf(_check.run(), custody);
		assertEquals(1, violations.size());
		assertTrue(violations.get(0).contains("INV11"), violations.get(0));
		assertEquals("FROZEN", _f.text("SELECT state FROM lc_custody WHERE custody_id = " + custody));
		assertTrue(_f.frozen("CHECKED"));
		assertEquals(101, _f.held(custody, ADENA), "never repaired");
		assertEquals(1, _f.facts("InvariantViolated", custody));
		_check.run();
		assertEquals(1, _f.facts("InvariantViolated", custody), "an already frozen custody is reported, not recorded again");
	}

	@Test
	void anEscrowRowUnderAClosedCustodyIsAViolation() throws Exception
	{
		final int custody = _f.open("CLOSER");
		try (AssetTx tx = _f.service.begin(_f.op("CLOSE"), "CLOSER", "CLOSE", custody))
		{
			tx.close(custody);
			tx.commit();
		}
		_f.execute("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data) VALUES (-1, 899999001, " + ADENA + ", 5, 0, 'ESCROW', " + custody + ")");
		final List<String> violations = violationsOf(_check.run(), custody);
		assertEquals(1, violations.size());
		assertTrue(violations.get(0).contains("INV04"), violations.get(0));
	}

	@Test
	void anOwnerThatNoLongerAllowsHoldingIsAViolation() throws Exception
	{
		final int custody = _f.open("GUILD");
		_f.seed(custody, ADENA, 10, false);
		GUILD_ALLOWS.set(false);
		try
		{
			final List<String> violations = violationsOf(_check.run(), custody);
			assertEquals(1, violations.size());
			assertTrue(violations.get(0).contains("INV05"), violations.get(0));
			assertTrue(_f.frozen("GUILD"));
		}
		finally
		{
			GUILD_ALLOWS.set(true);
		}
	}

	@Test
	void anEscrowRowOfNoCustodyFreezesTheAssetModule() throws Exception
	{
		final int missing = 2_000_000_000;
		_f.execute("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data) VALUES (-1, 899999002, " + ADENA + ", 7, 0, 'ESCROW', " + missing + ")");
		final List<String> violations = violationsOf(_check.run(), missing);
		assertEquals(1, violations.size());
		assertTrue(violations.get(0).contains("INV03"), violations.get(0));
		assertTrue(_f.frozen(Violations.ASSET_MODULE));
		assertEquals(7, _f.number("SELECT count FROM items WHERE object_id = 899999002"), "never deleted");
	}
}
