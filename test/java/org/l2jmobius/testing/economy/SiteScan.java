package org.l2jmobius.testing.economy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * The static site scan of AT-ASSET-12 (docs/spec/asset-service.md §2.7): every call that can change an item's existence
 * or quantity ({@code ItemManager.createItem}, {@code ItemManager.destroyItem}, {@code Item.changeCount},
 * {@code Item.setCount}, an {@code Item} constructor) and every SQL string that writes a table the census counts, read from
 * bytecode: the compiled core, and the datapack's scripts compiled here as Mobius compiles them at boot. A site is a
 * (file, member, kind): repeated calls of one kind in one member are one site, and a call inside a lambda belongs to the
 * member that wrote the lambda. Living Chronicle's own package is not scanned: its writes are the Asset Service's,
 * each one posted in the ledger.
 */
public final class SiteScan
{
	public enum Kind
	{
		CREATE_ITEM,
		DESTROY_ITEM,
		CHANGE_COUNT,
		SET_COUNT,
		NEW_ITEM,
		CENSUS_SQL
	}

	public record Site(String file, String member, Kind kind) implements Comparable<Site>
	{
		@Override
		public int compareTo(Site other)
		{
			return (file + "#" + member + "#" + kind).compareTo(other.file + "#" + other.member + "#" + other.kind);
		}
	}

	private static final String ITEM = "org/l2jmobius/gameserver/entity/item/instance/Item";
	private static final String ITEM_MANAGER = "org/l2jmobius/gameserver/managers/ItemManager";
	private static final String LIVING_CHRONICLE = "org/l2jmobius/gameserver/livingchronicle/";
	/** SQL that writes a table the census counts (§2.6): items, saved ground items, both kinds of open bid, a treasury. */
	private static final Pattern CENSUS_WRITE = Pattern.compile("(?i)\\b(DELETE\\s+FROM|UPDATE|INSERT\\s+(IGNORE\\s+)?INTO|REPLACE\\s+INTO)\\s+`?(items|itemsonground|item_auction_bid|auction_bid)`?(\\s|\\(|$)");
	private static final Pattern TREASURY_WRITE = Pattern.compile("(?i)\\bUPDATE\\s+`?castle`?\\s+SET\\b.*\\btreasury\\b");
	private static final Pattern LAMBDA = Pattern.compile("lambda\\$(.+)\\$\\d+");

	private SiteScan()
	{
	}

	/** Every site of the core and of the datapack's scripts. */
	public static Set<Site> scanAll(Path coreClasses, Path scriptsSource, Path scriptsClasses) throws IOException
	{
		final Set<Site> sites = new TreeSet<>(scanClasses(coreClasses, "java/", name -> !name.startsWith(LIVING_CHRONICLE)));
		sites.addAll(scanClasses(compileScripts(scriptsSource, scriptsClasses), "dist/game/data/scripts/", name -> true));
		return sites;
	}

	/**
	 * Compiles every script as Mobius's ScriptExecutor does (its source path, its debug options), into {@code out}.
	 * @return {@code out}
	 */
	static Path compileScripts(Path scriptsSource, Path out) throws IOException
	{
		Files.createDirectories(out);
		final List<String> sources = new ArrayList<>();
		try (Stream<Path> files = Files.walk(scriptsSource))
		{
			files.filter(f -> f.toString().endsWith(".java")).forEach(f -> sources.add(f.toString()));
		}
		final List<String> arguments = new ArrayList<>(List.of("-sourcepath", scriptsSource.toString(), "-g:source,lines,vars", "-proc:none", "-encoding", "UTF-8", "-nowarn", "-classpath", System.getProperty("java.class.path"), "-d", out.toString()));
		arguments.addAll(sources);
		final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		final StringWriter errors = new StringWriter();
		final int status = compiler.run(null, null, new java.io.OutputStream()
		{
			private final PrintWriter _sink = new PrintWriter(errors);

			@Override
			public void write(int b)
			{
				_sink.write(b);
				_sink.flush();
			}
		}, arguments.toArray(String[]::new));
		if (status != 0)
		{
			throw new IllegalStateException("the datapack's scripts did not compile:\n" + errors);
		}
		return out;
	}

	/**
	 * The sites of every class under {@code root} whose internal name {@code include} accepts.
	 * @param filePrefix the repository path of {@code root}'s package tree ({@code java/}, {@code dist/game/data/scripts/})
	 */
	public static Set<Site> scanClasses(Path root, String filePrefix, Predicate<String> include) throws IOException
	{
		final Set<Site> sites = new TreeSet<>();
		try (Stream<Path> files = Files.walk(root))
		{
			for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList())
			{
				final ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
				final String name = model.thisClass().asInternalName();
				if (!include.test(name))
				{
					continue;
				}
				final String source = model.findAttribute(Attributes.sourceFile()).map(a -> a.sourceFile().stringValue()).orElse(name.substring(name.lastIndexOf('/') + 1) + ".java");
				final String repositoryFile = filePrefix + name.substring(0, name.lastIndexOf('/') + 1) + source;
				for (MethodModel method : model.methods())
				{
					final String member = member(method.methodName().stringValue());
					method.code().ifPresent(code ->
					{
						for (CodeElement element : code)
						{
							final Kind kind = kind(name, method.methodName().stringValue(), element);
							if (kind != null)
							{
								sites.add(new Site(repositoryFile, member, kind));
							}
						}
					});
				}
			}
		}
		return sites;
	}

	private static String member(String methodName)
	{
		final Matcher lambda = LAMBDA.matcher(methodName);
		return lambda.matches() ? lambda.group(1) : methodName;
	}

	private static Kind kind(String owner, String methodName, CodeElement element)
	{
		if (element instanceof InvokeInstruction invoke)
		{
			final String target = invoke.owner().asInternalName();
			final String called = invoke.name().stringValue();
			if (target.equals(ITEM_MANAGER) && called.equals("createItem"))
			{
				return Kind.CREATE_ITEM;
			}
			if (target.equals(ITEM_MANAGER) && called.equals("destroyItem"))
			{
				return Kind.DESTROY_ITEM;
			}
			if (target.equals(ITEM) && called.equals("changeCount"))
			{
				return Kind.CHANGE_COUNT;
			}
			if (target.equals(ITEM) && called.equals("setCount"))
			{
				return Kind.SET_COUNT;
			}
			if (target.equals(ITEM) && called.equals("<init>") && !(owner.equals(ITEM) && methodName.equals("<init>")))
			{
				return Kind.NEW_ITEM; // not an Item constructor delegating to another
			}
			return null;
		}
		if ((element instanceof ConstantInstruction.LoadConstantInstruction load) && (load.constantEntry() instanceof StringEntry text) && (CENSUS_WRITE.matcher(text.stringValue()).find() || TREASURY_WRITE.matcher(text.stringValue()).find()))
		{
			return Kind.CENSUS_SQL;
		}
		return null;
	}
}
