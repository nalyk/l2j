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

	/**
	 * Opens a custody (§4.3 {@code open}): an operation of its own, since a custody must exist before other operations
	 * name it. A retry of the same id returns the custody it opened.
	 */
	public int open(OpId op, Custody.Kind kind, String ownerModule, long ownerRef) throws SQLException, AssetAbort
	{
		final int[] opened = { 0 };
		try (AssetTx tx = begin(op, ownerModule, "OPEN", ownerRef))
		{
			tx.step(execution ->
			{
				if (ModuleState.isFrozen(execution.connection(), ownerModule))
				{
					throw new AssetAbort(AssetAbort.Reason.MODULE_FROZEN, ownerModule + " is frozen");
				}
				try (PreparedStatement insert = execution.connection().prepareStatement("INSERT INTO lc_custody (kind, owner_module, owner_ref, state, version, created_op, created_at) VALUES (?, ?, ?, 'OPEN', 0, ?, NOW(3))", java.sql.Statement.RETURN_GENERATED_KEYS))
				{
					insert.setString(1, kind.name());
					insert.setString(2, ownerModule);
					insert.setLong(3, ownerRef);
					insert.setBytes(4, op.bytes());
					insert.executeUpdate();
					try (ResultSet keys = insert.getGeneratedKeys())
					{
						keys.next();
						opened[0] = keys.getInt(1);
					}
				}
				execution.facts().add(AssetFact.of("CustodyOpened", opened[0], "custody", opened[0], "kind", kind.name(), "owner_module", ownerModule, "owner_ref", ownerRef));
			});
			if (tx.commit() == Outcome.COMMITTED)
			{
				return opened[0];
			}
		}
		try (Connection connection = _db.open();
			PreparedStatement read = connection.prepareStatement("SELECT custody_id FROM lc_custody WHERE created_op = ?"))
		{
			read.setBytes(1, op.bytes());
			try (ResultSet result = read.executeQuery())
			{
				if (!result.next())
				{
					throw new IllegalStateException(op + " committed but opened no custody");
				}
				return result.getInt(1);
			}
		}
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
