package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The invariants over one custody (docs/spec/asset-service.md §6): INV02 (no escrowed object also on the ground), INV03 (its ESCROW rows are owned by -1), INV04 (a
 * CLOSED custody holds nothing), INV05 (its owner allows the holding), INV11 (its postings equal its rows per item id).
 * Each check reads on the caller's connection and returns the evidence of a violation, or null.
 */
final class CustodyInvariants
{
	private CustodyInvariants()
	{
	}

	/** The first violation over the custody, as "INVnn: evidence", or null. */
	static String violation(Connection connection, Custody custody, Function<String, HoldingPolicy> owners) throws SQLException
	{
		final long twice = count(connection, "SELECT COUNT(*) FROM items i JOIN itemsonground g ON g.object_id = i.object_id WHERE i.loc = 'ESCROW' AND i.loc_data = ?", custody.id());
		if (twice > 0)
		{
			return "INV02: " + twice + " escrowed objects also lie on the ground";
		}
		final long foreign = count(connection, "SELECT COUNT(*) FROM items WHERE loc = 'ESCROW' AND loc_data = ? AND (owner_id IS NULL OR owner_id <> -1)", custody.id());
		if (foreign > 0)
		{
			return "INV03: " + foreign + " ESCROW rows not owned by -1";
		}
		final long rows = count(connection, "SELECT COUNT(*) FROM items WHERE owner_id = -1 AND loc = 'ESCROW' AND loc_data = ?", custody.id());
		if ((custody.state() == Custody.State.CLOSED) && (rows > 0))
		{
			return "INV04: CLOSED custody holds " + rows + " rows";
		}
		if (rows > 0)
		{
			final HoldingPolicy owner = owners.apply(custody.ownerModule());
			if ((owner == null) || !owner.allowsHolding(connection, custody.ownerRef()))
			{
				return "INV05: " + custody.ownerModule() + " " + custody.ownerRef() + " may not hold";
			}
		}
		final String inv11 = inv11(connection, custody.id());
		return inv11 == null ? null : "INV11: " + inv11;
	}

	/** INV11's evidence for the custody, or null when its postings equal its rows. */
	static String inv11(Connection connection, int custody) throws SQLException
	{
		final Map<Integer, Long> posted = posted(connection, custody);
		final Map<Integer, Long> held = held(connection, custody);
		return posted.equals(held) ? null : "postings " + posted + ", rows " + held;
	}

	static Map<Integer, Long> posted(Connection connection, int custody) throws SQLException
	{
		return sums(connection, "SELECT item_id, SUM(IF(direction = 'CREDIT', qty, -qty)) FROM lc_ledger_posting WHERE account_kind = 'CUSTODY' AND account_id = ? GROUP BY item_id", custody);
	}

	static Map<Integer, Long> held(Connection connection, int custody) throws SQLException
	{
		return sums(connection, "SELECT item_id, SUM(count) FROM items WHERE owner_id = -1 AND loc = 'ESCROW' AND loc_data = ? GROUP BY item_id", custody);
	}

	private static Map<Integer, Long> sums(Connection connection, String sql, int custody) throws SQLException
	{
		final Map<Integer, Long> sums = new TreeMap<>();
		try (PreparedStatement read = connection.prepareStatement(sql))
		{
			read.setInt(1, custody);
			try (ResultSet result = read.executeQuery())
			{
				while (result.next())
				{
					if (result.getLong(2) != 0)
					{
						sums.put(result.getInt(1), result.getLong(2));
					}
				}
			}
		}
		return sums;
	}

	private static long count(Connection connection, String sql, int custody) throws SQLException
	{
		try (PreparedStatement read = connection.prepareStatement(sql))
		{
			read.setInt(1, custody);
			try (ResultSet result = read.executeQuery())
			{
				result.next();
				return result.getLong(1);
			}
		}
	}
}
