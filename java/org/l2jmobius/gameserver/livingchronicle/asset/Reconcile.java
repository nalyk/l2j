package org.l2jmobius.gameserver.livingchronicle.asset;

/**
 * What a corrective operation does about a custody whose rows and postings disagree (INV11). Moving assets in or out
 * changes rows and postings alike, so it cannot repair a disagreement; only an explicit decision can.
 */
public enum Reconcile
{
	/** Postings and rows are left as they are; the moves of the correction only. */
	NONE,
	/**
	 * The rows as found are accepted: for each item id, an {@code ADMIN_CORRECTION} leg of the difference (SOURCE into the
	 * custody where rows exceed postings, the custody into SINK where postings exceed rows), rows untouched. To restore rows
	 * to what the postings said, recognise them and then move the excess out.
	 */
	RECOGNIZE_ROWS
}
