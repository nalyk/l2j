package org.l2jmobius.testing.agent;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Points static method calls in a compiled class at other static methods, without touching a single instruction.
 * <p>
 * A call names its target through a {@code Methodref} entry in the class's constant pool. Redirecting a call means making that entry name a different class and method with the same descriptor. The bytecode, its offsets, its stack maps and every other byte of the class stay exactly as javac wrote them; only the constant pool grows by the few entries that spell the new name. Method references ({@code System::currentTimeMillis}) are redirected too, because their method handles point at the same entry.
 * </p>
 * <p>
 * It works only for what it is used for: static methods of classes, replaced by static methods with an identical descriptor. Constructors cannot be redirected this way; references the caller wants to hear about instead are reported as sightings.
 * </p>
 */
final class MethodRedirector
{
	/** Where a redirected call goes. Class names are in internal form, {@code java/lang/System}. */
	record Target(String owner, String name)
	{
	}

	/** A patched class and the watched references it still contains, each as {@code owner.name descriptor}. */
	record Result(byte[] classFile, Set<String> sightings)
	{
	}

	private static final int MAGIC = 0xCAFEBABE;
	private static final int POOL_COUNT_OFFSET = 8;
	private static final int POOL_OFFSET = 10;
	private static final int MAX_POOL_COUNT = 0xFFFF;

	private static final int TAG_UTF8 = 1;
	private static final int TAG_CLASS = 7;
	private static final int TAG_METHODREF = 10;
	private static final int TAG_INTERFACE_METHODREF = 11;
	private static final int TAG_NAME_AND_TYPE = 12;

	private final Map<String, Target> _redirects;
	private final Predicate<String> _watched;

	/**
	 * @param redirects from {@code owner.name descriptor}, e.g. {@code java/lang/System.currentTimeMillis()J}, to its replacement
	 * @param watched references to report when they are present and not redirected, in the same form
	 */
	MethodRedirector(Map<String, Target> redirects, Predicate<String> watched)
	{
		_redirects = Map.copyOf(redirects);
		_watched = watched;
	}

	/**
	 * @return the class with its calls redirected. The returned array is the argument itself when nothing had to change.
	 * @throws IllegalArgumentException if the bytes are not a class file this code understands; nothing is ever patched on a guess
	 */
	Result apply(byte[] classFile)
	{
		final ByteBuffer in = ByteBuffer.wrap(classFile);
		if ((classFile.length < POOL_OFFSET) || (in.getInt() != MAGIC))
		{
			throw new IllegalArgumentException("not a class file");
		}

		// Index the constant pool: where each entry starts and what kind it is.
		final int count = in.getShort(POOL_COUNT_OFFSET) & 0xFFFF;
		final int[] offset = new int[count];
		final int[] tag = new int[count];
		in.position(POOL_OFFSET);
		for (int i = 1; i < count; i++)
		{
			offset[i] = in.position();
			tag[i] = in.get() & 0xFF;
			switch (tag[i])
			{
				case TAG_UTF8 -> in.position(in.position() + 2 + (in.getShort(in.position()) & 0xFFFF));
				case 3, 4 -> in.position(in.position() + 4); // Integer, Float
				case 5, 6 -> // Long, Double: eight bytes and two pool slots
				{
					in.position(in.position() + 8);
					i++;
				}
				case TAG_CLASS, 8, 16, 19, 20 -> in.position(in.position() + 2); // Class, String, MethodType, Module, Package
				case 9, TAG_METHODREF, TAG_INTERFACE_METHODREF, TAG_NAME_AND_TYPE, 17, 18 -> in.position(in.position() + 4); // Fieldref, Methodref, InterfaceMethodref, NameAndType, Dynamic, InvokeDynamic
				case 15 -> in.position(in.position() + 3); // MethodHandle
				default -> throw new IllegalArgumentException("unknown constant pool tag " + tag[i] + " at entry " + i);
			}
		}
		final int poolEnd = in.position();

		final byte[] pool = new byte[poolEnd - POOL_OFFSET];
		System.arraycopy(classFile, POOL_OFFSET, pool, 0, pool.length);
		final ByteBuffer patched = ByteBuffer.wrap(pool);

		final ByteArrayOutputStream appended = new ByteArrayOutputStream();
		final Map<String, int[]> appendedTargets = new HashMap<>();
		final Set<String> sightings = new TreeSet<>();
		int nextIndex = count;

		for (int i = 1; i < count; i++)
		{
			if ((tag[i] != TAG_METHODREF) && (tag[i] != TAG_INTERFACE_METHODREF))
			{
				continue;
			}

			final int classIndex = in.getShort(offset[i] + 1) & 0xFFFF;
			final int nameAndTypeIndex = in.getShort(offset[i] + 3) & 0xFFFF;
			requireTag(tag, classIndex, TAG_CLASS);
			requireTag(tag, nameAndTypeIndex, TAG_NAME_AND_TYPE);

			final int descriptorIndex = in.getShort(offset[nameAndTypeIndex] + 3) & 0xFFFF;
			final String owner = utf8(classFile, offset, tag, in.getShort(offset[classIndex] + 1) & 0xFFFF);
			final String name = utf8(classFile, offset, tag, in.getShort(offset[nameAndTypeIndex] + 1) & 0xFFFF);
			final String reference = owner + "." + name + utf8(classFile, offset, tag, descriptorIndex);

			final Target target = tag[i] == TAG_METHODREF ? _redirects.get(reference) : null;
			if (target == null)
			{
				if (_watched.test(reference))
				{
					sightings.add(reference);
				}
				continue;
			}

			// New entries: Utf8 owner, Class, Utf8 name, NameAndType reusing the original descriptor. One set per target and descriptor.
			int[] indexes = appendedTargets.get(reference);
			if (indexes == null)
			{
				if ((nextIndex + 4) > MAX_POOL_COUNT)
				{
					throw new IllegalArgumentException("constant pool is full");
				}

				writeUtf8(appended, target.owner());
				appended.write(TAG_CLASS);
				writeShort(appended, nextIndex);
				writeUtf8(appended, target.name());
				appended.write(TAG_NAME_AND_TYPE);
				writeShort(appended, nextIndex + 2);
				writeShort(appended, descriptorIndex);

				indexes = new int[]
				{
					nextIndex + 1,
					nextIndex + 3
				};
				appendedTargets.put(reference, indexes);
				nextIndex += 4;
			}

			patched.putShort((offset[i] - POOL_OFFSET) + 1, (short) indexes[0]);
			patched.putShort((offset[i] - POOL_OFFSET) + 3, (short) indexes[1]);
		}

		if (appendedTargets.isEmpty())
		{
			return new Result(classFile, sightings);
		}

		final ByteBuffer out = ByteBuffer.allocate(classFile.length + appended.size());
		out.put(classFile, 0, POOL_COUNT_OFFSET);
		out.putShort((short) nextIndex);
		out.put(pool);
		out.put(appended.toByteArray());
		out.put(classFile, poolEnd, classFile.length - poolEnd);
		return new Result(out.array(), sightings);
	}

	private static void requireTag(int[] tag, int index, int expected)
	{
		if ((index <= 0) || (index >= tag.length) || (tag[index] != expected))
		{
			throw new IllegalArgumentException("constant pool entry " + index + " is not of kind " + expected);
		}
	}

	/**
	 * Names of classes, methods and descriptors we match on are ASCII, where the class file's modified UTF-8 and UTF-8 agree.
	 */
	private static String utf8(byte[] classFile, int[] offset, int[] tag, int index)
	{
		requireTag(tag, index, TAG_UTF8);
		final int length = ((classFile[offset[index] + 1] & 0xFF) << 8) | (classFile[offset[index] + 2] & 0xFF);
		return new String(classFile, offset[index] + 3, length, StandardCharsets.UTF_8);
	}

	private static void writeUtf8(ByteArrayOutputStream out, String value)
	{
		final byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
		out.write(TAG_UTF8);
		writeShort(out, bytes.length);
		out.writeBytes(bytes);
	}

	private static void writeShort(ByteArrayOutputStream out, int value)
	{
		out.write(value >>> 8);
		out.write(value);
	}
}
