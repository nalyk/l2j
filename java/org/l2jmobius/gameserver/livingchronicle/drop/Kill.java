package org.l2jmobius.gameserver.livingchronicle.drop;

import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * A kill whose drops the drop seam offers (docs/spec/asset-service.md §11.33): its key is unique across restarts because
 * the epoch is (one per server start, the journal's), and each consumer derives its own {@code OpId} from it.
 * @param epoch the epoch given at install
 * @param seq the kill's number in this epoch, from 1
 * @param npc the monster that died
 * @param owner the player Mobius decided owns its drops
 */
public record Kill(int epoch, long seq, Attackable npc, Player owner)
{
	/** @return {@code kill/<epoch>/<seq>} */
	public String key()
	{
		return "kill/" + epoch + "/" + seq;
	}
}
