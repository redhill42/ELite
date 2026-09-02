package org.elite.ir.infer;

import java.math.BigInteger;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import javax.el.ELContext;
import org.elite.eval.ELProgram;
import org.elite.eval.TypeCoercion;
import org.elite.eval.closure.MethodClosure;
import org.elite.parser.DefaultVisitor;
import org.elite.parser.ELNode;
import org.elite.ir.SymbolTable;
import org.elite.resolver.ClassResolver;
import org.elite.resolver.MethodResolver;
import org.elite.type.AnyType;
import org.elite.type.ELiteClassType;
import org.elite.type.ErrorType;
import org.elite.type.JavaClassType;
import org.elite.type.JavaConstructorType;
import org.elite.type.JavaFieldType;
import org.elite.type.JavaMethodType;
import org.elite.type.PrimitiveType;
import org.elite.type.Type;

import static org.elite.resources.Resources.*;

/**
 * Shared state and helpers for the sub-pass type-inference pipeline.
 *
 * Carries every mutex between Pass 1/2/3 (variable / assign / define / for-var
 * maps, the elite-class index, recorded errors) together with the type-level
 * helpers (merge/normalize/numeric and Java-class loading) each pass relies on.
 * The orchestrator owns the convergence progress flag via
 * {@link #resetProgress()} / {@link #takeProgress()}.
 */
public final class InferenceContext {

  /** Alias names for boxed classes used in type annotations. */
  private static final Map<String, String> ALIASES = Map.of(
    "Int", "java.lang.Integer",
    "Char", "java.lang.Character",
    "BigInt", "java.math.BigInteger");

  private final ELContext elctx;
  private final String filename;
  private final Map<String, SymbolTable.Symbol> eliteClasses = new HashMap<>();
  private final java.util.List<org.elite.parser.ParseError> errors =
    new java.util.ArrayList<>();

  private boolean progress;

  final Map<SymbolTable.Symbol, java.util.List<Type>> assigns = new HashMap<>();
  final Map<SymbolTable.Symbol, Type> varTypes = new HashMap<>();
  final Map<String, ELNode.DEFINE> topDefs = new HashMap<>();
  final Map<String, Type> forVars = new HashMap<>();

  public InferenceContext(ELContext elctx, ELProgram program) {
    this.elctx = elctx;
    this.filename = program.getFilename();
  }

  // ---- public entry (orchestrator) ----

  /** Propagates the collected assign right-hand types. */
  public void recomputeVarTypes() {
    for (Map.Entry<SymbolTable.Symbol, java.util.List<Type>> e : assigns.entrySet()) {
      // Fold in each collected right-hand type, tracking whether any pair of
      // distinct concrete types ever conflicts (which forces Any). Conflict
      // detection is order-insensitive: "x = 1; x = \"s\"; x = 2" must be Any
      // whether or not the Int assigns are adjacent.
      Type merged = AnyType.ANY;
      boolean first = true;
      boolean conflicted = false;
      for (Type t : e.getValue()) {
        if (first) {
          merged = t;
          first = false;
          continue;
        }
        Type m = merge(merged, t);
        // Only a real conflict if both operands were non-Any, different
        // concrete types that degrade to Any. merge(Any, X) returning Any is
        // just Any-as-identity and is NOT a conflict.
        if (m == AnyType.ANY && merged != AnyType.ANY &&
            t != AnyType.ANY && !merged.equals(t)) {
          conflicted = true;
        }
        merged = m;
      }
      Type result = conflicted ? AnyType.ANY : merged;
      Type old = varTypes.get(e.getKey());
      if (old == null || !old.equals(result)) {
        varTypes.put(e.getKey(), result);
        progress = true;
      }
    }
  }

  public void registerEliteClasses(ELProgram program) {
    DefaultVisitor collector = new DefaultVisitor() {
      @Override
      public void visit(ELNode.DEFINE e) {
        if (e.symbol != null && e.symbol.clazz != null) {
          eliteClasses.put(e.symbol.name, e.symbol);
        }
        if (e.symbol != null && e.id != null) {
          topDefs.put(e.id, e);
        }
        super.visit(e);
      }
    };
    for (ELNode node : program.getDefinitions()) {
      collector.scan(node);
    }
    for (ELNode node : program.getExpressions()) {
      collector.scan(node);
    }
  }

  public void failIfErrors() throws org.elite.parser.ParseException {
    if (!errors.isEmpty()) {
      throw new org.elite.parser.ParseException(filename, errors);
    }
  }

  public ELContext elctx() { return elctx; }

  public String filename() { return filename; }

  // ---- progress (owned by the orchestrator) ----

  public void resetProgress() { progress = false; }

  public boolean takeProgress() { return progress; }

  // ---- typing API ----

  public final void setType(ELNode node, Type type) {
    if (node.inferredType == null || !node.inferredType.equals(type)) {
      node.inferredType = type;
      progress = true;
    }
  }

  public final Type typeOf(ELNode node) {
    return node != null && node.inferredType != null
      ? node.inferredType : AnyType.ANY;
  }

  public boolean hasType(ELNode node) {
    return node != null && node.inferredType != null;
  }

  /**
   * The Java class a value of this node would evaluate to at runtime — the
   * receiver for further member access. A Java constructor's value is its
   * declaring class; a Java field's value is the field's type. Anything else
   * keeps its own type (so a JavaMethod reference stays a JavaMethodType).
   */
  public Type valueType(Type t) {
    if (t instanceof JavaConstructorType jc) {
      return typeOfJavaClass(jc.declaringClass());
    }
    if (t instanceof JavaFieldType jf) {
      return typeOfJavaClass(jf.field().getType());
    }
    return t;
  }

  /** Type of a reflected Java class: the boxed/primitive PrimitiveType when
   *  TypeCoercion knows it, otherwise a JavaClassType of the given class. */
  public Type typeOfJavaClass(Class<?> c) {
    if (TypeCoercion.typeof(c) != TypeCoercion.UNKNOWN_TYPE) {
      return PrimitiveType.of(c);
    }
    return new JavaClassType(c);
  }

  public Type valueTypeOf(ELNode node) {
    return valueType(typeOf(node));
  }

  protected final Type typeOfValue(Object value) {
    if (value == null) return AnyType.ANY;
    Class<?> cls = value.getClass();
    if (TypeCoercion.typeof(cls) != TypeCoercion.UNKNOWN_TYPE) {
      return PrimitiveType.of(cls);
    }
    return new JavaClassType(cls);
  }

  // ---- error reporting (mirrors IRBuilder.reportError / failIfErrors) ----

  void reportError(int pos, String message) {
    errors.add(new org.elite.parser.ParseError(pos, message, null));
    if (errors.size() >= 20) {
      failIfErrors();
    }
  }

  // ---- type helpers (numeric widening / merge) ----

  /**
   * Merge two types: errors absorb, Any is the identity (so [1,2,3] builds
   * List[Int] from NIL's List[Any]), equal types merge to themselves,
   * numeric pairs normalize (Task 6), anything else degrades to Any.
   */
  Type merge(Type a, Type b) {
    if (a == ErrorType.ERROR || b == ErrorType.ERROR) return ErrorType.ERROR;
    if (a.equals(b)) return a;
    if (a == AnyType.ANY) return b;
    if (b == AnyType.ANY) return a;
    if (isNumeric(a) && isNumeric(b)) return normalize(a, b);
    return AnyType.ANY;
  }

  /** Widening rank per TypeCoercion's numeric distance order. */
  private static final java.util.List<String> NUMERIC_ORDER = java.util.List.of(
    "Byte", "Short", "Char", "Int", "Long", "Float", "Double",
    "Decimal", "Rational", "BigInt", "BigDecimal");

  private int rank(PrimitiveType pt) {
    int i = NUMERIC_ORDER.indexOf(pt.toString());
    return i >= 0 ? i : -1;
  }

  boolean isIntFamily(Type t) {
    if (t == PrimitiveType.ANY_INT) return true;
    if (t instanceof PrimitiveType pt && pt.javaClass() != null) {
      return pt.javaClass() == Byte.class || pt.javaClass() == Short.class ||
             pt.javaClass() == Character.class ||
             pt.javaClass() == Integer.class || pt.javaClass() == Long.class ||
             pt.javaClass() == java.math.BigInteger.class;
    }
    return false;
  }

  boolean isFloatFamily(Type t) {
    if (t == PrimitiveType.ANY_FLOAT) return true;
    if (t instanceof PrimitiveType pt && pt.javaClass() != null) {
      return pt.javaClass() == Float.class || pt.javaClass() == Double.class ||
             pt.javaClass() == java.math.BigDecimal.class;
    }
    return false;
  }

  /** Normalizes two concrete numeric types to the wider one per the
   *  TypeCoercion distance order; ANY_INT/ANY_FLOAT follow family rules. */
  private Type normalize(Type a, Type b) {
    if (a == PrimitiveType.ANY_INT && isIntFamily(b)) return PrimitiveType.ANY_INT;
    if (b == PrimitiveType.ANY_INT && isIntFamily(a)) return PrimitiveType.ANY_INT;
    if (a == PrimitiveType.ANY_FLOAT || b == PrimitiveType.ANY_FLOAT) {
      return PrimitiveType.ANY_FLOAT;
    }
    if (isFloatFamily(a) && (isFloatFamily(b) || isIntFamily(b))) return a;
    if (isFloatFamily(b) && (isFloatFamily(a) || isIntFamily(a))) return b;
    int ra = rank((PrimitiveType)a);
    int rb = rank((PrimitiveType)b);
    if (ra < 0 || rb < 0) return AnyType.ANY;
    return ra >= rb ? a : b;
  }

  /** Types an arithmetic-like binary node with promotion semantics. */
  Type numericResult(ELNode.Binary e, Type lt, Type rt) {
    if (lt == ErrorType.ERROR || rt == ErrorType.ERROR) {
      return ErrorType.ERROR;
    }
    if (lt.toString().equals("String") || rt.toString().equals("String")) {
      return PrimitiveType.of(String.class);
    }
    if (!isNumeric(lt) || !isNumeric(rt)) return AnyType.ANY;

    // Int-family arithmetic promotes (may overflow at runtime). A concrete
    // operand wider than plain Int (Long / BigInteger) pins the result to that
    // concrete type instead of the AnyInt marker: Int + Long -> Long, Int +
    // BigInt -> BigInt. Unannotated Int-level arithmetic is the AnyInt chain.
    if (isIntFamily(lt) && isIntFamily(rt)) {
      Type wider = normalize(lt, rt);   // the wider concrete int-family type
      String n = wider.toString();
      if (n.equals("BigInt") || n.equals("Long")) {
        return wider;   // a pinned Long / BigInt operand dominates
      }
      return PrimitiveType.ANY_INT;
    }
    if (isFloatFamily(lt) && isFloatFamily(rt)) {
      if (isConcreteBigDecimal(lt) && isConcreteBigDecimal(rt)) {
        return lt;
      }
      return PrimitiveType.ANY_FLOAT;
    }
    // A float operand dominates an int operand: BigDecimal stays concrete;
    // a concrete BigInt operand keeps the float side as-is (BigInt + Double
    // -> Double, BigInt + Float -> Float, BigInt + AnyFloat -> AnyFloat);
    // otherwise (Int/Long/AnyInt + float) the result is the AnyFloat marker.
    if (isFloatFamily(lt) && isIntFamily(rt)) {
      return floatSideResult(lt, rt);
    }
    if (isIntFamily(lt) && isFloatFamily(rt)) {
      return floatSideResult(rt, lt);
    }
    // Decimal / Rational and any other numeric: widen by distance order.
    return normalize(lt, rt);
  }

  private Type floatSideResult(Type floatSide, Type intSide) {
    if (isConcreteBigDecimal(floatSide)) return floatSide;
    if (isConcreteBigInt(intSide)) return floatSide;
    return PrimitiveType.ANY_FLOAT;
  }

  private boolean isConcreteBigInt(Type t) {
    return t instanceof PrimitiveType pt &&
           pt.javaClass() == java.math.BigInteger.class;
  }

  private boolean isConcreteBigDecimal(Type t) {
    return t != null
      && t.toString().equals("BigDecimal");
  }

  private boolean isAnyMarker(Type t) {
    return t == PrimitiveType.ANY_INT || t == PrimitiveType.ANY_FLOAT;
  }

  private boolean isNumeric(Type t) {
    if (t == PrimitiveType.ANY_INT || t == PrimitiveType.ANY_FLOAT) return true;
    if (t instanceof PrimitiveType pt && pt.javaClass() != null) {
      return Number.class.isAssignableFrom(pt.javaClass()) ||
             pt.javaClass() == Character.class;
    }
    return false;
  }

  /**
   * Whether a value of type actual may be stored into a variable of the
   * annotated type expected. Cannot judge Any / promotion markers /
   * errors; both-numeric is allowed here and refined (widening check)
   * in the numeric task.
   */
  boolean compatible(Type expected, Type actual) {
    if (expected == AnyType.ANY || expected == ErrorType.ERROR) {
      return true;   // an Any/Error-typed slot accepts anything; never worsen
    }
    if (actual == AnyType.ANY || actual == PrimitiveType.ANY_INT ||
        actual == PrimitiveType.ANY_FLOAT || actual == ErrorType.ERROR) {
      return true;   // cannot judge
    }
    if (expected.equals(actual)) {
      return true;
    }
    // Numeric assignment is allowed only when the value widens to the
    // annotated type (e.g. Int -> Long ok, Double -> Int rejected).
    if (isNumeric(expected) && isNumeric(actual)) {
      return normalize(actual, expected).equals(expected);
    }
    return false;
  }

  /**
   * Current inferred type of a variable symbol. When the symbol itself has no
   * recorded type but another symbol of the same name does (the annotation
   * declaration and a use can arrive as distinct symbol objects), this scans
   * EVERY symbol across ALL scopes — any symbol sharing the name resolves the
   * type, in HashMap iteration order. Known v1 limitation: this is scope-blind
   * by design, so same-name collisions across different scopes may mis-type.
   * Best-effort only; the consumer that reaches this fallback merely displays
   * a type annotation.
   */
  Type varType(SymbolTable.Symbol s) {
    Type t = varTypes.get(s);
    if (t != null) return t;
    final String name = s.name;
    for (Map.Entry<SymbolTable.Symbol, Type> e : varTypes.entrySet()) {
      if (e.getKey().name.equals(name)) return e.getValue();
    }
    return null;
  }

  /** Collects every RETURN node under the given subtree. */
  static void collectReturns(ELNode node, java.util.List<ELNode.RETURN> out) {
    if (node == null) return;
    if (node instanceof ELNode.RETURN r) {
      out.add(r);
      return;
    }
    if (node instanceof ELNode.LAMBDA) {
      return;   // do not descend into nested lambdas
    }
    new DefaultVisitor() {
      @Override
      public void visit(ELNode.RETURN r) {
        out.add(r);
      }

      @Override
      public void visit(ELNode.LAMBDA l) {
        // skip nested lambdas
      }
    }.scan(node);
  }

  // ---- Java class loading / annotation resolution ----

  /**
   * Copies of IRBuilder's private class-loading helpers (IRBuilder.java:1283
   * loadJavaClass / resolveJavaClass / loadClassAtCompileTime). Loads are
   * required because Java classes must be compile-time visible; a failed
   * load is an error: report it and return null (the caller types the node
   * ErrorType). TODO: refactor into a shared helper later.
   */
  private Class<?> loadJavaClass(ELNode base) {
    return resolveJavaClass(base, true);
  }

  private Class<?> resolveJavaClass(ELNode node, boolean load) {
    if (node instanceof ELNode.IDENT var && var.symbol != null &&
        var.symbol.def.expr instanceof ELNode.CLASS c) {
      return loadClassAtCompileTime(node.pos, c.getClassName());
    }

    StringBuilder buf = new StringBuilder();
    while (node instanceof ELNode.ACCESS acc) {
      if (!(acc.index instanceof ELNode.STRINGVAL str))
        return null;
      buf.insert(0, str.value);
      buf.insert(0, '.');
      node = acc.right;
    }

    if (node instanceof ELNode.IDENT var) {
      buf.insert(0, var.id);
      if (var.symbol == null) {
        return loadClassAtCompileTime(node.pos, buf.toString());
      }
    }
    return null;
  }

  private Class<?> loadClassAtCompileTime(int pos, String name) {
    try {
      return ClassResolver.getInstance(elctx).resolveClass(name);
    } catch (ClassNotFoundException ex) {
      reportError(pos, _T(EL_CLASS_NOT_FOUND, name));
      return null;
    }
  }

  /** Resolves a ::Type annotation node (IDENT/ACCESS chain). */
  Type resolveAnnotationType(ELNode typeNode) {
    if (typeNode == null) return AnyType.ANY;

    // 1. ELite class? (simple-name annotations only in v1)
    if (typeNode instanceof ELNode.IDENT id) {
      SymbolTable.Symbol sym = eliteClasses.get(id.id);
      if (sym != null) {
        return new ELiteClassType(sym);
      }

      // 2. Alias?
      String alias = ALIASES.get(id.id);
      if (alias != null) {
        try {
          return PrimitiveType.of(Class.forName(alias));
        } catch (ClassNotFoundException e) {
          return ErrorType.ERROR;
        }
      }
    }

    // 3. Java class — must be compile-time visible; failure is an error.
    try {
      Class<?> cls = loadJavaClass(typeNode);
      if (cls == null) return ErrorType.ERROR;
      if (TypeCoercion.typeof(cls) != TypeCoercion.UNKNOWN_TYPE) {
        return PrimitiveType.of(cls);
      }
      return new JavaClassType(cls);
    } catch (RuntimeException e) {
      return ErrorType.ERROR;
    }
  }

  Type numberType(Number value) {
    if (value instanceof BigInteger bi) {
      return PrimitiveType.of(BigInteger.class);
    }
    if (value instanceof java.math.BigDecimal bd) {
      return PrimitiveType.of(java.math.BigDecimal.class);
    }
    if (value instanceof Float || value instanceof Double) {
      return PrimitiveType.of(Double.class);
    }
    long v = value.longValue();
    if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
      return PrimitiveType.of(Integer.class);
    }
    return PrimitiveType.of(Long.class);
  }

  /** Reflectively finds the return type of the named method; null if ambiguous. */
  Type methodReturnType(Class<?> cls, String name, int argCount) {
    java.lang.reflect.Method match = null;
    for (java.lang.reflect.Method m : cls.getMethods()) {
      if (m.getName().equals(name) && m.getParameterCount() == argCount) {
        if (match != null) {
          return null;   // ambiguous overload — give up
        }
        match = m;
      }
    }
    if (match == null) return null;
    return methodReturnType(match);
  }

  /** Type a resolved reflective Method: void → the void PrimitiveType, known
   *  boxed/primitive returns → their PrimitiveType, otherwise JavaClassType. */
  Type methodReturnType(Method m) {
    Class<?> rt = m.getReturnType();
    if (rt == void.class || rt == Void.class) {
      return PrimitiveType.of(void.class);
    }
    return typeOfJavaClass(rt);
  }

  // ---- reflective Java member resolution (Task 4) ----

  /**
   * Resolves a named public Java method (static first, then instance)
   * on the given class with exact arity; null when absent or ambiguous.
   */
  public Method resolveJavaMethod(Class<?> cls, String name, int arity) {
    MethodClosure mc = MethodResolver.getInstance(elctx).resolveStaticMethod(cls, name);
    if (mc == null) {
      mc = MethodResolver.getInstance(elctx).resolveMethod(cls, name);
    }
    if (mc == null) return null;
    return mc.getJavaMethod(arity);
  }

  /** Resolves a public Java field by name; null when absent or non-public. */
  public Field resolveJavaField(Class<?> cls, String name) {
    try {
      Field f = cls.getField(name);
      return Modifier.isPublic(f.getModifiers()) ? f : null;
    } catch (NoSuchFieldException e) {
      return null;
    }
  }

  /** True when a declared field exists but is not public. */
  public boolean hasNonPublicField(Class<?> cls, String name) {
    try {
      cls.getDeclaredField(name);
      return true;
    } catch (NoSuchFieldException e) {
      return false;
    }
  }

  /**
   * Resolves a public non-varargs constructor with exact arity; null when
   * absent or ambiguous (multiple candidates).
   */
  public Constructor<?> resolveJavaConstructor(Class<?> cls, int arity) {
    Constructor<?> match = null;
    for (Constructor<?> c : cls.getConstructors()) {
      if (!c.isVarArgs() && c.getParameterCount() == arity) {
        if (match != null) return null;   // ambiguous
        match = c;
      }
    }
    return match;
  }

  /**
   * Resolves a whole-dotted member reference reached only through the Java
   * classpath, e.g. {@code java.lang.Integer.parseInt("1")}. Returns the
   * method return type or null when the shape is not a Java static call.
   */
  Type staticAccessReturnType(ELNode root, int argCount) {
    if (!(root instanceof ELNode.ACCESS)) return null;
    java.util.List<String> parts = new java.util.ArrayList<>();
    ELNode cur = root;
    while (cur instanceof ELNode.ACCESS acc) {
      String seg = acc.index instanceof ELNode.STRINGVAL s
        ? s.value : acc.index instanceof ELNode.IDENT id ? id.id : null;
      if (seg == null) return null;
      parts.add(seg);
      cur = acc.right;
    }
    if (cur instanceof ELNode.IDENT id) {
      parts.add(id.id);
    } else {
      return null;   // receiver-based call, handled above
    }
    java.util.Collections.reverse(parts);   // leaf package name first ...
    if (parts.size() < 2) return null;      // ... method last
    String method = parts.remove(parts.size() - 1);
    String className = String.join(".", parts);
    Class<?> cls;
    try {
      cls = ClassResolver.getInstance(elctx).resolveClass(className);
    } catch (RuntimeException | ClassNotFoundException ex) {
      return null;
    }
    return methodReturnType(cls, method, argCount);
  }
}
