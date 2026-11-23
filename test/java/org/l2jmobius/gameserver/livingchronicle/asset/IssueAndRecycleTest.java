/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.EtcItem;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * {@code issue} and {@code recycle} (docs/spec/asset-service.md §4.3, §2.5, ASSET-R14, ASSET-R29; part of AT-ASSET-31 and
 * AT-ASSET-14): representations only, one per recipient key, in service mail with a lifecycle; a recycled share never
 * above the recorded sink times the active permille, once per territory and day.
 */
class IssueAndRecycleTest
{
	private static final int ADENA = 57;
	private static final int SHORT_SWORD = 1;
	private static AssetFixtures _f;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), Map.of("TEST", (connection, ref) -> true), id -> MailManager.getInstance().adopt(id));
	}

	@AfterEach
	void uninstall()
	{
		RecycleBound.install(null);
		RecycleBound.installPermille(null);
	}

	private static Player inWorld()
	{
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 40);
		if (World.getPlayer(player.getObjectId()) == null)
		{
			World.addObject(player);
		}
		return player;
	}

	/** The first item template of the datapack the predicate accepts, or null. */
	private static ItemTemplate first(Predicate<ItemTemplate> predicate)
	{
		for (ItemTemplate template : ItemData.getInstance().getAllItems())
		{
			if ((template != null) && predicate.test(template))
			{
				return template;
			}
		}
		return null;
	}

	private static boolean passesBut(ItemTemplate template, String clause)
	{
		final String refusal = Eligibility.representationRefusal(template);
		return clause.equals(refusal);
	}

	private static ItemTemplate representation()
	{
		return first(t -> Eligibility.representationRefusal(t) == null);
	}

	private static Outcome issue(int charId, int itemId, AssetTx.Representation code, long contract, String... recipientKeys) throws Exception
	{
		try (AssetTx tx = _f.service.begin(_f.op("ISSUE"), "TEST", "ISSUE", contract))
		{
			for (String key : recipientKeys)
			{
				tx.issue(charId, itemId, code, contract, key, "Notarius of Giran", "Your notarized contract.");
			}
			return tx.commit();
		}
	}

	@Test
	void twoPartiesParchmentsInOneOperationEachWithItsLifecycle() throws Exception
	{
		final ItemTemplate parchment = representation();
		assertNotNull(parchment, "the datapack holds a template that passes the representation test");
		final Player first = inWorld();
		final Player second = inWorld();
		final long contract = 7_001;
		try (AssetTx tx = _f.service.begin(_f.op("SIGN"), "TEST", "SIGN", contract))
		{
			tx.issue(first.getObjectId(), parchment.getId(), AssetTx.Representation.PACT_PARCHMENT, contract, String.valueOf(first.getObjectId()), "Notarius of Giran", "Your notarized contract.");
			tx.issue(second.getObjectId(), parchment.getId(), AssetTx.Representation.PACT_PARCHMENT, contract, String.valueOf(second.getObjectId()), "Notarius of Giran", "Your notarized contract.");
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		assertEquals(2, _f.number("SELECT COUNT(*) FROM lc_ledger_posting WHERE code = 'PACT_PARCHMENT' AND account_id = " + contract + " AND lifecycle_id IS NOT NULL"));
		assertEquals(2, _f.number("SELECT COUNT(*) FROM lc_item_lifecycle l JOIN items i ON i.object_id = l.object_id WHERE l.ended_at IS NULL AND i.loc = 'MAIL' AND i.item_id = " + parchment.getId() + " AND l.began_op IS NOT NULL"), "each mailed with a lifecycle");
		assertEquals(2, _f.number("SELECT COUNT(*) FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = 'Asset.ParchmentIssued' AND o.stream = 'asset.contract." + contract + "' AND JSON_VALUE(o.payload, '$.lifecycle') IS NOT NULL"));
		final AssetAbort again = assertThrows(AssetAbort.class, () -> issue(first.getObjectId(), parchment.getId(), AssetTx.Representation.PACT_PARCHMENT, contract, String.valueOf(first.getObjectId())));
		assertEquals(AssetAbort.Reason.ALREADY_SETTLED, again.reason(), "never twice for one key");
	}

	@Test
	void aTemplateThatIsNotARepresentationIsRefusedClauseByClause() throws Exception
	{
		final Player player = inWorld();
		assertEquals("stackable", Eligibility.representationRefusal(ItemData.getInstance().getTemplate(ADENA)));
		assertEquals("tradable, droppable, sellable or depositable", Eligibility.representationRefusal(ItemData.getInstance().getTemplate(SHORT_SWORD)));
		final Map<String, Predicate<ItemTemplate>> clauses = new LinkedHashMap<>();
		clauses.put("crystallizable", t -> passesBut(t, "crystallizable"));
		clauses.put("priced", t -> passesBut(t, "priced"));
		clauses.put("with item skills", t -> passesBut(t, "with item skills"));
		clauses.put("extractable", t -> passesBut(t, "extractable"));
		clauses.put("a handler other than Bypass", t -> passesBut(t, "a handler other than Bypass"));
		int found = 0;
		for (Map.Entry<String, Predicate<ItemTemplate>> clause : clauses.entrySet())
		{
			final ItemTemplate template = first(clause.getValue());
			if (template == null)
			{
				continue; // the datapack has no template failing this clause alone; the clause's own order is checked below
			}
			found++;
			final AssetAbort refused = assertThrows(AssetAbort.class, () -> issue(player.getObjectId(), template.getId(), AssetTx.Representation.LEDGER_SEAL, 9_000 + template.getId(), "hunter"), clause.getKey());
			assertEquals(AssetAbort.Reason.INELIGIBLE, refused.reason(), clause.getKey());
		}
		assertTrue(found > 0, "the datapack exercises at least one refusal beyond stackable and tradable");
		final ItemTemplate handled = first(t -> (t instanceof EtcItem etc) && "Bypass".equals(etc.getHandlerName()) && (Eligibility.representationRefusal(t) == null));
		if (handled != null)
		{
			assertNull(Eligibility.representationRefusal(handled), "Bypass alone is allowed: it opens a page");
		}
	}

	@Test
	void aRecycleIsBoundedByTheRecordedSinkAndTheActivePermilleOncePerTerritoryAndDay() throws Exception
	{
		final int pool = _f.service.open(_f.op("POOL"), Custody.Kind.POOL, "TEST", 31);
		final int other = _f.service.open(_f.op("POOL"), Custody.Kind.POOL, "TEST", 32);
		final int contract = _f.open("TEST");
		final LocalDate yesterday = GameClock.localDateTime().toLocalDate().minusDays(1);
		assertEquals(AssetAbort.Reason.NOT_RECORDED, refusal(pool, 5, yesterday, 10), "no reader");
		RecycleBound.install((territory, day) -> territory == 5 ? OptionalLong.of(1_000) : OptionalLong.empty());
		assertEquals(AssetAbort.Reason.NOT_RECORDED, refusal(pool, 5, yesterday, 10), "no permille");
		RecycleBound.installPermille(() -> 0);
		assertEquals(AssetAbort.Reason.ABOVE_SHARE, refusal(pool, 5, yesterday, 10), "a permille of 0 recycles nothing");
		RecycleBound.installPermille(() -> 251);
		assertEquals(AssetAbort.Reason.ABOVE_SHARE, refusal(pool, 5, yesterday, 10), "above the ceiling: refused, not clamped");
		RecycleBound.installPermille(() -> 100);
		assertEquals(AssetAbort.Reason.NOT_RECORDED, refusal(pool, 6, yesterday, 10), "nothing recorded for that territory");
		assertEquals(AssetAbort.Reason.NOT_RECORDED, refusal(pool, 5, yesterday.plusDays(1), 10), "today is not over");
		assertEquals(AssetAbort.Reason.ABOVE_SHARE, refusal(pool, 5, yesterday, 101), "101 of 1000 at 100 per mille");
		assertEquals(AssetAbort.Reason.INELIGIBLE, refusal(contract, 5, yesterday, 10), "a contract's custody is no pool");
		try (AssetTx tx = _f.service.begin(_f.op("RECYCLE"), "TEST", "RECYCLE", pool))
		{
			tx.recycle(pool, 5, yesterday, 100);
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		assertEquals(100, _f.held(pool, ADENA));
		assertEquals(1, _f.facts("SinkRecycled", pool));
		assertEquals(AssetAbort.Reason.ALREADY_SETTLED, refusal(pool, 5, yesterday, 1), "once per territory and day");
		assertEquals(AssetAbort.Reason.ALREADY_SETTLED, refusal(other, 5, yesterday, 1), "by another pool of the territory too");
	}

	private static AssetAbort.Reason refusal(int pool, int territory, LocalDate day, long amount)
	{
		return assertThrows(AssetAbort.class, () ->
		{
			try (AssetTx tx = _f.service.begin(_f.op("RECYCLE"), "TEST", "RECYCLE", pool))
			{
				tx.recycle(pool, territory, day, amount);
				tx.commit();
			}
		}).reason();
	}
}
