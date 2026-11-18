package org.l2jmobius.testing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;

/**
 * A short, readable description of everything in the world, exact enough that two worlds with the same summary hold the same objects in the same places in the same condition.
 * <p>
 * The world has tens of thousands of objects, so a golden cannot list them. It lists how many there are of each kind, and three digests that each cover every object but look at a different aspect. When a summary changes, which digests changed says what kind of thing moved: the population, where it stands, or how it is doing.
 * </p>
 */
public final class WorldSummary
{
	private WorldSummary()
	{
	}

	/**
	 * @return the summary, one fact per line, in a fixed order.
	 */
	public static List<String> of()
	{
		final List<WorldObject> objects = new ArrayList<>(World.getVisibleObjects());

		final Map<String, Integer> kinds = new TreeMap<>();
		for (WorldObject object : objects)
		{
			kinds.merge(object.getClass().getSimpleName(), 1, Integer::sum);
		}

		final List<String> lines = new ArrayList<>();
		lines.add("objects\t" + objects.size());
		for (Map.Entry<String, Integer> kind : kinds.entrySet())
		{
			lines.add("kind\t" + kind.getKey() + "\t" + kind.getValue());
		}

		// Who exists: independent of where and of the order objects were created in.
		lines.add("digest\tpopulation\t" + digest(objects, object -> kind(object)));

		// Which object got which id: sensitive to creation order.
		lines.add("digest\tidentity\t" + digest(objects, object -> object.getObjectId() + "|" + kind(object)));

		// Where everything stands and faces.
		lines.add("digest\tplacement\t" + digest(objects, object -> kind(object) + "|" + object.getInstanceId() + "|" + object.getX() + "|" + object.getY() + "|" + object.getZ() + "|" + object.getHeading()));

		// How every creature is doing.
		lines.add("digest\tcondition\t" + digest(objects, object -> object.isCreature() ? kind(object) + "|" + object.getX() + "|" + object.getY() + "|" + object.getZ() + "|" + object.asCreature().isDead() + "|" + object.asCreature().getCurrentHp() + "|" + object.asCreature().getCurrentMp() : ""));
		return lines;
	}

	/**
	 * @return one line per object, sorted: kind, instance, position, heading and, for creatures, condition. Far too long for a golden; this is what to diff when a digest changes.
	 */
	public static List<String> detail()
	{
		final List<String> lines = new ArrayList<>();
		for (WorldObject object : World.getVisibleObjects())
		{
			lines.add(kind(object) + "\t" + object.getName() + "\t" + object.getInstanceId() + "\t" + object.getX() + "\t" + object.getY() + "\t" + object.getZ() + "\t" + object.getHeading() + (object.isCreature() ? "\t" + object.asCreature().isDead() + "\t" + object.asCreature().getCurrentHp() + "\t" + object.asCreature().getCurrentMp() : "") + "\tid=" + object.getObjectId());
		}
		lines.sort(null);
		return lines;
	}

	private static String kind(WorldObject object)
	{
		return object.getClass().getSimpleName() + "#" + object.getId();
	}

	/**
	 * A digest over one line per object, sorted, so it does not depend on iteration order.
	 */
	private static String digest(List<WorldObject> objects, Function<WorldObject, String> aspect)
	{
		final List<String> lines = new ArrayList<>(objects.size());
		for (WorldObject object : objects)
		{
			lines.add(aspect.apply(object));
		}
		lines.sort(null);

		final MessageDigest sha256;
		try
		{
			sha256 = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("every Java platform is required to provide SHA-256", e);
		}
		for (String line : lines)
		{
			sha256.update(line.getBytes(StandardCharsets.UTF_8));
			sha256.update((byte) '\n');
		}
		return HexFormat.of().formatHex(sha256.digest());
	}
}
