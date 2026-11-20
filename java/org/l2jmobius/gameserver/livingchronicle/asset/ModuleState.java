package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The freeze switch every Living Chronicle module honours ({@code lc_module_state}, blueprint 04 §8): a module with no row
 * runs. A violated invariant freezes the owning module; only a corrective admin operation, and then {@code unfreeze}, end it.
 * <p>
 * Each call runs in the caller's connection and transaction. A freeze keeps the reason and evidence of the first violation:
 * a later one adds nothing a person repairing the first needs more.
 * </p>
 */
public final class ModuleState
{
	private ModuleState()
	{
	}

	public static boolean isFrozen(Connection connection, String module) throws SQLException
	{
		try (PreparedStatement read = connection.prepareStatement("SELECT state FROM lc_module_state WHERE module = ?"))
		{
			read.setString(1, module);
			try (ResultSet result = read.executeQuery())
			{
				return result.next() && "FROZEN".equals(result.getString(1));
			}
		}
	}

	public static void freeze(Connection connection, String module, String reason, Long evidenceFactId) throws SQLException
	{
		if ((reason == null) || reason.isBlank())
		{
			throw new IllegalArgumentException("a freeze needs a reason");
		}
		// MariaDB applies the assignments left to right, so state changes last, after the others have read the old one.
		try (PreparedStatement freeze = connection.prepareStatement("INSERT INTO lc_module_state (module, state, reason, since, evidence_fact_id) VALUES (?, 'FROZEN', ?, NOW(3), ?) ON DUPLICATE KEY UPDATE reason = IF(state = 'FROZEN', reason, VALUES(reason)), evidence_fact_id = IF(state = 'FROZEN', evidence_fact_id, VALUES(evidence_fact_id)), since = IF(state = 'FROZEN', since, VALUES(since)), state = 'FROZEN'"))
		{
			freeze.setString(1, module);
			freeze.setString(2, reason);
			if (evidenceFactId == null)
			{
				freeze.setNull(3, java.sql.Types.BIGINT);
			}
			else
			{
				freeze.setLong(3, evidenceFactId);
			}
			freeze.executeUpdate();
		}
	}

	public static void unfreeze(Connection connection, String module) throws SQLException
	{
		try (PreparedStatement run = connection.prepareStatement("UPDATE lc_module_state SET state = 'RUNNING', reason = NULL, evidence_fact_id = NULL, since = NOW(3) WHERE module = ?"))
		{
			run.setString(1, module);
			run.executeUpdate();
		}
	}
}
