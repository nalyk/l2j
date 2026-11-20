package org.l2jmobius.gameserver.livingchronicle.asset;

/**
 * A commit whose result could not be learned within {@code UncertainCommitLookupMillis} (docs/spec/asset-service.md §3.2
 * {@code UNCERTAIN}): the operation may have committed. Its module is frozen; it is never treated as aborted.
 */
public final class UncertainCommitException extends RuntimeException
{
	private static final long serialVersionUID = 1L;

	public UncertainCommitException(String message, Throwable cause)
	{
		super(message, cause);
	}
}
