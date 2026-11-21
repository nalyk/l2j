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
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.item.instance.Item;

/**
 * The census (docs/spec/asset-service.md §2.6): per item id, what the holdings the census counts hold in the database:
 * items in every location but VOID, REFUND and LEASE (custody rows included), the saved ground when SaveDroppedItem is
 * on, castle treasuries, open item-auction bids less each finished auction's winning bid, and clan-hall bids. An SQL site
 * that writes census rows directly runs through {@link #counting}, so the existence counters see exactly what it changed.
 */
public final class Census
{
	private static final int ADENA = 57;
	private static final Logger LOGGER = Logger.getLogger(Census.class.getName());

	/** A holding the census counts, with its count and the rows a statement on it locks. */
	public enum Holding
	{
		ITEMS("items", "SELECT item_id, SUM(count) FROM items WHERE loc IS NOT NULL AND loc NOT IN ('VOID', 'REFUND', 'LEASE') AND (%s) GROUP BY item_id", List.of("SELECT object_id FROM items WHERE %s FOR UPDATE")),
		GROUND("itemsonground", "SELECT item_id, SUM(count) FROM itemsonground WHERE %s GROUP BY item_id", List.of("SELECT object_id FROM itemsonground WHERE %s FOR UPDATE")),
		TREASURY("castle", "SELECT " + ADENA + ", SUM(treasury) FROM castle WHERE %s", List.of("SELECT id FROM castle WHERE %s FOR UPDATE")),
		/**
		 * Per auction: its open bids, less its highest one once it finished (that one was paid then). A statement on it is
		 * counted over every bid ({@code 1 = 1}) or over one auction's ({@code item_auction_bid.auctionId = ?}), never
		 * over a filter of bid rows, which would take the wrong bid off; its rows and its auctions' are locked whole.
		 */
		ITEM_AUCTION_BIDS("item_auction_bid", "SELECT " + ADENA + ", COALESCE(SUM(t.open - t.won), 0) FROM (SELECT item_auction_bid.auctionId, SUM(CASE WHEN item_auction_bid.playerBid > 0 THEN item_auction_bid.playerBid ELSE 0 END) AS open, CASE WHEN MAX(item_auction.auctionStateId) = 2 THEN COALESCE(MAX(CASE WHEN item_auction_bid.playerBid > 0 THEN item_auction_bid.playerBid END), 0) ELSE 0 END AS won FROM item_auction_bid LEFT JOIN item_auction ON item_auction.auctionId = item_auction_bid.auctionId WHERE %s GROUP BY item_auction_bid.auctionId) t", List.of("SELECT auctionId FROM item_auction FOR UPDATE", "SELECT auctionId FROM item_auction_bid FOR UPDATE")),
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
		try (PreparedStatement ps = connection.prepareStatement(String.format(holding._count, where)))
		{
			bind(ps, params);
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
		if (!connection.getAutoCommit())
		{
			// Its commit would be the caller's, and an earlier read's snapshot would skew the count before (Codex amd11).
			throw new IllegalStateException("a counted statement runs in a transaction of its own; this connection is in one");
		}
		connection.setAutoCommit(false);
		final Map<Integer, Long> before;
		final Map<Integer, Long> after;
		final Set<Integer> deleted;
		final int result;
		try
		{
			final Set<Integer> locked = lock(connection, holding, where, params);
			before = count(connection, holding, where, params);
			result = work.run();
			after = count(connection, holding, where, params);
			deleted = holding == Holding.ITEMS ? gone(connection, locked) : Set.of();
		}
		catch (SQLException | RuntimeException e)
		{
			connection.rollback();
			connection.setAutoCommit(true);
			throw e;
		}
		try
		{
			connection.commit();
		}
		catch (SQLException e)
		{
			// The server may have committed and lost only the acknowledgement: the rows decide, read under their locks.
			final Boolean committed = committed(holding, where, params, before, after);
			if (!Boolean.TRUE.equals(committed))
			{
				if (committed == null)
				{
					LOGGER.severe("Living Chronicle: a counted statement on " + holding + " (" + where + ") ended unknown; the census will show it");
				}
				throw e;
			}
		}
		finally
		{
			try
			{
				connection.setAutoCommit(true);
			}
			catch (SQLException e)
			{
				// a connection that failed its commit may be gone; the pool replaces it
			}
		}
		post(before, after);
		forget(deleted);
		return result;
	}

	/** Locks the rows a statement on {@code holding} matching {@code where} writes. @return their object ids, for items */
	private static Set<Integer> lock(Connection connection, Holding holding, String where, Object[] params) throws SQLException
	{
		final Set<Integer> ids = new HashSet<>();
		for (String lock : holding._locks)
		{
			try (PreparedStatement ps = connection.prepareStatement(String.format(lock, where)))
			{
				bind(ps, lock.contains("%s") ? params : new Object[0]);
				try (ResultSet rs = ps.executeQuery())
				{
					while (rs.next())
					{
						ids.add(rs.getInt(1));
					}
				}
			}
		}
		return ids;
	}

	/** @return the object ids among {@code locked} whose rows are gone */
	private static Set<Integer> gone(Connection connection, Set<Integer> locked) throws SQLException
	{
		final Set<Integer> gone = new HashSet<>(locked);
		if (locked.isEmpty())
		{
			return gone;
		}
		try (PreparedStatement ps = connection.prepareStatement("SELECT object_id FROM items WHERE object_id IN (" + String.join(",", java.util.Collections.nCopies(locked.size(), "?")) + ")"))
		{
			bind(ps, locked.toArray());
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					gone.remove(rs.getInt(1));
				}
			}
		}
		return gone;
	}

	/** @return whether a statement whose commit failed did commit: TRUE, FALSE, or null when the rows cannot tell */
	private static Boolean committed(Holding holding, String where, Object[] params, Map<Integer, Long> before, Map<Integer, Long> after)
	{
		try (Connection fresh = DatabaseFactory.getConnection())
		{
			fresh.setAutoCommit(false);
			try
			{
				lock(fresh, holding, where, params); // waits until the server has ended the first transaction either way
				final Map<Integer, Long> now = count(fresh, holding, where, params);
				return now.equals(after) ? Boolean.TRUE : now.equals(before) ? Boolean.FALSE : null;
			}
			finally
			{
				fresh.rollback();
			}
		}
		catch (SQLException e)
		{
			return null;
		}
	}

	private static void post(Map<Integer, Long> before, Map<Integer, Long> after)
	{
		final Set<Integer> ids = new HashSet<>(before.keySet());
		ids.addAll(after.keySet());
		for (int itemId : ids)
		{
			EconomyCounter.post(itemId, after.getOrDefault(itemId, 0L) - before.getOrDefault(itemId, 0L));
		}
	}

	/** A loaded copy of a row the statement deleted no longer counts: the deletion counted it (Codex amd11). */
	private static void forget(Set<Integer> deleted)
	{
		for (int objectId : deleted)
		{
			if (World.findObject(objectId) instanceof Item copy)
			{
				copy.forgetCounted();
			}
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
			if ((holding._table.equals(table) || ((holding == Holding.ITEM_AUCTION_BIDS) && table.equals("item_auction"))) && ((holding != Holding.TREASURY) || sql.toLowerCase().matches("(?s).*\\bset\\b.*\\btreasury\\b.*")))
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
