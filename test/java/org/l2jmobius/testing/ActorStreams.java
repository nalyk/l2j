package org.l2jmobius.testing;

import java.util.function.Supplier;

import org.l2jmobius.gameserver.ai.CreatureAI;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.taskmanagers.DecayTaskManager;
import org.l2jmobius.gameserver.taskmanagers.RespawnTaskManager;

/**
 * Where the test agent sends the calls that are one actor's turn in the server's many-actor loops (moving, thinking, decaying, respawning). Each does the call it stands for, drawing on the stream of the case the actor belongs to (see {@link CaseStreams}), and then on whatever the loop draws on again.
 * <p>
 * A method here has exactly the name of the call it receives and takes its receiver first; the agent fails a class whose listed calls it cannot find. With no paused world (a JVM that boots in real time) each simply does the call.
 * </p>
 */
public final class ActorStreams
{
	private static volatile CaseStreams _streams;

	private ActorStreams()
	{
	}

	static void install(CaseStreams streams)
	{
		_streams = streams;
	}

	private static <T> T asActor(WorldObject actor, Supplier<T> call)
	{
		final CaseStreams streams = _streams;
		return streams == null ? call.get() : streams.asActor(actor, call);
	}

	public static boolean updatePosition(Creature creature)
	{
		return asActor(creature, creature::updatePosition);
	}

	public static CreatureAI getAI(Creature creature)
	{
		return asActor(creature, creature::getAI);
	}

	public static CreatureAI getAI(Attackable attackable)
	{
		return asActor(attackable, attackable::getAI);
	}

	public static void notifyActionArrived(CreatureAI ai)
	{
		asActor(ai.getActor(), () ->
		{
			ai.notifyActionArrived();
			return null;
		});
	}

	/**
	 * A dying NPC putting itself on the decay list: until it has decayed, it belongs to the stream this is drawn on.
	 */
	public static void add(DecayTaskManager manager, Creature creature)
	{
		final CaseStreams streams = _streams;
		if (streams != null)
		{
			streams.own(creature);
		}
		manager.add(creature);
	}

	/**
	 * The decay manager taking a corpse away, on the stream its death was on; any other creature's (a player's, a pet's) on the stream the manager runs on, as before.
	 */
	public static void onDecay(Creature creature)
	{
		final CaseStreams streams = _streams;
		if (streams == null)
		{
			creature.onDecay();
			return;
		}
		streams.asOwnerIfAny(creature, () ->
		{
			creature.onDecay();
			return null;
		});
	}

	/**
	 * A spawn putting its dead NPC on the respawn list: the NPC belongs, until it is back, to the stream this is drawn on.
	 */
	public static void add(RespawnTaskManager manager, Npc npc, long time)
	{
		final CaseStreams streams = _streams;
		if (streams != null)
		{
			streams.own(npc);
		}
		manager.add(npc, time);
	}

	/**
	 * The respawn manager bringing an NPC back, on the stream it belongs to.
	 */
	public static void respawnNpc(Spawn spawn, Npc npc)
	{
		final CaseStreams streams = _streams;
		if (streams == null)
		{
			spawn.respawnNpc(npc);
			return;
		}
		streams.asOwner(npc, () ->
		{
			spawn.respawnNpc(npc);
			return null;
		});
	}

	public static void notifyActionThink(CreatureAI ai)
	{
		asActor(ai.getActor(), () ->
		{
			ai.notifyActionThink();
			return null;
		});
	}
}
