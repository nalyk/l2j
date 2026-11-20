package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * An owner module's holding predicate (ASSET-INV05): whether its object {@code ownerRef} may hold assets in a custody now.
 * Called inside the operation's transaction, after the custody rows are locked; it reads the owner's rows on that
 * connection.
 */
@FunctionalInterface
public interface HoldingPolicy
{
	boolean allowsHolding(Connection connection, long ownerRef) throws SQLException;
}
