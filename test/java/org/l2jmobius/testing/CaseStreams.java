package org.l2jmobius.testing;

import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

import org.l2jmobius.gameserver.livingchronicle.clock.GameScheduler;

/**
 * Which random stream each piece of a paused world draws on: the world's own, or the stream of the lock case that set it going.
 * <p>
 * A task draws on the stream of whatever scheduled it, so the hits, casts and effects a case starts draw on the case's stream however late they land, while the world's own timers (doors, script events, spawns) draw on the world's stream even when they fall due inside a case. Neither then moves the other: a change to some script elsewhere in the world does not change a case's draws, and a case does not change the world's.
 * </p>
 * <p>
 * A few tasks of the server serve every actor of their kind at once: thinking, following, combat stance, seeing, automation, flagging. In a world with no player near, none of them draws anything, so whatever they draw while a case is open is the case's actors', and they draw on the open case's stream then, on the world's otherwise. The others stay on the world's stream, and so does what they set going; a case actor's draw that happens inside them is the world's too. Movement and decay: the world keeps them busy with no player near (measured over an idle hour), walking routes and roaming squads, NPCs that scripts kill. Idle animation: it keeps every NPC it has ever seen, deleted ones included, drawing when each next turns, so earlier cases' NPCs would draw inside later cases; what it draws for is cosmetic.
 * </p>
 */
final class CaseStreams implements GameScheduler.TaskContext
{
	private static final String TASK_MANAGERS = "org.l2jmobius.gameserver.taskmanagers.";

	/** The tasks that serve every actor of their kind and draw nothing with no player near, by class name. Checked at boot: a renamed one would silently stop being one. */
	static final List<String> ACTOR_POOLS = List.of( //
		TASK_MANAGERS + "AttackableThinkTaskManager$AttackableThink", //
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
		if (_actorPools.contains(runnable.getClass().getName()))
		{
			final RandomGenerator open = _case;
			return open != null ? open : _world;
		}
		return scheduled;
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
