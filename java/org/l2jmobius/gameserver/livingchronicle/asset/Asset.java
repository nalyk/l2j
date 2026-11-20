package org.l2jmobius.gameserver.livingchronicle.asset;

/**
 * Assets named by an operation: a whole object ({@code objectId} set, its whole count) or an amount of a stackable item
 * ({@code objectId} null; a custody holds one row per stackable item id, docs/spec/asset-service.md §2.3).
 */
public record Asset(int itemId, long qty, Integer objectId)
{
	public Asset
	{
		if (qty <= 0)
		{
			throw new IllegalArgumentException("an asset's quantity is positive, not " + qty);
		}
	}
}
