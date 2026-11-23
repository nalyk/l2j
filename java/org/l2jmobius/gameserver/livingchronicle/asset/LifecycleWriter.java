/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.IdManagerConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.item.enums.ItemLocation;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.livingchronicle.journal.DurableFact;
import org.l2jmobius.gameserver.livingchronicle.journal.OutboxStager;

/**
 * The lifecycle writer (docs/spec/asset-service.md §11.35, CP-0034): while Living Chronicle runs, a non-stackable item's
 * object id is never released at runtime, so no id a lifecycle names is ever reused; every such destruction and every
 * {@code track} capture is offered, with no lock but the queue's, to one bounded queue this one thread drains in the
 * order of the offers. It keeps the ids whose destruction it handled this run; it checks a capture in memory holding no
 * row lock, then decides in a transaction that locks the item's row and then the lifecycle's (fact-journal.md §3.1).
 * Nothing waits on it: a full queue loses the offer, which the next start repairs ({@code VANISHED}).
 */
public final class LifecycleWriter
{
	private static final Logger LOGGER = Logger.getLogger(LifecycleWriter.class.getName());

	/** {@code LifecycleQueueCapacity} (§8): 64-65536, default 4096. */
	public static final int DEFAULT_CAPACITY = 4096;

	/** The writer of this run, or null: then every id is released as upstream does (truth, and tests that install none). */
	private static volatile LifecycleWriter _running;

	/** The live lifecycles' object ids the last startup pass found, for the id set (§11.35); empty before it. */
	private static volatile Set<Integer> _reserved = Set.of();

	/** One offer: a destruction ({@code item} null) or a capture. */
	record Offer(int objectId, int itemId, Item item, int ownerId, String location, long capturedAt, String process, int reference)
	{
		boolean isEnd()
		{
			return item == null;
		}
	}

	private final BlockingQueue<Offer> _queue;
	private final AssetService _service;
	private final int _run;
	private final BitSet _destroyed = new BitSet(); // writer thread only
	private final AtomicLong _lost = new AtomicLong();
	private final AtomicLong _sequence = new AtomicLong();
	private volatile Thread _thread;

	LifecycleWriter(AssetService service, int run, int capacity)
	{
		if ((capacity < 64) || (capacity > 65536))
		{
			throw new IllegalArgumentException("LifecycleQueueCapacity = " + capacity + " is outside 64-65536");
		}
		_service = service;
		_run = run;
		_queue = new ArrayBlockingQueue<>(capacity);
	}

	/**
	 * CP-0034, from {@code Item.releaseObjectIdWhenRowDeleted} (which {@code ItemManager.destroyItem} calls under the item's
	 * monitor, right after count 0 and the removal from the world): a non-stackable item's id is kept and its destruction
	 * offered. O(1); no lock but the queue's.
	 * @return true when the id is kept (the caller then neither releases it nor leaves it to {@code removeFromDb})
	 */
	public static boolean keepsDestroyed(Item item)
	{
		final LifecycleWriter writer = _running;
		if ((writer == null) || item.isStackable())
		{
			return false;
		}
		if (!writer._queue.offer(new Offer(item.getObjectId(), item.getId(), null, 0, null, 0, null, 0)))
		{
			writer._lost.incrementAndGet(); // the id is still kept; the next start ends its lifecycle VANISHED
		}
		return true;
	}

	/**
	 * {@code track} (§4.3): the capture, O(1), no lock but the queue's.
	 * @return false when refused, nothing changed: no writer, a stackable, retired or destroyed item, or a full queue
	 */
	public static boolean capture(Item item, ItemProcessType process, int reference)
	{
		final LifecycleWriter writer = _running;
		if ((writer == null) || item.isStackable() || item.isRetired() || (item.getCount() <= 0) || (item.getItemLocation() == ItemLocation.VOID))
		{
			return false;
		}
		return writer._queue.offer(captureOf(item, process, reference));
	}

	/** The capture's offer, as {@link #capture} builds it. */
	static Offer captureOf(Item item, ItemProcessType process, int reference)
	{
		return new Offer(item.getObjectId(), item.getId(), item, item.getOwnerId(), item.getItemLocation().name(), GameClock.millis(), process == null ? "NONE" : process.name(), reference);
	}

	/** Tests: an offer as a hot path would make it. */
	boolean offer(Offer offer)
	{
		return _queue.offer(offer);
	}

	/** @return this writer's run */
	int run()
	{
		return _run;
	}

	/** @return the live lifecycles' object ids the startup pass found, which the id set marks used (CP-0034) */
	public static Set<Integer> reservedAtStartup()
	{
		return _reserved;
	}

	/** @return whether a writer runs (Living Chronicle runs, not truth) */
	public static boolean isRunning()
	{
		return _running != null;
	}

	/** What the startup pass found: this run, and the live lifecycles' object ids. */
	record Startup(int run, Set<Integer> live)
	{
	}

	/**
	 * The startup pass (§11.35), from {@code DatabaseIdManager.cleanDatabase} after its cleanup queries, before the id set
	 * is built, while Living Chronicle runs: {@link #startupPass}, its live ids kept for the id set, and the writer of this
	 * run installed and started. Any failure throws, which stops the start.
	 */
	public static void atStartup(Connections db, ObjectIds ids, OutboxStager stager)
	{
		final Startup startup;
		try (Connection connection = db.open())
		{
			startup = startupPass(connection, stager);
		}
		catch (SQLException e)
		{
			throw new IllegalStateException("Living Chronicle: the lifecycles' startup pass failed; the server must not start (asset-service.md §11.35)", e);
		}
		_reserved = Set.copyOf(startup.live());
		final LifecycleWriter writer = new LifecycleWriter(new AssetService(db, ids, stager, AssetConfig.defaults(), Map.of()), startup.run(), DEFAULT_CAPACITY);
		writer.start();
		_running = writer;
		LOGGER.info("Living Chronicle: lifecycles, run " + startup.run() + ", " + startup.live().size() + " live");
	}

	/**
	 * In one transaction: ends {@code VANISHED}, in the previous run (the last its object was known in), every live lifecycle
	 * whose object id is in no {@code items} and no {@code itemsonground} row, with its fact; writes this run's
	 * {@code lc_asset_run} row; reads the live lifecycles' object ids.
	 */
	static Startup startupPass(Connection connection, OutboxStager stager) throws SQLException
	{
		final boolean autoCommit = connection.getAutoCommit();
		connection.setAutoCommit(false);
		try
		{
			final OpId op = OpId.of("ASSET", 0, "LIFECYCLE_START/" + GameClock.millis(), 0);
			Integer previous = null;
			try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT MAX(run_id) FROM lc_asset_run FOR UPDATE"))
			{
				if (result.next())
				{
					final int value = result.getInt(1);
					previous = result.wasNull() ? null : value;
				}
			}
			final List<long[]> vanished = new ArrayList<>();
			try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT l.lifecycle_id, l.object_id, l.item_id FROM lc_item_lifecycle l WHERE l.ended_at IS NULL AND NOT EXISTS (SELECT 1 FROM items i WHERE i.object_id = l.object_id) AND NOT EXISTS (SELECT 1 FROM itemsonground g WHERE g.object_id = l.object_id) FOR UPDATE"))
			{
				while (result.next())
				{
					vanished.add(new long[]
					{
						result.getLong(1),
						result.getInt(2),
						result.getInt(3)
					});
				}
			}
			if (!vanished.isEmpty())
			{
				try (PreparedStatement opRow = connection.prepareStatement("INSERT INTO lc_asset_op (op_id, module, action, subject_ref, committed_at) VALUES (?, 'ASSET', 'LIFECYCLE_START', 0, NOW(3))"))
				{
					opRow.setBytes(1, op.bytes());
					opRow.executeUpdate();
				}
				final List<DurableFact> facts = new ArrayList<>();
				try (PreparedStatement end = connection.prepareStatement("UPDATE lc_item_lifecycle SET ended_at = GREATEST(NOW(3), began_at), end_reason = 'VANISHED', ended_op = ?, ended_run = ? WHERE lifecycle_id = ? AND ended_at IS NULL"))
				{
					for (long[] row : vanished)
					{
						end.setBytes(1, op.bytes());
						if (previous == null)
						{
							end.setNull(2, java.sql.Types.INTEGER);
						}
						else
						{
							end.setInt(2, previous);
						}
						end.setLong(3, row[0]);
						end.executeUpdate();
						facts.add(AssetFact.lifecycle("LifecycleEnded", "lifecycle", row[0], "object", row[1], "item", row[2], "reason", "VANISHED", "operation", op.hex()));
					}
				}
				stager.stage(connection, op, facts);
			}
			final int run;
			try (PreparedStatement insert = connection.prepareStatement("INSERT INTO lc_asset_run (started_at) VALUES (NOW(3))", Statement.RETURN_GENERATED_KEYS))
			{
				insert.executeUpdate();
				try (ResultSet keys = insert.getGeneratedKeys())
				{
					keys.next();
					run = keys.getInt(1);
				}
			}
			final Set<Integer> live = new HashSet<>();
			try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT object_id FROM lc_item_lifecycle WHERE ended_at IS NULL"))
			{
				while (result.next())
				{
					live.add(result.getInt(1));
				}
			}
			connection.commit();
			return new Startup(run, Set.copyOf(live));
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

	/** Tests: install a writer for a run, started or held (its offers queue until {@link #start}), or none (null). */
	static void install(LifecycleWriter writer, boolean started)
	{
		final LifecycleWriter previous = _running;
		_running = writer;
		if (previous != null)
		{
			previous.stop();
		}
		if ((writer != null) && started)
		{
			writer.start();
		}
	}

	/** Tests: the ids the id set must hold. */
	static void reserve(Set<Integer> ids)
	{
		_reserved = Set.copyOf(ids);
	}

	void start()
	{
		final Thread thread = new Thread(this::drain, "lc-lifecycles");
		thread.setDaemon(true);
		_thread = thread;
		thread.start();
	}

	void stop()
	{
		final Thread thread = _thread;
		_thread = null;
		if (thread != null)
		{
			thread.interrupt();
		}
	}

	/** @return offers lost to a full queue (logged and counted, §11.35) */
	long lost()
	{
		return _lost.get();
	}

	/** Tests: whether the queue is empty and nothing is being handled. */
	boolean idle()
	{
		return _queue.isEmpty() && (_handling == 0);
	}

	private volatile int _handling;

	private void drain()
	{
		while (_thread == Thread.currentThread())
		{
			final Offer offer;
			try
			{
				offer = _queue.take();
			}
			catch (InterruptedException e)
			{
				return;
			}
			_handling = 1;
			try
			{
				if (offer.isEnd())
				{
					end(offer);
				}
				else
				{
					capture(offer);
				}
			}
			catch (RuntimeException e)
			{
				_lost.incrementAndGet();
				LOGGER.log(Level.WARNING, "Living Chronicle: a lifecycle " + (offer.isEnd() ? "end" : "capture") + " of object " + offer.objectId() + " failed; the next start repairs what it left", e);
			}
			finally
			{
				_handling = 0;
			}
		}
	}

	private int bit(int objectId)
	{
		return objectId - IdManagerConfig.FIRST_OBJECT_ID;
	}

	private void end(Offer offer)
	{
		final int bit = bit(offer.objectId());
		if (bit >= 0)
		{
			_destroyed.set(bit); // the id is kept for the run: whatever happens to the transaction, the object is gone
		}
		run(offer, "LIFECYCLE_END", tx -> tx.endDestroyed(offer.objectId(), _run));
	}

	private void capture(Offer offer)
	{
		final int bit = bit(offer.objectId());
		if ((bit >= 0) && _destroyed.get(bit))
		{
			return; // its destruction was handled first: gone
		}
		if (World.findObject(offer.objectId()) instanceof Item item)
		{
			final boolean retired;
			synchronized (item) // an operation's retirement and retail's destruction hold it; no row lock is held here
			{
				retired = item.isRetired();
			}
			if (retired)
			{
				return; // an operation took it and begins its own lifecycle
			}
		}
		run(offer, "TRACK", tx -> tx.track(offer, _run));
	}

	private interface Body
	{
		void apply(AssetTx tx);
	}

	private void run(Offer offer, String action, Body body)
	{
		final OpId op = OpId.of("ASSET", offer.objectId(), action + "/" + _run, (int) _sequence.incrementAndGet());
		try (AssetTx tx = _service.begin(op, "ASSET", action, offer.objectId()))
		{
			body.apply(tx);
			final Outcome outcome = tx.commit();
			if (outcome != Outcome.COMMITTED)
			{
				_lost.incrementAndGet();
				LOGGER.warning("Living Chronicle: lifecycle " + action + " of object " + offer.objectId() + " ended " + outcome + "; the next start repairs what it left");
			}
		}
		catch (AssetAbort e)
		{
			if (e.reason() != AssetAbort.Reason.INSUFFICIENT) // nothing to write: the object is gone, or has no live lifecycle to end
			{
				_lost.incrementAndGet();
				LOGGER.log(Level.WARNING, "Living Chronicle: lifecycle " + action + " of object " + offer.objectId() + " aborted", e);
			}
		}
		catch (SQLException e)
		{
			_lost.incrementAndGet();
			LOGGER.log(Level.WARNING, "Living Chronicle: lifecycle " + action + " of object " + offer.objectId() + " failed", e);
		}
	}
}
