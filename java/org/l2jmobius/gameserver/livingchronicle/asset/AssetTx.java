package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.l2jmobius.gameserver.livingchronicle.journal.DurableFact;

/**
 * One asset operation (docs/spec/asset-service.md §3.2, §4.1, §4.2). The caller declares what the operation does; nothing
 * touches the database until {@link #commit()}, which runs every statement on one connection with autocommit off, in the
 * global row-lock order (fact-journal.md §3.1), and commits once. Because the whole transaction is replayable, a deadlock
 * victim or a lock-wait timeout is retried as a whole, up to {@code DeadlockRetries} times.
 * <p>
 * A commit whose result is lost is resolved by a locking read of the operation's {@code lc_asset_op} row on a new
 * connection: present means committed, absent means aborted. A read that cannot be made within
 * {@code UncertainCommitLookupMillis} freezes the module and throws {@link UncertainCommitException}; it never aborts.
 * </p>
 */
public final class AssetTx implements AutoCloseable
{
	private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,63}");
	private static final int DEADLOCK = 1213;
	private static final int LOCK_WAIT_TIMEOUT = 1205;
	private static final int DUPLICATE_KEY = 1062;

	/** One step of the operation, run inside the transaction after the custody rows are locked. */
	@FunctionalInterface
	interface Step
	{
		void run(Execution execution) throws SQLException, AssetAbort;
	}

	private record Version(String table, String idColumn, long id, int version)
	{
	}

	private record Key(String scope, String key)
	{
	}

	private final AssetService _service;
	private final OpId _op;
	private final String _module;
	private final String _action;
	private final long _subjectRef;
	private final Outcome _prior;
	private final List<Step> _steps = new ArrayList<>();
	private final TreeSet<Integer> _custodies = new TreeSet<>();
	private final List<Version> _versions = new ArrayList<>();
	private final List<Key> _keys = new ArrayList<>();
	private final List<DurableFact> _facts = new ArrayList<>();
	private boolean _ended;

	AssetTx(AssetService service, OpId op, String module, String action, long subjectRef, Outcome prior)
	{
		_service = service;
		_op = op;
		_module = module;
		_action = action;
		_subjectRef = subjectRef;
		_prior = prior;
	}

	public OpId op()
	{
		return _op;
	}

	/** The owner's row {@code table.idColumn = id} must be at {@code version}; the operation advances it by one. */
	public void expectVersion(String table, String idColumn, long id, int version)
	{
		if (!IDENTIFIER.matcher(table).matches() || !IDENTIFIER.matcher(idColumn).matches())
		{
			throw new IllegalArgumentException("not an identifier: " + table + "." + idColumn);
		}
		_versions.add(new Version(table, idColumn, id, version));
	}

	/** The operation settles {@code (scope, key)}; a second operation with the same key aborts as already settled. */
	public void settlementKey(String scope, String key)
	{
		_keys.add(new Key(scope, key));
	}

	/** A durable fact staged in the operation's transaction. */
	public void fact(DurableFact fact)
	{
		_facts.add(fact);
	}

	/** Declares a step that touches the given custodies; they are locked in id order before any step runs. */
	void step(Step step, int... custodies)
	{
		for (int custody : custodies)
		{
			_custodies.add(custody);
		}
		_steps.add(step);
	}

	public Outcome commit() throws AssetAbort
	{
		if (_ended)
		{
			throw new IllegalStateException("an operation commits once");
		}
		_ended = true;
		if (_prior != null)
		{
			return _prior;
		}
		final AssetConfig config = _service.config();
		for (int attempt = 0;; attempt++)
		{
			final Connection connection;
			try
			{
				connection = _service.db().open();
			}
			catch (SQLException e)
			{
				throw new AssetAbort(AssetAbort.Reason.ERROR, "no connection for " + _op, e);
			}
			try
			{
				connection.setAutoCommit(false);
				lockWait(connection, config.lockWaitMillis());
				execute(connection);
			}
			catch (AssetAbort e)
			{
				rollback(connection);
				throw e;
			}
			catch (SQLException e)
			{
				rollback(connection);
				if (contention(e))
				{
					if (attempt < config.deadlockRetries())
					{
						continue;
					}
					throw new AssetAbort(AssetAbort.Reason.CONTENTION, _op + " after " + (attempt + 1) + " attempts", e);
				}
				throw new AssetAbort(AssetAbort.Reason.ERROR, _op.toString(), e);
			}
			try
			{
				connection.commit();
			}
			catch (SQLException e)
			{
				close(connection); // never rolled back: the server may have committed
				return resolve(e);
			}
			close(connection);
			return Outcome.COMMITTED;
		}
	}

	private void execute(Connection connection) throws SQLException, AssetAbort
	{
		final Execution execution = new Execution(connection, _service, _op);
		execution.lockCustodies(_custodies);
		for (Step step : _steps)
		{
			step.run(execution);
		}
		final List<Version> versions = new ArrayList<>(_versions);
		versions.sort(Comparator.comparing(Version::table).thenComparingLong(Version::id));
		for (Version version : versions)
		{
			checkVersion(connection, version);
		}
		try (PreparedStatement insert = connection.prepareStatement("INSERT INTO lc_asset_op (op_id, module, action, subject_ref, committed_at) VALUES (?, ?, ?, ?, NOW(3))"))
		{
			insert.setBytes(1, _op.bytes());
			insert.setString(2, _module);
			insert.setString(3, _action);
			insert.setLong(4, _subjectRef);
			insert.executeUpdate();
		}
		final List<Key> keys = new ArrayList<>(_keys);
		keys.sort(Comparator.comparing(Key::scope).thenComparing(Key::key));
		for (Key key : keys)
		{
			settle(connection, key);
		}
		postLegs(connection, execution.legs());
		execution.checkCustodies();
		final List<DurableFact> facts = new ArrayList<>(execution.facts());
		facts.addAll(_facts);
		if (!facts.isEmpty())
		{
			_service.stager().stage(connection, _op, facts);
		}
	}

	private static void checkVersion(Connection connection, Version version) throws SQLException, AssetAbort
	{
		try (PreparedStatement read = connection.prepareStatement("SELECT version FROM " + version.table() + " WHERE " + version.idColumn() + " = ? FOR UPDATE"))
		{
			read.setLong(1, version.id());
			try (ResultSet result = read.executeQuery())
			{
				if (!result.next() || (result.getInt(1) != version.version()))
				{
					throw new AssetAbort(AssetAbort.Reason.STALE_VERSION, version.table() + " " + version.id() + " is not at version " + version.version());
				}
			}
		}
		try (PreparedStatement advance = connection.prepareStatement("UPDATE " + version.table() + " SET version = version + 1 WHERE " + version.idColumn() + " = ?"))
		{
			advance.setLong(1, version.id());
			advance.executeUpdate();
		}
	}

	private void settle(Connection connection, Key key) throws SQLException, AssetAbort
	{
		try (PreparedStatement insert = connection.prepareStatement("INSERT INTO lc_settlement_key (scope, skey, op_id) VALUES (?, ?, ?)"))
		{
			insert.setString(1, key.scope());
			insert.setString(2, key.key());
			insert.setBytes(3, _op.bytes());
			insert.executeUpdate();
		}
		catch (SQLException e)
		{
			if (e.getErrorCode() == DUPLICATE_KEY)
			{
				throw new AssetAbort(AssetAbort.Reason.ALREADY_SETTLED, key.scope() + "/" + key.key(), e);
			}
			throw e;
		}
	}

	/** INV01 before anything is written: per item id, Σ DEBIT = Σ CREDIT. */
	private void postLegs(Connection connection, List<Leg> legs) throws SQLException, AssetAbort
	{
		final Map<Integer, Long> balance = new TreeMap<>();
		for (Leg leg : legs)
		{
			balance.merge(leg.itemId(), leg.direction() == Leg.Direction.DEBIT ? leg.qty() : -leg.qty(), Math::addExact);
		}
		for (Map.Entry<Integer, Long> item : balance.entrySet())
		{
			if (item.getValue() != 0)
			{
				throw new AssetAbort(AssetAbort.Reason.UNBALANCED, "item " + item.getKey() + " is off by " + item.getValue() + " in " + _op);
			}
		}
		try (PreparedStatement insert = connection.prepareStatement("INSERT INTO lc_ledger_posting (op_id, leg, account_kind, account_id, code, item_id, object_id, lifecycle_id, qty, direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"))
		{
			for (int i = 0; i < legs.size(); i++)
			{
				final Leg leg = legs.get(i);
				insert.setBytes(1, _op.bytes());
				insert.setInt(2, i);
				insert.setString(3, leg.kind().name());
				insert.setLong(4, leg.accountId());
				insert.setString(5, leg.code());
				insert.setInt(6, leg.itemId());
				insert.setObject(7, leg.objectId());
				insert.setObject(8, leg.lifecycleId());
				insert.setLong(9, leg.qty());
				insert.setString(10, leg.direction().name());
				insert.addBatch();
			}
			insert.executeBatch();
		}
	}

	/**
	 * The locking read of §3.2 {@code UNCERTAIN}: it waits while the original transaction is unresolved, so its answer is
	 * final. Retried until {@code UncertainCommitLookupMillis}; then the module freezes and the outcome stays unknown.
	 */
	private Outcome resolve(SQLException lost) throws AssetAbort
	{
		final AssetConfig config = _service.config();
		final long deadline = System.nanoTime() + (config.uncertainCommitLookupMillis() * 1_000_000L);
		SQLException last = lost;
		do
		{
			try (Connection connection = _service.db().open())
			{
				connection.setAutoCommit(false);
				lockWait(connection, Math.max(1, (deadline - System.nanoTime()) / 1_000_000L));
				try (PreparedStatement read = connection.prepareStatement("SELECT op_id FROM lc_asset_op WHERE op_id = ? FOR UPDATE"))
				{
					read.setBytes(1, _op.bytes());
					try (ResultSet result = read.executeQuery())
					{
						final boolean committed = result.next();
						connection.rollback();
						if (committed)
						{
							return Outcome.COMMITTED;
						}
						throw new AssetAbort(AssetAbort.Reason.NOT_COMMITTED, _op + ": the connection failed before the commit took effect", lost);
					}
				}
			}
			catch (SQLException e)
			{
				last = e;
			}
		}
		while (System.nanoTime() < deadline);
		try (Connection connection = _service.db().open())
		{
			ModuleState.freeze(connection, _module, "UNCERTAIN_COMMIT", null);
		}
		catch (SQLException e)
		{
			last.addSuppressed(e);
		}
		throw new UncertainCommitException(_op + ": the commit's result could not be learned; module " + _module + " frozen", last);
	}

	/** MariaDB counts lock waits in whole seconds; the wait is rounded up, never to zero (which would mean no wait). */
	private static void lockWait(Connection connection, long millis) throws SQLException
	{
		try (Statement statement = connection.createStatement())
		{
			statement.execute("SET SESSION innodb_lock_wait_timeout = " + Math.max(1, (millis + 999) / 1000));
		}
	}

	private static boolean contention(SQLException e)
	{
		for (Throwable t = e; t != null; t = t.getCause())
		{
			if ((t instanceof SQLException sql) && ((sql.getErrorCode() == DEADLOCK) || (sql.getErrorCode() == LOCK_WAIT_TIMEOUT)))
			{
				return true;
			}
		}
		return false;
	}

	private static void rollback(Connection connection)
	{
		try
		{
			connection.rollback();
		}
		catch (SQLException e)
		{
			// The connection is unusable: InnoDB rolls the transaction back when the session ends.
		}
		close(connection);
	}

	private static void close(Connection connection)
	{
		try
		{
			connection.close();
		}
		catch (SQLException e)
		{
			// Nothing to do: the pool or the server discards the session.
		}
	}

	/** Ends an uncommitted operation: since nothing is written before {@link #commit()}, there is nothing to undo. */
	@Override
	public void close()
	{
		_ended = true;
	}

	/** The state of one attempt: its connection, the custodies it locked, the legs and facts its steps produced. */
	static final class Execution
	{
		private final Connection _connection;
		private final AssetService _service;
		private final OpId _op;
		private final Map<Integer, Custody> _locked = new HashMap<>();
		private final TreeSet<Integer> _touched = new TreeSet<>();
		private final List<Leg> _legs = new ArrayList<>();
		private final List<DurableFact> _facts = new ArrayList<>();

		Execution(Connection connection, AssetService service, OpId op)
		{
			_connection = connection;
			_service = service;
			_op = op;
		}

		Connection connection()
		{
			return _connection;
		}

		AssetService service()
		{
			return _service;
		}

		OpId op()
		{
			return _op;
		}

		List<Leg> legs()
		{
			return _legs;
		}

		List<DurableFact> facts()
		{
			return _facts;
		}

		/** Locks the custody rows, in id order, before any step runs (§3.1: custody rows change only under their lock). */
		void lockCustodies(TreeSet<Integer> ids) throws SQLException
		{
			for (int id : ids)
			{
				try (PreparedStatement read = _connection.prepareStatement("SELECT kind, owner_module, owner_ref, state, version FROM lc_custody WHERE custody_id = ? FOR UPDATE"))
				{
					read.setInt(1, id);
					try (ResultSet result = read.executeQuery())
					{
						if (result.next())
						{
							_locked.put(id, new Custody(id, Custody.Kind.valueOf(result.getString(1)), result.getString(2), result.getLong(3), Custody.State.valueOf(result.getString(4)), result.getInt(5)));
						}
					}
				}
			}
		}

		/** The locked custody, or null if it does not exist. */
		Custody custody(int id)
		{
			return _locked.get(id);
		}

		/** Records that the operation changed the custody's rows; its version advances and INV11 is checked before commit. */
		void touched(int custody)
		{
			_touched.add(custody);
		}

		void checkCustodies() throws SQLException, AssetAbort
		{
			// Filled by the custody operations.
		}
	}
}
