package org.l2jmobius.testing;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.livingchronicle.clock.GameScheduler;

/**
 * Which random stream each piece of a paused world draws on: the world's own, or the stream of the lock case that set it going.
 * <p>
 * A task draws on the stream of whatever scheduled it, so the hits, casts and effects a case starts draw on the case's stream however late they land, while the world's own timers (doors, script events, spawns) draw on the world's stream even when they fall due inside a case. Neither then moves the other: a change to some script elsewhere in the world does not change a case's draws, and a case does not change the world's.
 * </p>
 * <p>
 * The server's task managers serve the whole world, and run on the world's stream whoever made them. Inside the ones that serve many actors in one loop and draw for them (moving, thinking, respawning), each actor's turn draws on the stream it belongs to: an NPC brought back by the respawn manager on the stream its death was on ({@link #own}); otherwise an actor in the {@link Arena} while a case is open is that case's, any other is the world's (the arena is open sea far from every spawn, so the world's actors are never there). The test agent hands those turns to {@link ActorStreams}. A few more managers serve every actor of their kind and draw nothing with no player near (following, combat stance, seeing, automation, flagging); whatever they draw while a case is open is the case's actors', so they run on the open case's stream then. Decay and idle animation stay on the world's: scripts kill NPCs with no player near, and idle animation keeps every NPC it has seen, deleted ones too, drawing when each next turns; what it draws for is cosmetic.
 * </p>
 */
final class CaseStreams implements GameScheduler.TaskContext
{
	private static final String TASK_MANAGERS = "org.l2jmobius.gameserver.taskmanagers.";

	/** The task managers that serve every actor of their kind and draw nothing with no player near, by class name. Checked at boot: a renamed one would silently stop being one. */
	static final List<String> ACTOR_POOLS = List.of( //
		TASK_MANAGERS + "CreatureFollowTaskManager$CreatureFollowNormalTask", //
		TASK_MANAGERS + "CreatureFollowTaskManager$CreatureFollowAttackTask", //
		TASK_MANAGERS + "AttackStanceTaskManager", //
		TASK_MANAGERS + "CreatureSeeTaskManager", //
		TASK_MANAGERS + "AutoUseTaskManager$AutoUse", //
		TASK_MANAGERS + "AutoPlayTaskManager$AutoPlay", //
		TASK_MANAGERS + "AutoPotionTaskManager", //
		TASK_MANAGERS + "PvpFlagTaskManager");

	private final Set<String> _actorPools = Set.copyOf(ACTOR_POOLS);
	private final SimulationRandom _random;
	private final RandomGenerator _world;
	private volatile RandomGenerator _case;

	/** Actors whose next turn belongs to a stream other than the one the loop would choose, by object id. */
	private final Map<Integer, RandomGenerator> _owners = new ConcurrentHashMap<>();

	/**
	 * @param random the generator every simulation thread is bound to, drawing on the world's stream.
	 */
	CaseStreams(SimulationRandom random)
	{
		_random = random;
		_world = random.source();
		for (String pool : ACTOR_POOLS)
		{
			try
			{
				Class.forName(pool, false, CaseStreams.class.getClassLoader());
			}
			catch (ClassNotFoundException e)
			{
				throw new IllegalStateException("the actor pool " + pool + " no longer exists; find what serves those actors now and name it here", e);
			}
		}
		ActorStreams.install(this);
	}

	@Override
	public Object capture()
	{
		return _random.source();
	}

	@Override
	public void restore(Object context)
	{
		_random.use((RandomGenerator) context);
	}

	@Override
	public Object forRun(Runnable runnable, Object scheduled)
	{
		final String name = runnable.getClass().getName();
		if (_actorPools.contains(name))
		{
			final RandomGenerator open = _case;
			return open != null ? open : _world;
		}
		return name.startsWith(TASK_MANAGERS) ? _world : scheduled;
	}

	/**
	 * Does {@code call}, one actor's turn in a many-actor loop, drawing on the stream {@code actor} belongs to, then on what was drawn on before.
	 */
	<T> T asActor(WorldObject actor, Supplier<T> call)
	{
		final RandomGenerator open = _case;
		final RandomGenerator previous = _random.source();
		_random.use(((open != null) && Arena.contains(actor)) ? open : _world);
		try
		{
			return call.get();
		}
		finally
		{
			_random.use(previous);
		}
	}

	/**
	 * Records that {@code actor} belongs to the stream drawn on now, until {@link #asOwner} takes it: a spawn putting its dead NPC on the respawn list does this, so that the NPC comes back on the stream its death was on, however much the world has drawn meanwhile.
	 */
	void own(WorldObject actor)
	{
		_owners.put(actor.getObjectId(), _random.source());
	}

	/**
	 * Does {@code call} on the stream {@code actor} was recorded as belonging to, and forgets the record; on the stream {@link #asActor} would choose if there is none.
	 */
	<T> T asOwner(WorldObject actor, Supplier<T> call)
	{
		final RandomGenerator owner = _owners.remove(actor.getObjectId());
		if (owner == null)
		{
			return asActor(actor, call);
		}
		final RandomGenerator previous = _random.source();
		_random.use(owner);
		try
		{
			return call.get();
		}
		finally
		{
			_random.use(previous);
		}
	}

	/**
	 * Opens a case drawing on {@code generator}.
	 * @return the case that was open before, to give back to {@link #close(RandomGenerator)}.
	 */
	RandomGenerator open(RandomGenerator generator)
	{
		final RandomGenerator previous = _case;
		_case = generator;
		return previous;
	}

	/**
	 * Closes the case {@link #open(RandomGenerator)} opened, {@code previous} being open again.
	 */
	void close(RandomGenerator previous)
	{
		_case = previous;
	}
}
