package org.l2jmobius.gameserver.livingchronicle.asset;

/**
 * How an operation ended for its caller (docs/spec/asset-service.md §3.2). A retry of an id that already committed, or
 * whose refund committed, returns that earlier outcome and does nothing.
 */
public enum Outcome
{
	COMMITTED,
	ALREADY_COMMITTED,
	ALREADY_REFUNDED
}
