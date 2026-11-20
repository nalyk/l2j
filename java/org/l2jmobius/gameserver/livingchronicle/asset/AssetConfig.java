package org.l2jmobius.gameserver.livingchronicle.asset;

/**
 * The Asset Service's configuration (docs/spec/asset-service.md §8) and the two journal keys its transactions use
 * (fact-journal.md §8). Nothing here changes a game mechanic.
 * @param commitBudgetMillis {@code CommitBudgetMillis}, 1-1000
 * @param uncertainCommitLookupMillis {@code UncertainCommitLookupMillis}, 100-60000
 * @param lockWaitMillis {@code LockWaitMillis}, 50-10000
 * @param deadlockRetries {@code DeadlockRetries}, 0-10
 */
public record AssetConfig(int commitBudgetMillis, int uncertainCommitLookupMillis, int lockWaitMillis, int deadlockRetries)
{
	public AssetConfig
	{
		range("CommitBudgetMillis", commitBudgetMillis, 1, 1000);
		range("UncertainCommitLookupMillis", uncertainCommitLookupMillis, 100, 60000);
		range("LockWaitMillis", lockWaitMillis, 50, 10000);
		range("DeadlockRetries", deadlockRetries, 0, 10);
	}

	public static AssetConfig defaults()
	{
		return new AssetConfig(15, 5000, 200, 3);
	}

	private static void range(String key, int value, int min, int max)
	{
		if ((value < min) || (value > max))
		{
			throw new IllegalArgumentException(key + " = " + value + " is outside " + min + "-" + max);
		}
	}
}
