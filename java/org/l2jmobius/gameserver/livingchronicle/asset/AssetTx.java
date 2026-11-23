package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.entity.itemcontainer.ItemContainer;
import org.l2jmobius.gameserver.livingchronicle.journal.DurableFact;
import org.l2jmobius.gameserver.managers.CastleManager;
import org.l2jmobius.gameserver.mechanics.siege.Castle;
import org.l2jmobius.gameserver.mechanics.variables.ItemVariables;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;

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

	/** Found inside the transaction: the operation, or its refund, has already committed (§3.2). */
	private static final class Prior extends Exception
	{
		private static final long serialVersionUID = 1L;
		final Outcome _outcome;

		Prior(Outcome outcome)
		{
			super(outcome.name(), null, false, false);
			_outcome = outcome;
		}
	}

	/** A debit from a Mobius holder into a custody (§4.3 {@code fund}, custody set) or a sink ({@code charge}, sink set). */
	private record Debit(int custody, Holder holder, List<Asset> assets, SinkCode sink)
	{
	}

	/**
	 * What was taken in memory: a whole object (§3.3 step 1, {@code part} 0) or {@code part} units of a stack (step 2);
	 * what the transaction writes and a refund returns.
	 */
	private record Taken(Debit debit, Item item, Map<String, String> variables, long part)
	{
		boolean whole()
		{
			return part == 0;
		}
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
	/** Characters whose rows the operation locks first (the global row-lock order starts with `characters`). */
	private final TreeSet<Integer> _characters = new TreeSet<>();
	private final List<Debit> _debits = new ArrayList<>();
	private final List<Taken> _taken = new ArrayList<>();
	/** Characters whose items this operation takes: marked before the first debit (§2.4). */
	private final TreeSet<Integer> _marked = new TreeSet<>();
	/** The service mails the last attempt wrote, adopted by the game after commit. */
	private List<Integer> _mails = List.of();
	/** What the committed attempt's legs created or destroyed, by item id, for the existence counters (§2.6). */
	private Map<Integer, Long> _existence = Map.of();
	private final List<Version> _versions = new ArrayList<>();
	private final List<Key> _keys = new ArrayList<>();
	private final List<DurableFact> _facts = new ArrayList<>();
	/** Run once by this call after its commit is known (§4.3 {@code raid}): never for a prior commit, never by recovery. */
	private final List<Runnable> _afterCommit = new ArrayList<>();
	private boolean _ended;
	/** The owner modules of the custodies the last attempt locked: frozen with the operation's module on an unresolved commit. */
	private final TreeSet<String> _owners = new TreeSet<>();

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

	/**
	 * Moves assets from one custody to another, of the same or of two owner modules (§4.3 {@code move}): both must be
	 * {@code OPEN}, neither owner frozen, and the receiving owner must allow the holding (INV05).
	 */
	public void move(int from, int to, List<Asset> assets)
	{
		if (from == to)
		{
			throw new IllegalArgumentException("a move needs two custodies");
		}
		final List<Asset> moved = List.copyOf(assets);
		step(execution ->
		{
			final Custody source = execution.open(from);
			final Custody target = execution.open(to);
			execution.notFrozen(source);
			execution.notFrozen(target);
			execution.allowsHolding(target);
			final List<Map<String, Object>> legs = new ArrayList<>();
			for (Asset asset : execution.wholes(from, moved))
			{
				execution.take(from, asset, false);
				execution.give(to, from, asset);
				final Long lifecycle = execution.lifecycle(asset);
				execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, from, null, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.DEBIT));
				execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, to, null, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.CREDIT));
				legs.add(legOf(asset, lifecycle));
			}
			execution.facts().add(AssetFact.of("CustodyMoved", from, "operation", _op.hex(), "from", from, "to", to, "legs", legs));
		}, from, to);
	}

	/**
	 * The lifecycle writer's capture (§11.35, {@code track}): locks the object's lifecycle rows (all of them, so an
	 * operation's begin of the same object waits). A live lifecycle is bound; one ended in this run means an operation's
	 * sink took the object since the writer's check ({@code INSUFFICIENT}, nothing written); otherwise one is begun
	 * ({@code began_op} null): within a run the id names this item only, so a destruction since the capture is ended by its
	 * end, behind this capture in the queue.
	 */
	void track(LifecycleWriter.Offer capture, int run)
	{
		step(execution ->
		{
			final Connection connection = execution.connection();
			long live = 0;
			long latest = 0;
			boolean latestEndedThisRun = false;
			try (PreparedStatement rows = connection.prepareStatement("SELECT lifecycle_id, ended_at IS NULL, ended_run FROM lc_item_lifecycle WHERE object_id = ? FOR UPDATE"))
			{
				rows.setInt(1, capture.objectId());
				try (ResultSet result = rows.executeQuery())
				{
					while (result.next())
					{
						final long id = result.getLong(1);
						if (result.getBoolean(2))
						{
							live = id;
						}
						if (id > latest)
						{
							latest = id;
							final int endedRun = result.getInt(3);
							latestEndedThisRun = !result.wasNull() && (endedRun == run);
						}
					}
				}
			}
			final boolean begun;
			final long lifecycle;
			if (live != 0)
			{
				lifecycle = live;
				begun = false;
			}
			else if (latestEndedThisRun)
			{
				throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, "object " + capture.objectId() + " was taken by an operation in this run");
			}
			else
			{
				try (PreparedStatement insert = connection.prepareStatement("INSERT INTO lc_item_lifecycle (object_id, item_id, began_op, began_at) VALUES (?, ?, NULL, NOW(3))", Statement.RETURN_GENERATED_KEYS))
				{
					insert.setInt(1, capture.objectId());
					insert.setInt(2, capture.itemId());
					insert.executeUpdate();
					try (ResultSet keys = insert.getGeneratedKeys())
					{
						keys.next();
						lifecycle = keys.getLong(1);
					}
				}
				begun = true;
			}
			execution.facts().add(AssetFact.lifecycle("LifecycleTracked", "lifecycle", lifecycle, "object", capture.objectId(), "item", capture.itemId(), "owner", capture.ownerId(), "location", capture.location(), "captured_at", capture.capturedAt(), "process", capture.process(), "reference", capture.reference(), "begun", begun));
		});
	}

	/** The lifecycle writer's end (§11.35): the live lifecycle of a destroyed object, if any, ends {@code DESTROYED}. */
	void endDestroyed(int objectId, int run)
	{
		step(execution ->
		{
			final Connection connection = execution.connection();
			long lifecycle = 0;
			int itemId = 0;
			try (PreparedStatement live = connection.prepareStatement("SELECT lifecycle_id, item_id FROM lc_item_lifecycle WHERE live_object_id = ? FOR UPDATE"))
			{
				live.setInt(1, objectId);
				try (ResultSet result = live.executeQuery())
				{
					if (result.next())
					{
						lifecycle = result.getLong(1);
						itemId = result.getInt(2);
					}
				}
			}
			if (lifecycle == 0)
			{
				throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, "object " + objectId + " has no live lifecycle");
			}
			try (PreparedStatement end = connection.prepareStatement("UPDATE lc_item_lifecycle SET ended_at = GREATEST(NOW(3), began_at), end_reason = 'DESTROYED', ended_op = ?, ended_run = ? WHERE lifecycle_id = ?"))
			{
				end.setBytes(1, _op.bytes());
				end.setInt(2, run);
				end.setLong(3, lifecycle);
				end.executeUpdate();
			}
			execution.facts().add(AssetFact.lifecycle("LifecycleEnded", "lifecycle", lifecycle, "object", objectId, "item", itemId, "reason", "DESTROYED", "operation", _op.hex()));
		});
	}

	/**
	 * A caravan's raid (§4.3 {@code raid}, §2.5 {@code RAID_SHARE}, {@code ROAD_LOSS}; aden-remembers.md §4.6): {@code share}
	 * and {@code rest} (non-stackable items by object id, a stackable by item id and quantity) are together exactly the
	 * manifest's contents, else nothing happens; every manifest row is deleted. A non-stackable share object's row is read
	 * first and its lifecycle stays live; a stackable's share becomes one new object of its quantity. The share drops at
	 * the Master's place and instance, as taken now, only once this call's commit is known, never repeated; the rest is
	 * lost to the road, its lifecycles ended.
	 */
	public void raid(int manifest, List<Asset> share, List<Asset> rest, Npc master)
	{
		final List<Asset> shared = List.copyOf(share);
		final List<Asset> lost = List.copyOf(rest);
		final int x = master.getX();
		final int y = master.getY();
		final int z = master.getZ();
		final int instanceId = master.getInstanceId();
		final List<RaidShare.Snapshot> drops = new ArrayList<>();
		step(execution ->
		{
			drops.clear(); // a retried attempt starts again
			final Custody custody = execution.open(manifest);
			execution.notFrozen(custody);
			execution.exactlyTheContents(manifest, shared, lost); // locks every row and its lifecycle, in object-id order
			final long caravan = custody.ownerRef();
			final List<Map<String, Object>> shareLegs = new ArrayList<>();
			final Map<Integer, Long> stackShare = new TreeMap<>(); // one new object per stackable item id, whatever entries named it
			final List<Asset> wholeShare = new ArrayList<>();
			for (Asset asset : shared)
			{
				if (asset.objectId() != null)
				{
					wholeShare.add(asset);
				}
				else
				{
					stackShare.merge(asset.itemId(), asset.qty(), Long::sum);
				}
			}
			wholeShare.sort(Comparator.comparingInt(Asset::objectId));
			for (Asset asset : wholeShare)
			{
				final RaidShare.Snapshot snapshot = execution.snapshot(asset.objectId());
				final Long lifecycle = execution.lifecycleOf(asset.objectId(), asset.itemId());
				execution.take(manifest, asset, true, false); // RAID_SHARE keeps its lifecycle: the object falls on the ground
				execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, manifest, null, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.DEBIT));
				execution.legs().add(new Leg(Leg.AccountKind.SINK, caravan, SinkCode.RAID_SHARE.name(), asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.CREDIT));
				execution.existence(asset.itemId(), -asset.qty());
				shareLegs.add(legOf(asset, lifecycle));
				drops.add(snapshot);
			}
			for (Map.Entry<Integer, Long> stack : stackShare.entrySet())
			{
				final Asset asset = new Asset(stack.getKey(), stack.getValue(), null);
				execution.take(manifest, asset, true);
				execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, manifest, null, asset.itemId(), null, null, asset.qty(), Leg.Direction.DEBIT));
				execution.legs().add(new Leg(Leg.AccountKind.SINK, caravan, SinkCode.RAID_SHARE.name(), asset.itemId(), null, null, asset.qty(), Leg.Direction.CREDIT));
				execution.existence(asset.itemId(), -asset.qty());
				shareLegs.add(legOf(asset));
				drops.add(RaidShare.Snapshot.amount(_service.ids().next(), asset.itemId(), asset.qty()));
			}
			final List<Map<String, Object>> restLegs = new ArrayList<>();
			for (Asset asset : execution.wholes(manifest, lost))
			{
				execution.take(manifest, asset, true);
				final Long lifecycle = execution.lifecycle(asset);
				execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, manifest, null, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.DEBIT));
				execution.legs().add(new Leg(Leg.AccountKind.SINK, caravan, SinkCode.ROAD_LOSS.name(), asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.CREDIT));
				execution.existence(asset.itemId(), -asset.qty());
				restLegs.add(legOf(asset, lifecycle));
			}
			final Map<String, Object> place = new LinkedHashMap<>();
			place.put("x", x);
			place.put("y", y);
			place.put("z", z);
			place.put("instance", instanceId);
			execution.facts().add(AssetFact.of("ManifestRaided", manifest, "custody", manifest, "caravan", caravan, "share", shareLegs, "rest", restLegs, "place", place));
		}, manifest);
		_afterCommit.add(() -> RaidShare.drop(List.copyOf(drops), master, x, y, z, instanceId));
	}

	/** The representation codes {@code issue} creates from (§2.5): valueless, non-tradable, never minted value. */
	public enum Representation
	{
		PACT_PARCHMENT("ParchmentIssued"),
		LEDGER_SEAL("SealIssued");

		private final String _fact;

		Representation(String fact)
		{
			_fact = fact;
		}
	}

	/**
	 * Issues one representation item (§4.3 {@code issue}, §2.5 {@code PACT_PARCHMENT}, {@code LEDGER_SEAL}): from its source
	 * to the character by service mail, with a lifecycle begun, under the settlement key
	 * {@code issue}/{@code <code>/<reference>/<recipientKey>}, so one operation may issue to every party and an item is
	 * never issued twice for one key; refused ({@code INELIGIBLE}) unless the template is a representation.
	 * @param reference the contract the item represents
	 * @param recipientKey a parchment's character (pact.md §4.8), a seal's hunter (black-ledger.md §4.9)
	 */
	public void issue(int charId, int itemId, Representation code, long reference, String recipientKey, String subject, String content)
	{
		if ((subject == null) || subject.isBlank() || (content == null))
		{
			throw new IllegalArgumentException("a service mail names its institution and says why");
		}
		settlementKey("issue", code.name() + "/" + reference + "/" + recipientKey);
		_characters.add(charId);
		step(execution ->
		{
			final String refusal = Eligibility.representationRefusal(ItemData.getInstance().getTemplate(itemId));
			if (refusal != null)
			{
				throw new AssetAbort(AssetAbort.Reason.INELIGIBLE, "item " + itemId + " is not a representation: " + refusal);
			}
			if (!execution.receiverAlive(charId))
			{
				throw new AssetAbort(AssetAbort.Reason.RECEIVER_GONE, "character " + charId + " is gone or marked for deletion");
			}
			final Connection connection = execution.connection();
			final int mail = insertServiceMail(connection, charId, subject, content);
			final Asset asset = attachCreated(connection, mail, new Asset(itemId, 1, null));
			final Long lifecycle = execution.lifecycle(asset);
			execution.legs().add(new Leg(Leg.AccountKind.SOURCE, reference, code.name(), itemId, asset.objectId(), lifecycle, 1, Leg.Direction.DEBIT));
			execution.legs().add(new Leg(Leg.AccountKind.MAIL, mail, null, itemId, asset.objectId(), lifecycle, 1, Leg.Direction.CREDIT));
			execution.existence(itemId, 1);
			execution.mailed(mail, charId, MailReason.PAYOUT);
			execution.facts().add(AssetFact.in("asset.contract." + reference, code._fact, "contract", reference, "character", charId, "mail", mail, "object", asset.objectId(), "lifecycle", lifecycle));
		});
	}

	/**
	 * A Guild pool's daily recycled share (§4.3 {@code recycle}, §2.5 {@code SINK_RECYCLE}; aden-remembers.md §4.6): Adena
	 * from the source to a {@code POOL} custody, for a day already over, at most the recorded teleport-fee sink of the
	 * territory that day × the active {@code RecyclePermille} / 1000, once per territory and day (settlement key
	 * {@code recycle}/{@code <territory>/<day>}). Refused with no reader or permille installed, nothing recorded, a
	 * permille of 0 or above {@link RecycleBound#CEILING_PERMILLE}, or above the share.
	 */
	public void recycle(int pool, int territory, java.time.LocalDate day, long amount)
	{
		if (amount <= 0)
		{
			throw new IllegalArgumentException("a recycled amount is positive, not " + amount);
		}
		settlementKey("recycle", territory + "/" + day);
		step(execution ->
		{
			if (!day.isBefore(org.l2jmobius.gameserver.livingchronicle.clock.GameClock.localDateTime().toLocalDate()))
			{
				throw new AssetAbort(AssetAbort.Reason.NOT_RECORDED, "day " + day + " is not over");
			}
			final RecycleBound.RecordedSinks reader = RecycleBound.reader();
			final java.util.function.IntSupplier permilles = RecycleBound.permille();
			if ((reader == null) || (permilles == null))
			{
				throw new AssetAbort(AssetAbort.Reason.NOT_RECORDED, "no recorded-sink reader or no RecyclePermille installed");
			}
			final java.util.OptionalLong recorded = reader.teleportFeesDestroyed(territory, day);
			if (recorded.isEmpty())
			{
				throw new AssetAbort(AssetAbort.Reason.NOT_RECORDED, "nothing recorded for territory " + territory + " on " + day);
			}
			final int permille = permilles.getAsInt();
			if ((permille <= 0) || (permille > RecycleBound.CEILING_PERMILLE))
			{
				throw new AssetAbort(AssetAbort.Reason.ABOVE_SHARE, "RecyclePermille " + permille + " is outside 1-" + RecycleBound.CEILING_PERMILLE);
			}
			final long sunk = recorded.getAsLong();
			final long share = ((sunk / 1000) * permille) + (((sunk % 1000) * permille) / 1000); // floor(sunk × permille / 1000), never overflowing
			if (amount > share)
			{
				throw new AssetAbort(AssetAbort.Reason.ABOVE_SHARE, amount + " is above the share " + share + " (" + permille + " per mille of " + recorded.getAsLong() + ")");
			}
			final Custody custody = execution.open(pool);
			if (custody.kind() != Custody.Kind.POOL)
			{
				throw new AssetAbort(AssetAbort.Reason.INELIGIBLE, "custody " + pool + " is not a pool");
			}
			execution.notFrozen(custody);
			execution.allowsHolding(custody);
			execution.give(pool, pool, new Asset(Inventory.ADENA_ID, amount, null));
			execution.legs().add(new Leg(Leg.AccountKind.SOURCE, pool, "SINK_RECYCLE", Inventory.ADENA_ID, null, null, amount, Leg.Direction.DEBIT));
			execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, pool, null, Inventory.ADENA_ID, null, null, amount, Leg.Direction.CREDIT));
			execution.existence(Inventory.ADENA_ID, amount);
			execution.facts().add(AssetFact.of("SinkRecycled", pool, "pool", pool, "territory", territory, "day", day.toString(), "amount", amount, "recorded", recorded.getAsLong(), "permille", permille));
		}, pool);
	}

	/** Closes an empty custody for good (§3.1 {@code OPEN → CLOSED}). */
	public void close(int custody)
	{
		step(execution ->
		{
			execution.notFrozen(execution.open(custody));
			try (PreparedStatement rows = execution.connection().prepareStatement("SELECT COUNT(*) FROM items WHERE owner_id = -1 AND loc = 'ESCROW' AND loc_data = ?"))
			{
				rows.setInt(1, custody);
				try (ResultSet result = rows.executeQuery())
				{
					result.next();
					if (result.getLong(1) > 0)
					{
						throw new AssetAbort(AssetAbort.Reason.NOT_EMPTY, "custody " + custody + " still holds " + result.getLong(1) + " rows");
					}
				}
			}
			try (PreparedStatement closing = execution.connection().prepareStatement("UPDATE lc_custody SET state = 'CLOSED', closed_op = ?, closed_at = NOW(3), version = version + 1 WHERE custody_id = ?"))
			{
				closing.setBytes(1, _op.bytes());
				closing.setInt(2, custody);
				closing.executeUpdate();
			}
			execution.touched(custody); // INV11 before closing: postings with no rows are a disagreement, not an empty custody
			execution.facts().add(AssetFact.of("CustodyClosed", custody, "custody", custody, "reason", "EMPTY"));
		}, custody);
	}

	/**
	 * Releases assets from a custody to a character as one service mail (§2.1, §4.3 {@code release}): a message from the
	 * system, written in this transaction with its attachments, its delivery and the {@code MAIL} legs, and adopted by the
	 * game after commit. The receiver's row is locked first; a receiver who is gone or marked for deletion aborts it
	 * ({@code RECEIVER_GONE}) and nothing moves. A whole object keeps its object id; an amount becomes a new attachment row.
	 */
	public void release(int custody, int receiverCharId, List<Asset> assets, MailReason reason, String subject, String content)
	{
		if ((subject == null) || subject.isBlank() || (content == null))
		{
			throw new IllegalArgumentException("a service mail names its institution and says why");
		}
		final List<Asset> released = List.copyOf(assets);
		_characters.add(receiverCharId);
		step(execution ->
		{
			execution.notFrozen(execution.open(custody));
			if (!execution.receiverAlive(receiverCharId))
			{
				throw new AssetAbort(AssetAbort.Reason.RECEIVER_GONE, "character " + receiverCharId + " is gone or marked for deletion");
			}
			final Connection connection = execution.connection();
			final int message = insertServiceMail(connection, receiverCharId, subject, content);
			final List<Map<String, Object>> legs = new ArrayList<>();
			for (Asset asset : execution.wholes(custody, released))
			{
				execution.take(custody, asset, false);
				if (asset.objectId() != null)
				{
					try (PreparedStatement move = connection.prepareStatement("UPDATE items SET loc = 'MAIL', loc_data = ? WHERE object_id = ? AND owner_id = -1 AND loc = 'ESCROW' AND loc_data = ?"))
					{
						move.setInt(1, message);
						move.setInt(2, asset.objectId());
						move.setInt(3, custody);
						Execution.expectOne(move.executeUpdate(), asset.objectId());
					}
				}
				else
				{
					try (PreparedStatement attach = connection.prepareStatement("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data, time_of_use, custom_type1, custom_type2, mana_left, time) VALUES (-1, ?, ?, ?, 0, 'MAIL', ?, NULL, 0, 0, -1, 0)"))
					{
						attach.setInt(1, _service.ids().next());
						attach.setInt(2, asset.itemId());
						attach.setLong(3, asset.qty());
						attach.setInt(4, message);
						attach.executeUpdate();
					}
				}
				final Long lifecycle = execution.lifecycle(asset);
				execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, custody, null, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.DEBIT));
				execution.legs().add(new Leg(Leg.AccountKind.MAIL, message, null, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.CREDIT));
				legs.add(legOf(asset, lifecycle));
			}
			execution.mailed(message, receiverCharId, reason);
			execution.facts().add(AssetFact.of("EscrowReleased", custody, "custody", custody, "receiver", receiverCharId, "mail", message, "reason", reason.name(), "legs", legs));
		}, custody);
	}

	/**
	 * Routes a kill's drops (§4.3 {@code route}, §2.5 {@code LOOT_DROP}; pact.md §4.5): each enters from the
	 * {@code LOOT_DROP} source, referenced by the charter (the custody's owner); each receiver's by one service mail, the auctioned
	 * ones (receiver 0) into the custody, which they must be eligible for by their template (§4.4). All or nothing: an
	 * ineligible auctioned drop or a receiver gone refuses the whole operation. A non-stackable count is rows of one. The
	 * caller writes the kill's settlement key; {@code kill} is its number ({@code Kill.ref}) and {@code npcObjectId} the
	 * monster's, both in {@code Asset.LootRouted} with the charter, the receivers and their mails (§5).
	 */
	public void route(int custody, long kill, int npcObjectId, List<RoutedDrop> drops, String subject, String content)
	{
		if ((subject == null) || subject.isBlank() || (content == null))
		{
			throw new IllegalArgumentException("a service mail names its institution and says why");
		}
		if (drops.isEmpty())
		{
			throw new IllegalArgumentException("a route routes something");
		}
		final List<RoutedDrop> routed = List.copyOf(drops);
		final Map<Integer, List<RoutedDrop>> byReceiver = new LinkedHashMap<>();
		final List<RoutedDrop> auctioned = new ArrayList<>();
		for (RoutedDrop drop : routed)
		{
			if (drop.receiverCharId() == 0)
			{
				auctioned.add(drop);
			}
			else
			{
				byReceiver.computeIfAbsent(drop.receiverCharId(), receiver -> new ArrayList<>()).add(drop);
				_characters.add(drop.receiverCharId());
			}
		}
		step(execution ->
		{
			final Custody target = execution.open(custody);
			execution.notFrozen(target);
			execution.allowsHolding(target); // INV05, as every operation that touches a custody checks it
			final long charter = target.ownerRef(); // the posting's reference is the charter (§2.5), the custody's owner
			for (RoutedDrop drop : auctioned)
			{
				final String refusal = Eligibility.templateRefusal(ItemData.getInstance().getTemplate(drop.itemId()));
				if (refusal != null)
				{
					throw new AssetAbort(AssetAbort.Reason.INELIGIBLE, "item " + drop.itemId() + " may not enter custody " + custody + ": " + refusal);
				}
			}
			for (int receiver : byReceiver.keySet())
			{
				if (!execution.receiverAlive(receiver))
				{
					throw new AssetAbort(AssetAbort.Reason.RECEIVER_GONE, "character " + receiver + " is gone or marked for deletion");
				}
			}
			final String code = "LOOT_DROP";
			final List<Map<String, Object>> legs = new ArrayList<>();
			final List<Map<String, Object>> receivers = new ArrayList<>();
			final Connection connection = execution.connection();
			for (Map.Entry<Integer, List<RoutedDrop>> entry : byReceiver.entrySet())
			{
				final int message = insertServiceMail(connection, entry.getKey(), subject, content);
				for (RoutedDrop drop : entry.getValue())
				{
					for (Asset amount : units(drop.itemId(), drop.count()))
					{
						final Asset asset = attachCreated(connection, message, amount);
						final Long lifecycle = execution.lifecycle(asset);
						execution.legs().add(new Leg(Leg.AccountKind.SOURCE, charter, code, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.DEBIT));
						execution.legs().add(new Leg(Leg.AccountKind.MAIL, message, null, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.CREDIT));
						execution.existence(asset.itemId(), asset.qty());
						legs.add(legOf(asset, lifecycle));
					}
				}
				execution.mailed(message, entry.getKey(), MailReason.PAYOUT);
				final Map<String, Object> receiver = new LinkedHashMap<>();
				receiver.put("receiver", entry.getKey());
				receiver.put("mail", message);
				receivers.add(receiver);
			}
			for (RoutedDrop drop : auctioned)
			{
				for (Asset asset : execution.create(custody, new Asset(drop.itemId(), drop.count(), null)))
				{
					final Long lifecycle = execution.lifecycle(asset);
					execution.legs().add(new Leg(Leg.AccountKind.SOURCE, charter, code, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.DEBIT));
					execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, custody, null, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.CREDIT));
					execution.existence(asset.itemId(), asset.qty());
					legs.add(legOf(asset, lifecycle));
				}
			}
			execution.touched(custody);
			execution.facts().add(AssetFact.of("LootRouted", custody, "operation", _op.hex(), "charter", charter, "kill", kill, "npc", npcObjectId, "custody", custody, "legs", legs, "receivers", receivers));
		}, custody);
	}

	/**
	 * Relocates a kill's selected drops (§4.3 {@code relocate}, §2.5 {@code RELOCATED_DROP}; aden-remembers.md §4.3.1):
	 * each enters from the {@code RELOCATED_DROP} source, referenced by the kill ({@code Kill.ref}), into its own Guild
	 * warehouse custody, which it must be eligible for by its template (§4.4) and whose owner must hold it. All or nothing.
	 * The caller writes the kill's settlement key.
	 */
	public void relocate(long kill, List<Relocation> drops)
	{
		if (drops.isEmpty())
		{
			throw new IllegalArgumentException("a relocation relocates something");
		}
		final List<Relocation> relocated = List.copyOf(drops);
		final TreeSet<Integer> custodies = new TreeSet<>();
		for (Relocation drop : relocated)
		{
			custodies.add(drop.custody());
		}
		step(execution ->
		{
			for (int custody : custodies)
			{
				final Custody target = execution.open(custody);
				execution.notFrozen(target);
				execution.allowsHolding(target);
			}
			for (Relocation drop : relocated)
			{
				final String refusal = Eligibility.templateRefusal(ItemData.getInstance().getTemplate(drop.itemId()));
				if (refusal != null)
				{
					throw new AssetAbort(AssetAbort.Reason.INELIGIBLE, "item " + drop.itemId() + " may not enter custody " + drop.custody() + ": " + refusal);
				}
			}
			final String code = "RELOCATED_DROP";
			final Map<Integer, List<Map<String, Object>>> legs = new TreeMap<>();
			for (Relocation drop : relocated)
			{
				for (Asset asset : execution.create(drop.custody(), new Asset(drop.itemId(), drop.count(), null)))
				{
					final Long lifecycle = execution.lifecycle(asset);
					execution.legs().add(new Leg(Leg.AccountKind.SOURCE, kill, code, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.DEBIT));
					execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, drop.custody(), null, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.CREDIT));
					execution.existence(asset.itemId(), asset.qty());
					legs.computeIfAbsent(drop.custody(), custody -> new ArrayList<>()).add(legOf(asset, lifecycle));
				}
			}
			for (Map.Entry<Integer, List<Map<String, Object>>> entry : legs.entrySet())
			{
				execution.touched(entry.getKey());
				execution.facts().add(AssetFact.of("DropRelocated", entry.getKey(), "operation", _op.hex(), "kill", kill, "custody", entry.getKey(), "legs", entry.getValue()));
			}
		}, custodies.stream().mapToInt(Integer::intValue).toArray());
	}

	/** What a created quantity becomes as rows: one of its count if its item stacks, else rows of one. */
	private static List<Asset> units(int itemId, long count)
	{
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		if ((template == null) || template.isStackable())
		{
			return List.of(new Asset(itemId, count, null));
		}
		final List<Asset> units = new ArrayList<>();
		for (long i = 0; i < count; i++)
		{
			units.add(new Asset(itemId, 1, null));
		}
		return units;
	}

	/**
	 * A created item's shadow mana and time limit, as a new {@code Item} gets them from its template (Item.java:230-231):
	 * {mana_left, time}, -1 for none.
	 */
	static long[] lifetime(int itemId)
	{
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		if (template == null)
		{
			return new long[]
			{
				-1,
				-1
			};
		}
		return new long[]
		{
			template.getDuration(),
			template.getTime() == -1 ? -1 : System.currentTimeMillis() + (template.getTime() * 60 * 1000)
		};
	}

	/**
	 * Writes a service mail's attachment row for a created quantity (no object of it exists yet), with its lifetime.
	 * @return the created asset: for a non-stackable unit, the whole object (its id), so its lifecycle can begin
	 */
	private Asset attachCreated(Connection connection, int message, Asset asset) throws SQLException
	{
		final long[] lifetime = lifetime(asset.itemId());
		final int objectId = _service.ids().next();
		final ItemTemplate template = ItemData.getInstance().getTemplate(asset.itemId());
		try (PreparedStatement attach = connection.prepareStatement("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data, time_of_use, custom_type1, custom_type2, mana_left, time) VALUES (-1, ?, ?, ?, 0, 'MAIL', ?, NULL, 0, 0, ?, ?)"))
		{
			attach.setInt(1, objectId);
			attach.setInt(2, asset.itemId());
			attach.setLong(3, asset.qty());
			attach.setInt(4, message);
			attach.setLong(5, lifetime[0]);
			attach.setLong(6, lifetime[1]);
			attach.executeUpdate();
		}
		return ((template != null) && !template.isStackable()) ? new Asset(asset.itemId(), asset.qty(), objectId) : asset;
	}

	/**
	 * A contractual craft (§4.3 {@code craft}, §2.5 {@code CRAFT_INPUT}, {@code CRAFT_OUTPUT}; pact.md §4.3): the materials
	 * leave the contract's custody to the {@code CRAFT_INPUT} sink; the product, if the craft succeeded, enters from the
	 * {@code CRAFT_OUTPUT} source straight to the receiver by service mail, never into a custody. Both are referenced by
	 * the contract (the custody's owner). The owner writes its outcome row in the same operation ({@link #ownerRow}).
	 * @param attempt the contract's attempt this craft applies (pact.md §4.3), in {@code Asset.Crafted}
	 * @param product what the craft made, or null when it failed
	 */
	public void craft(int custody, int attempt, List<Asset> materials, int receiverCharId, ItemHolder product, String subject, String content)
	{
		if ((product != null) && ((subject == null) || subject.isBlank() || (content == null)))
		{
			throw new IllegalArgumentException("a service mail names its institution and says why");
		}
		final List<Asset> used = List.copyOf(materials);
		if (product != null)
		{
			_characters.add(receiverCharId);
		}
		step(execution ->
		{
			final Custody source = execution.open(custody);
			execution.notFrozen(source);
			execution.allowsHolding(source); // INV05, as every operation that touches a custody checks it
			final long contract = source.ownerRef();
			if ((product != null) && !execution.receiverAlive(receiverCharId))
			{
				throw new AssetAbort(AssetAbort.Reason.RECEIVER_GONE, "character " + receiverCharId + " is gone or marked for deletion");
			}
			final List<Map<String, Object>> inputs = new ArrayList<>();
			for (Asset asset : execution.wholes(custody, used))
			{
				execution.take(custody, asset, true);
				final Long lifecycle = execution.lifecycle(asset);
				execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, custody, null, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.DEBIT));
				execution.legs().add(new Leg(Leg.AccountKind.SINK, contract, SinkCode.CRAFT_INPUT.name(), asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.CREDIT));
				execution.existence(asset.itemId(), -asset.qty());
				inputs.add(legOf(asset, lifecycle));
			}
			final List<Map<String, Object>> outputs = new ArrayList<>();
			Integer mail = null;
			if (product != null)
			{
				final Connection connection = execution.connection();
				mail = insertServiceMail(connection, receiverCharId, subject, content);
				for (Asset amount : units(product.getId(), product.getCount()))
				{
					final Asset asset = attachCreated(connection, mail, amount);
					final Long lifecycle = execution.lifecycle(asset);
					execution.legs().add(new Leg(Leg.AccountKind.SOURCE, contract, "CRAFT_OUTPUT", asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.DEBIT));
					execution.legs().add(new Leg(Leg.AccountKind.MAIL, mail, null, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.CREDIT));
					execution.existence(asset.itemId(), asset.qty());
					outputs.add(legOf(asset, lifecycle));
				}
				execution.mailed(mail, receiverCharId, MailReason.PAYOUT);
			}
			execution.touched(custody);
			execution.facts().add(AssetFact.of("Crafted", custody, "operation", _op.hex(), "custody", custody, "contract", contract, "attempt", attempt, "materials", inputs, "product", outputs, "mail", mail));
		}, custody);
	}

	/**
	 * A row of the owner module's own state, written in the operation's transaction (§4.2 rule 10): a table of the
	 * operation's module ({@code lc_<module>_...}), never another module's or Mobius's.
	 */
	public void ownerRow(String table, Map<String, Object> columns)
	{
		final String prefix = "lc_" + _module.toLowerCase(java.util.Locale.ROOT) + "_";
		if (!IDENTIFIER.matcher(table).matches() || !table.startsWith(prefix))
		{
			throw new IllegalArgumentException("module " + _module + " writes only its own tables (" + prefix + "...), not " + table);
		}
		if (columns.isEmpty())
		{
			throw new IllegalArgumentException("a row has columns");
		}
		for (String column : columns.keySet())
		{
			if (!IDENTIFIER.matcher(column).matches())
			{
				throw new IllegalArgumentException("not a column: " + column);
			}
		}
		final List<Map.Entry<String, Object>> row = List.copyOf(new LinkedHashMap<>(columns).entrySet());
		step(execution ->
		{
			final StringBuilder names = new StringBuilder();
			final StringBuilder marks = new StringBuilder();
			for (Map.Entry<String, Object> column : row)
			{
				names.append(names.length() > 0 ? ", " : "").append(column.getKey());
				marks.append(marks.length() > 0 ? ", " : "").append('?');
			}
			try (PreparedStatement insert = execution.connection().prepareStatement("INSERT INTO " + table + " (" + names + ") VALUES (" + marks + ")"))
			{
				for (int i = 0; i < row.size(); i++)
				{
					insert.setObject(i + 1, row.get(i).getValue());
				}
				insert.executeUpdate();
			}
		});
	}

	/**
	 * Funds a custody from a live character's inventory or warehouse (§4.3 {@code fund}, §3.3 step 1): each whole object
	 * is taken in memory under Mobius's own locks, retired, and written as an {@code ESCROW} row from memory in the
	 * transaction. The character must be in the world; every item must be eligible (§4.4). (Amounts of a stack: part 4,
	 * task 5.)
	 */
	public void fund(int custody, Holder holder, List<Asset> assets)
	{
		if (holder.kind() == Holder.Kind.CASTLE_TREASURY)
		{
			throw new IllegalArgumentException("a treasury is funded through commitTreasury");
		}
		final Debit debit = new Debit(custody, holder, List.copyOf(assets), null);
		_debits.add(debit);
		if (holder.isCharacter())
		{
			_characters.add(holder.id());
			_marked.add(holder.id());
		}
		step(execution ->
		{
			final Custody target = execution.open(custody);
			execution.notFrozen(target);
			execution.allowsHolding(target);
			final List<Map<String, Object>> legs = new ArrayList<>();
			for (Taken taken : _taken)
			{
				if (taken.debit() != debit)
				{
					continue;
				}
				final Item item = taken.item();
				if (taken.whole())
				{
					ItemRows.write(execution.connection(), item, -1, "ESCROW", custody, taken.variables());
					final Long lifecycle = execution.lifecycleOf(item.getObjectId(), item.getId());
					execution.legs().add(new Leg(holder.account(), holder.id(), null, item.getId(), item.getObjectId(), lifecycle, item.getCount(), Leg.Direction.DEBIT));
					execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, custody, null, item.getId(), item.getObjectId(), lifecycle, item.getCount(), Leg.Direction.CREDIT));
					legs.add(legOf(new Asset(item.getId(), item.getCount(), item.getObjectId()), lifecycle));
				}
				else
				{
					ItemRows.writeStack(execution.connection(), item); // the stack as Mobius would write it now, debit applied
					execution.give(custody, custody, new Asset(item.getId(), taken.part(), null));
					execution.legs().add(new Leg(holder.account(), holder.id(), null, item.getId(), null, null, taken.part(), Leg.Direction.DEBIT));
					execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, custody, null, item.getId(), null, null, taken.part(), Leg.Direction.CREDIT));
					legs.add(legOf(new Asset(item.getId(), taken.part(), null)));
				}
			}
			execution.touched(custody);
			execution.facts().add(AssetFact.of("EscrowFunded", custody, "custody", custody, "holder", holder.kind().name(), "holder_id", holder.id(), "legs", legs));
		}, custody);
	}

	/**
	 * Charges a live character's Adena into a sink (§4.3 {@code charge}, §2.5): part of its Adena stack, taken as §3.3
	 * step 2 orders it, never its last unit.
	 */
	public void charge(Holder holder, long amount, SinkCode sink)
	{
		if (!holder.isCharacter())
		{
			throw new IllegalArgumentException("charge from " + holder.kind() + ": part 4, task 6");
		}
		final Debit debit = new Debit(-1, holder, List.of(new Asset(Inventory.ADENA_ID, amount, null)), sink);
		_debits.add(debit);
		_characters.add(holder.id());
		_marked.add(holder.id());
		step(execution ->
		{
			for (Taken taken : _taken)
			{
				if (taken.debit() != debit)
				{
					continue;
				}
				ItemRows.writeStack(execution.connection(), taken.item());
				execution.legs().add(new Leg(holder.account(), holder.id(), null, taken.item().getId(), null, null, taken.part(), Leg.Direction.DEBIT));
				execution.legs().add(new Leg(Leg.AccountKind.SINK, 0, sink.name(), taken.item().getId(), null, null, taken.part(), Leg.Direction.CREDIT));
				execution.existence(taken.item().getId(), -taken.part()); // the stack's decrease itself posted nothing (CP-0021)
			}
		});
	}

	/** A castle treasury debited (commit) or credited (return) under its write lock (§3.3 step 3). */
	private record TreasuryMove(int castleId, int custody, long amount, boolean debit)
	{
	}

	private final List<TreasuryMove> _treasury = new ArrayList<>();
	/** Treasury debits taken in memory, written back by a refund. */
	private final List<TreasuryMove> _treasuryTaken = new ArrayList<>();

	/** A castle's treasury into a custody (§4.3 {@code commit}, Crown Law): debited in memory, its row written from memory. */
	public void commitTreasury(int castleId, int custody, long amount)
	{
		final TreasuryMove move = new TreasuryMove(castleId, custody, amount, true);
		_treasury.add(move);
		step(execution ->
		{
			final Custody target = execution.open(custody);
			execution.notFrozen(target);
			execution.allowsHolding(target);
			writeTreasury(execution.connection(), castleId, castle(castleId).getTreasury());
			execution.give(custody, custody, new Asset(Inventory.ADENA_ID, amount, null));
			execution.legs().add(new Leg(Leg.AccountKind.CASTLE_TREASURY, castleId, null, Inventory.ADENA_ID, null, null, amount, Leg.Direction.DEBIT));
			execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, custody, null, Inventory.ADENA_ID, null, null, amount, Leg.Direction.CREDIT));
			execution.facts().add(AssetFact.of("TreasuryCommitted", custody, "castle", castleId, "custody", custody, "amount", amount));
		}, custody);
	}

	/** A custody's Adena back into a castle's treasury (§4.3 {@code return}): the row written as memory plus the amount, memory credited after commit. */
	public void returnToTreasury(int custody, int castleId, long amount)
	{
		final TreasuryMove move = new TreasuryMove(castleId, custody, amount, false);
		_treasury.add(move);
		step(execution ->
		{
			execution.notFrozen(execution.open(custody));
			final long after = castle(castleId).getTreasury() + amount;
			if (after > Inventory.MAX_ADENA)
			{
				throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, "castle " + castleId + "'s treasury would pass MAX_ADENA: refused, never clamped (§4.2 rule 4)");
			}
			execution.take(custody, new Asset(Inventory.ADENA_ID, amount, null), true);
			writeTreasury(execution.connection(), castleId, after);
			execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, custody, null, Inventory.ADENA_ID, null, null, amount, Leg.Direction.DEBIT));
			execution.legs().add(new Leg(Leg.AccountKind.CASTLE_TREASURY, castleId, null, Inventory.ADENA_ID, null, null, amount, Leg.Direction.CREDIT));
			execution.facts().add(AssetFact.of("TreasuryReturned", custody, "castle", castleId, "custody", custody, "amount", amount));
		}, custody);
	}

	private static Castle castle(int castleId) throws AssetAbort
	{
		final Castle castle = CastleManager.getInstance().getCastleById(castleId);
		if (castle == null)
		{
			throw new AssetAbort(AssetAbort.Reason.HOLDER_ABSENT, "castle " + castleId + " does not exist");
		}
		return castle;
	}

	private static void writeTreasury(Connection connection, int castleId, long treasury) throws SQLException
	{
		try (PreparedStatement write = connection.prepareStatement("UPDATE castle SET treasury = ? WHERE id = ?"))
		{
			write.setLong(1, treasury);
			write.setInt(2, castleId);
			write.executeUpdate();
		}
	}

	/** MARKED then TAKEN (§3.2, §3.3 step 1): marks committed, then each whole object taken out of memory. */
	private void take() throws AssetAbort
	{
		try (Connection connection = _service.db().open())
		{
			for (int charId : _marked)
			{
				PendingMarks.mark(connection, charId, _op);
			}
		}
		catch (SQLException e)
		{
			clearMarks();
			throw new AssetAbort(AssetAbort.Reason.ERROR, _op + ": could not mark", e);
		}
		try
		{
			for (Debit debit : _debits)
			{
				final Player player = player(debit);
				final ItemContainer container = container(debit, player);
				for (Asset asset : debit.assets())
				{
					if (asset.objectId() == null)
					{
						continue; // an amount: taken under the stack locks (§3.3 step 2)
					}
					final Item item = container.getItemByObjectId(asset.objectId());
					if (item == null)
					{
						throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, "object " + asset.objectId() + " is not in " + debit.holder());
					}
					// Mobius's order: the item's monitor, then the container's (§3.3, CP-0010 refusals run under them).
					synchronized (item)
					{
						synchronized (container)
						{
							if ((container.getItemByObjectId(asset.objectId()) != item) || item.isRetired() || (item.getId() != asset.itemId()) || (item.getCount() != asset.qty()))
							{
								throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, "object " + asset.objectId() + " is no longer as named in " + debit.holder());
							}
							final String refusal = refusal(player, item);
							if (refusal != null)
							{
								throw new AssetAbort(AssetAbort.Reason.INELIGIBLE, item + ": " + refusal);
							}
							item.retire();
							container.removeForAsset(item);
						}
					}
					final Map<String, String> variables = captureVariables(item);
					item.awaitWrites();
					_taken.add(new Taken(debit, item, variables, 0));
				}
			}
		}
		catch (AssetAbort e)
		{
			if (_taken.isEmpty())
			{
				clearMarks();
			}
			else
			{
				refund(e);
			}
			throw e;
		}
	}

	/** The character a debit takes from, in the world; null for a clan warehouse. */
	private static Player player(Debit debit) throws AssetAbort
	{
		if (!debit.holder().isCharacter())
		{
			return null;
		}
		final Player player = World.getPlayer(debit.holder().id());
		if (player == null)
		{
			throw new AssetAbort(AssetAbort.Reason.HOLDER_ABSENT, "character " + debit.holder().id() + " is not in the world");
		}
		return player;
	}

	private static ItemContainer container(Debit debit, Player player) throws AssetAbort
	{
		switch (debit.holder().kind())
		{
			case PLAYER:
			{
				return player.getInventory();
			}
			case PLAYER_WAREHOUSE:
			{
				return player.getWarehouse();
			}
			default:
			{
				final Clan clan = ClanTable.getInstance().getClan(debit.holder().id());
				if (clan == null)
				{
					throw new AssetAbort(AssetAbort.Reason.HOLDER_ABSENT, "clan " + debit.holder().id() + " does not exist");
				}
				return clan.getWarehouse(); // always in memory (Clan.java:145, 177)
			}
		}
	}

	private static String refusal(Player player, Item item)
	{
		return player != null ? Eligibility.refusal(player, item) : Eligibility.clanRefusal(item);
	}

	/** Who receives what is returned to a holder: the character, or a clan's leader (§2.1). */
	private static int receiverOf(Holder holder)
	{
		if (holder.kind() == Holder.Kind.CLAN_WAREHOUSE)
		{
			final Clan clan = ClanTable.getInstance().getClan(holder.id());
			return clan == null ? 0 : clan.getLeaderId();
		}
		return holder.id();
	}

	/** The stacks this operation takes amounts from, distinct, in object-id order (§3.3 step 2's lock order). */
	private List<Item> stacks() throws AssetAbort
	{
		final TreeMap<Integer, Item> stacks = new TreeMap<>();
		for (Debit debit : _debits)
		{
			for (Asset asset : debit.assets())
			{
				if (asset.objectId() != null)
				{
					continue;
				}
				final Item stack = container(debit, player(debit)).getItemByItemId(asset.itemId());
				if ((stack == null) || !stack.isStackable())
				{
					throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, debit.holder() + " has no stack of item " + asset.itemId());
				}
				stacks.put(stack.getObjectId(), stack);
			}
		}
		return new ArrayList<>(stacks.values());
	}

	/** Every stack monitor in object-id order, then every stack write lock in object-id order, held to DONE (§3.3). */
	private Outcome holding(List<Item> stacks, int index) throws AssetAbort
	{
		if (index < stacks.size())
		{
			synchronized (stacks.get(index))
			{
				return holding(stacks, index + 1);
			}
		}
		for (Item stack : stacks)
		{
			stack.writeLock().lock();
		}
		final List<Castle> castles = new ArrayList<>();
		try
		{
			final TreeMap<Integer, Castle> byId = new TreeMap<>();
			for (TreasuryMove move : _treasury)
			{
				byId.put(move.castleId(), castle(move.castleId()));
			}
			for (Castle castle : byId.values())
			{
				castle.treasuryLock().lock(); // held from the debit to DONE (§3.3 step 3)
				castles.add(castle);
			}
			return finish();
		}
		finally
		{
			for (int i = castles.size() - 1; i >= 0; i--)
			{
				castles.get(i).treasuryLock().unlock();
			}
			for (int i = stacks.size() - 1; i >= 0; i--)
			{
				stacks.get(i).writeLock().unlock();
			}
		}
	}

	/** Step 2 under the locks: each amount by an atomic checked decrease that leaves at least one unit (CP-0009). */
	private void takeParts() throws AssetAbort
	{
		for (Debit debit : _debits)
		{
			final Player player = player(debit);
			final ItemContainer container = container(debit, player);
			for (Asset asset : debit.assets())
			{
				if (asset.objectId() != null)
				{
					continue;
				}
				final Item stack = container.getItemByItemId(asset.itemId());
				if ((stack == null) || stack.isRetired() || (container.getItemByObjectId(stack.getObjectId()) != stack))
				{
					throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, debit.holder() + " no longer has its stack of item " + asset.itemId());
				}
				final String refusal = refusal(player, stack);
				if (refusal != null)
				{
					throw new AssetAbort(AssetAbort.Reason.INELIGIBLE, stack + ": " + refusal);
				}
				if (!stack.tryDecrease(asset.qty()))
				{
					throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, debit.holder() + " holds " + stack.getCount() + " of item " + asset.itemId() + "; a debit leaves at least one unit");
				}
				_taken.add(new Taken(debit, stack, Map.of(), asset.qty()));
			}
		}
	}

	private void abortAfter(AssetAbort cause)
	{
		if (_taken.isEmpty() && _treasuryTaken.isEmpty())
		{
			clearMarks();
		}
		else
		{
			refund(cause);
		}
	}

	/** An item's variables, retired (no more writes or saves, CP-0013) and copied as the row will hold them. */
	private static Map<String, String> captureVariables(Item item)
	{
		final ItemVariables variables = item.getScript(ItemVariables.class);
		if (variables == null)
		{
			return Map.of();
		}
		variables.retire();
		final Map<String, String> copy = new TreeMap<>();
		variables.getSet().forEach((name, value) -> copy.put(name, String.valueOf(value)));
		return copy;
	}

	private void clearMarks()
	{
		try (Connection connection = _service.db().open())
		{
			for (int charId : _marked)
			{
				PendingMarks.clear(connection, charId, _op);
			}
		}
		catch (SQLException e)
		{
			// Startup recovery removes the marks of an operation that never committed (§3.2).
		}
	}

	private static final int REFUND_ATTEMPTS = 10;

	/**
	 * ABORTED → REFUNDED (§3.2): the refund operation {@code OpId.refundOf(op)} returns each object taken to its holder as
	 * service mail (written from memory, unsaved state kept), clears this operation's pending marks, and is retried until
	 * it commits; nothing it does to memory happens before its commit. If it cannot commit, the module freezes and the
	 * retired objects stay out of every container; their rows were never changed, so a restart's recovery gives them back.
	 */
	private void refund(AssetAbort cause)
	{
		final List<Taken> taken = List.copyOf(_taken);
		final List<TreasuryMove> treasury = List.copyOf(_treasuryTaken);
		final TreeSet<Integer> marked = new TreeSet<>(_marked);
		Exception last = cause;
		for (int attempt = 0; attempt < REFUND_ATTEMPTS; attempt++)
		{
			try (AssetTx refund = _service.begin(_op.refund(), _module, "REFUND", _subjectRef))
			{
				refund.returnByMail(taken, marked, _op);
				refund.restoreTreasuries(treasury);
				refund.commit();
				rowsWritten(taken);
				for (TreasuryMove move : treasury)
				{
					castle(move.castleId()).creditTreasuryInMemory(move.amount()); // memory only after the refund committed
				}
				discardTaken();
				_taken.clear();
				_treasuryTaken.clear();
				return;
			}
			catch (AssetAbort | SQLException e)
			{
				e.addSuppressed(last);
				last = e;
				pause();
			}
		}
		try (Connection connection = _service.db().open())
		{
			ModuleState.freeze(connection, _module, "REFUND_FAILED", null);
		}
		catch (SQLException e)
		{
			last.addSuppressed(e);
		}
		throw new IllegalStateException(_op + " could not be refunded; module " + _module + " frozen", last);
	}

	/** The refund's treasury rows: written back as memory plus each debited amount (§3.2), memory credited after commit. */
	private void restoreTreasuries(List<TreasuryMove> treasury)
	{
		if (treasury.isEmpty())
		{
			return;
		}
		step(execution ->
		{
			for (TreasuryMove move : treasury)
			{
				writeTreasury(execution.connection(), move.castleId(), castle(move.castleId()).getTreasury() + move.amount());
			}
		});
	}

	/** The refund operation's one step: each holder's objects back as one service mail, the original marks cleared. */
	private void returnByMail(List<Taken> taken, Set<Integer> marked, OpId original)
	{
		final Map<Holder, List<Taken>> byHolder = new LinkedHashMap<>();
		for (Taken t : taken)
		{
			byHolder.computeIfAbsent(t.debit().holder(), h -> new ArrayList<>()).add(t);
			_characters.add(receiverOf(t.debit().holder()));
		}
		step(execution ->
		{
			final Connection connection = execution.connection();
			for (Map.Entry<Holder, List<Taken>> entry : byHolder.entrySet())
			{
				final Holder holder = entry.getKey();
				final int receiver = receiverOf(holder);
				final int message = insertServiceMail(connection, receiver, "Returned", "An operation on your items did not complete; they are returned to you.");
				final List<Map<String, Object>> legs = new ArrayList<>();
				for (Taken t : entry.getValue())
				{
					final Item item = t.item();
					if (t.whole())
					{
						ItemRows.write(connection, item, ServiceMail.SENDER, "MAIL", message, t.variables());
						final Long lifecycle = execution.lifecycleOf(item.getObjectId(), item.getId());
						execution.legs().add(new Leg(holder.account(), holder.id(), null, item.getId(), item.getObjectId(), lifecycle, item.getCount(), Leg.Direction.DEBIT));
						execution.legs().add(new Leg(Leg.AccountKind.MAIL, message, null, item.getId(), item.getObjectId(), lifecycle, item.getCount(), Leg.Direction.CREDIT));
						legs.add(legOf(new Asset(item.getId(), item.getCount(), item.getObjectId()), lifecycle));
					}
					else
					{
						// A part: the stack's row from memory (debit applied), the part as a new attachment row.
						ItemRows.writeStack(connection, item);
						try (PreparedStatement attach = connection.prepareStatement("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data, time_of_use, custom_type1, custom_type2, mana_left, time) VALUES (-1, ?, ?, ?, 0, 'MAIL', ?, NULL, 0, 0, -1, 0)"))
						{
							attach.setInt(1, _service.ids().next());
							attach.setInt(2, item.getId());
							attach.setLong(3, t.part());
							attach.setInt(4, message);
							attach.executeUpdate();
						}
						execution.legs().add(new Leg(holder.account(), holder.id(), null, item.getId(), null, null, t.part(), Leg.Direction.DEBIT));
						execution.legs().add(new Leg(Leg.AccountKind.MAIL, message, null, item.getId(), null, null, t.part(), Leg.Direction.CREDIT));
						legs.add(legOf(new Asset(item.getId(), t.part(), null)));
					}
				}
				execution.mailed(message, receiver, MailReason.REFUND);
				execution.facts().add(AssetFact.of("Refunded", entry.getValue().get(0).debit().custody(), "aborted_op", original.hex(), "receiver", receiver, "mail", message, "legs", legs));
			}
			for (int charId : marked)
			{
				PendingMarks.clear(connection, charId, original);
			}
		});
	}

	/** DONE (§3.2): each retired item leaves memory for good; its object id stays reserved by its row (§4.2 rule 8). */
	private void discardTaken()
	{
		for (Taken taken : _taken)
		{
			if (taken.whole())
			{
				taken.item().stopAllTasks();
				World.removeObject(taken.item());
			}
		}
	}

	/**
	 * One exchange with a live character (§4.3 {@code exchange}; the Guild's buying and selling): the character's
	 * {@code gives} enter custody {@code into} and custody {@code from}'s {@code pays} reach the character as service mail,
	 * in one transaction, so either both happen or neither does (what was already taken comes back by refund).
	 */
	public void exchange(int charId, List<Asset> gives, int into, List<Asset> pays, int from, String subject, String content)
	{
		fund(into, Holder.player(charId), gives);
		release(from, charId, pays, MailReason.PAYOUT, subject, content);
		final List<Map<String, Object>> given = new ArrayList<>();
		gives.forEach(asset -> given.add(legOf(asset)));
		final List<Map<String, Object>> paid = new ArrayList<>();
		pays.forEach(asset -> paid.add(legOf(asset)));
		step(execution -> execution.facts().add(AssetFact.of("Exchanged", into, "operation", _op.hex(), "character", charId, "custody_in", into, "gives", given, "custody_out", from, "pays", paid)), into, from);
	}

	/** A service mail's message row (§2.1), in the operation's transaction; its attachment rows follow. */
	private int insertServiceMail(Connection connection, int receiverCharId, String subject, String content) throws SQLException
	{
		final int message = _service.ids().next();
		try (PreparedStatement insert = connection.prepareStatement("INSERT INTO messages (messageId, senderId, receiverId, subject, content, expiration, reqAdena, hasAttachments, isUnread, isDeletedBySender, isDeletedByReceiver, sendBySystem, isReturned) VALUES (?, ?, ?, ?, ?, ?, 0, 'true', 'true', 'true', 'false', ?, 'false')"))
		{
			insert.setInt(1, message);
			insert.setInt(2, ServiceMail.SENDER);
			insert.setInt(3, receiverCharId);
			insert.setString(4, subject);
			insert.setString(5, content);
			insert.setLong(6, ServiceMail.EXPIRATION);
			insert.setInt(7, ServiceMail.SEND_BY_SYSTEM);
			insert.executeUpdate();
		}
		return message;
	}

	/** Debits a custody into a sink (§2.5): the asset leaves existence under the code and its reference. */
	public void sink(int custody, List<Asset> assets, SinkCode code, long reference)
	{
		final List<Asset> sunk = List.copyOf(assets);
		step(execution ->
		{
			execution.notFrozen(execution.open(custody));
			final List<Map<String, Object>> legs = new ArrayList<>();
			for (Asset asset : execution.wholes(custody, sunk))
			{
				execution.take(custody, asset, true);
				final Long lifecycle = execution.lifecycle(asset);
				execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, custody, null, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.DEBIT));
				execution.legs().add(new Leg(Leg.AccountKind.SINK, reference, code.name(), asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.CREDIT));
				execution.existence(asset.itemId(), -asset.qty());
				legs.add(legOf(asset, lifecycle));
			}
			execution.facts().add(AssetFact.of("FeeCharged", custody, "holder", "CUSTODY", "custody", custody, "code", code.name(), "reference", reference, "legs", legs));
		}, custody);
	}

	/**
	 * A corrective admin operation (§4.5): allowed on a {@code FROZEN} custody and a frozen module, never on a
	 * {@code CLOSED} one. {@code in} (amounts only) enters from SOURCE, {@code out} leaves to SINK, both under the code
	 * {@code ADMIN_CORRECTION} referencing the violation fact; {@code reconcile} decides what to do about rows that disagree
	 * with postings. Writes {@code Asset.AdminCorrection} with the custody's holdings before and after.
	 */
	public void correct(int custody, List<Asset> in, List<Asset> out, Reconcile reconcile, long violationFactId, String reason, int adminCharId)
	{
		if ((reason == null) || reason.isBlank())
		{
			throw new IllegalArgumentException("a correction needs a reason");
		}
		if (violationFactId <= 0)
		{
			throw new IllegalArgumentException("a correction names the violation it repairs");
		}
		for (Asset asset : in)
		{
			if (asset.objectId() != null)
			{
				throw new IllegalArgumentException("a correction creates amounts, not objects");
			}
		}
		final List<Asset> added = List.copyOf(in);
		final List<Asset> removed = List.copyOf(out);
		final String code = "ADMIN_CORRECTION";
		step(execution ->
		{
			final Custody locked = execution.custody(custody);
			// Rows under a CLOSED custody (INV04) or under no custody (INV03) may only be taken out; the custody stays as it is.
			if (((locked == null) || (locked.state() == Custody.State.CLOSED)) && !added.isEmpty())
			{
				throw new AssetAbort(AssetAbort.Reason.CUSTODY_NOT_OPEN, "custody " + custody + " is " + (locked == null ? "unknown" : "CLOSED") + ": nothing may be put into it");
			}
			final Connection connection = execution.connection();
			final Map<Integer, Long> before = CustodyInvariants.held(connection, custody);
			if (reconcile == Reconcile.RECOGNIZE_ROWS)
			{
				final Map<Integer, Long> posted = CustodyInvariants.posted(connection, custody);
				final TreeSet<Integer> items = new TreeSet<>(before.keySet());
				items.addAll(posted.keySet());
				for (int item : items)
				{
					final long difference = before.getOrDefault(item, 0L) - posted.getOrDefault(item, 0L);
					if (difference > 0)
					{
						execution.legs().add(new Leg(Leg.AccountKind.SOURCE, violationFactId, code, item, null, null, difference, Leg.Direction.DEBIT));
						execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, custody, null, item, null, null, difference, Leg.Direction.CREDIT));
					}
					else if (difference < 0)
					{
						execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, custody, null, item, null, null, -difference, Leg.Direction.DEBIT));
						execution.legs().add(new Leg(Leg.AccountKind.SINK, violationFactId, code, item, null, null, -difference, Leg.Direction.CREDIT));
					}
				}
			}
			for (Asset amount : added)
			{
				for (Asset asset : execution.create(custody, amount))
				{
					final Long lifecycle = execution.lifecycle(asset);
					execution.legs().add(new Leg(Leg.AccountKind.SOURCE, violationFactId, code, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.DEBIT));
					execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, custody, null, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.CREDIT));
					execution.existence(asset.itemId(), asset.qty());
				}
			}
			for (Asset asset : execution.wholes(custody, removed))
			{
				execution.take(custody, asset, true);
				final Long lifecycle = execution.lifecycle(asset);
				execution.legs().add(new Leg(Leg.AccountKind.CUSTODY, custody, null, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.DEBIT));
				execution.legs().add(new Leg(Leg.AccountKind.SINK, violationFactId, code, asset.itemId(), asset.objectId(), lifecycle, asset.qty(), Leg.Direction.CREDIT));
				execution.existence(asset.itemId(), -asset.qty());
			}
			execution.touched(custody);
			execution.facts().add(AssetFact.of("AdminCorrection", custody, "admin", adminCharId, "violation", violationFactId, "reason", reason, "custody", custody, "reconcile", reconcile.name(), "before", byItem(before), "after", byItem(CustodyInvariants.held(connection, custody))));
		}, custody);
	}

	private static Map<String, Object> byItem(Map<Integer, Long> holdings)
	{
		final Map<String, Object> out = new LinkedHashMap<>();
		holdings.forEach((item, qty) -> out.put(String.valueOf(item), qty));
		return out;
	}

	static Map<String, Object> legOf(Asset asset)
	{
		final Map<String, Object> leg = new LinkedHashMap<>();
		leg.put("item", asset.itemId());
		leg.put("object", asset.objectId());
		leg.put("qty", asset.qty());
		return leg;
	}

	/** A fact's leg naming the object's lifecycle (§5: item id, object id, lifecycle id, qty). */
	static Map<String, Object> legOf(Asset asset, Long lifecycle)
	{
		final Map<String, Object> leg = legOf(asset);
		leg.put("lifecycle", lifecycle);
		return leg;
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
		if (_debits.isEmpty() && _treasury.isEmpty())
		{
			final Outcome outcome = attempts();
			if (outcome == Outcome.COMMITTED)
			{
				postExistence();
			}
			return outcome;
		}
		take();
		final List<Item> stacks;
		try
		{
			stacks = stacks();
		}
		catch (AssetAbort e)
		{
			abortAfter(e);
			throw e;
		}
		return holding(stacks, 0);
	}

	/** Under the stack locks: the amounts taken, the transaction, then DONE or the refund, before any lock is released. */
	private Outcome finish() throws AssetAbort
	{
		try
		{
			takeParts();
			for (TreasuryMove move : _treasury)
			{
				if (move.debit())
				{
					if (!castle(move.castleId()).tryDebitTreasury(move.amount()))
					{
						throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, "castle " + move.castleId() + "'s treasury holds less than " + move.amount());
					}
					_treasuryTaken.add(move);
				}
			}
		}
		catch (AssetAbort e)
		{
			abortAfter(e);
			throw e;
		}
		final Outcome outcome;
		try
		{
			outcome = attempts();
		}
		catch (AssetAbort e)
		{
			abortAfter(e);
			throw e;
		}
		if (outcome != Outcome.COMMITTED)
		{
			abortAfter(new AssetAbort(AssetAbort.Reason.NOT_COMMITTED, _op + " ended " + outcome + " after taking assets"));
			return outcome;
		}
		rowsWritten(_taken);
		for (TreasuryMove move : _treasury)
		{
			if (!move.debit())
			{
				castle(move.castleId()).creditTreasuryInMemory(move.amount()); // after commit, still under the treasury lock
			}
		}
		discardTaken();
		postExistence();
		tellClients();
		return outcome;
	}

	/** Posts what the committed operation's legs created or destroyed to the existence counters, once (§2.6). */
	private void postExistence()
	{
		_existence.forEach(EconomyCounter::post);
	}

	/** A stack whose row the transaction wrote now exists for Mobius too (CP-0019), under the write lock still held. */
	private static void rowsWritten(List<Taken> taken)
	{
		for (Taken t : taken)
		{
			if (!t.whole())
			{
				t.item().markRowWritten();
			}
		}
	}

	/** DONE: the characters debited see their inventories as they are now. */
	private void tellClients()
	{
		for (int charId : _marked)
		{
			final Player player = World.getPlayer(charId);
			if (player != null)
			{
				player.sendItemList(false);
			}
		}
	}

	private Outcome attempts() throws AssetAbort
	{
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
			Long previousWait = null;
			try
			{
				previousWait = sessionLockWait(connection);
				connection.setAutoCommit(false);
				lockWait(connection, config.lockWaitMillis());
				execute(connection);
			}
			catch (Prior e)
			{
				rollback(connection, previousWait);
				return e._outcome;
			}
			catch (RuntimeException | Error e)
			{
				rollback(connection, previousWait);
				throw e;
			}
			catch (AssetAbort e)
			{
				rollback(connection, previousWait);
				if (e.violation() != null)
				{
					recordViolation(e.violation(), e);
				}
				throw e;
			}
			catch (SQLException e)
			{
				rollback(connection, previousWait);
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
			restoreLockWait(connection, previousWait);
			close(connection);
			adoptMails();
			return Outcome.COMMITTED;
		}
	}

	private void execute(Connection connection) throws SQLException, AssetAbort, Prior
	{
		final Execution execution = new Execution(connection, _service, _op);
		execution.lockCharacters(_characters);
		execution.lockCustodies(_custodies);
		_owners.clear();
		for (int custody : _custodies)
		{
			if (execution.custody(custody) != null)
			{
				_owners.add(execution.custody(custody).ownerModule());
			}
		}
		for (Step step : _steps)
		{
			step.run(execution);
		}
		for (int charId : _marked)
		{
			PendingMarks.clear(connection, charId, _op); // the marks go with the operation's commit (§2.4)
		}
		final List<Version> versions = new ArrayList<>(_versions);
		versions.sort(Comparator.comparing(Version::table).thenComparingLong(Version::id));
		for (Version version : versions)
		{
			checkVersion(connection, version);
		}
		// A refund that committed after begin() makes this attempt final (§3.2); read under a lock so a racing one is seen.
		try (PreparedStatement refund = connection.prepareStatement("SELECT op_id FROM lc_asset_op WHERE op_id = ? FOR UPDATE"))
		{
			refund.setBytes(1, _op.refund().bytes());
			try (ResultSet result = refund.executeQuery())
			{
				if (result.next())
				{
					throw new Prior(Outcome.ALREADY_REFUNDED);
				}
			}
		}
		try (PreparedStatement insert = connection.prepareStatement("INSERT INTO lc_asset_op (op_id, module, action, subject_ref, committed_at) VALUES (?, ?, ?, ?, NOW(3))"))
		{
			insert.setBytes(1, _op.bytes());
			insert.setString(2, _module);
			insert.setString(3, _action);
			insert.setLong(4, _subjectRef);
			insert.executeUpdate();
		}
		catch (SQLException e)
		{
			if (e.getErrorCode() == DUPLICATE_KEY)
			{
				throw new Prior(Outcome.ALREADY_COMMITTED); // another attempt of the same id committed meanwhile
			}
			throw e;
		}
		// Deliveries name the operation (a foreign key to lc_asset_op), so they follow its row; they are new rows nobody waits on.
		for (Execution.Delivery delivery : execution.deliveries())
		{
			try (PreparedStatement insert = connection.prepareStatement("INSERT INTO lc_mail_delivery (message_id, op_id, receiver_char, reason) VALUES (?, ?, ?, ?)"))
			{
				insert.setInt(1, delivery.message());
				insert.setBytes(2, _op.bytes());
				insert.setInt(3, delivery.receiver());
				insert.setString(4, delivery.reason().name());
				insert.executeUpdate();
			}
		}
		final List<Key> keys = new ArrayList<>(_keys);
		keys.sort(Comparator.comparing(Key::scope).thenComparing(Key::key));
		for (Key key : keys)
		{
			settle(connection, key);
		}
		postLegs(connection, execution.legs());
		execution.checkCustodies();
		execution.checkMails();
		_mails = List.copyOf(execution.mails());
		_existence = Map.copyOf(execution.existence());
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
				final Long previousWait = sessionLockWait(connection);
				connection.setAutoCommit(false);
				lockWait(connection, Math.max(1, (deadline - System.nanoTime()) / 1_000_000L));
				try (PreparedStatement read = connection.prepareStatement("SELECT op_id FROM lc_asset_op WHERE op_id = ? FOR UPDATE"))
				{
					read.setBytes(1, _op.bytes());
					try (ResultSet result = read.executeQuery())
					{
						final boolean committed = result.next();
						connection.rollback();
						restoreLockWait(connection, previousWait);
						if (committed)
						{
							adoptMails();
							return Outcome.COMMITTED;
						}
						throw new AssetAbort(AssetAbort.Reason.NOT_COMMITTED, _op + ": the connection failed before the commit took effect", lost);
					}
				}
			}
			catch (SQLException e)
			{
				last = e;
				pause();
			}
		}
		while (System.nanoTime() < deadline);
		try (Connection connection = _service.db().open())
		{
			// The operation's module and every owner of a custody it touched: owners' modules are what operations check.
			final TreeSet<String> modules = new TreeSet<>(_owners);
			modules.add(_module);
			for (String module : modules)
			{
				ModuleState.freeze(connection, module, "UNCERTAIN_COMMIT", null);
			}
		}
		catch (SQLException e)
		{
			last.addSuppressed(e);
		}
		throw new UncertainCommitException(_op + ": the commit's result could not be learned; modules frozen: " + _module + " " + _owners, last);
	}

	/** DONE (§3.2): the game's mail adopts each message this operation committed. */
	private void adoptMails()
	{
		for (int message : _mails)
		{
			_service.adopt(message);
		}
		for (Runnable action : _afterCommit)
		{
			action.run();
		}
		_afterCommit.clear();
	}

	private void recordViolation(AssetAbort.Violation violation, AssetAbort abort)
	{
		try (Connection connection = _service.db().open())
		{
			Violations.record(connection, _service.stager(), violation.custody(), violation.invariant(), violation.evidence());
		}
		catch (SQLException e)
		{
			abort.addSuppressed(e); // the operation still did not commit; the census will find the violation again
		}
	}

	private static Long sessionLockWait(Connection connection) throws SQLException
	{
		try (Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery("SELECT @@session.innodb_lock_wait_timeout"))
		{
			result.next();
			return result.getLong(1);
		}
	}

	/** A pooled connection goes back with the lock wait it came with, so Mobius's own statements keep the server's. */
	private static void restoreLockWait(Connection connection, Long previous)
	{
		if (previous == null)
		{
			return;
		}
		try (Statement statement = connection.createStatement())
		{
			statement.execute("SET SESSION innodb_lock_wait_timeout = " + previous);
		}
		catch (SQLException e)
		{
			// The connection is unusable and will be discarded.
		}
	}

	private static void pause()
	{
		try
		{
			Thread.sleep(50);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
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

	private static void rollback(Connection connection, Long previousWait)
	{
		try
		{
			connection.rollback();
		}
		catch (SQLException e)
		{
			// The connection is unusable: InnoDB rolls the transaction back when the session ends.
		}
		restoreLockWait(connection, previousWait);
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
		private final java.util.Set<Integer> _alive = new java.util.HashSet<>();
		private final List<Integer> _mails = new ArrayList<>();
		private final List<Delivery> _deliveries = new ArrayList<>();
		private final List<Leg> _legs = new ArrayList<>();
		/** Object id to lifecycle id, as this operation found or began it (§2.2, §11.35). */
		private final Map<Integer, Long> _lifecycles = new HashMap<>();
		private final Map<Integer, Long> _existence = new java.util.TreeMap<>();
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

		/**
		 * Records that a leg created ({@code delta} positive) or destroyed rows of {@code itemId}: a SOURCE or SINK leg that
		 * changes holdings. A reconciliation that recognises rows as they are changes none and records nothing.
		 */
		void existence(int itemId, long delta)
		{
			_existence.merge(itemId, delta, Long::sum);
		}

		Map<Integer, Long> existence()
		{
			return _existence;
		}

		List<DurableFact> facts()
		{
			return _facts;
		}

		/** Locks the characters' rows first, in id order (§2.1: service mail and deletion serialise on them). */
		void lockCharacters(TreeSet<Integer> ids) throws SQLException
		{
			for (int id : ids)
			{
				try (PreparedStatement read = _connection.prepareStatement("SELECT deletetime FROM characters WHERE charId = ? FOR UPDATE"))
				{
					read.setInt(1, id);
					try (ResultSet result = read.executeQuery())
					{
						if (result.next() && (result.getLong(1) == 0))
						{
							_alive.add(id);
						}
					}
				}
			}
		}

		/** Whether the locked character exists and is not marked for deletion. */
		boolean receiverAlive(int charId)
		{
			return _alive.contains(charId);
		}

		record Delivery(int message, int receiver, MailReason reason)
		{
		}

		void mailed(int message, int receiver, MailReason reason)
		{
			_mails.add(message);
			_deliveries.add(new Delivery(message, receiver, reason));
		}

		List<Delivery> deliveries()
		{
			return _deliveries;
		}

		List<Integer> mails()
		{
			return _mails;
		}

		/**
		 * INV12 for this operation's mails: each message is a service mail, and its attachment rows equal its {@code MAIL}
		 * credit legs per item id.
		 */
		void checkMails() throws SQLException, AssetAbort
		{
			for (int message : _mails)
			{
				final Map<Integer, Long> legs = new TreeMap<>();
				for (Leg leg : _legs)
				{
					if ((leg.kind() == Leg.AccountKind.MAIL) && (leg.accountId() == message))
					{
						legs.merge(leg.itemId(), leg.direction() == Leg.Direction.CREDIT ? leg.qty() : -leg.qty(), Long::sum);
					}
				}
				final Map<Integer, Long> rows = new TreeMap<>();
				try (PreparedStatement read = _connection.prepareStatement("SELECT i.item_id, SUM(i.count) FROM items i JOIN messages m ON m.messageId = i.loc_data WHERE i.owner_id = -1 AND i.loc = 'MAIL' AND i.loc_data = ? AND m.senderId = -1 AND m.sendBySystem <> 0 AND m.reqAdena = 0 AND m.hasAttachments = 'true' GROUP BY i.item_id"))
				{
					read.setInt(1, message);
					try (ResultSet result = read.executeQuery())
					{
						while (result.next())
						{
							rows.put(result.getInt(1), result.getLong(2));
						}
					}
				}
				if (!legs.equals(rows))
				{
					throw new AssetAbort(AssetAbort.Reason.UNBALANCED, "INV12: message " + message + " has attachments " + rows + " for legs " + legs);
				}
			}
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

		/** The custody, locked and {@code OPEN}; otherwise the operation aborts. */
		Custody open(int id) throws AssetAbort
		{
			final Custody custody = _locked.get(id);
			if ((custody == null) || (custody.state() != Custody.State.OPEN))
			{
				throw new AssetAbort(AssetAbort.Reason.CUSTODY_NOT_OPEN, "custody " + id + " is " + (custody == null ? "unknown" : custody.state()));
			}
			return custody;
		}

		void notFrozen(Custody custody) throws SQLException, AssetAbort
		{
			if (ModuleState.isFrozen(_connection, custody.ownerModule()))
			{
				throw new AssetAbort(AssetAbort.Reason.MODULE_FROZEN, custody.ownerModule() + " owns custody " + custody.id() + " and is frozen");
			}
		}

		void allowsHolding(Custody custody) throws SQLException, AssetAbort
		{
			final HoldingPolicy owner = _service.owner(custody.ownerModule());
			if ((owner == null) || !owner.allowsHolding(_connection, custody.ownerRef()))
			{
				throw new AssetAbort(AssetAbort.Reason.NOT_ALLOWED_TO_HOLD, custody.ownerModule() + " " + custody.ownerRef() + " may not hold in custody " + custody.id());
			}
		}

		/**
		 * Takes an asset out of a custody's rows. A whole object is checked and locked; when it leaves existence
		 * ({@code removing}: a sink, a correction out) its row and metadata rows are deleted, otherwise ({@code move}) the
		 * row stays for {@link #give} to move. An amount is cut from the custody's rows of the item, in object-id order.
		 */
		void take(int custody, Asset asset, boolean removing) throws SQLException, AssetAbort
		{
			take(custody, asset, removing, true);
		}

		/** As {@link #take(int, Asset, boolean)}; {@code ending} false keeps a removed object's lifecycle live (a raided share, §4.3). */
		void take(int custody, Asset asset, boolean removing, boolean ending) throws SQLException, AssetAbort
		{
			_touched.add(custody);
			if (asset.objectId() != null)
			{
				final long[] row = lockRow(asset.objectId(), custody, asset.itemId());
				if ((row == null) || (row[1] != asset.qty()))
				{
					throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, "object " + asset.objectId() + " is not whole in custody " + custody);
				}
				if (removing)
				{
					if (ending)
					{
						endLifecycle(asset.objectId(), asset.itemId()); // a sink takes its row: the object leaves existence (§11.35)
					}
					deleteObject(asset.objectId());
				}
				return;
			}
			final List<long[]> rows = stacks(custody, asset.itemId());
			long available = 0;
			for (long[] row : rows)
			{
				available += row[1];
			}
			if (available < asset.qty())
			{
				throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, "custody " + custody + " holds " + available + " of item " + asset.itemId() + ", not " + asset.qty());
			}
			long remaining = asset.qty();
			for (long[] row : rows)
			{
				if (remaining == 0)
				{
					break;
				}
				final long cut = Math.min(remaining, row[1]);
				if (cut == row[1])
				{
					deleteObject(row[0]);
				}
				else
				{
					try (PreparedStatement write = _connection.prepareStatement("UPDATE items SET count = count - ? WHERE object_id = ?"))
					{
						write.setLong(1, cut);
						write.setLong(2, row[0]);
						expectOne(write.executeUpdate(), row[0]);
					}
				}
				remaining -= cut;
			}
		}

		/** Puts an asset into a custody's rows: a whole object changes custody from {@code source}, an amount joins the custody's first row of the item. */
		void give(int custody, int source, Asset asset) throws SQLException, AssetAbort
		{
			_touched.add(custody);
			if (asset.objectId() != null)
			{
				try (PreparedStatement move = _connection.prepareStatement("UPDATE items SET loc_data = ? WHERE object_id = ? AND owner_id = -1 AND loc = 'ESCROW' AND loc_data = ?"))
				{
					move.setInt(1, custody);
					move.setInt(2, asset.objectId());
					move.setInt(3, source);
					expectOne(move.executeUpdate(), asset.objectId());
				}
				return;
			}
			final ItemTemplate template = ItemData.getInstance().getTemplate(asset.itemId());
			if ((template != null) && !template.isStackable())
			{
				// an amount of a non-stackable item is that many objects, never one row counting several
				for (long i = 0; i < asset.qty(); i++)
				{
					final long[] lifetime = lifetime(asset.itemId());
					try (PreparedStatement insert = _connection.prepareStatement("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data, time_of_use, custom_type1, custom_type2, mana_left, time) VALUES (-1, ?, ?, 1, 0, 'ESCROW', ?, NULL, 0, 0, ?, ?)"))
					{
						insert.setInt(1, _service.ids().next());
						insert.setInt(2, asset.itemId());
						insert.setInt(3, custody);
						insert.setLong(4, lifetime[0]);
						insert.setLong(5, lifetime[1]);
						insert.executeUpdate();
					}
				}
				return;
			}
			final List<long[]> rows = stacks(custody, asset.itemId());
			if (!rows.isEmpty())
			{
				try (PreparedStatement add = _connection.prepareStatement("UPDATE items SET count = count + ? WHERE object_id = ?"))
				{
					add.setLong(1, asset.qty());
					add.setLong(2, rows.get(0)[0]);
					expectOne(add.executeUpdate(), rows.get(0)[0]);
				}
				return;
			}
			final long[] lifetime = lifetime(asset.itemId());
			try (PreparedStatement insert = _connection.prepareStatement("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data, time_of_use, custom_type1, custom_type2, mana_left, time) VALUES (-1, ?, ?, ?, 0, 'ESCROW', ?, NULL, 0, 0, ?, ?)"))
			{
				insert.setInt(1, _service.ids().next());
				insert.setInt(2, asset.itemId());
				insert.setLong(3, asset.qty());
				insert.setInt(4, custody);
				insert.setLong(5, lifetime[0]);
				insert.setLong(6, lifetime[1]);
				insert.executeUpdate();
			}
		}

		/**
		 * The custody's rows of an item, each locked by its primary key in object-id order: candidates are read without a
		 * lock (the custody row's lock already keeps other operations off this custody), then locked and re-checked, so no
		 * range of a secondary index is locked. {object id, count} per row.
		 */
		private List<long[]> stacks(int custody, int itemId) throws SQLException
		{
			final List<Long> candidates = new ArrayList<>();
			try (PreparedStatement read = _connection.prepareStatement("SELECT object_id FROM items WHERE owner_id = -1 AND loc = 'ESCROW' AND loc_data = ? AND item_id = ? ORDER BY object_id"))
			{
				read.setInt(1, custody);
				read.setInt(2, itemId);
				try (ResultSet result = read.executeQuery())
				{
					while (result.next())
					{
						candidates.add(result.getLong(1));
					}
				}
			}
			final List<long[]> rows = new ArrayList<>();
			for (long objectId : candidates)
			{
				final long[] row = lockRow(objectId, custody, itemId);
				if (row != null)
				{
					rows.add(row);
				}
			}
			return rows;
		}

		/** Locks one row by primary key and returns {object id, count} if it is still the custody's row of the item. */
		private long[] lockRow(long objectId, int custody, int itemId) throws SQLException
		{
			try (PreparedStatement read = _connection.prepareStatement("SELECT count FROM items WHERE object_id = ? AND owner_id = -1 AND loc = 'ESCROW' AND loc_data = ? AND item_id = ? FOR UPDATE"))
			{
				read.setLong(1, objectId);
				read.setInt(2, custody);
				read.setInt(3, itemId);
				try (ResultSet result = read.executeQuery())
				{
					return result.next() ? new long[] { objectId, result.getLong(1) } : null;
				}
			}
		}

		/** An object leaves existence: its row and its metadata rows (§2.3), in the transaction. */
		/** {@code raid}'s check (§4.3): share and rest are exactly the custody's contents, objects by id, stacks by quantity. */
		void exactlyTheContents(int custody, List<Asset> share, List<Asset> rest) throws SQLException, AssetAbort
		{
			final Map<Integer, Long> stacks = new TreeMap<>();
			final Map<Integer, Integer> objects = new TreeMap<>();
			try (PreparedStatement rows = _connection.prepareStatement("SELECT object_id, item_id, count FROM items WHERE owner_id = -1 AND loc = 'ESCROW' AND loc_data = ? ORDER BY object_id"))
			{
				rows.setInt(1, custody);
				try (ResultSet result = rows.executeQuery())
				{
					while (result.next())
					{
						final ItemTemplate template = ItemData.getInstance().getTemplate(result.getInt(2));
						if ((template != null) && !template.isStackable())
						{
							objects.put(result.getInt(1), result.getInt(2));
						}
						else
						{
							stacks.merge(result.getInt(2), result.getLong(3), Long::sum);
						}
					}
				}
			}
			final Map<Integer, Long> claimedStacks = new TreeMap<>();
			final Map<Integer, Integer> claimedObjects = new TreeMap<>();
			final List<Asset> all = new ArrayList<>(share);
			all.addAll(rest);
			for (Asset asset : all)
			{
				if (asset.objectId() != null)
				{
					if ((asset.qty() != 1) || (claimedObjects.put(asset.objectId(), asset.itemId()) != null))
					{
						throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, "object " + asset.objectId() + " is named twice or not as one");
					}
				}
				else
				{
					final ItemTemplate template = ItemData.getInstance().getTemplate(asset.itemId());
					if ((template == null) || !template.isStackable())
					{
						throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, "a non-stackable item " + asset.itemId() + " is named by quantity");
					}
					claimedStacks.merge(asset.itemId(), asset.qty(), Long::sum);
				}
			}
			if (!stacks.equals(claimedStacks) || !objects.equals(claimedObjects))
			{
				throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, "share and rest are not exactly custody " + custody + "'s contents");
			}
			// the global row-lock order (fact-journal.md §3.1): each row, then its object's lifecycle rows, by object id
			final List<Integer> ids = new ArrayList<>();
			try (PreparedStatement rows = _connection.prepareStatement("SELECT object_id, item_id FROM items WHERE owner_id = -1 AND loc = 'ESCROW' AND loc_data = ? ORDER BY object_id"))
			{
				rows.setInt(1, custody);
				try (ResultSet result = rows.executeQuery())
				{
					while (result.next())
					{
						ids.add(result.getInt(1));
					}
				}
			}
			for (int objectId : ids)
			{
				try (PreparedStatement lock = _connection.prepareStatement("SELECT item_id FROM items WHERE object_id = ? FOR UPDATE"))
				{
					lock.setInt(1, objectId);
					try (ResultSet result = lock.executeQuery())
					{
						if (result.next())
						{
							lifecycleOf(objectId, result.getInt(1));
						}
					}
				}
			}
		}

		/** What a custody row of a whole object holds (§4.3 {@code raid}): the row, its attributes, elementals and variables. */
		RaidShare.Snapshot snapshot(int objectId) throws SQLException, AssetAbort
		{
			int itemId;
			long count;
			int enchant;
			int type1;
			int type2;
			int mana;
			long time;
			try (PreparedStatement read = _connection.prepareStatement("SELECT item_id, count, enchant_level, custom_type1, custom_type2, mana_left, time FROM items WHERE object_id = ? FOR UPDATE"))
			{
				read.setInt(1, objectId);
				try (ResultSet result = read.executeQuery())
				{
					if (!result.next())
					{
						throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, "object " + objectId + " has no row");
					}
					itemId = result.getInt(1);
					count = result.getLong(2);
					enchant = result.getInt(3);
					type1 = result.getInt(4);
					type2 = result.getInt(5);
					mana = result.getInt(6);
					time = result.getLong(7);
				}
			}
			int augmentation = -1;
			try (PreparedStatement read = _connection.prepareStatement("SELECT augAttributes FROM item_attributes WHERE itemId = ?"))
			{
				read.setInt(1, objectId);
				try (ResultSet result = read.executeQuery())
				{
					if (result.next())
					{
						augmentation = result.getInt(1);
					}
				}
			}
			final List<int[]> elementals = new ArrayList<>();
			try (PreparedStatement read = _connection.prepareStatement("SELECT elemType, elemValue FROM item_elementals WHERE itemId = ?"))
			{
				read.setInt(1, objectId);
				try (ResultSet result = read.executeQuery())
				{
					while (result.next())
					{
						final int type = result.getByte(1);
						final int value = result.getInt(2);
						if ((type != -1) && (value != -1))
						{
							elementals.add(new int[]
							{
								type,
								value
							});
						}
					}
				}
			}
			final Map<String, String> variables = new LinkedHashMap<>();
			try (PreparedStatement read = _connection.prepareStatement("SELECT var, val FROM item_variables WHERE id = ?"))
			{
				read.setInt(1, objectId);
				try (ResultSet result = read.executeQuery())
				{
					while (result.next())
					{
						variables.put(result.getString(1), result.getString(2));
					}
				}
			}
			return new RaidShare.Snapshot(objectId, itemId, count, enchant, type1, type2, mana, time, augmentation, elementals.toArray(new int[0][]), variables);
		}

		/**
		 * An amount of a non-stackable item in a custody is its whole objects there, lowest object ids first (§11.35,
		 * ASSET-R07): each then moves, leaves or is sunk as itself, its id and lifecycle kept; any other asset as it is.
		 */
		List<Asset> wholes(int custody, List<Asset> assets) throws SQLException, AssetAbort
		{
			final List<Asset> wholes = new ArrayList<>();
			for (Asset asset : assets)
			{
				final ItemTemplate template = ItemData.getInstance().getTemplate(asset.itemId());
				if ((asset.objectId() != null) || (template == null) || template.isStackable())
				{
					wholes.add(asset);
					continue;
				}
				final List<Integer> objects = new ArrayList<>();
				try (PreparedStatement rows = _connection.prepareStatement("SELECT object_id FROM items WHERE owner_id = -1 AND loc = 'ESCROW' AND loc_data = ? AND item_id = ? AND count = 1 ORDER BY object_id"))
				{
					rows.setInt(1, custody);
					rows.setInt(2, asset.itemId());
					try (ResultSet result = rows.executeQuery())
					{
						while (result.next() && (objects.size() < asset.qty()))
						{
							final int objectId = result.getInt(1);
							if (!isWholeAlready(wholes, objectId))
							{
								objects.add(objectId);
							}
						}
					}
				}
				if (objects.size() < asset.qty())
				{
					throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, "custody " + custody + " holds " + objects.size() + " of item " + asset.itemId() + ", not " + asset.qty());
				}
				for (int objectId : objects)
				{
					wholes.add(new Asset(asset.itemId(), 1, objectId));
				}
			}
			// whole objects in object-id order, as their rows and lifecycles are locked (fact-journal.md §3.1), then amounts
			wholes.sort(Comparator.comparing((Asset a) -> a.objectId() == null).thenComparing(a -> a.objectId() == null ? 0 : a.objectId()).thenComparingInt(Asset::itemId));
			return wholes;
		}

		private static boolean isWholeAlready(List<Asset> wholes, int objectId)
		{
			for (Asset whole : wholes)
			{
				if ((whole.objectId() != null) && (whole.objectId() == objectId))
				{
					return true;
				}
			}
			return false;
		}

		/**
		 * The object's live lifecycle, begun by this operation ({@code began_op}) if it has none; null for a stackable item,
		 * which has none (§2.2, §11.35). All the object's lifecycle rows are locked, after its {@code items} row
		 * (fact-journal.md §3.1), so the lifecycle writer's capture of the same object and this begin are ordered.
		 */
		Long lifecycleOf(int objectId, int itemId) throws SQLException
		{
			final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
			if ((template == null) || template.isStackable())
			{
				return null;
			}
			final Long known = _lifecycles.get(objectId);
			if (known != null)
			{
				return known;
			}
			long live = 0;
			try (PreparedStatement rows = _connection.prepareStatement("SELECT lifecycle_id, ended_at IS NULL FROM lc_item_lifecycle WHERE object_id = ? FOR UPDATE"))
			{
				rows.setInt(1, objectId);
				try (ResultSet result = rows.executeQuery())
				{
					while (result.next())
					{
						if (result.getBoolean(2))
						{
							live = result.getLong(1);
						}
					}
				}
			}
			if (live == 0)
			{
				try (PreparedStatement insert = _connection.prepareStatement("INSERT INTO lc_item_lifecycle (object_id, item_id, began_op, began_at) VALUES (?, ?, ?, NOW(3))", Statement.RETURN_GENERATED_KEYS))
				{
					insert.setInt(1, objectId);
					insert.setInt(2, itemId);
					insert.setBytes(3, _op.bytes());
					insert.executeUpdate();
					try (ResultSet keys = insert.getGeneratedKeys())
					{
						keys.next();
						live = keys.getLong(1);
					}
				}
			}
			_lifecycles.put(objectId, live);
			return live;
		}

		/** The lifecycle of a whole object's leg: its own for a non-stackable object, none for an amount or a stack. */
		Long lifecycle(Asset asset) throws SQLException
		{
			return asset.objectId() == null ? null : lifecycleOf(asset.objectId(), asset.itemId());
		}

		/** Ends the object's live lifecycle {@code DESTROYED} in this operation: a sink took its custody row (§11.35). */
		void endLifecycle(int objectId, int itemId) throws SQLException
		{
			final Long lifecycle = lifecycleOf(objectId, itemId);
			if (lifecycle == null)
			{
				return;
			}
			final Integer run = LifecycleWriter.currentRun();
			try (PreparedStatement end = _connection.prepareStatement("UPDATE lc_item_lifecycle SET ended_at = GREATEST(NOW(3), began_at), end_reason = 'DESTROYED', ended_op = ?, ended_run = ? WHERE lifecycle_id = ? AND ended_at IS NULL"))
			{
				end.setBytes(1, _op.bytes());
				end.setObject(2, run);
				end.setLong(3, lifecycle);
				end.executeUpdate();
			}
			_facts.add(AssetFact.lifecycle("LifecycleEnded", "lifecycle", lifecycle, "object", objectId, "item", itemId, "reason", "DESTROYED", "operation", _op.hex()));
		}

		/**
		 * Creates an asset in a custody (a source's): a non-stackable amount is that many new objects of one, each with a
		 * lifecycle begun here, returned as whole assets; any other amount joins the custody's rows as {@link #give} does.
		 */
		List<Asset> create(int custody, Asset asset) throws SQLException, AssetAbort
		{
			final ItemTemplate template = ItemData.getInstance().getTemplate(asset.itemId());
			if ((template == null) || template.isStackable())
			{
				give(custody, custody, asset);
				return List.of(asset);
			}
			_touched.add(custody);
			final List<Asset> created = new ArrayList<>();
			for (long i = 0; i < asset.qty(); i++)
			{
				final int objectId = _service.ids().next();
				final long[] lifetime = lifetime(asset.itemId());
				try (PreparedStatement insert = _connection.prepareStatement("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data, time_of_use, custom_type1, custom_type2, mana_left, time) VALUES (-1, ?, ?, 1, 0, 'ESCROW', ?, NULL, 0, 0, ?, ?)"))
				{
					insert.setInt(1, objectId);
					insert.setInt(2, asset.itemId());
					insert.setInt(3, custody);
					insert.setLong(4, lifetime[0]);
					insert.setLong(5, lifetime[1]);
					insert.executeUpdate();
				}
				created.add(new Asset(asset.itemId(), 1, objectId));
				lifecycleOf(objectId, asset.itemId());
			}
			return created;
		}

		private void deleteObject(long objectId) throws SQLException, AssetAbort
		{
			for (String table : new String[] { "item_attributes", "item_elementals", "item_variables" })
			{
				try (PreparedStatement delete = _connection.prepareStatement("DELETE FROM " + table + " WHERE " + (table.equals("item_variables") ? "id" : "itemId") + " = ?"))
				{
					delete.setLong(1, objectId);
					delete.executeUpdate();
				}
			}
			try (PreparedStatement delete = _connection.prepareStatement("DELETE FROM items WHERE object_id = ?"))
			{
				delete.setLong(1, objectId);
				expectOne(delete.executeUpdate(), objectId);
			}
		}

		static void expectOne(int affected, long objectId) throws AssetAbort
		{
			if (affected != 1)
			{
				throw new AssetAbort(AssetAbort.Reason.INSUFFICIENT, "object " + objectId + ": " + affected + " rows changed, not 1");
			}
		}

		/** Advances every touched custody's version and checks INV11 for it, after the postings are written. */
		void checkCustodies() throws SQLException, AssetAbort
		{
			for (int custody : _touched)
			{
				try (PreparedStatement advance = _connection.prepareStatement("UPDATE lc_custody SET version = version + 1 WHERE custody_id = ?"))
				{
					advance.setInt(1, custody);
					advance.executeUpdate();
				}
				final String evidence = CustodyInvariants.inv11(_connection, custody);
				if (evidence != null)
				{
					throw new AssetAbort(new AssetAbort.Violation(custody, "INV11", evidence));
				}
			}
		}
	}
}
