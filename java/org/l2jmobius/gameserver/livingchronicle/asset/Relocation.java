package org.l2jmobius.gameserver.livingchronicle.asset;

/**
 * One drop of a kill relocated by {@code relocate} (docs/spec/asset-service.md §4.3; aden-remembers.md §4.3.1): into the
 * Guild warehouse custody of the town decided for its family.
 * @param itemId the item id retail rolled
 * @param count the count retail rolled
 * @param custody the destination warehouse custody
 */
public record Relocation(int itemId, long count, int custody)
{
	public Relocation
	{
		if (count <= 0)
		{
			throw new IllegalArgumentException("a drop has a positive count, not " + count);
		}
	}
}
