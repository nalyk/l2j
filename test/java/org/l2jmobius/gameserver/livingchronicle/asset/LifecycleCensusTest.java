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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.livingchronicle.journal.OutboxStager;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.testing.TestServer;

/**
 * ASSET-INV08 at the census (docs/spec/asset-service.md §6, §11.35): a live lifecycle whose object id a row holds as
 * another item, or a live lifecycle of a stackable item, is a violation that freezes the Asset Service; an object in no
 * row is not (it is ended at the next start).
 */
class LifecycleCensusTest
{
	private static final int SHORT_SWORD = 1;
	private static final int ADENA = 57;
	private static AssetFixtures _f;
	private static TestServer _server;
	private String _inserted = "0";

	@BeforeAll
	static void boot() throws Exception
	{
		_server = TestServer.boot();
		_f = new AssetFixtures(_server.database(), () -> IdManager.getInstance().getNextId(), Map.of("TEST", (connection, ref) -> true), id -> MailManager.getInstance().adopt(id));
	}

	@AfterEach
	void clear() throws Exception
	{
		_f.execute("UPDATE lc_item_lifecycle SET ended_at = NOW(3), end_reason = 'VANISHED' WHERE ended_at IS NULL AND object_id IN (" + _inserted + ")");
		_inserted = "0";
		_f.execute("DELETE FROM lc_module_state WHERE module = 'ASSET'");
	}

	private void lifecycle(int objectId, int itemId) throws Exception
	{
		_f.execute("INSERT INTO lc_item_lifecycle (object_id, item_id, began_at) VALUES (" + objectId + ", " + itemId + ", NOW(3))");
		_inserted += "," + objectId;
	}

	private static List<String> census() throws Exception
	{
		try (Connection connection = _server.database().connect())
		{
			return AssetRecovery.lifecycles(connection, new OutboxStager(Clock.systemUTC(), () -> 0L, () -> 1));
		}
	}

	@Test
	void aLifecycleWhoseObjectIdIsHeldAsAnotherItemIsAViolation() throws Exception
	{
		final int custody = _f.open("TEST");
		final int object = _f.seed(custody, ADENA, 10, true); // the id now holds Adena
		lifecycle(object, SHORT_SWORD);
		final List<String> violations = census();
		assertEquals(1, violations.size(), violations.toString());
		assertTrue(violations.get(0).startsWith("INV08"), violations.get(0));
		assertTrue(_f.frozen("ASSET"), "the Asset Service freezes: no custody owns a lifecycle");
	}

	@Test
	void aLiveLifecycleOfAStackableItemIsAViolation() throws Exception
	{
		lifecycle(IdManager.getInstance().getNextId(), ADENA);
		final List<String> violations = census();
		assertEquals(1, violations.size(), violations.toString());
		assertTrue(violations.get(0).contains("stackable"), violations.get(0));
	}

	@Test
	void aLifecycleWhoseObjectIsInNoRowIsNoViolation() throws Exception
	{
		lifecycle(IdManager.getInstance().getNextId(), SHORT_SWORD);
		assertEquals(List.of(), census(), "a decayed ground item: ended at the next start");
	}
}
