package org.l2jmobius.gameserver.livingchronicle.drop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;

/**
 * The drop seam (docs/spec/asset-service.md §11.33, core patch CP-0032): {@code Attackable.doItemDrop} hands it a kill's
 * death-drop list right after retail computed it. Installed routers run in order on the dropping thread, each offered
 * what earlier ones left and taking the positions its own committed operation took; the rest goes back to retail
 * unchanged. A router that throws gave no answer: what it was offered is withheld, never dropped twice. With none
 * installed (always in {@code truth}) the very list comes back after one volatile read.
 */
public final class DropSeam
{
	private static final Logger LOGGER = Logger.getLogger(DropSeam.class.getName());

	private record Installed(int epoch, List<DropRouter> routers, AtomicLong seq)
	{
	}

	private static volatile Installed _installed;
	/** The epoch whose kills were last numbered, and its counter: reinstalling the epoch keeps counting. */
	private static int _epoch = Integer.MIN_VALUE;
	private static AtomicLong _seq = new AtomicLong();

	private DropSeam()
	{
	}

	/**
	 * Installs the routers, in the order they run.
	 * @param epoch the epoch of this server start, part of every kill's key
	 * @param routers the routers
	 */
	public static synchronized void install(int epoch, List<DropRouter> routers)
	{
		if (epoch != _epoch)
		{
			_epoch = epoch;
			_seq = new AtomicLong(); // a new epoch numbers from 1; the same one goes on, so no key is given twice
		}
		_installed = routers.isEmpty() ? null : new Installed(epoch, List.copyOf(routers), _seq);
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
			final Set<Integer> positions;
			try
			{
				positions = router.route(kill, offered);
			}
			catch (Throwable e)
			{
				// No answer: it may have committed before it threw, or its commit may be unresolved. What it was offered
				// goes neither to the next router nor to retail, where a committed copy would be a duplicate; if it did not
				// commit, it is lost, as a JVM death loses a kill's drops (§11.33).
				LOGGER.log(Level.SEVERE, "Drop router " + router + " gave no answer for " + kill.key() + "; withheld " + offered + ": " + e.getMessage(), e);
				return new ArrayList<>(); // mutable: doItemDrop clears what it is given
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
