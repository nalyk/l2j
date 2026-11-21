package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.gameserver.config.GeneralConfig;

/**
 * The census (docs/spec/asset-service.md §2.6): per item id, what the holdings the census counts hold in the database:
 * items in every location but VOID, REFUND and LEASE (custody rows included), the saved ground when SaveDroppedItem is
 * on, castle treasuries, open item-auction bids less each finished auction's winning bid, and clan-hall bids. An SQL site
 * that writes census rows directly runs through {@link #counting}, so the existence counters see exactly what it changed.
 */
public final class Census
{
	private static final int ADENA = 57;

	/** A holding the census counts, with its count and the rows a statement on it locks. */
	public enum Holding
	{
		ITEMS("items", "SELECT item_id, SUM(count) FROM items WHERE loc IS NOT NULL AND loc NOT IN ('VOID', 'REFUND', 'LEASE') AND (%s) GROUP BY item_id", List.of("SELECT object_id FROM items WHERE %s FOR UPDATE")),
		GROUND("itemsonground", "SELECT item_id, SUM(count) FROM itemsonground WHERE %s GROUP BY item_id", List.of("SELECT object_id FROM itemsonground WHERE %s FOR UPDATE")),
		TREASURY("castle", "SELECT " + ADENA + ", SUM(treasury) FROM castle WHERE %s", List.of("SELECT id FROM castle WHERE %s FOR UPDATE")),
		/**
		 * Counted whole (a statement on it is counted over every bid): a finished auction's winning bid was paid when it
		 * finished, so it is the highest open bid of each finished auction that is taken off.
		 */
		ITEM_AUCTION_BIDS("item_auction_bid", "SELECT " + ADENA + ", COALESCE(SUM(item_auction_bid.playerBid), 0) - COALESCE((SELECT SUM(won.top) FROM (SELECT b.auctionId, MAX(b.playerBid) AS top FROM item_auction_bid b JOIN item_auction a ON a.auctionId = b.auctionId WHERE a.auctionStateId = 2 AND b.playerBid > 0 GROUP BY b.auctionId) won), 0) FROM item_auction_bid WHERE item_auction_bid.playerBid > 0 AND (%s)", List.of("SELECT auctionId FROM item_auction FOR UPDATE", "SELECT auctionId FROM item_auction_bid FOR UPDATE")),
		CLAN_HALL_BIDS("auction_bid", "SELECT " + ADENA + ", SUM(maxBid) FROM auction_bid WHERE %s", List.of("SELECT id FROM auction_bid WHERE %s FOR UPDATE"));

		private final String _table;
		private final String _count;
		private final List<String> _locks;

		Holding(String table, String count, List<String> locks)
		{
			_table = table;
			_count = count;
			_locks = locks;
		}

		private boolean countedWhole()
		{
			return this == ITEM_AUCTION_BIDS;
		}
	}

	/** Work run in a counted transaction: one or more statements on the connection it was given. */
	@FunctionalInterface
	public interface Work
	{
		int run() throws SQLException;
	}

	private static final Pattern WRITE = Pattern.compile("(?is)^\\s*(DELETE\\s+FROM|UPDATE|INSERT\\s+(IGNORE\\s+)?INTO|REPLACE\\s+INTO)\\s+`?(\\w+)`?");
	private static final Pattern WHERE = Pattern.compile("(?is)\\bWHERE\\b(.*)$");

	private Census()
	{
	}

	/** @return every holding's count, by item id (the census of §2.6) */
	public static Map<Integer, Long> all(Connection connection) throws SQLException
	{
		final Map<Integer, Long> census = new TreeMap<>();
		detailed(connection).values().forEach(counts -> counts.forEach((itemId, quantity) -> census.merge(itemId, quantity, Long::sum)));
		census.values().removeIf(quantity -> quantity == 0);
		return census;
	}

	/**
	 * @return the census by holding, as {@code lc_economy_census} records it: items by their location, then {@code GROUND}
	 *         (only when saved), {@code TREASURY}, {@code ITEM_BIDS} and {@code HALL_BIDS}
	 */
	public static Map<String, Map<Integer, Long>> detailed(Connection connection) throws SQLException
	{
		final Map<String, Map<Integer, Long>> census = new TreeMap<>();
		try (PreparedStatement ps = connection.prepareStatement("SELECT loc, item_id, SUM(count) FROM items WHERE loc IS NOT NULL AND loc NOT IN ('VOID', 'REFUND', 'LEASE') GROUP BY loc, item_id");
			ResultSet rs = ps.executeQuery())
		{
			while (rs.next())
			{
				if (rs.getLong(3) != 0)
				{
					census.computeIfAbsent(rs.getString(1), loc -> new TreeMap<>()).put(rs.getInt(2), rs.getLong(3));
				}
			}
		}
		if (GeneralConfig.SAVE_DROPPED_ITEM) // the ground is a holding only when saved
		{
			put(census, "GROUND", count(connection, Holding.GROUND, "1 = 1"));
		}
		put(census, "TREASURY", count(connection, Holding.TREASURY, "1 = 1"));
		put(census, "ITEM_BIDS", count(connection, Holding.ITEM_AUCTION_BIDS, "1 = 1"));
		put(census, "HALL_BIDS", count(connection, Holding.CLAN_HALL_BIDS, "1 = 1"));
		return census;
	}

	private static void put(Map<String, Map<Integer, Long>> census, String holding, Map<Integer, Long> counts)
	{
		if (!counts.isEmpty())
		{
			census.put(holding, counts);
		}
	}

	/** @return what {@code holding} counts in its rows matching {@code where}, by item id */
	public static Map<Integer, Long> count(Connection connection, Holding holding, String where, Object... params) throws SQLException
	{
		final Map<Integer, Long> counts = new TreeMap<>();
		try (PreparedStatement ps = connection.prepareStatement(String.format(holding._count, holding.countedWhole() ? "1 = 1" : where)))
		{
			bind(ps, holding.countedWhole() ? new Object[0] : params);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					final long quantity = rs.getLong(2);
					if (!rs.wasNull() && (quantity != 0))
					{
						counts.merge(rs.getInt(1), quantity, Long::sum);
					}
				}
			}
		}
		return counts;
	}

	/**
	 * Runs {@code work}, a statement writing {@code holding}'s rows matching {@code where}, in a transaction of its own:
	 * the rows are locked, counted before and after it, and the difference is posted to the existence counters only when
	 * the transaction commits, so a statement that fails posts nothing and changes nothing (asset-service.md §2.6). The
	 * connection is handed back in the autocommit mode it came in. With the counters inactive, {@code work} runs as it did
	 * upstream.
	 * @return what {@code work} returned
	 */
	public static int counting(Connection connection, Holding holding, String where, Object[] params, Work work) throws SQLException
	{
		if (!EconomyCounter.isActive())
		{
			return work.run();
		}
		final boolean autoCommit = connection.getAutoCommit();
		connection.setAutoCommit(false);
		try
		{
			for (String lock : holding._locks)
			{
				try (PreparedStatement ps = connection.prepareStatement(String.format(lock, holding.countedWhole() ? "1 = 1" : where)))
				{
					bind(ps, lock.contains("%s") && !holding.countedWhole() ? params : new Object[0]);
					ps.executeQuery().close();
				}
			}
			final Map<Integer, Long> before = count(connection, holding, where, params);
			final int result = work.run();
			final Map<Integer, Long> after = count(connection, holding, where, params);
			connection.commit();
			final Set<Integer> ids = new HashSet<>(before.keySet());
			ids.addAll(after.keySet());
			for (int itemId : ids)
			{
				EconomyCounter.post(itemId, after.getOrDefault(itemId, 0L) - before.getOrDefault(itemId, 0L));
			}
			return result;
		}
		catch (SQLException | RuntimeException e)
		{
			connection.rollback();
			throw e;
		}
		finally
		{
			connection.setAutoCommit(autoCommit);
		}
	}

	/** @return the census holding {@code sql} writes, or null when it writes none */
	public static Holding written(String sql)
	{
		final Matcher write = WRITE.matcher(sql);
		if (!write.find())
		{
			return null;
		}
		final String table = write.group(3).toLowerCase();
		for (Holding holding : Holding.values())
		{
			if (holding._table.equals(table) && ((holding != Holding.TREASURY) || sql.toLowerCase().matches("(?s).*\\bset\\b.*\\btreasury\\b.*")))
			{
				return holding;
			}
		}
		return null;
	}

	/** @return the condition of {@code sql}'s WHERE clause, or a condition every row meets */
	public static String whereOf(String sql)
	{
		final Matcher where = WHERE.matcher(sql);
		return where.find() ? where.group(1).trim() : "1 = 1";
	}

	private static void bind(PreparedStatement ps, Object[] params) throws SQLException
	{
		for (int i = 0; i < params.length; i++)
		{
			ps.setObject(i + 1, params[i]);
		}
	}
}
