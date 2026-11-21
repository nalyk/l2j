package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.livingchronicle.journal.DurableFact;
import org.l2jmobius.gameserver.livingchronicle.journal.OutboxStager;

/**
 * The economy monitor's guarantee across a restart (docs/spec/asset-service.md §2.6, INV06): at the shutdown boundary the
 * census and the existence counters are recorded; at the next startup the database is counted again and compared with
 * both, per item id. A difference is {@code Asset.ConservationBroken} and an admin alarm, freezing nothing; a startup
 * with no shutdown census before it (a crash, or the first start) is a {@code REBASE}, recorded with its difference from
 * the last census in {@code Asset.MonitorRebased} and never reported as conservation holding. Either way the counters
 * then start from the startup census.
 */
public final class EconomyMonitor
{
	private static final Logger LOGGER = Logger.getLogger(EconomyMonitor.class.getName());

	public enum Kind
	{
		STARTUP,
		SHUTDOWN,
		REBASE
	}

	/**
	 * What a startup found.
	 * @param differences per item id whose shutdown census, persisted counters and startup census disagree (after a clean
	 *            shutdown) or whose count changed since the last census (a rebase): the startup census less the counters,
	 *            or less the last census
	 */
	public record Startup(Kind kind, long censusId, Map<Integer, Long> differences)
	{
	}

	private EconomyMonitor()
	{
	}

	/** Counts the database, compares it with the last boundary, records the census and starts the counters from it. */
	public static Startup atStartup(Connection connection, OutboxStager stager) throws SQLException
	{
		final boolean autoCommit = connection.getAutoCommit();
		connection.setAutoCommit(false);
		try
		{
			final Map<String, Map<Integer, Long>> detailed = Census.detailed(connection);
			final Map<Integer, Long> census = totals(detailed);
			final long[] last = last(connection);
			final Kind kind;
			final Map<Integer, Long> differences = new TreeMap<>();
			final List<Map<String, Object>> items = new ArrayList<>();
			if ((last != null) && (last[1] == Kind.SHUTDOWN.ordinal()))
			{
				kind = Kind.STARTUP;
				final Map<Integer, Long> shutdown = census(connection, last[0]);
				final Map<Integer, Long> counters = counters(connection, last[0]);
				for (int itemId : ids(census, shutdown, counters))
				{
					final long now = census.getOrDefault(itemId, 0L);
					final long then = shutdown.getOrDefault(itemId, 0L);
					final long counted = counters.getOrDefault(itemId, 0L);
					if ((now != then) || (now != counted))
					{
						differences.put(itemId, now - counted);
						items.add(item("item", itemId, "shutdown", then, "counters", counted, "startup", now));
					}
				}
			}
			else
			{
				kind = Kind.REBASE;
				final Map<Integer, Long> previous = last == null ? Map.of() : census(connection, last[0]);
				for (int itemId : ids(census, previous, Map.of()))
				{
					final long now = census.getOrDefault(itemId, 0L);
					final long then = previous.getOrDefault(itemId, 0L);
					if ((last != null) && (now != then))
					{
						differences.put(itemId, now - then);
						items.add(item("item", itemId, "previous", then, "startup", now));
					}
				}
			}
			final long censusId = record(connection, kind, detailed, null);
			final List<DurableFact> facts = new ArrayList<>();
			if (kind == Kind.REBASE)
			{
				facts.add(AssetFact.economy("MonitorRebased", "census", censusId, "previous", last == null ? null : last[0], "items", items));
			}
			else if (!differences.isEmpty())
			{
				facts.add(AssetFact.economy("ConservationBroken", "census", censusId, "shutdown", last[0], "items", items));
				LOGGER.severe("Living Chronicle: conservation broken across the restart, census " + censusId + ": " + items);
			}
			if (!facts.isEmpty())
			{
				stager.stage(connection, null, facts);
			}
			connection.commit();
			EconomyCounter.activate(census);
			return new Startup(kind, censusId, differences);
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

	/**
	 * At the shutdown boundary, after every write the boundary makes: records the {@code SHUTDOWN} census and the counters.
	 * @return the census id
	 */
	public static long atShutdown(Connection connection) throws SQLException
	{
		final boolean autoCommit = connection.getAutoCommit();
		connection.setAutoCommit(false);
		try
		{
			final long censusId = record(connection, Kind.SHUTDOWN, Census.detailed(connection), EconomyCounter.snapshot());
			connection.commit();
			return censusId;
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

	/** The row that records a census's kind and time, so a census of empty holdings still exists (item 0, holding CENSUS). */
	private static final String HEADER = "CENSUS";

	/** Records a census in {@code lc_economy_census} (V010), and with a shutdown census the counters in {@code lc_economy_counter}. */
	private static long record(Connection connection, Kind kind, Map<String, Map<Integer, Long>> census, Map<Integer, Long> counters) throws SQLException
	{
		final long censusId;
		try (PreparedStatement next = connection.prepareStatement("SELECT COALESCE(MAX(census_id), 0) + 1 FROM lc_economy_census FOR UPDATE");
			ResultSet result = next.executeQuery())
		{
			result.next();
			censusId = result.getLong(1);
		}
		final Timestamp takenAt = new Timestamp(GameClock.millis());
		try (PreparedStatement insert = connection.prepareStatement("INSERT INTO lc_economy_census (census_id, taken_at, kind, item_id, holding, qty) VALUES (?, ?, ?, ?, ?, ?)"))
		{
			add(insert, censusId, takenAt, kind, 0, HEADER, 0);
			for (Map.Entry<String, Map<Integer, Long>> holding : census.entrySet())
			{
				for (Map.Entry<Integer, Long> count : holding.getValue().entrySet())
				{
					add(insert, censusId, takenAt, kind, count.getKey(), holding.getKey(), count.getValue());
				}
			}
			insert.executeBatch();
		}
		if (counters != null)
		{
			try (PreparedStatement clear = connection.prepareStatement("DELETE FROM lc_economy_counter"))
			{
				clear.executeUpdate();
			}
			try (PreparedStatement insert = connection.prepareStatement("INSERT INTO lc_economy_counter (item_id, existence, as_of_census) VALUES (?, ?, ?)"))
			{
				for (Map.Entry<Integer, Long> count : counters.entrySet())
				{
					insert.setInt(1, count.getKey());
					insert.setLong(2, count.getValue());
					insert.setLong(3, censusId);
					insert.addBatch();
				}
				insert.executeBatch();
			}
		}
		return censusId;
	}

	private static void add(PreparedStatement insert, long censusId, Timestamp takenAt, Kind kind, int itemId, String holding, long qty) throws SQLException
	{
		insert.setLong(1, censusId);
		insert.setTimestamp(2, takenAt);
		insert.setString(3, kind.name());
		insert.setInt(4, itemId);
		insert.setString(5, holding);
		insert.setLong(6, qty);
		insert.addBatch();
	}

	/** @return the last census as {id, kind ordinal}, or null before the first */
	private static long[] last(Connection connection) throws SQLException
	{
		try (PreparedStatement read = connection.prepareStatement("SELECT census_id, kind FROM lc_economy_census WHERE item_id = 0 AND holding = '" + HEADER + "' ORDER BY census_id DESC LIMIT 1 FOR UPDATE");
			ResultSet result = read.executeQuery())
		{
			return result.next() ? new long[]
			{
				result.getLong(1),
				Kind.valueOf(result.getString(2)).ordinal()
			} : null;
		}
	}

	/** @return a recorded census's count per item id */
	private static Map<Integer, Long> census(Connection connection, long censusId) throws SQLException
	{
		final Map<Integer, Long> counts = new TreeMap<>();
		try (PreparedStatement read = connection.prepareStatement("SELECT item_id, SUM(qty) FROM lc_economy_census WHERE census_id = ? AND holding <> '" + HEADER + "' GROUP BY item_id"))
		{
			read.setLong(1, censusId);
			try (ResultSet result = read.executeQuery())
			{
				while (result.next())
				{
					counts.put(result.getInt(1), result.getLong(2));
				}
			}
		}
		return counts;
	}

	/** @return the counters persisted with the shutdown census {@code censusId}, empty when none were */
	private static Map<Integer, Long> counters(Connection connection, long censusId) throws SQLException
	{
		final Map<Integer, Long> counts = new TreeMap<>();
		try (PreparedStatement read = connection.prepareStatement("SELECT item_id, existence FROM lc_economy_counter WHERE as_of_census = ?"))
		{
			read.setLong(1, censusId);
			try (ResultSet result = read.executeQuery())
			{
				while (result.next())
				{
					counts.put(result.getInt(1), result.getLong(2));
				}
			}
		}
		return counts;
	}

	private static Map<Integer, Long> totals(Map<String, Map<Integer, Long>> detailed)
	{
		final Map<Integer, Long> totals = new TreeMap<>();
		detailed.values().forEach(counts -> counts.forEach((itemId, quantity) -> totals.merge(itemId, quantity, Long::sum)));
		totals.values().removeIf(quantity -> quantity == 0);
		return totals;
	}

	@SafeVarargs
	private static TreeSet<Integer> ids(Map<Integer, Long>... maps)
	{
		final TreeSet<Integer> ids = new TreeSet<>();
		for (Map<Integer, Long> map : maps)
		{
			ids.addAll(map.keySet());
		}
		return ids;
	}

	private static Map<String, Object> item(Object... fields)
	{
		final Map<String, Object> item = new LinkedHashMap<>();
		for (int i = 0; i < fields.length; i += 2)
		{
			item.put((String) fields[i], fields[i + 1]);
		}
		return item;
	}
}
