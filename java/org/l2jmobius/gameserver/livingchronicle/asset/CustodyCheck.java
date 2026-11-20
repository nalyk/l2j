package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.livingchronicle.journal.OutboxStager;

/**
 * The ledger half of the Asset Service's startup recovery (docs/spec/asset-service.md §3.2, §7): before any login, INV03,
 * INV04, INV05 and INV11 are checked over every custody, and ESCROW rows whose custody does not exist are found. Each
 * violation over an {@code OPEN} custody is recorded ({@link Violations}: evidence, custody and owner module frozen); one
 * already {@code FROZEN} is reported again but not recorded twice. Nothing is ever repaired here.
 */
public final class CustodyCheck
{
	private final Connections _db;
	private final OutboxStager _stager;
	private final Map<String, HoldingPolicy> _owners;

	/**
	 * @param custodiesChecked how many custodies the pass read
	 * @param violations one line per violation: "custody &lt;id&gt;: INVnn: evidence"
	 */
	public record Report(int custodiesChecked, List<String> violations)
	{
	}

	public CustodyCheck(Connections db, OutboxStager stager, Map<String, HoldingPolicy> owners)
	{
		_db = db;
		_stager = stager;
		_owners = Map.copyOf(owners);
	}

	public Report run() throws SQLException
	{
		final List<String> violations = new ArrayList<>();
		final List<Custody> custodies = new ArrayList<>();
		final Map<Integer, Long> orphans = new LinkedHashMap<>();
		try (Connection connection = _db.open())
		{
			try (PreparedStatement read = connection.prepareStatement("SELECT custody_id, kind, owner_module, owner_ref, state, version FROM lc_custody ORDER BY custody_id");
				ResultSet result = read.executeQuery())
			{
				while (result.next())
				{
					custodies.add(new Custody(result.getInt(1), Custody.Kind.valueOf(result.getString(2)), result.getString(3), result.getLong(4), Custody.State.valueOf(result.getString(5)), result.getInt(6)));
				}
			}
			try (PreparedStatement read = connection.prepareStatement("SELECT i.loc_data, COUNT(*) FROM items i LEFT JOIN lc_custody c ON c.custody_id = i.loc_data WHERE i.loc = 'ESCROW' AND c.custody_id IS NULL GROUP BY i.loc_data ORDER BY i.loc_data");
				ResultSet result = read.executeQuery())
			{
				while (result.next())
				{
					orphans.put(result.getInt(1), result.getLong(2));
				}
			}
			for (Custody custody : custodies)
			{
				final String violation = CustodyInvariants.violation(connection, custody, _owners::get);
				if (violation == null)
				{
					continue;
				}
				violations.add("custody " + custody.id() + ": " + violation);
				// Recorded once: a frozen custody, or a closed one whose owner is already frozen, is reported but not recorded again.
				if ((custody.state() == Custody.State.OPEN) || ((custody.state() == Custody.State.CLOSED) && !ModuleState.isFrozen(connection, custody.ownerModule())))
				{
					Violations.record(connection, _stager, custody.id(), invariant(violation), violation);
				}
			}
			for (Map.Entry<Integer, Long> orphan : orphans.entrySet())
			{
				final String violation = "INV03: " + orphan.getValue() + " ESCROW rows under no custody";
				violations.add("custody " + orphan.getKey() + ": " + violation);
				if (!ModuleState.isFrozen(connection, Violations.ASSET_MODULE))
				{
					Violations.record(connection, _stager, orphan.getKey(), "INV03", violation);
				}
			}
			connection.setAutoCommit(false);
			_stager.stage(connection, null, List.of(new AssetFact("Asset.RecoveryCompleted", "asset.recovery", Json.object(Map.of("custodies_checked", custodies.size(), "violations", violations.size())))));
			connection.commit();
		}
		return new Report(custodies.size(), List.copyOf(violations));
	}

	private static String invariant(String violation)
	{
		return violation.substring(0, violation.indexOf(':'));
	}
}
