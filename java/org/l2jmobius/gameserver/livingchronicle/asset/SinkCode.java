package org.l2jmobius.gameserver.livingchronicle.asset;

/**
 * The SINK posting codes a custody can be debited into directly (docs/spec/asset-service.md §2.5); the others need a
 * Mobius holder or belong to a composite operation.
 */
public enum SinkCode
{
	/** Road wardens' wages paid from an Open Roads law's custody (aden-remembers.md §4.5). */
	CROWN_UPKEEP,
	/** The share of a raided manifest that falls on the ground after the commit (asset-service.md §4.3 {@code raid}). */
	RAID_SHARE,
	/** The rest of a raided manifest. */
	ROAD_LOSS,
	/** A contractual craft's materials, consumed from the contract's custody (pact.md §4.3). */
	CRAFT_INPUT,
	/** Institutional fees charged to a holder (15 §6, 16 §7, 17 §2). */
	FEE_NOTARIUS,
	FEE_BROKER,
	FEE_QUESTIONING,
	FEE_ALIAS,
	FEE_GAZETTE,
	/** A bribe paid to a witness NPC (black-ledger.md §4.7). */
	FEE_BRIBE
}
