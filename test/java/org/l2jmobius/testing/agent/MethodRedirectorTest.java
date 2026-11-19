package org.l2jmobius.testing.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.agent.MethodRedirector.Target;

/**
 * {@link MethodRedirector} on a real compiled class: the patched class must load, verify, call the replacements, and differ from the original in its constant pool only.
 */
class MethodRedirectorTest
{
	/**
	 * The class that gets patched. It is compiled like any other; the test patches its bytes and loads the result in a loader of its own.
	 */
	public static final class Subject
	{
		private Subject()
		{
		}

		public static long direct()
		{
			return System.currentTimeMillis();
		}

		public static long throughAMethodReference()
		{
			final LongSupplier supplier = System::currentTimeMillis;
			return supplier.getAsLong();
		}

		public static long twiceInOneMethod()
		{
			return System.currentTimeMillis() + System.currentTimeMillis();
		}

		public static long calendar()
		{
			return Calendar.getInstance().getTimeInMillis();
		}

		public static long notRedirected()
		{
			return Math.max(3L, 7L);
		}

		public static Date constructsNow()
		{
			return new Date();
		}
	}

	/**
	 * Where the calls are sent.
	 */
	public static final class Replacement
	{
		private Replacement()
		{
		}

		public static long millis()
		{
			return 4242L;
		}

		public static Calendar calendar()
		{
			final Calendar calendar = Calendar.getInstance();
			calendar.setTimeInMillis(777L);
			return calendar;
		}
	}

	private static final String REPLACEMENT = Replacement.class.getName().replace('.', '/');

	private static final MethodRedirector REDIRECTOR = new MethodRedirector(Map.of( //
		"java/lang/System.currentTimeMillis()J", new Target(REPLACEMENT, "millis"), //
		"java/util/Calendar.getInstance()Ljava/util/Calendar;", new Target(REPLACEMENT, "calendar")), //
		"java/util/Date.<init>()V"::equals);

	private static byte[] bytesOf(Class<?> type) throws IOException
	{
		try (InputStream in = type.getResourceAsStream(type.getName().substring(type.getName().lastIndexOf('.') + 1) + ".class"))
		{
			return in.readAllBytes();
		}
	}

	/**
	 * Defines the patched bytes as a class of their own, next to the original.
	 */
	private static Class<?> load(byte[] classFile)
	{
		return new ClassLoader(MethodRedirectorTest.class.getClassLoader())
		{
			Class<?> define()
			{
				return defineClass(Subject.class.getName(), classFile, 0, classFile.length);
			}
		}.define();
	}

	private static long call(Class<?> type, String method) throws ReflectiveOperationException
	{
		return (long) type.getMethod(method).invoke(null);
	}

	@Test
	void redirectedCallsReachTheReplacement() throws Exception
	{
		final Class<?> patched = load(REDIRECTOR.apply(bytesOf(Subject.class)).classFile());

		assertEquals(4242L, call(patched, "direct"));
		assertEquals(4242L, call(patched, "throughAMethodReference"));
		assertEquals(8484L, call(patched, "twiceInOneMethod"));
		assertEquals(777L, call(patched, "calendar"));
		assertEquals(7L, call(patched, "notRedirected"));
	}

	@Test
	void theOriginalClassIsUnaffected()
	{
		assertTrue(Subject.direct() > 4242L);
		assertTrue(Subject.calendar() > 777L);
	}

	@Test
	void whatCannotBeRedirectedIsReported() throws Exception
	{
		final MethodRedirector.Result result = REDIRECTOR.apply(bytesOf(Subject.class));
		assertEquals(Set.of("java/util/Date.<init>()V"), result.sightings());

		// And it still does what it did: the constructor reads the real clock.
		final Date constructed = (Date) load(result.classFile()).getMethod("constructsNow").invoke(null);
		assertTrue(constructed.getTime() > 4242L);
	}

	@Test
	void notOneInstructionChanges() throws Exception
	{
		final byte[] original = bytesOf(Subject.class);
		final byte[] patched = REDIRECTOR.apply(original).classFile();
		assertNotSame(original, patched);

		final List<byte[]> before = codeOf(original);
		final List<byte[]> after = codeOf(patched);
		assertEquals(before.size(), after.size());
		for (int i = 0; i < before.size(); i++)
		{
			assertArrayEquals(before.get(i), after.get(i));
		}
	}

	private static List<byte[]> codeOf(byte[] classFile)
	{
		final ClassModel model = ClassFile.of().parse(classFile);
		final List<byte[]> code = new ArrayList<>();
		for (MethodModel method : model.methods())
		{
			method.findAttribute(java.lang.classfile.Attributes.code()).map(CodeAttribute::codeArray).ifPresent(code::add);
		}
		return code;
	}

	@Test
	void aClassWithNothingToRedirectIsReturnedAsIs() throws Exception
	{
		final byte[] untouched = bytesOf(Replacement.class);
		final MethodRedirector onlySystem = new MethodRedirector(Map.of("java/lang/System.currentTimeMillis()J", new Target(REPLACEMENT, "millis")), _ -> false);
		assertSame(untouched, onlySystem.apply(untouched).classFile());
	}

	@Test
	void patchingTwiceChangesNothingMore() throws Exception
	{
		final byte[] once = REDIRECTOR.apply(bytesOf(Subject.class)).classFile();
		assertSame(once, REDIRECTOR.apply(once).classFile());
	}

	@Test
	void anythingThatIsNotAnUnderstoodClassFileIsRefused() throws Exception
	{
		assertThrows(IllegalArgumentException.class, () -> REDIRECTOR.apply(new byte[0]));
		assertThrows(IllegalArgumentException.class, () -> REDIRECTOR.apply("not a class file".getBytes()));

		// A constant pool entry of a kind that does not exist.
		final byte[] corrupt = bytesOf(Subject.class);
		corrupt[10] = 2;
		assertThrows(IllegalArgumentException.class, () -> REDIRECTOR.apply(corrupt));
	}
}
