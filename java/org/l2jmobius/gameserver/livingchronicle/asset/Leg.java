package org.l2jmobius.gameserver.livingchronicle.asset;

/**
 * One ledger posting of an operation ({@code lc_ledger_posting}, docs/spec/asset-service.md §2.2): every operation's legs
 * balance per item id (INV01).
 * @param code the posting code of a SOURCE or SINK leg (§2.5), null otherwise
 * @param objectId the object of a whole-object leg, null for an amount
 */
public record Leg(AccountKind kind, long accountId, String code, int itemId, Integer objectId, Long lifecycleId, long qty, Direction direction)
{
	public enum AccountKind
	{
		CASTLE_TREASURY,
		CLAN_WAREHOUSE,
		PLAYER,
		PLAYER_WAREHOUSE,
		CUSTODY,
		MAIL,
		SINK,
		SOURCE
	}

	public enum Direction
	{
		DEBIT,
		CREDIT
	}

	public Leg
	{
		if (qty <= 0)
		{
			throw new IllegalArgumentException("a posting's quantity is positive, not " + qty);
		}
		if (((kind == AccountKind.SINK) || (kind == AccountKind.SOURCE)) != (code != null))
		{
			throw new IllegalArgumentException("a code exactly on SOURCE and SINK legs");
		}
	}
}
