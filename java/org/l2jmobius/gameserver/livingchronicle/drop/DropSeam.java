package org.l2jmobius.gameserver.livingchronicle.drop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;

/**
 * The drop seam (docs/spec/asset-service.md §11.33, core patch CP-0032): {@code Attackable.doItemDrop} hands it a kill's
 * death-drop list right after retail computed it. Installed routers run in order on the dropping thread, each offered
 * what earlier ones left and taking the positions its own committed operation took; the rest goes back to retail
 * unchanged. A router that throws is asked what it committed; one that cannot tell yet has what it was offered held
 * until it can, then the rest dropped at the kill's place, never twice. With none
 * installed (always in {@code truth}) the very list comes back after one volatile read.
 */
public final class DropSeam
{
	private static final Logger LOGGER = Logger.getLogger(DropSeam.class.getName());

	private record Installed(int epoch, List<DropRouter> routers, AtomicLong seq)
	{
	}

	private static volatile Installed _installed;
	/** Every epoch's kill counter in this JVM: reinstalling any epoch keeps counting its kills. */
	private static final Map<Integer, AtomicLong> SEQUENCES = new ConcurrentHashMap<>();

	private DropSeam()
	{
	}

	/** A kill whose router's outcome could not be read: what it was offered and where the kill was. */
	private record Pending(Kill kill, DropRouter router, List<ItemHolder> offered, int x, int y, int z, int instanceId)
	{
	}

	/** How many kills may be routed or wait for their router's outcome at once (02 §6: no unbounded queue). */
	static final int MAX_PENDING = 10_000;
	private static final List<Pending> PENDING = new ArrayList<>();
	/**
	 * Kills being routed or held: a kill takes a slot before any router runs and keeps it while held, so a kill whose
	 * outcome becomes unknown always has room to be held; with none free, no router runs and retail drops the kill.
	 */
	private static final AtomicInteger SLOTS = new AtomicInteger();
	private static volatile int _capacity = MAX_PENDING;

	/** Sets how many kills may be routed or held at once (tests). */
	static void capacity(int slots)
	{
		_capacity = slots;
	}
	private static boolean _resolving;

	private static void hold(Pending pending, Throwable cause)
	{
		synchronized (PENDING)
		{
			PENDING.add(pending); // its slot was taken before its routers ran, so there is always room
			LOGGER.log(Level.SEVERE, "Drop router " + pending.router() + " cannot tell what it committed of " + pending.kill().key() + "; " + pending.offered() + " held until it can", cause);
			if (!_resolving)
			{
				_resolving = true;
				ThreadPool.scheduleAtFixedRate(DropSeam::resolvePending, 10_000, 10_000);
			}
		}
	}

	/** @return how many kills wait for their router's outcome */
	public static int pending()
	{
		synchronized (PENDING)
		{
			return PENDING.size();
		}
	}

	/**
	 * Asks each waiting router again what it committed; once it answers, what it did not commit drops at the kill's
	 * place as retail drops it (Npc.dropItem), protected for the kill's owner. A JVM death first loses them, as it loses
	 * a kill's drops before any commit (pact.md §4.5).
	 */
	public static void resolvePending()
	{
		final List<Pending> waiting;
		synchronized (PENDING)
		{
			waiting = List.copyOf(PENDING);
		}
		for (Pending pending : waiting)
		{
			final Set<Integer> committed;
			try
			{
				committed = pending.router().committed(pending.kill(), pending.offered());
			}
			catch (Throwable e)
			{
				continue; // still unknown
			}
			synchronized (PENDING)
			{
				if (!PENDING.remove(pending))
				{
					continue; // another pass resolved it
				}
			}
			SLOTS.decrementAndGet();
			for (int i = 0; i < pending.offered().size(); i++)
			{
				if ((committed == null) || !committed.contains(i))
				{
					drop(pending, pending.offered().get(i));
				}
			}
		}
	}

	/** Mobius's own drop (Npc.dropItemAt, CP-0032), at the kill's place rather than where the monster is now. */
	private static void drop(Pending pending, ItemHolder holder)
	{
		pending.kill().npc().dropItemAt(pending.kill().owner(), holder.getId(), holder.getCount(), pending.x(), pending.y(), pending.z(), pending.instanceId());
	}

	/**
	 * Installs the routers, in the order they run.
	 * @param epoch the epoch of this server start, part of every kill's key
	 * @param routers the routers
	 */
	public static synchronized void install(int epoch, List<DropRouter> routers)
	{
		// a new epoch numbers from 1; one seen before goes on, so no key is ever given to two kills
		final AtomicLong seq = SEQUENCES.computeIfAbsent(epoch, e -> new AtomicLong());
		_installed = routers.isEmpty() ? null : new Installed(epoch, List.copyOf(routers), seq);
	}

	/** Removes every router: kills are retail's again. */
	public static synchronized void uninstall()
	{
		_installed = null;
	}

	/**
	 * @param npc the monster that died
	 * @param owner the player Mobius decided owns its drops
	 * @param drops its death-drop list as retail computed it, possibly null
	 * @return what retail drops: {@code drops} itself when no router is installed or none took anything
	 */
	public static Collection<ItemHolder> route(Attackable npc, Player owner, Collection<ItemHolder> drops)
	{
		final Installed installed = _installed;
		if ((installed == null) || (drops == null) || drops.isEmpty())
		{
			return drops;
		}

		if (SLOTS.incrementAndGet() > _capacity)
		{
			SLOTS.decrementAndGet();
			LOGGER.warning("Drop seam: " + _capacity + " kills are routed or held; this one is retail's, no router asked");
			return drops;
		}
		
		final Routed routed = new Routed();
		try
		{
			return routed(installed, npc, owner, drops, routed);
		}
		finally
		{
			if (!routed.held)
			{
				SLOTS.decrementAndGet(); // a held kill keeps its slot until it is resolved
			}
		}
	}
	
	/** Whether a kill was held. */
	private static final class Routed
	{
		boolean held;
	}
	
	private static Collection<ItemHolder> routed(Installed installed, Attackable npc, Player owner, Collection<ItemHolder> drops, Routed routed)
	{
		final int x = npc.getX();
		final int y = npc.getY();
		final int z = npc.getZ();
		final int instanceId = npc.getInstanceId();
		final Kill kill = new Kill(installed.epoch(), installed.seq().incrementAndGet(), npc, owner);
		List<ItemHolder> left = new ArrayList<>(drops);
		boolean taken = false;
		for (DropRouter router : installed.routers())
		{
			if (left.isEmpty())
			{
				break;
			}
			
			final List<ItemHolder> offered = List.copyOf(left);
			Set<Integer> positions;
			try
			{
				positions = router.route(kill, offered);
			}
			catch (Throwable e)
			{
				// It may have committed before it threw: it is asked what its operation committed, read from durable state,
				// so what it committed stays taken and the rest goes on. Only if it cannot tell either is what it was
				// offered withheld from the next router and from retail, where a committed copy would be a duplicate (§11.33).
				LOGGER.log(Level.WARNING, "Drop router " + router + " failed on " + kill.key() + ": " + e.getMessage(), e);
				try
				{
					positions = router.committed(kill, offered);
				}
				catch (Throwable unknown)
				{
					// Unknown until the store answers: held here, with the kill's place, and resolved later (resolvePending);
					// what the router did not commit then drops there, once. Nothing goes on meanwhile.
					hold(new Pending(kill, router, offered, x, y, z, instanceId), unknown);
					routed.held = true;
					return new ArrayList<>(); // mutable: doItemDrop clears what it is given
				}
			}
			
			if ((positions == null) || positions.isEmpty())
			{
				continue;
			}
			
			final List<ItemHolder> rest = new ArrayList<>(offered.size());
			for (int i = 0; i < offered.size(); i++)
			{
				if (!positions.contains(i))
				{
					rest.add(offered.get(i));
				}
			}
			
			for (Integer position : positions)
			{
				if ((position == null) || (position < 0) || (position >= offered.size()))
				{
					LOGGER.severe("Drop router " + router + " took position " + position + " of " + kill.key() + ", which offered " + offered.size() + ": ignored");
				}
			}
			
			taken |= rest.size() < offered.size();
			left = rest;
		}
		
		return taken ? left : drops;
	}
}
