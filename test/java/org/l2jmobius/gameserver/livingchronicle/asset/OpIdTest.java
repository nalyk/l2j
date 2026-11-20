package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Operation ids (docs/spec/asset-service.md §2.2, §4.1): deterministic over (module, subject, action, attempt), so a retry of
 * one logical operation has one id, and a refund's id is derived from its operation's.
 */
class OpIdTest
{
	@Test
	void sameInputsSameId()
	{
		assertEquals(OpId.of("PACT", 7, "FUND", 0), OpId.of("PACT", 7, "FUND", 0));
		assertEquals(OpId.of("PACT", 7, "FUND", 0).hashCode(), OpId.of("PACT", 7, "FUND", 0).hashCode());
	}

	@Test
	void anyInputChangesTheId()
	{
		final List<OpId> ids = List.of(OpId.of("PACT", 7, "FUND", 0), OpId.of("LEDGER", 7, "FUND", 0), OpId.of("PACT", 8, "FUND", 0), OpId.of("PACT", 7, "RELEASE", 0), OpId.of("PACT", 7, "FUND", 1));
		assertEquals(ids.size(), new HashSet<>(ids).size());
	}

	@Test
	void fieldsCannotRunTogether()
	{
		assertNotEquals(OpId.of("AB", 1, "C", 0), OpId.of("A", 1, "BC", 0));
	}

	@Test
	void refundIsDeterministicAndNeverTheOriginalOrAnotherRefund()
	{
		final OpId op = OpId.of("PACT", 7, "FUND", 0);
		assertEquals(OpId.refundOf(op), OpId.refundOf(op));
		assertEquals(OpId.refundOf(op), op.refund());
		final Set<OpId> distinct = Set.of(op, op.refund(), op.refund().refund());
		assertEquals(3, distinct.size());
	}

	@Test
	void bytesAreSixteenAndCopied()
	{
		final OpId op = OpId.of("PACT", 7, "FUND", 0);
		assertEquals(16, op.bytes().length);
		op.bytes()[0] ^= 1;
		assertEquals(OpId.of("PACT", 7, "FUND", 0), op, "the caller's array is a copy");
		final byte[] raw = new byte[16];
		final OpId fromRaw = new OpId(raw);
		raw[0] = 1;
		assertEquals(0, fromRaw.bytes()[0], "the constructor copies too");
		assertEquals(32, op.hex().length());
	}
}
