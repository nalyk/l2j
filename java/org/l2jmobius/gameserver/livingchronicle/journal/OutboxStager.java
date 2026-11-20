package org.l2jmobius.gameserver.livingchronicle.journal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import org.l2jmobius.gameserver.livingchronicle.asset.OpId;

/**
 * Stages outbox facts in the caller's transaction (docs/spec/fact-journal.md §3.1): per stream, the head row is locked, the
 * next numbers are taken, the facts are inserted into {@code lc_fact_outbox} and the head advances, all before the caller's
 * {@code COMMIT}. A staged fact therefore exists exactly when the change it records does.
 * <p>
 * Stream heads come last in the global row-lock order, so the caller stages after taking every other lock; streams are
 * locked in name order, so two stagers never take two heads in opposite orders.
 * </p>
 */
public final class OutboxStager
{
	private final Clock _wall;
	private final LongSupplier _logicalTime;
	private final IntSupplier _configVersion;
	private final Map<String, Integer> _types = new ConcurrentHashMap<>();

	/**
	 * @param wall the wall clock of {@code staged_at}
	 * @param logicalTime the game clock at the fact ({@code GameClock::millis} in production)
	 * @param configVersion the configuration version the producer acts under
	 */
	public OutboxStager(Clock wall, LongSupplier logicalTime, IntSupplier configVersion)
	{
		_wall = wall;
		_logicalTime = logicalTime;
		_configVersion = configVersion;
	}

	/**
	 * Stages the facts on the caller's connection, which must not be in autocommit.
	 * @return the outbox ids, in the order of {@code facts}
	 */
	public List<Long> stage(Connection connection, OpId op, List<DurableFact> facts) throws SQLException
	{
		if (connection.getAutoCommit())
		{
			throw new IllegalStateException("facts are staged in the caller's transaction; autocommit is on");
		}
		final Map<String, List<Integer>> byStream = new TreeMap<>();
		for (int i = 0; i < facts.size(); i++)
		{
			byStream.computeIfAbsent(facts.get(i).stream(), s -> new ArrayList<>()).add(i);
		}
		final Map<Integer, Long> ids = new LinkedHashMap<>();
		final Timestamp stagedAt = Timestamp.from(_wall.instant());
		final long logicalTime = _logicalTime.getAsLong();
		final int configVersion = _configVersion.getAsInt();
		for (Map.Entry<String, List<Integer>> stream : byStream.entrySet())
		{
			long seq = lockHead(connection, stream.getKey());
			try (PreparedStatement insert = connection.prepareStatement("INSERT INTO lc_fact_outbox (staged_at, stream, stream_seq, type, schema_ver, logical_time, actor_snapshot, region_id, zone_id, instance_id, sensitivity, payload, op_id, config_ver) VALUES (?, ?, ?, ?, ?, ?, NULL, 0, NULL, 0, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS))
			{
				for (int index : stream.getValue())
				{
					final DurableFact fact = facts.get(index);
					insert.setTimestamp(1, stagedAt);
					insert.setString(2, stream.getKey());
					insert.setLong(3, ++seq);
					insert.setInt(4, type(connection, fact.typeName()));
					insert.setInt(5, fact.schemaVersion());
					insert.setLong(6, logicalTime);
					insert.setInt(7, fact.sensitivity());
					insert.setString(8, fact.payloadJson());
					insert.setBytes(9, op == null ? null : op.bytes());
					insert.setInt(10, configVersion);
					insert.executeUpdate();
					try (ResultSet keys = insert.getGeneratedKeys())
					{
						keys.next();
						ids.put(index, keys.getLong(1));
					}
				}
			}
			try (PreparedStatement advance = connection.prepareStatement("UPDATE lc_stream_head SET last_seq = ? WHERE stream = ?"))
			{
				advance.setLong(1, seq);
				advance.setString(2, stream.getKey());
				advance.executeUpdate();
			}
		}
		final List<Long> result = new ArrayList<>();
		for (int i = 0; i < facts.size(); i++)
		{
			result.add(ids.get(i));
		}
		return result;
	}

	/** Takes the head's exclusive lock at once (an upsert), never a shared lock first that two stagers would both upgrade. */
	private static long lockHead(Connection connection, String stream) throws SQLException
	{
		try (PreparedStatement upsert = connection.prepareStatement("INSERT INTO lc_stream_head (stream, last_seq) VALUES (?, 0) ON DUPLICATE KEY UPDATE last_seq = last_seq"))
		{
			upsert.setString(1, stream);
			upsert.executeUpdate();
		}
		try (PreparedStatement read = connection.prepareStatement("SELECT last_seq FROM lc_stream_head WHERE stream = ? FOR UPDATE"))
		{
			read.setString(1, stream);
			try (ResultSet result = read.executeQuery())
			{
				result.next();
				return result.getLong(1);
			}
		}
	}

	private int type(Connection connection, String name) throws SQLException
	{
		final Integer cached = _types.get(name);
		if (cached != null)
		{
			return cached;
		}
		try (PreparedStatement read = connection.prepareStatement("SELECT type FROM lc_fact_type WHERE name = ?"))
		{
			read.setString(1, name);
			try (ResultSet result = read.executeQuery())
			{
				if (!result.next())
				{
					throw new IllegalStateException("fact type " + name + " is not registered in lc_fact_type");
				}
				_types.put(name, result.getInt(1));
				return result.getInt(1);
			}
		}
	}
}
