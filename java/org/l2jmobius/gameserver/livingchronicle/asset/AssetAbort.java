package org.l2jmobius.gameserver.livingchronicle.asset;

/**
 * An operation that ended without committing (docs/spec/asset-service.md §3.2 {@code ABORTED}): nothing it would have
 * written was written. Thrown by {@link AssetTx#commit()}.
 */
public final class AssetAbort extends Exception
{
	private static final long serialVersionUID = 1L;

	public enum Reason
	{
		/** A debit is no longer possible: gone, moved, or more than the holding holds. */
		INSUFFICIENT,
		/** The owner's row is not at the version the caller expected. */
		STALE_VERSION,
		/** The settlement key is already taken: settled by another operation. */
		ALREADY_SETTLED,
		/** A custody touched is not {@code OPEN}. */
		CUSTODY_NOT_OPEN,
		/** The owner module's holding predicate refuses the custody (INV05). */
		NOT_ALLOWED_TO_HOLD,
		/** The owner module is frozen (§4.2 rule 7). */
		MODULE_FROZEN,
		/** The legs do not balance, or a custody's postings would not match its rows: a bug or tampering, never a user error. */
		UNBALANCED,
		/** The connection failed before COMMIT, and the locking read found no committed row. */
		NOT_COMMITTED,
		/** A deadlock or lock-wait timeout on every attempt. */
		CONTENTION,
		/** Any other database error before COMMIT was sent. */
		ERROR
	}

	private final Reason _reason;

	public AssetAbort(Reason reason, String message)
	{
		this(reason, message, null);
	}

	public AssetAbort(Reason reason, String message, Throwable cause)
	{
		super(reason + ": " + message, cause);
		_reason = reason;
	}

	public Reason reason()
	{
		return _reason;
	}
}
