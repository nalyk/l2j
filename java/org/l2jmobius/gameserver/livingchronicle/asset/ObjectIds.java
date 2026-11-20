package org.l2jmobius.gameserver.livingchronicle.asset;

/**
 * Where new object ids come from: Mobius's {@code IdManager} in production (bound with the Mobius holders, part 2 of the
 * Asset Service), a counter in tests. An id handed out here is reserved by the row the operation writes.
 */
@FunctionalInterface
public interface ObjectIds
{
	int next();
}
