package org.l2jmobius.gameserver.livingchronicle.asset;

/**
 * A holding that is not a Mobius container ({@code lc_custody}, docs/spec/asset-service.md §2.2, §3.1): what it is for is
 * named by its owner module and reference; what it holds is its {@code ESCROW} rows in {@code items}.
 */
public record Custody(int id, Kind kind, String ownerModule, long ownerRef, State state, int version)
{
	public enum Kind
	{
		CONTRACT,
		POOL,
		MANIFEST
	}

	public enum State
	{
		OPEN,
		FROZEN,
		CLOSED
	}
}
