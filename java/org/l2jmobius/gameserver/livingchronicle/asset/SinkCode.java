package org.l2jmobius.gameserver.livingchronicle.asset;

/**
 * The SINK posting codes a custody can be debited into directly (docs/spec/asset-service.md §2.5); the others need a
 * Mobius holder or belong to a composite operation.
 */
public enum SinkCode
{
	/** Road wardens' wages paid from an Open Roads law's custody (aden-remembers.md §4.5). */
	CROWN_UPKEEP,
	/** The rest of a raided manifest. */
	ROAD_LOSS,
	/** A contractual craft's materials, consumed from the contract's custody (pact.md §4.3). */
	CRAFT_INPUT
}
