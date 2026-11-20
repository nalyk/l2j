package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * The freeze switch every Living Chronicle module honours (docs/spec/asset-service.md §2.2 lc_module_state, §6).
 */
class ModuleStateTest
{
	private static TestDatabase _database;

	@BeforeAll
	static void migrate() throws Exception
	{
		_database = TestDatabase.start();
		try (Connection connection = _database.connect())
		{
			Migrations.applyAll(connection);
		}
	}

	@Test
	void aModuleWithNoRowRuns() throws Exception
	{
		try (Connection connection = _database.connect())
		{
			assertFalse(ModuleState.isFrozen(connection, "NEVER"));
		}
	}

	@Test
	void freezeAndUnfreeze() throws Exception
	{
		try (Connection connection = _database.connect())
		{
			ModuleState.freeze(connection, "PACT", "INV11", 77L);
			assertTrue(ModuleState.isFrozen(connection, "PACT"));
			ModuleState.freeze(connection, "PACT", "INV03", null);
			try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT reason, evidence_fact_id FROM lc_module_state WHERE module = 'PACT'"))
			{
				result.next();
				assertEquals("INV11", result.getString(1), "a second freeze keeps the first reason and evidence");
				assertEquals(77L, result.getLong(2));
			}
			ModuleState.unfreeze(connection, "PACT");
			assertFalse(ModuleState.isFrozen(connection, "PACT"));
		}
	}

	@Test
	void aFreezeNeedsAReason() throws Exception
	{
		try (Connection connection = _database.connect())
		{
			assertThrows(IllegalArgumentException.class, () -> ModuleState.freeze(connection, "PACT", " ", null));
		}
	}
}
