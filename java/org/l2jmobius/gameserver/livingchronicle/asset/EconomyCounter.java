package org.l2jmobius.gameserver.livingchronicle.asset;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.l2jmobius.gameserver.entity.item.enums.ItemLocation;

/**
 * The existence counters (docs/spec/asset-service.md §2.6, INV06): per item id, the quantity in the holdings the census
 * counts, kept from the changes themselves. Mobius's mutators post here through core patches (CP-0021 and on), each a
 * classified site (§2.7); the ledger posts its SOURCE and SINK legs. Until {@link #activate} the hooks do nothing, so a
 * profile that starts no module (profiles.md §4.2 rule 4) runs exactly as upstream.
 */
public final class EconomyCounter
{
	private static final int STRIPES = 256;
	private static final Object[] LOCKS = new Object[STRIPES];
	static
	{
		for (int i = 0; i < STRIPES; i++)
		{
			LOCKS[i] = new Object();
		}
	}

	private static final Map<Integer, LongAdder> COUNTS = new ConcurrentHashMap<>();
	/** Quantities asked of a change but not applied, by item id (a clamp, §2.7): watched, never part of the counts. */
	private static final Map<Integer, LongAdder> CLAMPED = new ConcurrentHashMap<>();
	private static final java.util.Set<Integer> COUNTED_OUT = ConcurrentHashMap.newKeySet();
	private static volatile boolean _active;
	/** A counted statement's commit could not be read either way: the counters may be off by its change (§2.6). */
	private static volatile boolean _uncertain;

	private EconomyCounter()
	{
	}

	/** Starts counting from {@code base} (the startup census, per item id). */
	public static synchronized void activate(Map<Integer, Long> base)
	{
		COUNTS.clear();
		CLAMPED.clear();
		COUNTED_OUT.clear();
		_uncertain = false;
		base.forEach((itemId, quantity) -> COUNTS.computeIfAbsent(itemId, id -> new LongAdder()).add(quantity));
		_active = true;
	}

	/** Stops counting; the counts are kept as they are. */
	public static synchronized void deactivate()
	{
		_active = false;
	}

	public static boolean isActive()
	{
		return _active;
	}

	/** Counts {@code delta} of {@code itemId} entering (positive) or leaving (negative) the counted holdings. */
	public static void post(int itemId, long delta)
	{
		if (_active && (delta != 0))
		{
			COUNTS.computeIfAbsent(itemId, id -> new LongAdder()).add(delta);
		}
	}

	/**
	 * Records that a change asked for {@code quantity} of {@code itemId} more than it applied (a gain clamped at the cap is
	 * positive, a decrease clamped at zero negative). The counts already hold the quantity applied; this is the clamp's
	 * own sink or source (ASSET-R13), kept apart for the watched part of the monitor.
	 */
	public static void postClamp(int itemId, long quantity)
	{
		if (_active && (quantity != 0))
		{
			CLAMPED.computeIfAbsent(itemId, id -> new LongAdder()).add(quantity);
		}
	}

	/**
	 * An object was counted out of the holdings in memory (destroyed, dropped, sold) while its row still holds it, until
	 * Mobius writes or deletes the row: a counted SQL statement deleting that row first must not count it again (CP-0025).
	 */
	public static void countedOut(int objectId)
	{
		if (_active)
		{
			COUNTED_OUT.add(objectId);
		}
	}

	/**
	 * Mobius wrote or deleted the object's row: the mark goes only if the row as written holds nothing the census counts
	 * ({@code rowCounted} 0). A save that began before the object was counted out and wrote its older quantity keeps the
	 * mark, as that row still holds it (Codex amd15).
	 */
	public static void rowWritten(int objectId, long rowCounted)
	{
		if (rowCounted == 0)
		{
			COUNTED_OUT.remove(objectId);
		}
	}

	/** The object counts again, or a counted statement settled its row. */
	public static void clearCountedOut(int objectId)
	{
		COUNTED_OUT.remove(objectId);
	}

	/** @return whether memory counted this object out while its row still holds it */
	public static boolean isCountedOut(int objectId)
	{
		return COUNTED_OUT.contains(objectId);
	}

	/** A counted statement's commit could not be read: no shutdown census may certify these counters (§2.6). */
	public static void uncertain()
	{
		_uncertain = true;
	}

	/** @return whether a commit since the counters started could not be read */
	public static boolean isUncertain()
	{
		return _uncertain;
	}

	/** @return every non-zero count, by item id */
	public static Map<Integer, Long> snapshot()
	{
		return sums(COUNTS);
	}

	/** @return every non-zero clamped quantity, by item id */
	public static Map<Integer, Long> clamped()
	{
		return sums(CLAMPED);
	}

	private static Map<Integer, Long> sums(Map<Integer, LongAdder> counts)
	{
		final Map<Integer, Long> snapshot = new TreeMap<>();
		counts.forEach((itemId, count) ->
		{
			final long sum = count.sum();
			if (sum != 0)
			{
				snapshot.put(itemId, sum);
			}
		});
		return snapshot;
	}

	/** @return whether the census counts an item in {@code location}: every location but VOID, REFUND and LEASE */
	public static boolean counted(ItemLocation location)
	{
		return (location != null) && (location != ItemLocation.VOID) && (location != ItemLocation.REFUND) && (location != ItemLocation.LEASE);
	}

	/**
	 * The leaf lock under which an item settles its counted quantity: never an item's or a container's monitor, and nothing
	 * else is taken while it is held, so it joins no lock order.
	 */
	public static Object lockFor(int objectId)
	{
		return LOCKS[stripe(objectId)];
	}

	/** @return the stripe of {@link #lockFor}: a caller holding several takes them in this order */
	public static int stripe(int objectId)
	{
		return Math.floorMod(objectId, STRIPES);
	}
}
