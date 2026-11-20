package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import org.l2jmobius.gameserver.livingchronicle.journal.OutboxStager;

/**
 * The one path by which Living Chronicle code moves or holds an item or Adena (docs/spec/asset-service.md). Each asset
 * operation is an {@link AssetTx}: legs collected by the caller, executed and committed as one InnoDB transaction on the
 * service's own connection.
 */
public final class AssetService
{
	private final Connections _db;
	private final ObjectIds _ids;
	private final OutboxStager _stager;
	private final AssetConfig _config;
	private final Map<String, HoldingPolicy> _owners;

	/**
	 * @param owners each owner module's holding predicate (INV05), by module name
	 */
	public AssetService(Connections db, ObjectIds ids, OutboxStager stager, AssetConfig config, Map<String, HoldingPolicy> owners)
	{
		_db = db;
		_ids = ids;
		_stager = stager;
		_config = config;
		_owners = Map.copyOf(owners);
	}

	/**
	 * Begins an operation. If {@code op} committed before, or its refund did, the returned transaction does nothing and its
	 * {@link AssetTx#commit()} returns that earlier outcome (§3.2: a refunded attempt is final; a retry is a new attempt).
	 */
	public AssetTx begin(OpId op, String module, String action, long subjectRef) throws SQLException
	{
		return new AssetTx(this, op, module, action, subjectRef, priorOutcome(op));
	}

	private Outcome priorOutcome(OpId op) throws SQLException
	{
		try (Connection connection = _db.open();
			PreparedStatement read = connection.prepareStatement("SELECT op_id FROM lc_asset_op WHERE op_id IN (?, ?)"))
		{
			read.setBytes(1, op.bytes());
			read.setBytes(2, op.refund().bytes());
			Outcome outcome = null;
			try (ResultSet result = read.executeQuery())
			{
				while (result.next())
				{
					final OpId found = new OpId(result.getBytes(1));
					outcome = found.equals(op) ? Outcome.ALREADY_COMMITTED : (outcome == null ? Outcome.ALREADY_REFUNDED : outcome);
				}
			}
			return outcome;
		}
	}

	Connections db()
	{
		return _db;
	}

	ObjectIds ids()
	{
		return _ids;
	}

	OutboxStager stager()
	{
		return _stager;
	}

	AssetConfig config()
	{
		return _config;
	}

	HoldingPolicy owner(String module)
	{
		return _owners.get(module);
	}
}
