package org.l2jmobius.gameserver.livingchronicle.asset;

/**
 * A Mobius holder an operation debits (docs/spec/asset-service.md §2.1): a character's inventory or warehouse, a clan's
 * warehouse, a castle's treasury. Its {@code id} is the character, clan or castle.
 */
public record Holder(Kind kind, int id)
{
	public enum Kind
	{
		PLAYER,
		PLAYER_WAREHOUSE,
		CLAN_WAREHOUSE,
		CASTLE_TREASURY
	}

	public static Holder player(int charId)
	{
		return new Holder(Kind.PLAYER, charId);
	}

	public static Holder playerWarehouse(int charId)
	{
		return new Holder(Kind.PLAYER_WAREHOUSE, charId);
	}

	public static Holder clanWarehouse(int clanId)
	{
		return new Holder(Kind.CLAN_WAREHOUSE, clanId);
	}

	/** The ledger account of this holder. */
	Leg.AccountKind account()
	{
		return Leg.AccountKind.valueOf(kind.name());
	}

	/** Whether this holder is a character's, whose login waits on the pending mark (§2.4). */
	boolean isCharacter()
	{
		return (kind == Kind.PLAYER) || (kind == Kind.PLAYER_WAREHOUSE);
	}
}
