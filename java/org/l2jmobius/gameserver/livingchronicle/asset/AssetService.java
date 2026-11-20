package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.function.IntConsumer;

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
	private final IntConsumer _adopt;

	/**
	 * @param owners each owner module's holding predicate (INV05), by module name
	 */
	public AssetService(Connections db, ObjectIds ids, OutboxStager stager, AssetConfig config, Map<String, HoldingPolicy> owners)
	{
		this(db, ids, stager, config, owners, messageId ->
		{
			// No game server: nothing adopts service mail.
		});
	}
	
	/**
	 * @param adopt hands each committed service mail to the game's mail (Mobius's {@code MailManager.adopt}, CP-0014)
	 */
	public AssetService(Connections db, ObjectIds ids, OutboxStager stager, AssetConfig config, Map<String, HoldingPolicy> owners, IntConsumer adopt)
	{
		_adopt = adopt;
		_db = db;
		ServiceMail.activate(); // Living Chronicle runs: claims of service mail are recorded
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
			final Outcome outcome = tx.commit();
			if (outcome == Outcome.COMMITTED)
			{
				return opened[0];
			}
			if (outcome == Outcome.ALREADY_REFUNDED)
			{
				throw new AssetAbort(AssetAbort.Reason.NOT_COMMITTED, op + " was refunded; opening again needs a new attempt id");
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

	/**
	 * Returns a frozen module to {@code RUNNING} (§3.1 {@code FROZEN → OPEN}, §4.5): only when every invariant over each of
	 * its custodies holds; its frozen custodies reopen, each with {@code Asset.AdminCorrection}. One transaction.
	 * @throws IllegalStateException naming the first custody whose invariant is still broken
	 */
	public void unfreeze(String module, int adminCharId, String reason) throws SQLException
	{
		if ((reason == null) || reason.isBlank())
		{
			throw new IllegalArgumentException("an unfreeze needs a reason");
		}
		try (Connection connection = _db.open())
		{
			connection.setAutoCommit(false);
			try
			{
				final java.util.List<Custody> custodies = new java.util.ArrayList<>();
				try (PreparedStatement read = connection.prepareStatement("SELECT custody_id, kind, owner_ref, state, version FROM lc_custody WHERE owner_module = ? ORDER BY custody_id FOR UPDATE"))
				{
					read.setString(1, module);
					try (ResultSet result = read.executeQuery())
					{
						while (result.next())
						{
							custodies.add(new Custody(result.getInt(1), Custody.Kind.valueOf(result.getString(2)), module, result.getLong(3), Custody.State.valueOf(result.getString(4)), result.getInt(5)));
						}
					}
				}
				final java.util.List<org.l2jmobius.gameserver.livingchronicle.journal.DurableFact> facts = new java.util.ArrayList<>();
				for (Custody custody : custodies)
				{
					final String violation = CustodyInvariants.violation(connection, custody, this::owner);
					if (violation != null)
					{
						throw new IllegalStateException(module + " stays frozen: custody " + custody.id() + " " + violation);
					}
					if (custody.state() == Custody.State.FROZEN)
					{
						try (PreparedStatement reopen = connection.prepareStatement("UPDATE lc_custody SET state = 'OPEN', version = version + 1 WHERE custody_id = ?"))
						{
							reopen.setInt(1, custody.id());
							reopen.executeUpdate();
						}
						facts.add(AssetFact.of("AdminCorrection", custody.id(), "admin", adminCharId, "reason", reason, "custody", custody.id(), "unfrozen", true));
					}
				}
				if (Violations.ASSET_MODULE.equals(module))
				{
					try (PreparedStatement orphans = connection.prepareStatement("SELECT COUNT(*) FROM items i LEFT JOIN lc_custody c ON c.custody_id = i.loc_data WHERE i.loc = 'ESCROW' AND c.custody_id IS NULL");
						ResultSet result = orphans.executeQuery())
					{
						result.next();
						if (result.getLong(1) > 0)
						{
							throw new IllegalStateException(module + " stays frozen: " + result.getLong(1) + " ESCROW rows under no custody (INV03)");
						}
					}
				}
				ModuleState.unfreeze(connection, module);
				if (!facts.isEmpty())
				{
					_stager.stage(connection, null, facts);
				}
				connection.commit();
			}
			catch (SQLException | RuntimeException e)
			{
				connection.rollback();
				throw e;
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

	/** After commit: the game's mail adopts the message; a failure is logged by the adopter and repaired by the census. */
	void adopt(int messageId)
	{
		_adopt.accept(messageId);
	}
	
	HoldingPolicy owner(String module)
	{
		return _owners.get(module);
	}
}
