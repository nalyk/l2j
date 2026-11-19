package org.l2jmobius.testing;

import java.util.function.Supplier;

import org.l2jmobius.gameserver.ai.CreatureAI;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Creature;

/**
 * Where the test agent sends the calls that are one actor's turn in the server's many-actor loops (moving, thinking). Each does the call it stands for, drawing on the stream of the case the actor belongs to (see {@link CaseStreams}), and then on whatever the loop draws on again.
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

	public static void notifyActionThink(CreatureAI ai)
	{
		asActor(ai.getActor(), () ->
		{
			ai.notifyActionThink();
			return null;
		});
	}
}
