package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.l2jmobius.gameserver.livingchronicle.journal.OutboxStager;

/**
 * Records a violated invariant (docs/spec/asset-service.md §6): {@code Asset.InvariantViolated}, the custody
 * {@code FROZEN} with {@code Asset.CustodyFrozen}, and its owner module frozen (the {@code ASSET} module itself when the
 * owner cannot be told). It changes nothing else: the evidence stays as found, and repair is a corrective operation.
 */
final class Violations
{
	static final String ASSET_MODULE = "ASSET";

	private Violations()
	{
	}

	/** In its own transaction on {@code connection}, which it commits. */
	static void record(Connection connection, OutboxStager stager, int custody, String invariant, String evidence) throws SQLException
	{
		connection.setAutoCommit(false);
		try
		{
			String owner = ASSET_MODULE;
			try (PreparedStatement read = connection.prepareStatement("SELECT owner_module, state FROM lc_custody WHERE custody_id = ? FOR UPDATE"))
			{
				read.setInt(1, custody);
				try (ResultSet result = read.executeQuery())
				{
					if (result.next())
					{
						owner = result.getString(1);
					}
				}
			}
			final boolean froze;
			try (PreparedStatement freeze = connection.prepareStatement("UPDATE lc_custody SET state = 'FROZEN', version = version + 1 WHERE custody_id = ? AND state = 'OPEN'"))
			{
				freeze.setInt(1, custody);
				froze = freeze.executeUpdate() == 1;
			}
			ModuleState.freeze(connection, owner, invariant, null);
			final AssetFact violated = AssetFact.of("InvariantViolated", custody, "invariant", invariant, "custody", custody, "evidence", evidence);
			stager.stage(connection, null, froze ? List.of(violated, AssetFact.of("CustodyFrozen", custody, "custody", custody, "reason", invariant)) : List.of(violated));
			connection.commit();
		}
		catch (SQLException e)
		{
			connection.rollback();
			throw e;
		}
	}
}
