package org.elite.ir.infer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.elite.eval.ELProgram;
import org.elite.parser.DefaultVisitor;
import org.elite.parser.ELNode;
import org.elite.resolver.ClassResolver;
import org.elite.type.AnyType;
import org.elite.type.ArrayType;
import org.elite.type.ELiteClassType;
import org.elite.type.ErrorType;
import org.elite.type.FunctionType;
import org.elite.type.JavaClassType;
import org.elite.type.JavaConstructorType;
import org.elite.type.JavaFieldType;
import org.elite.type.JavaMethodType;
import org.elite.type.ListType;
import org.elite.type.PrimitiveType;
import org.elite.type.Type;

import static org.elite.resources.Resources.EL_CONSTRUCTOR_NOT_FOUND;
import static org.elite.resources.Resources.EL_ILLEGAL_ACCESS;
import static org.elite.resources.Resources.EL_METHOD_NOT_FOUND;
import static org.elite.resources.Resources.EL_PROPERTY_NOT_FOUND;
import static org.elite.resources.Resources._T;

/**
 * Pass 3: reflective complex nodes.
 *
 * Types Java member references and calls entirely from reflection: a bare
 * {@code System} class name becomes a JavaClassType, {@code System.out} a
 * JavaFieldType, {@code System.out.println} a JavaMethodType, and
 * {@code new java.util.ArrayList()} a JavaConstructorType. Reflective lookups
 * also report the semantic errors (missing property / illegal access /
 * missing method / missing constructor), surfaced as a ParseException by the
 * orchestrator once the convergence loop finishes.
 */
public class Pass3 extends DefaultVisitor {

  private final InferenceContext ctx;

  public Pass3(InferenceContext ctx) {
    this.ctx = ctx;
  }

  /** Traverses definitions and expressions; progress is owned by the orchestrator. */
  public void run(ELProgram program) {
    for (ELNode node : program.getDefinitions()) {
      scan(node);
    }
    for (ELNode node : program.getExpressions()) {
      scan(node);
    }
  }

  @Override
  public void visit(ELNode.APPLY e) {
    super.visit(e);

    // User-function call: the target is a FunctionType.
    if (ctx.typeOf(e.right) instanceof FunctionType ft) {
      ctx.setType(e, ft.returnType());
      return;
    }

    Type target = ctx.valueTypeOf(e.right);
    boolean positional = (e.keys == null || e.keys.length == 0);

    // (1) Method reference that resolved fully (see ACCESS): call its signature.
    if (target instanceof JavaMethodType jm) {
      // The reference captured one overload purely for display (arg-blind), so a
      // call carrying a statically-indeterminate (Any/marker) argument cannot be
      // bound to that overload's return — per the reflective spec it falls back
      // to Any rather than hard-erroring or claiming a guessed return type.
      // Concrete arguments keep the static method's return type.
      if (someArgIndeterminate(argTypes(e.args))) {
        ctx.setType(e, AnyType.ANY);
        return;
      }
      ctx.setType(e, ctx.methodReturnType(jm.method()));
      return;
    }

    // (2) obj/Cls.method(args) — the callee ACCESS names the method on a Java
    // class receiver. Resolve against the supplied argument types and type both
    // the call and (redundantly) the callee, or report a missing method.
    if (positional && e.right instanceof ELNode.ACCESS acc
        && acc.index instanceof ELNode.STRINGVAL key) {
      Type receiver = ctx.valueTypeOf(acc.right);
      if (receiver instanceof JavaClassType recvClass) {
        Method best = resolveCall(recvClass.javaClass(), key.value, argTypes(e.args));
        if (best != null) {
          ctx.setType(e, ctx.methodReturnType(best));
          // Reflect the resolved argument signature onto the callee ACCESS too,
          // so a method reference node reads e.g. println(String), not println().
          ctx.setType(acc, new JavaMethodType(best));
          return;
        }
        // Typed resolution failed (any arg Any/marker-typed, or an overload
        // tie): an uncovered reflective scenario per spec falls back to Any —
        // no static method dispatch, but no hard error either. Only when the
        // method is absent at this arity at all is a missing-method reported.
        Method arity = ctx.resolveJavaMethod(recvClass.javaClass(), key.value, e.args.length);
        if (arity != null) {
          ctx.setType(e, AnyType.ANY);
          return;
        }
        ctx.reportError(e.pos,
          _T(EL_METHOD_NOT_FOUND, recvClass.javaClass().getName(), key.value));
        ctx.setType(e, ErrorType.ERROR);
        return;
      }
    }

    // (3) Constructor call on a bare class name: File("x") form.
    if (positional && target instanceof JavaClassType jcClass
        && e.right instanceof ELNode.IDENT id && id.symbol == null) {
      Class<?> cls = jcClass.javaClass();
      Type[] at = argTypes(e.args);
      Constructor<?> c = ctx.resolveJavaConstructor(cls, at.length);
      if (c != null) {
        ctx.setType(e, new JavaConstructorType(c));
        return;
      }
      // No single unambiguous candidate at this arity (two same-arity ctors,
      // e.g. File(String) and File(URI)): when some candidate accepts the
      // argument types the call falls back to the class type (dynamic
      // dispatch); when no candidate accepts them (e.g. ArrayList(String)
      // against the int/Collection ctors) it is a genuine mismatch and stays a
      // missing-constructor error.
      if (ctx.hasJavaConstructor(cls, at.length) && someCtorAccepts(cls, at)) {
        ctx.setType(e, new JavaClassType(cls));
        return;
      }
      ctx.reportError(e.pos, _T(EL_CONSTRUCTOR_NOT_FOUND, cls.getName()));
      ctx.setType(e, ErrorType.ERROR);
      return;
    }

    // (4) Pre-refactor service model: instance method off a JavaClass or boxed
    // receiver, or a fully-qualified static form such as
    // java.lang.Integer.parseInt(...).
    Type rt = legacyServiceReturn(e);
    if (rt != null) {
      ctx.setType(e, rt);
      return;
    }
    ctx.setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.NEW e) {
    super.visit(e);
    Type annotated = ctx.resolveAnnotationType(e.base);
    if (annotated instanceof ELiteClassType) {
      ctx.setType(e, annotated);   // value of an ELite-class NEW is the class
      return;
    }
    if (annotated instanceof JavaClassType jc) {
      Type[] at = argTypes(e.args);
      Constructor<?> c = ctx.resolveJavaConstructor(jc.javaClass(), at.length);
      if (c != null) {
        ctx.setType(e, new JavaConstructorType(c));
        return;
      }
      // No single unambiguous candidate (see APPLY): dynamic-dispatch fallback
      // to the class type only when some same-arity candidate accepts the args;
      // otherwise (e.g. ArrayList(String)) it is a genuine mismatch and stays a
      // missing-constructor error.
      if (ctx.hasJavaConstructor(jc.javaClass(), at.length) && someCtorAccepts(jc.javaClass(), at)) {
        ctx.setType(e, new JavaClassType(jc.javaClass()));
        return;
      }
      ctx.reportError(e.pos, _T(EL_CONSTRUCTOR_NOT_FOUND, jc.javaClass().getName()));
      ctx.setType(e, ErrorType.ERROR);
      return;
    }
    // A primitive/boxed base (e.g. new int[n]) or an unresolvable annotation
    // that already reported EL_CLASS_NOT_FOUND: only reseed a meaningful type.
    if (annotated != AnyType.ANY && annotated != ErrorType.ERROR) {
      ctx.setType(e, annotated);
    }
  }

  @Override
  public void visit(ELNode.ACCESS e) {
    super.visit(e);
    Type target = ctx.typeOf(e.right);

    // Array/List element access is the pre-existing structural rule.
    if (target instanceof ArrayType at) {
      ctx.setType(e, at.element());
      return;
    }
    if (target instanceof ListType lt) {
      ctx.setType(e, lt.element());
      return;
    }

    // Java member access: class.field / class.methodReference.
    if (e.index instanceof ELNode.STRINGVAL key) {
      Type value = ctx.valueType(target);
      if (value instanceof JavaClassType jc) {
        handleJavaClassMember(e, jc, key.value);
        return;
      }
    }

    ctx.setType(e, AnyType.ANY);
  }

  /** Types a Java member reference, or reports a missing property. */
  private void handleJavaClassMember(ELNode.ACCESS e, JavaClassType jc, String name) {
    Field f = ctx.resolveJavaField(jc.javaClass(), name);
    if (f != null) {
      ctx.setType(e, new JavaFieldType(f));
      return;
    }
    if (ctx.hasNonPublicField(jc.javaClass(), name)) {
      ctx.reportError(e.pos, _T(EL_ILLEGAL_ACCESS, jc.javaClass().getName(), name));
      ctx.setType(e, ErrorType.ERROR);
      return;
    }
    // Still a plausible method reference (name resolves to any public method)?
    Method m = captureReference(jc.javaClass(), name);
    if (m != null) {
      ctx.setType(e, new JavaMethodType(m));
      return;
    }
    // Neither a public/private field nor any method of that name → property
    // read missing on the class.
    ctx.reportError(e.pos, _T(EL_PROPERTY_NOT_FOUND, jc.javaClass().getName(), name));
    ctx.setType(e, ErrorType.ERROR);
  }

  @Override
  public void visit(ELNode.IDENT e) {
    // Java class name used as a value: only when not a symbol-bound variable or
    // function (those belong to Pass 2) and it resolves to a compile-time class.
    if (e.symbol != null) {
      return;
    }
    try {
      Class<?> cls = ClassResolver.getInstance(ctx.elctx()).resolveClass(e.id);
      ctx.setType(e, new JavaClassType(cls));
    } catch (ClassNotFoundException ex) {
      // Not a Java class name: leave for Pass 2 (stays null / reads as Any).
    }
  }

  @Override
  public void visitNode(ELNode e) {
    // Anything not covered above stays untyped (reads as Any).
  }

  // ---------------------------------------------------------------------------
  // Reflective method-resolution helpers
  // ---------------------------------------------------------------------------

  private Type[] argTypes(ELNode[] args) {
    Type[] out = new Type[args.length];
    for (int i = 0; i < args.length; i++) {
      out[i] = args[i] == null ? AnyType.ANY : ctx.typeOf(args[i]);
    }
    return out;
  }

  /** Exact-arity signature resolution backed by the supplied static arg types. */
  private Method resolveCall(Class<?> cls, String name, Type[] argTypes) {
    return pick(methodsNamed(cls, name, argTypes.length), argTypes);
  }

  /**
   * Resolves a first-class method reference for display. The String-typed
   * one-arg overload is the conventional rendering of line/call families such
   * as PrintStream.println (System.out.println); fall back to any single-arg
   * overload, then to the zero-arg one.
   */
  private Method captureReference(Class<?> cls, String name) {
    for (Method cand : methodsNamed(cls, name, 1)) {
      if (cand.getParameterTypes()[0].equals(String.class)) return cand;
    }
    Method anyOne = firstOrNull(methodsNamed(cls, name, 1));
    if (anyOne != null) return anyOne;
    return firstOrNull(methodsNamed(cls, name, 0));
  }

  private static Method firstOrNull(List<Method> ms) {
    return ms.isEmpty() ? null : ms.get(0);
  }

  private List<Method> methodsNamed(Class<?> cls, String name, int arity) {
    List<Method> out = new ArrayList<>();
    for (Method m : cls.getMethods()) {
      if (Modifier.isPublic(m.getModifiers())
          && m.getName().equals(name) && m.getParameterCount() == arity) {
        out.add(m);
      }
    }
    return out;
  }

  /** Exact match preferred; else the unique assignable one; null on a tie. */
  private Method pick(List<Method> cands, Type[] argTypes) {
    Method exact = null;
    Method assignable = null;
    for (Method m : cands) {
      Class<?>[] params = m.getParameterTypes();
      boolean allExact = true;
      boolean allAssign = true;
      for (int i = 0; i < params.length; i++) {
        Class<?> a = argJavaClass(argTypes[i]);
        if (a == null) {
          allExact = false;
          allAssign = false;
          break;
        }
        if (!params[i].equals(a)) {
          allExact = false;
          if (!accepts(params[i], a)) {
            allAssign = false;
            break;
          }
        }
      }
      if (allExact) {
        if (exact != null) return null;      // >1 exact → ambiguous
        exact = m;
      } else if (allAssign) {
        if (assignable != null) return null; // >1 assignable tie
        assignable = m;
      }
    }
    return exact != null ? exact : assignable;
  }

  private Class<?> argJavaClass(Type t) {
    if (t instanceof PrimitiveType pt) return pt.javaClass();
    if (t instanceof JavaClassType jc) return jc.javaClass();
    return null;
  }

  /** True when any argument type has no concrete Java class (Any / promotion
   *  markers / untyped), so the reflective method/ctor result is indeterminate. */
  private boolean someArgIndeterminate(Type[] argTypes) {
    for (Type t : argTypes) {
      if (argJavaClass(t) == null) {
        return true;
      }
    }
    return false;
  }

  /** True when some public, non-varargs, same-arity constructor accepts the
   *  argument types. Any/marker-typed args are treated as possibly-accepting so
   *  an untyped reflective call never hard-errors on a constructor ambiguity. */
  private boolean someCtorAccepts(Class<?> cls, Type[] argTypes) {
    for (Constructor<?> c : cls.getConstructors()) {
      if (c.isVarArgs()) continue;
      if (c.getParameterCount() != argTypes.length) continue;
      Class<?>[] params = c.getParameterTypes();
      boolean allAccept = true;
      for (int i = 0; i < params.length; i++) {
        Class<?> a = argJavaClass(argTypes[i]);
        if (a == null) continue;   // Any / promotion marker — assume it might
        if (!accepts(params[i], a)) {
          allAccept = false;
          break;
        }
      }
      if (allAccept) return true;
    }
    return false;
  }

  private boolean accepts(Class<?> param, Class<?> arg) {
    return param.isAssignableFrom(arg);
  }

  /** The pre-refactor service fallback. Returns null when it does not apply. */
  private Type legacyServiceReturn(ELNode.APPLY e) {
    if (e.keys != null && e.keys.length != 0) return null;
    String method = null;
    Type receiver = ctx.typeOf(e.right);
    if (e.right instanceof ELNode.ACCESS acc) {
      receiver = ctx.typeOf(acc.right);
      method = acc.index instanceof ELNode.STRINGVAL s ? s.value
        : acc.index instanceof ELNode.IDENT id ? id.id : null;
    }
    Class<?> cls = receiver instanceof JavaClassType jc ? jc.javaClass()
      : receiver instanceof PrimitiveType pt && pt.javaClass() != null
          ? pt.javaClass() : null;
    if (cls != null && method != null) {
      Type rt = ctx.methodReturnType(cls, method, e.args.length);
      if (rt != null) return rt;
    }
    return ctx.staticAccessReturnType(e.right, e.args.length);
  }
}
