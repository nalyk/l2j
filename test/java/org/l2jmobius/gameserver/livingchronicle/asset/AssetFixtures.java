package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.gameserver.livingchronicle.journal.OutboxStager;
import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * A migrated test database with an Asset Service over it, and the queries the asset tests share.
 */
final class AssetFixtures
{
	static final int ADENA = 57;
	static final int SWORD = 2369;

	final TestDatabase database;
	final AssetService service;
	private final AtomicInteger _ids;
	private ObjectIds _external;
	private final AtomicInteger _subjects = new AtomicInteger();

	AssetFixtures(int firstObjectId, Map<String, HoldingPolicy> owners) throws Exception
	{
		database = TestDatabase.start();
		try (Connection connection = database.connect())
		{
			Migrations.applyAll(connection);
		}
		_ids = new AtomicInteger(firstObjectId);
		_external = null;
		service = new AssetService(database::connect, _ids::incrementAndGet, new OutboxStager(Clock.systemUTC(), () -> 0L, () -> 1), AssetConfig.defaults(), owners);
	}

	/**
	 * Over a booted server's database: the Living Chronicle migrations applied once, object ids from Mobius's id
	 * manager, and service mail adopted by its {@code MailManager}.
	 */
	AssetFixtures(TestDatabase serverDatabase, ObjectIds ids, Map<String, HoldingPolicy> owners, java.util.function.IntConsumer adopt) throws Exception
	{
		database = serverDatabase;
		try (Connection connection = database.connect();
			ResultSet result = connection.getMetaData().getTables(null, null, "lc_custody", null))
		{
			if (!result.next())
			{
				Migrations.applyAll(connection);
			}
		}
		_ids = null;
		_external = ids;
		service = new AssetService(database::connect, ids, new OutboxStager(Clock.systemUTC(), () -> 0L, () -> 1), AssetConfig.defaults(), owners, adopt);
	}

	OpId op(String action)
	{
		return OpId.of("TEST", _subjects.incrementAndGet(), action, 0);
	}

	int open(String module) throws Exception
	{
		return service.open(op("OPEN"), Custody.Kind.CONTRACT, module, _subjects.get());
	}

	/** Puts assets into a custody the way a correction would: an op row, a balanced SOURCE leg, and the row. */
	int seed(int custody, int itemId, long qty, boolean wholeObject) throws SQLException
	{
		final OpId op = op("SEED");
		final int objectId = _external != null ? _external.next() : _ids.incrementAndGet();
		try (Connection connection = database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO lc_asset_op VALUES (UNHEX('" + op.hex() + "'), 'TEST', 'SEED', 0, NOW(3))");
			statement.execute("INSERT INTO lc_ledger_posting (op_id, leg, account_kind, account_id, code, item_id, object_id, qty, direction) VALUES (UNHEX('" + op.hex() + "'), 0, 'SOURCE', 0, 'ADMIN_CORRECTION', " + itemId + ", NULL, " + qty + ", 'DEBIT'), (UNHEX('" + op.hex() + "'), 1, 'CUSTODY', " + custody + ", NULL, " + itemId + ", " + (wholeObject ? objectId : "NULL") + ", " + qty + ", 'CREDIT')");
			statement.execute("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data) VALUES (-1, " + objectId + ", " + itemId + ", " + qty + ", 0, 'ESCROW', " + custody + ")");
		}
		return objectId;
	}

	long held(int custody, int itemId) throws SQLException
	{
		return number("SELECT COALESCE(SUM(count), 0) FROM items WHERE owner_id = -1 AND loc = 'ESCROW' AND loc_data = " + custody + " AND item_id = " + itemId);
	}

	long posted(int custody, int itemId) throws SQLException
	{
		return number("SELECT COALESCE(SUM(IF(direction = 'CREDIT', qty, -qty)), 0) FROM lc_ledger_posting WHERE account_kind = 'CUSTODY' AND account_id = " + custody + " AND item_id = " + itemId);
	}

	void execute(String sql) throws SQLException
	{
		try (Connection connection = database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute(sql);
		}
	}

	long number(String sql) throws SQLException
	{
		try (Connection connection = database.connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery(sql))
		{
			result.next();
			return result.getLong(1);
		}
	}

	String text(String sql) throws SQLException
	{
		try (Connection connection = database.connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery(sql))
		{
			result.next();
			return result.getString(1);
		}
	}

	long facts(String type, int custody) throws SQLException
	{
		return number("SELECT COUNT(*) FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = 'Asset." + type + "' AND o.stream = 'asset.custody." + custody + "'");
	}

	String lastPayload(String type, int custody) throws SQLException
	{
		return text("SELECT o.payload FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = 'Asset." + type + "' AND o.stream = 'asset.custody." + custody + "' ORDER BY o.outbox_id DESC LIMIT 1");
	}

	boolean frozen(String module) throws SQLException
	{
		try (Connection connection = database.connect())
		{
			return ModuleState.isFrozen(connection, module);
		}
	}
}
