package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Where the Asset Service's connections come from: its own, never Mobius's write helpers (asset-service.md §11.1).
 * Production binds the game server's pool; tests bind the test database.
 */
@FunctionalInterface
public interface Connections
{
	Connection open() throws SQLException;
}
