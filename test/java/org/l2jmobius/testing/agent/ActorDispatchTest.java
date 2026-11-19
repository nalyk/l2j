package org.l2jmobius.testing.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link ActorDispatch} on a real compiled loop: the patched class must load and verify, hand each listed call to the dispatcher with its receiver, and otherwise do exactly what it did.
 */
class ActorDispatchTest
{
	/**
	 * An actor of the loop below.
	 */
	public static final class Actor
	{
		final String _name;
		int _steps;

		public Actor(String name)
		{
			_name = name;
		}

		public boolean step()
		{
			return ++_steps >= 2;
		}

		public void arrived(List<String> log)
		{
			log.add(_name + " arrived");
		}
	}

	/**
	 * A many-actor loop shaped like the server's: step each, take out and notify those that are done.
	 */
	public static final class Loop
	{
		private Loop()
		{
		}

		public static int run(List<Actor> actors, List<String> log)
		{
			int done = 0;
			final Iterator<Actor> iterator = actors.iterator();
			while (iterator.hasNext())
			{
				final Actor actor = iterator.next();
				if (actor.step())
				{
					iterator.remove();
					done++;
					actor.arrived(log);
				}
			}
			return done;
		}
	}

	/**
	 * Where the loop's calls are sent: each records whose turn it is, then does the call.
	 */
	public static final class Dispatcher
	{
		static final List<String> TURNS = new ArrayList<>();

		private Dispatcher()
		{
		}

		public static boolean step(Actor actor)
		{
			TURNS.add("step " + actor._name);
			return actor.step();
		}

		public static void arrived(Actor actor, List<String> log)
		{
			TURNS.add("arrived " + actor._name);
			actor.arrived(log);
		}
	}

	private static final String ACTOR = internal(Actor.class);
	private static final String LOOP = internal(Loop.class);

	private static final ActorDispatch DISPATCH = new ActorDispatch(Map.of(LOOP, new ActorDispatch.Site("run", List.of( //
		new ActorDispatch.Call(ACTOR, "step", "()Z"), //
		new ActorDispatch.Call(ACTOR, "arrived", "(Ljava/util/List;)V")))), internal(Dispatcher.class));

	private static String internal(Class<?> type)
	{
		return type.getName().replace('.', '/');
	}

	private static byte[] bytesOf(Class<?> type) throws IOException
	{
		try (InputStream in = type.getResourceAsStream(type.getName().substring(type.getName().lastIndexOf('.') + 1) + ".class"))
		{
			return in.readAllBytes();
		}
	}

	private static Class<?> load(byte[] classFile)
	{
		return new ClassLoader(ActorDispatchTest.class.getClassLoader())
		{
			Class<?> define()
			{
				return defineClass(Loop.class.getName(), classFile, 0, classFile.length);
			}
		}.define();
	}

	private static int run(Class<?> loop, List<Actor> actors, List<String> log) throws ReflectiveOperationException
	{
		return (int) loop.getMethod("run", List.class, List.class).invoke(null, actors, log);
	}

	@Test
	void eachListedCallIsTheDispatchersWithItsReceiver() throws Exception
	{
		final Class<?> patched = load(DISPATCH.apply(LOOP, bytesOf(Loop.class), ActorDispatchTest.class.getClassLoader()));
		final List<Actor> actors = new ArrayList<>(List.of(new Actor("a"), new Actor("b")));
		final List<String> log = new ArrayList<>();
		Dispatcher.TURNS.clear();

		assertEquals(0, run(patched, actors, log));
		assertEquals(2, run(patched, actors, log));
		assertEquals(List.of("step a", "step b", "step a", "arrived a", "step b", "arrived b"), Dispatcher.TURNS);
		assertEquals(List.of("a arrived", "b arrived"), log);
		assertTrue(actors.isEmpty());
	}

	@Test
	void theOriginalLoopIsUnaffected()
	{
		Dispatcher.TURNS.clear();
		final List<Actor> actors = new ArrayList<>(List.of(new Actor("a")));
		Loop.run(actors, new ArrayList<>());
		Loop.run(actors, new ArrayList<>());
		assertEquals(List.of(), Dispatcher.TURNS);
	}

	@Test
	void aListedCallThatIsNotThereAnyMoreIsAnError() throws Exception
	{
		final ActorDispatch stale = new ActorDispatch(Map.of(LOOP, new ActorDispatch.Site("run", List.of( //
			new ActorDispatch.Call(ACTOR, "step", "()Z"), //
			new ActorDispatch.Call(ACTOR, "rest", "()V")))), internal(Dispatcher.class));
		final IllegalStateException error = assertThrows(IllegalStateException.class, () -> stale.apply(LOOP, bytesOf(Loop.class), ActorDispatchTest.class.getClassLoader()));
		assertTrue(error.getMessage().contains(ACTOR + ".rest()V"), error.getMessage());
	}

	@Test
	void aClassThatIsNotListedIsReturnedAsIs() throws Exception
	{
		final byte[] untouched = bytesOf(Actor.class);
		assertSame(untouched, DISPATCH.apply(ACTOR, untouched, ActorDispatchTest.class.getClassLoader()));
	}
}
