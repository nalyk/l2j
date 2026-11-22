package org.l2jmobius.gameserver.livingchronicle.asset;

/**
 * One drop of a kill routed by {@code route} (docs/spec/asset-service.md §4.3; pact.md §4.5): to a receiver by service
 * mail, or, with {@code receiverCharId} 0, into the charter's custody.
 * @param itemId the item id retail rolled
 * @param count the count retail rolled
 * @param receiverCharId the receiving character, or 0 for the custody
 */
public record RoutedDrop(int itemId, long count, int receiverCharId)
{
	public RoutedDrop
	{
		if (count <= 0)
		{
			throw new IllegalArgumentException("a drop has a positive count, not " + count);
		}
	}
}
