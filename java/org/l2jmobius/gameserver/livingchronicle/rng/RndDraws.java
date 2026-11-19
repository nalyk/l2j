package org.l2jmobius.gameserver.livingchronicle.rng;

import java.util.List;

import org.l2jmobius.commons.util.Rnd;

/**
 * The JDK's own random draws, made through {@link Rnd} instead of a generator of the JDK's.
 * <p>
 * Mobius draws almost everything through {@link Rnd}, which a test can bind to a keyed stream. A few call sites use the JDK directly: {@code Collections.shuffle(List)} (monster race lineups, event teams, quest boards) and {@code Math.random()} (captcha images, login keys). Their generators cannot be bound, so a paused world that reaches one of them is no longer a function of its seed. Test JVMs point those calls here as classes load (see the truth agent), and this draws exactly as the JDK method would, through {@link Rnd}. Production never calls this class: its calls go to the JDK unchanged.
 * </p>
 */
public final class RndDraws
{
	private RndDraws()
	{
	}

	/**
	 * As {@code Collections.shuffle(List)}: the same swaps in the same order, each index drawn from {@link Rnd}.
	 * @param list the list to shuffle in place.
	 */
	public static void shuffle(List<?> list)
	{
		swapDownwards(list);
	}

	private static <T> void swapDownwards(List<T> list)
	{
		for (int i = list.size(); i > 1; i--)
		{
			list.set(i - 1, list.set(Rnd.get(i), list.get(i - 1)));
		}
	}

	/**
	 * As {@code Math.random()}: a double from 0 (inclusive) to 1 (exclusive), drawn from {@link Rnd}.
	 * @return the value.
	 */
	public static double random()
	{
		return Rnd.nextDouble();
	}
}
