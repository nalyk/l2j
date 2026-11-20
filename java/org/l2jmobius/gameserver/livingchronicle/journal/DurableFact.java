package org.l2jmobius.gameserver.livingchronicle.journal;

/**
 * A fact whose meaning depends on a state change, staged in the transaction that makes the change
 * (docs/spec/fact-journal.md §3.1). Its type is registered in {@code lc_fact_type} under {@link #typeName()}.
 */
public interface DurableFact
{
	/** The registered name, such as {@code Asset.CustodyOpened}. */
	String typeName();

	int schemaVersion();

	/** The aggregate the fact belongs to, such as {@code asset.custody.42}; at most 48 characters. */
	String stream();

	/** 0 to 3 (fact-journal.md §2). */
	int sensitivity();

	/** The payload, a JSON object. */
	String payloadJson();
}
