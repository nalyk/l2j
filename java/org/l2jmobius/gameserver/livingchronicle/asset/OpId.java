package org.l2jmobius.gameserver.livingchronicle.asset;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * The id of one asset operation (docs/spec/asset-service.md §2.2, §4.1): 128 bits, deterministic over (module, subject,
 * action, attempt), so a retry of the same logical operation after an uncertain commit has the same id and can never apply
 * twice. A refund is an operation of its own whose id is derived from the aborted operation's.
 */
public record OpId(byte[] bytes)
{
	private static final byte OPERATION = 0x01;
	private static final byte REFUND = 0x02;

	public OpId
	{
		if (bytes.length != 16)
		{
			throw new IllegalArgumentException("an operation id is 16 bytes, not " + bytes.length);
		}
		bytes = bytes.clone();
	}

	public static OpId of(String module, long subject, String action, int attempt)
	{
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(buffer))
		{
			out.writeByte(OPERATION);
			out.writeUTF(module); // length-prefixed, so fields cannot run together
			out.writeLong(subject);
			out.writeUTF(action);
			out.writeInt(attempt);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		return new OpId(digest(buffer.toByteArray()));
	}

	public static OpId refundOf(OpId op)
	{
		final byte[] input = new byte[17];
		input[0] = REFUND;
		System.arraycopy(op.bytes, 0, input, 1, 16);
		return new OpId(digest(input));
	}

	public OpId refund()
	{
		return refundOf(this);
	}

	@Override
	public byte[] bytes()
	{
		return bytes.clone();
	}

	public String hex()
	{
		return HexFormat.of().formatHex(bytes);
	}

	@Override
	public boolean equals(Object other)
	{
		return (other instanceof OpId id) && Arrays.equals(bytes, id.bytes);
	}

	@Override
	public int hashCode()
	{
		return Arrays.hashCode(bytes);
	}

	@Override
	public String toString()
	{
		return "OpId[" + hex() + "]";
	}

	private static byte[] digest(byte[] input)
	{
		try
		{
			return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(input), 16);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 is required of every JDK", e);
		}
	}
}
