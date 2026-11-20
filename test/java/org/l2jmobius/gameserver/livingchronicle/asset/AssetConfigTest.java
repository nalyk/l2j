package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The Asset Service's keys (docs/spec/asset-service.md §8) and the journal keys every transaction uses
 * (fact-journal.md §8: LockWaitMillis, DeadlockRetries).
 */
class AssetConfigTest
{
	@Test
	void defaultsAreTheSpecs()
	{
		assertEquals(new AssetConfig(15, 5000, 200, 3), AssetConfig.defaults());
	}

	@Test
	void outOfRangeIsRefused()
	{
		assertThrows(IllegalArgumentException.class, () -> new AssetConfig(0, 5000, 200, 3));
		assertThrows(IllegalArgumentException.class, () -> new AssetConfig(1001, 5000, 200, 3));
		assertThrows(IllegalArgumentException.class, () -> new AssetConfig(15, 99, 200, 3));
		assertThrows(IllegalArgumentException.class, () -> new AssetConfig(15, 60001, 200, 3));
		assertThrows(IllegalArgumentException.class, () -> new AssetConfig(15, 5000, 49, 3));
		assertThrows(IllegalArgumentException.class, () -> new AssetConfig(15, 5000, 10001, 3));
		assertThrows(IllegalArgumentException.class, () -> new AssetConfig(15, 5000, 200, -1));
		assertThrows(IllegalArgumentException.class, () -> new AssetConfig(15, 5000, 200, 11));
		new AssetConfig(1, 100, 50, 0);
		new AssetConfig(1000, 60000, 10000, 10);
	}
}
