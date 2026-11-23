package org.l2jmobius.gameserver.livingchronicle.drop;

import java.util.List;
import java.util.Set;

import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;

/**
 * A module's hand on a kill's drops (docs/spec/asset-service.md §11.33): offered what earlier routers left, it runs its
 * own Asset operation on the dropping thread and returns the positions that operation committed (an uncertain commit
 * resolved first), or no positions when it committed nothing (an aborted operation included). If it throws, the seam
 * asks it what it committed ({@link #committed}); only a router that cannot tell either has what it was offered
 * withheld from everyone after it.
 */
@FunctionalInterface
public interface DropRouter
{
	/**
	 * @param kill the kill
	 * @param offered what is left of its death-drop list, in retail's order
	 * @return the positions of {@code offered} its committed operation took, empty when it committed nothing
	 * @throws Exception only when it cannot tell: what it was offered is then withheld
	 */
	Set<Integer> route(Kill kill, List<ItemHolder> offered) throws Exception;
	
	/**
	 * Asked only when {@link #route} threw: what the router's operation for this kill committed, read from durable state
	 * (the operation whose id it derives from the kill's key, and its rows), so a failure after a commit keeps what was
	 * committed and gives retail the rest. The default cannot tell; a router that commits must answer.
	 * @param kill the kill
	 * @param offered what it was offered, as {@link #route} was
	 * @return the positions its committed operation took, empty if none committed
	 * @throws Exception when it cannot tell either: what it was offered is then withheld
	 */
	default Set<Integer> committed(Kill kill, List<ItemHolder> offered) throws Exception
	{
		throw new UnsupportedOperationException("this router cannot tell what it committed");
	}
}
