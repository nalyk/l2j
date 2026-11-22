package org.l2jmobius.gameserver.livingchronicle.drop;

import java.util.List;
import java.util.Set;

import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;

/**
 * A module's hand on a kill's drops (docs/spec/asset-service.md §11.33): offered what earlier routers left, it runs its
 * own Asset operation on the dropping thread and returns the positions that operation committed (an uncertain commit
 * resolved first); a router that throws took nothing.
 */
@FunctionalInterface
public interface DropRouter
{
	/**
	 * @param kill the kill
	 * @param offered what is left of its death-drop list, in retail's order
	 * @return the positions of {@code offered} its committed operation took
	 * @throws Exception when nothing was taken
	 */
	Set<Integer> route(Kill kill, List<ItemHolder> offered) throws Exception;
}
