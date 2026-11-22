package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;

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
	private static final java.util.concurrent.atomic.AtomicLong STATEMENTS = new java.util.concurrent.atomic.AtomicLong();

	/** A holding the census counts, with its count and the rows a statement on it locks. */
	public enum Holding
	{
		ITEMS("items", "SELECT item_id, SUM(count) FROM items WHERE loc IS NOT NULL AND loc NOT IN ('VOID', 'REFUND', 'LEASE') AND (%s) GROUP BY item_id", List.of("SELECT object_id, item_id, count, loc FROM items WHERE %s FOR UPDATE")),
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
	 * Runs {@code work}, a statement writing {@code holding}'s rows matching {@code where}, in a transaction of its own
	 * (asset-service.md §2.6): the rows are locked, counted before and after it, and the difference is posted to the
	 * existence counters only when the transaction commits, so a statement that fails posts nothing and changes nothing.
	 * The transaction writes a row of its own to {@code lc_economy_statement}, so a commit whose acknowledgement is lost is
	 * decided by that row, read under its lock on a fresh connection, never by quantities others may have changed since.
	 * Loaded copies of the item rows it locks are held under their leaf locks from the locking read until the posting, so
	 * none settles in between: a copy that still counts stops counting (its row's deletion counted it), and the posting of
	 * a row whose copy had already settled out before the read is cancelled (that copy counted it out). A connection
	 * already in a transaction is refused; the connection is handed back in autocommit. With the counters inactive,
	 * {@code work} runs as it did upstream.
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
		final Map<Integer, long[]> rows;
		try
		{
			rows = lock(connection, holding, where, params);
		}
		catch (SQLException | RuntimeException e)
		{
			abandon(connection, e);
			throw e;
		}
		final List<Item> copies = new ArrayList<>();
		for (int objectId : rows.keySet())
		{
			if (World.findObject(objectId) instanceof Item copy)
			{
				copies.add(copy);
			}
		}
		copies.sort(Comparator.comparingInt(copy -> EconomyCounter.stripe(copy.getObjectId())));
		return holdingStripes(copies, 0, new ArrayList<>(), () -> counted(connection, holding, where, params, work, rows, copies));
	}

	private static Set<Integer> loadedIds(List<Item> copies)
	{
		final Set<Integer> ids = new HashSet<>();
		for (Item copy : copies)
		{
			ids.add(copy.getObjectId());
		}
		return ids;
	}

	/** Runs {@code body} holding the leaf locks of {@code copies}, each stripe once, in stripe order. */
	private static int holdingStripes(List<Item> copies, int from, List<Integer> held, Work body) throws SQLException
	{
		for (int i = from; i < copies.size(); i++)
		{
			final int stripe = EconomyCounter.stripe(copies.get(i).getObjectId());
			if (!held.contains(stripe))
			{
				held.add(stripe);
				synchronized (EconomyCounter.lockFor(copies.get(i).getObjectId()))
				{
					return holdingStripes(copies, i + 1, held, body);
				}
			}
		}
		return body.run();
	}

	private static int counted(Connection connection, Holding holding, String where, Object[] params, Work work, Map<Integer, long[]> rows, List<Item> copies) throws SQLException
	{
		final byte[] statement = new byte[16];
		final Set<Integer> countedOut = new HashSet<>();
		final Map<Integer, Long> before;
		final Map<Integer, Long> after;
		final Set<Integer> deleted;
		final int result;
		try
		{
			before = count(connection, holding, where, params);
			result = work.run();
			after = count(connection, holding, where, params);
			deleted = holding == Holding.ITEMS ? gone(connection, rows.keySet()) : Set.of();
			// Read under the rows' locks, which Mobius's own deletion of such a row waits behind, so it cannot clear the mark
			// first (Codex amd14); a copy counted out later is a loaded one, its leaf lock held.
			final Set<Integer> loadedIds = loadedIds(copies);
			for (int objectId : deleted)
			{
				if (!loadedIds.contains(objectId) && EconomyCounter.isCountedOut(objectId))
				{
					countedOut.add(objectId);
				}
			}
			java.nio.ByteBuffer.wrap(statement).putLong(GameClock.millis()).putLong(STATEMENTS.incrementAndGet()); // unique in a run; startup clears the rest
			try (PreparedStatement mark = connection.prepareStatement("INSERT INTO lc_economy_statement (statement_id, staged_at) VALUES (?, ?)"))
			{
				mark.setBytes(1, statement);
				mark.setTimestamp(2, new java.sql.Timestamp(GameClock.millis()));
				mark.executeUpdate();
			}
		}
		catch (SQLException | RuntimeException e)
		{
			abandon(connection, e);
			throw e;
		}
		boolean ended = true; // no transaction of this statement is left open: autocommit can be restored
		try
		{
			connection.commit();
		}
		catch (SQLException e)
		{
			try
			{
				connection.rollback(); // a transaction the failed commit left open ends here, never in a later cleanup
			}
			catch (SQLException gone)
			{
				ended = false; // the connection is gone: the server ends the transaction itself
			}
			// The server may have committed and lost only the acknowledgement: the statement's own row decides.
			final Boolean committed = committed(statement);
			if (Boolean.TRUE.equals(committed))
			{
				ended = true; // it committed: nothing of it is left to commit (Codex amd17)
			}
			else
			{
				if (committed == null)
				{
					EconomyCounter.uncertain(); // no shutdown census follows: the next start is a rebase
					LOGGER.severe("Living Chronicle: a counted statement on " + holding + " (" + where + ") ended unknown; the counters are uncertain until the next start");
				}
				throw e;
			}
		}
		finally
		{
			if (ended)
			{
				try
				{
					connection.setAutoCommit(true);
				}
				catch (SQLException e)
				{
					// ended: the connection failing afterwards changes nothing the posting below accounts for
				}
			}
		}
		post(before, after);
		// Memory is the truth for a loaded object: a deleted row's posting gives way to what its copy counted.
		final Set<Integer> loaded = new HashSet<>();
		for (Item copy : copies)
		{
			loaded.add(copy.getObjectId());
			if (deleted.contains(copy.getObjectId()))
			{
				final long[] row = rows.get(copy.getObjectId());
				EconomyCounter.post((int) row[0], row[1] - copy.countedQuantity());
				copy.forgetCounted();
			}
		}
		for (int objectId : countedOut)
		{
			final long[] row = rows.get(objectId);
			EconomyCounter.post((int) row[0], row[1]); // memory counted it out before its row went
			EconomyCounter.clearCountedOut(objectId);
		}
		unmark(statement);
		return result;
	}

	/**
	 * Locks the rows a statement on {@code holding} matching {@code where} writes.
	 * @return for items, each locked row's object id with {item id, the quantity the census counts in it}; otherwise empty
	 */
	private static Map<Integer, long[]> lock(Connection connection, Holding holding, String where, Object[] params) throws SQLException
	{
		final Map<Integer, long[]> rows = new TreeMap<>();
		for (String lock : holding._locks)
		{
			try (PreparedStatement ps = connection.prepareStatement(String.format(lock, where)))
			{
				bind(ps, lock.contains("%s") ? params : new Object[0]);
				try (ResultSet rs = ps.executeQuery())
				{
					while (rs.next())
					{
						if (holding == Holding.ITEMS)
						{
							final String loc = rs.getString(4);
							final boolean counted = (loc != null) && !List.of("VOID", "REFUND", "LEASE").contains(loc);
							rows.put(rs.getInt(1), new long[]
							{
								rs.getInt(2),
								counted ? rs.getLong(3) : 0
							});
						}
					}
				}
			}
		}
		return rows;
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

	/** Where a lost acknowledgement is resolved from: the database (a test replaces it to make the read fail). */
	static Connections resolver = DatabaseFactory::getConnection;

	/** Ends a statement's transaction after it failed, keeping {@code cause} the exception the caller sees. */
	private static void abandon(Connection connection, Exception cause)
	{
		try
		{
			connection.rollback();
			connection.setAutoCommit(true);
		}
		catch (SQLException e)
		{
			cause.addSuppressed(e); // the connection is gone: the server ends the transaction itself
		}
	}

	/** @return whether the statement whose commit failed did commit: its own row decides; null when it cannot be read */
	private static Boolean committed(byte[] statement)
	{
		try (Connection fresh = resolver.open())
		{
			fresh.setAutoCommit(false);
			try (PreparedStatement read = fresh.prepareStatement("SELECT 1 FROM lc_economy_statement WHERE statement_id = ? FOR UPDATE"))
			{
				read.setBytes(1, statement); // waits until the server has ended the first transaction either way
				try (ResultSet result = read.executeQuery())
				{
					return result.next();
				}
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

	/** The statement's row has done its work once its commit is known; one a crash left is cleared at startup. */
	private static void unmark(byte[] statement)
	{
		try (Connection fresh = DatabaseFactory.getConnection();
			PreparedStatement delete = fresh.prepareStatement("DELETE FROM lc_economy_statement WHERE statement_id = ?"))
		{
			delete.setBytes(1, statement);
			delete.executeUpdate();
		}
		catch (SQLException e)
		{
			// harmless: it only ever decides its own statement
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
