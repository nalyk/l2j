package org.l2jmobius.testing.agent;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Points chosen instance calls, in chosen methods of chosen classes, at static methods that take the receiver first.
 * <p>
 * The server's task managers run one task for many actors: a loop that moves every walking creature, one that makes every monster think. A test that gives each actor the random stream of the case it belongs to needs to know, for each pass of such a loop, whose turn it is. Those few calls are sent through a static method of the test's that is handed the actor, does the call, and switches what it draws on around it. The stack takes exactly what it took before (the receiver, then the arguments) and gives back the same, so nothing else in the method moves.
 * </p>
 * <p>
 * Unlike {@link MethodRedirector} this changes instructions, so it is confined to the call sites listed, and every one of them must be found: a listed call that is not there any more is an error, never a silent pass, because then that loop would quietly stop telling actors apart.
 * </p>
 */
final class ActorDispatch
{
	/** One instance call, in internal names: {@code org/l2jmobius/gameserver/entity/actor/Creature}, {@code updatePosition}, {@code ()Z}. */
	record Call(String owner, String name, String descriptor)
	{
		String reference()
		{
			return owner + "." + name + descriptor;
		}
	}

	/** The calls to send through the dispatcher in one method of one class. */
	record Site(String method, List<Call> calls)
	{
	}

	private final Map<String, Site> _sites;
	private final ClassDesc _dispatcher;

	/**
	 * @param sites by internal class name, {@code org/l2jmobius/gameserver/taskmanagers/MovementTaskManager$Movement}
	 * @param dispatcher internal name of the class whose static methods receive the calls, one per call with the receiver's type first
	 */
	ActorDispatch(Map<String, Site> sites, String dispatcher)
	{
		_sites = Map.copyOf(sites);
		_dispatcher = ClassDesc.ofInternalName(dispatcher);
	}

	/**
	 * @return whether {@code className} has calls to send through the dispatcher.
	 */
	boolean covers(String className)
	{
		return _sites.containsKey(className);
	}

	/**
	 * @param className internal name of the class in {@code classFile}
	 * @param loader where the classes its stack maps mention can be read from
	 * @return the class with its listed calls sent through the dispatcher, or {@code classFile} itself if the class is not listed.
	 * @throws IllegalStateException if a listed call is not found in its method
	 */
	byte[] apply(String className, byte[] classFile, ClassLoader loader)
	{
		final Site site = _sites.get(className);
		if (site == null)
		{
			return classFile;
		}

		final ClassFile files = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofResourceParsing(loader))));
		final ClassModel model = files.parse(classFile);
		final Set<String> found = new HashSet<>();
		final byte[] dispatched = files.transformClass(model, ClassTransform.transformingMethodBodies(method -> method.methodName().equalsString(site.method()), (code, element) ->
		{
			if ((element instanceof InvokeInstruction invoke) && (invoke.opcode() == Opcode.INVOKEVIRTUAL))
			{
				final String reference = invoke.owner().asInternalName() + "." + invoke.name().stringValue() + invoke.type().stringValue();
				for (Call call : site.calls())
				{
					if (call.reference().equals(reference))
					{
						found.add(reference);
						code.invokestatic(_dispatcher, invoke.name().stringValue(), invoke.typeSymbol().insertParameterTypes(0, invoke.owner().asSymbol()));
						return;
					}
				}
			}
			code.with(element);
		}));

		for (Call call : site.calls())
		{
			if (!found.contains(call.reference()))
			{
				throw new IllegalStateException("expected " + call.reference() + " in " + className + "." + site.method() + ", not there any more");
			}
		}
		return dispatched;
	}
}
