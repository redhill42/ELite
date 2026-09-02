package org.elite.ir;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import javax.el.ELContext;
import org.elite.eval.ELProgram;
import org.elite.eval.TypeCoercion;
import org.elite.parser.DefaultVisitor;
import org.elite.parser.ELNode;
import org.elite.resolver.ClassResolver;
import org.elite.type.ArrayType;
import org.elite.type.AnyType;
import org.elite.type.ELiteClassType;
import org.elite.type.ErrorType;
import org.elite.type.FunctionType;
import org.elite.type.JavaClassType;
import org.elite.type.ListType;
import org.elite.type.MapType;
import org.elite.type.PrimitiveType;
import org.elite.type.TupleType;
import org.elite.type.Type;

import static org.elite.resources.Resources.*;

/**
 * Static type inference over the ELite AST.
 *
 * Runs after {@code SymbolTableBuilder} and annotates every
 * {@code ELNode.inferredType} in multiple passes until convergence.
 * Inference is best-effort: anything that cannot be typed becomes
 * {@code AnyType.ANY}. Type errors (unloadable annotation classes,
 * annotation mismatches) are recorded and reported at the end via
 * {@code ParseException}, and the offending nodes are typed
 * {@code ErrorType.ERROR}.
 */
public class TypeInference extends DefaultVisitor {

  /** Pass limit: recursion/cycles settle as Any well before this. */
  private static final int MAX_PASSES = 20;

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

  private boolean changed;

  private final Map<SymbolTable.Symbol, java.util.List<Type>> assigns = new HashMap<>();
  private final Map<SymbolTable.Symbol, Type> varTypes = new HashMap<>();
  private final Map<String, ELNode.DEFINE> topDefs = new HashMap<>();
  private final Map<String, Type> forVars = new HashMap<>();

  private TypeInference(ELContext elctx, ELProgram program) {
    this.elctx = elctx;
    this.filename = program.getFilename();
  }

  public static void infer(ELContext elctx, ELProgram program)
    throws org.elite.parser.ParseException
  {
    TypeInference infer = new TypeInference(elctx, program);
    infer.registerEliteClasses(program);

    int passes = 0;
    do {
      infer.changed = false;
      for (ELNode node : program.getDefinitions()) {
        infer.scan(node);
      }
      for (ELNode node : program.getExpressions()) {
        infer.scan(node);
      }
      infer.recomputeVarTypes();
    } while (infer.changed && ++passes < MAX_PASSES);

    infer.failIfErrors();
  }

  // ---- error reporting (mirrors IRBuilder.reportError / failIfErrors) ----

  private void reportError(int pos, String message) {
    errors.add(new org.elite.parser.ParseError(pos, message, null));
    if (errors.size() >= 20) {
      failIfErrors();
    }
  }

  private void failIfErrors() throws org.elite.parser.ParseException {
    if (!errors.isEmpty()) {
      throw new org.elite.parser.ParseException(filename, errors);
    }
  }

  /** First pass: collect all ELite classes so annotations can resolve. */
  private void registerEliteClasses(ELProgram program) {
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

  // ---- helpers ----

  protected final void setType(ELNode node, Type type) {
    if (node.inferredType == null || !node.inferredType.equals(type)) {
      node.inferredType = type;
      changed = true;
    }
  }

  protected final Type typeOf(ELNode node) {
    return node != null && node.inferredType != null
      ? node.inferredType : AnyType.ANY;
  }

  protected final Type typeOfValue(Object value) {
    if (value == null) return AnyType.ANY;
    Class<?> cls = value.getClass();
    if (TypeCoercion.typeof(cls) != TypeCoercion.UNKNOWN_TYPE) {
      return PrimitiveType.of(cls);
    }
    return new JavaClassType(cls);
  }

  /**
   * Merge two types: errors absorb, Any is the identity (so [1,2,3] builds
   * List[Int] from NIL's List[Any]), equal types merge to themselves,
   * numeric pairs normalize (Task 6), anything else degrades to Any.
   */
  protected Type merge(Type a, Type b) {
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

  private boolean isIntFamily(Type t) {
    if (t == PrimitiveType.ANY_INT) return true;
    if (t instanceof PrimitiveType pt && pt.javaClass() != null) {
      return pt.javaClass() == Byte.class || pt.javaClass() == Short.class ||
             pt.javaClass() == Character.class ||
             pt.javaClass() == Integer.class || pt.javaClass() == Long.class ||
             pt.javaClass() == java.math.BigInteger.class;
    }
    return false;
  }

  private boolean isFloatFamily(Type t) {
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
  private Type numericResult(ELNode.Binary e, Type lt, Type rt) {
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

  /** Merges the collected ASSIGN right-hand types into per-variable types. */
  private void recomputeVarTypes() {
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
        changed = true;
      }
    }
  }

  /** Collects every RETURN node under the given subtree. */
  private static void collectReturns(ELNode node, java.util.List<ELNode.RETURN> out) {
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
  private Type resolveAnnotationType(ELNode typeNode) {
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

  // ---- node rules (this task) ----

  @Override
  public void visit(ELNode.DEFINE e) {
    super.visit(e);

    Type annotated = resolveAnnotationType(e.type);
    if (annotated != AnyType.ANY) {
      if (e.expr != null && !compatible(annotated, typeOf(e.expr))) {
        reportError(e.pos, _T(IR_TYPE_MISMATCH, annotated, typeOf(e.expr)));
        setType(e, ErrorType.ERROR);
        setType(e.expr, ErrorType.ERROR);
        if (e.symbol != null) {
          varTypes.put(e.symbol, ErrorType.ERROR);
        }
        return;
      }
      setType(e, annotated);
      if (e.expr != null) {
        setType(e.expr, annotated);
      }
      if (e.symbol != null) {
        varTypes.put(e.symbol, annotated);
      }
      return;
    }

    if (e.expr instanceof ELNode.LAMBDA || e.expr instanceof ELNode.CLASSDEF) {
      // Function / class definition: type comes from the definition body.
      setType(e, typeOf(e.expr));
      return;
    }

    // Plain variable.
    Type t = typeOf(e.expr);
    if (e.symbol != null) {
      if (e.symbol.immutable) {
        varTypes.put(e.symbol, t);
      } else {
        // Mutable: initializer participates as one "assignment".
        assigns.computeIfAbsent(e.symbol, k -> new java.util.ArrayList<>());
        if (!assigns.get(e.symbol).contains(t)) {
          assigns.get(e.symbol).add(t);
        }
        t = varTypes.containsKey(e.symbol) ? varTypes.get(e.symbol) : t;
      }
    }
    setType(e, t);
  }

  @Override
  public void visit(ELNode.BOOLEANVAL e) {
    setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.CHARVAL e) {
    setType(e, PrimitiveType.of(Character.class));
  }

  @Override
  public void visit(ELNode.STRINGVAL e) {
    setType(e, PrimitiveType.of(String.class));
  }

  @Override
  public void visit(ELNode.NUMBER e) {
    setType(e, numberType(e.value));
  }

  private Type numberType(Number value) {
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

  @Override
  public void visit(ELNode.NULL e) {
    setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.NIL e) {
    setType(e, new ListType(AnyType.ANY));
  }

  @Override
  public void visit(ELNode.SYMBOL e) {
    setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.CONST e) {
    setType(e, typeOfValue(e.value));
  }

  @Override
  public void visit(ELNode.Composite e) {
    super.visit(e);
    setType(e, PrimitiveType.of(String.class));
  }

  @Override
  public void visit(ELNode.COMPOUND e) {
    super.visit(e);
    setType(e, e.exps.length == 0 ? AnyType.ANY : typeOf(e.exps[e.exps.length - 1]));
  }

  @Override
  public void visit(ELNode.LET e) {
    super.visit(e);
    setType(e, typeOf(e.right));
  }

  @Override
  public void visit(ELNode.EXPR e) {
    super.visit(e);
    setType(e, typeOf(e.right));
  }

  @Override
  public void visit(ELNode.METADATA e) {
    super.visit(e);
    setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.METASET e) {
    super.visit(e);
    setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.IDENT e) {
    SymbolTable.Symbol sym = e.symbol;
    if (sym == null) {
      // No symbol attached (e.g. a function referenced from a variable
      // initializer): fall back to the same-named top-level definition.
      ELNode.DEFINE top = topDefs.get(e.id);
      setType(e, top != null ? typeOf(top) : AnyType.ANY);
    } else if (sym.isFunction()) {
      setType(e, typeOf(sym.def));
    } else {
      Type t = varType(sym);
      if (t == null) {
        t = forVars.get(e.id);   // reading a loop variable by name
      }
      setType(e, t != null ? t : AnyType.ANY);
    }
  }

  @Override
  public void visit(ELNode.NOT e) {
    super.visit(e);
    setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.EMPTY e) {
    super.visit(e);
    setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.INSTANCEOF e) {
    super.visit(e);
    setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.EQ e)  { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.NE e)  { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.IDEQ e) { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.IDNE e) { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.LT e)  { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.LE e)  { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.GT e)  { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.GE e)  { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.CMP e) { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.IN e)  { super.visit(e); setType(e, PrimitiveType.of(Boolean.class)); }

  @Override
  public void visit(ELNode.OR e) {
    super.visit(e);
    setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.AND e) {
    super.visit(e);
    setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.CAT e) {
    super.visit(e);
    setType(e, PrimitiveType.of(String.class));
  }

  @Override
  public void visit(ELNode.ASSIGN e) {
    super.visit(e);
    Type t = typeOf(e.right);
    if (e.left instanceof ELNode.IDENT id && id.symbol != null &&
        !id.symbol.isFunction() && id.symbol.clazz == null) {
      Type expected = varType(id.symbol);
      if (expected != null && !compatible(expected, t)) {
        reportError(e.pos, _T(IR_TYPE_MISMATCH, expected, t));
        varTypes.put(id.symbol, ErrorType.ERROR);
        setType(e, ErrorType.ERROR);
        return;
      }
      assigns.computeIfAbsent(id.symbol, k -> new java.util.ArrayList<>());
      if (!assigns.get(id.symbol).contains(t)) {
        assigns.get(id.symbol).add(t);
      }
    }
    setType(e, t);
  }

  /**
   * Whether a value of type actual may be stored into a variable of the
   * annotated type expected. Cannot judge Any / promotion markers /
   * errors; both-numeric is allowed here and refined (widening check)
   * in the numeric task.
   */
  private boolean compatible(Type expected, Type actual) {
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

  private boolean isNumeric(Type t) {
    if (t == PrimitiveType.ANY_INT || t == PrimitiveType.ANY_FLOAT) return true;
    if (t instanceof PrimitiveType pt && pt.javaClass() != null) {
      return Number.class.isAssignableFrom(pt.javaClass()) ||
             pt.javaClass() == Character.class;
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
  private Type varType(SymbolTable.Symbol s) {
    Type t = varTypes.get(s);
    if (t != null) return t;
    final String name = s.name;
    for (Map.Entry<SymbolTable.Symbol, Type> e : varTypes.entrySet()) {
      if (e.getKey().name.equals(name)) return e.getValue();
    }
    return null;
  }

  @Override
  public void visit(ELNode.ASSIGNOP e) {
    super.visit(e);
    // By convention a compound assignment does not change the variable type.
    setType(e, typeOf(e.left));
  }

  @Override
  public void visit(ELNode.LAMBDA e) {
    super.visit(e);

    Type[] params = new Type[e.vars.length];
    for (int i = 0; i < e.vars.length; i++) {
      Type p = resolveAnnotationType(e.vars[i].type);
      params[i] = p != AnyType.ANY ? p : AnyType.ANY;
    }

    Type ret = resolveAnnotationType(e.rtype);
    if (ret == AnyType.ANY) {
      // Unannotated: merge the body type with every RETURN expression.
      java.util.List<ELNode.RETURN> returns = new java.util.ArrayList<>();
      collectReturns(e.body, returns);
      ret = typeOf(e.body);
      for (ELNode.RETURN r : returns) {
        ret = merge(ret, typeOf(r.right));
      }
    } else {
      // Annotated return type pins the body (no promotion).
      setType(e.body, ret);
    }

    setType(e, new FunctionType(ret, params));
  }

  @Override
  public void visit(ELNode.RETURN e) {
    super.visit(e);
    setType(e, typeOf(e.right));
  }

  @Override
  public void visit(ELNode.APPLY e) {
    super.visit(e);

    Type target = typeOf(e.right);
    if (target instanceof FunctionType ft) {
      setType(e, ft.returnType());
      return;
    }

    // Java method call: obj.method(args) — target is an ACCESS whose
    // receiver type carries the class.
    Type receiver = target;
    String method = null;
    if (e.right instanceof ELNode.ACCESS acc) {
      receiver = typeOf(acc.right);
      method = acc.index instanceof ELNode.STRINGVAL s
        ? s.value : acc.index instanceof ELNode.IDENT id ? id.id : null;
    }
    Class<?> cls = receiver instanceof JavaClassType jc ? jc.javaClass()
      : receiver instanceof PrimitiveType pt && pt.javaClass() != null
        ? pt.javaClass() : null;
    if (cls != null && method != null && (e.keys == null || e.keys.length == 0)) {
      Type rt = methodReturnType(cls, method, e.args.length);
      if (rt != null) {
        setType(e, rt);
        return;
      }
    }

    // Fully-qualified static call such as java.lang.Integer.parseInt(args):
    // the ACCESS subtree names both package/class and the trailing method.
    if (e.keys == null || e.keys.length == 0) {
      Type fs = staticAccessReturnType(e.right, e.args.length);
      if (fs != null) {
        setType(e, fs);
        return;
      }
    }
    setType(e, AnyType.ANY);
  }

  /**
   * Resolves a whole-dotted member reference reached only through the Java
   * classpath, e.g. {@code java.lang.Integer.parseInt("1")}. Returns the
   * method return type or null when the shape is not a Java static call.
   */
  private Type staticAccessReturnType(ELNode root, int argCount) {
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

  /** Reflectively finds the return type of the named method; null if ambiguous. */
  private Type methodReturnType(Class<?> cls, String name, int argCount) {
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
    Class<?> rt = match.getReturnType();
    if (rt == void.class || rt == Void.class) {
      return PrimitiveType.of(void.class);
    }
    if (TypeCoercion.typeof(rt) != TypeCoercion.UNKNOWN_TYPE) {
      return PrimitiveType.of(rt);
    }
    return new JavaClassType(rt);
  }

  @Override
  public void visit(ELNode.FOREACH e) {
    super.visit(e);
    Type range = typeOf(e.range);
    Type element = AnyType.ANY;
    if (range instanceof ListType lt) {
      element = lt.element();
    } else if (range instanceof ArrayType at) {
      element = at.element();
    }
    if (e.var != null) {
      setType(e.var, element);
      if (e.var.symbol != null) {
        varTypes.put(e.var.symbol, element);
      }
      if (e.var.id != null) {
        forVars.put(e.var.id, element);   // name fallback for loop-var reads
      } else if (e.var.symbol != null) {
        forVars.put(e.var.symbol.name, element);
      }
    }
    // Force the loop variable type onto references inside the body so reads
    // stay typed even when they carry a symbol object distinct from e.var's.
    if (e.body != null && element != AnyType.ANY) {
      // Loop-variable references inside the body may carry a different symbol
      // object; nudge them to the element type so the body stays typed.
      final ELNode.DEFINE vdef = e.var;
      final Type elemType = element;
      if (vdef != null) {
        new DefaultVisitor() {
          @Override
          public void visit(ELNode.IDENT x) {
            if (x.id != null && x.id.equals(vdef.id) ||
                x.symbol != null && x.symbol == vdef.symbol) {
              setType(x, elemType);
            }
            super.visit(x);
          }
        }.scan(e.body);
      }
    }
    if (e.index != null) {
      setType(e.index, PrimitiveType.of(Integer.class));
      if (e.index.symbol != null) {
        varTypes.put(e.index.symbol, PrimitiveType.of(Integer.class));
      }
      if (e.index.id != null) {
        forVars.put(e.index.id, PrimitiveType.of(Integer.class));
      }
    }
    // Re-derive the body now that the loop variable is known, in case it was
    // visited (as a COMPOUND) before varTypes were seeded for this iteration.
    if (e.body instanceof ELNode.COMPOUND c && c.exps.length > 0) {
      Type lt = typeOf(c.exps[c.exps.length - 1]);
      if (lt != AnyType.ANY) {
        setType(c, lt);
      }
    }
    setType(e, typeOf(e.body));
  }

  @Override
  public void visit(ELNode.WHILE e) {
    super.visit(e);
    setType(e, typeOf(e.body));
  }

  /**
   * Infix operator application such as {@code x + 1}. The result type is the
   * merged operand type; numeric widening/normalization arrives in Task 6.
   */
  @Override
  public void visit(ELNode.INFIX e) {
    super.visit(e);
    setType(e, merge(typeOf(e.left), typeOf(e.right)));
  }

  private void visitArithmetic(ELNode.Binary e) {
    Type lt = typeOf(e.left);
    Type rt = typeOf(e.right);
    setType(e, numericResult(e, lt, rt));
  }

  @Override public void visit(ELNode.ADD e) { super.visit(e); visitArithmetic(e); }
  @Override public void visit(ELNode.SUB e) { super.visit(e); visitArithmetic(e); }
  @Override public void visit(ELNode.MUL e) { super.visit(e); visitArithmetic(e); }
  @Override public void visit(ELNode.DIV e) { super.visit(e); visitArithmetic(e); }
  @Override public void visit(ELNode.REM e) { super.visit(e); visitArithmetic(e); }
  @Override public void visit(ELNode.POW e) { super.visit(e); visitArithmetic(e); }

  @Override
  public void visit(ELNode.BITOR e) {
    super.visit(e);
    Type lt = typeOf(e.left), rt = typeOf(e.right);
    if (lt.toString().equals("Boolean") && rt.toString().equals("Boolean")) {
      setType(e, PrimitiveType.of(Boolean.class));
    } else {
      setType(e, numericResult(e, lt, rt));
    }
  }

  @Override public void visit(ELNode.BITAND e) {
    super.visit(e);
    Type lt = typeOf(e.left), rt = typeOf(e.right);
    if (lt.toString().equals("Boolean") && rt.toString().equals("Boolean")) {
      setType(e, PrimitiveType.of(Boolean.class));
    } else {
      setType(e, numericResult(e, lt, rt));
    }
  }

  @Override public void visit(ELNode.XOR e) {
    super.visit(e);
    setType(e, numericResult(e, typeOf(e.left), typeOf(e.right)));
  }

  @Override
  public void visit(ELNode.SHL e) {
    super.visit(e);
    Type lt = typeOf(e.left);
    setType(e, isIntFamily(lt) ? lt : AnyType.ANY);
  }

  @Override public void visit(ELNode.SHR e) {
    super.visit(e);
    Type lt = typeOf(e.left);
    setType(e, isIntFamily(lt) ? lt : AnyType.ANY);
  }

  @Override public void visit(ELNode.USHR e) {
    super.visit(e);
    Type lt = typeOf(e.left);
    setType(e, isIntFamily(lt) ? lt : AnyType.ANY);
  }

  @Override
  public void visit(ELNode.NEG e) {
    super.visit(e);
    Type t = typeOf(e.right);
    if (isIntFamily(t)) {
      setType(e, t instanceof PrimitiveType pt &&
                  pt.javaClass() == java.math.BigInteger.class
        ? t : PrimitiveType.ANY_INT);
    } else if (isFloatFamily(t)) {
      setType(e, t instanceof PrimitiveType pt &&
                  pt.javaClass() == java.math.BigDecimal.class
        ? t : PrimitiveType.ANY_FLOAT);
    } else {
      setType(e, AnyType.ANY);
    }
  }

  @Override
  public void visit(ELNode.POS e) {
    super.visit(e);
    setType(e, typeOf(e.right));
  }

  @Override
  public void visit(ELNode.BITNOT e) {
    super.visit(e);
    Type t = typeOf(e.right);
    setType(e, isIntFamily(t) ? t : AnyType.ANY);
  }

  @Override
  public void visit(ELNode.INC e) {
    super.visit(e);
    Type t = typeOf(e.right);
    setType(e, isIntFamily(t) ? PrimitiveType.ANY_INT : AnyType.ANY);
  }

  @Override
  public void visit(ELNode.DEC e) {
    super.visit(e);
    Type t = typeOf(e.right);
    setType(e, isIntFamily(t) ? PrimitiveType.ANY_INT : AnyType.ANY);
  }

  @Override
  public void visit(ELNode.FOR e) {
    super.visit(e);
    setType(e, typeOf(e.body));
  }

  @Override
  public void visit(ELNode.MATCH e) {
    super.visit(e);
    Type t = AnyType.ANY;
    boolean first = true;
    for (ELNode.CASE alt : e.alts) {
      t = first ? typeOf(alt) : merge(t, typeOf(alt));
      first = false;
    }
    if (e.deflt != null) {
      t = first ? typeOf(e.deflt) : merge(t, typeOf(e.deflt));
    }
    setType(e, t);
  }

  @Override
  public void visit(ELNode.CASE e) {
    super.visit(e);
    Type t = AnyType.ANY;
    boolean first = true;
    for (ELNode body : e.bodies) {
      t = first ? typeOf(body) : merge(t, typeOf(body));
      first = false;
    }
    setType(e, t);
  }

  @Override
  public void visit(ELNode.NEW e) {
    super.visit(e);
    // NEW.base is an annotated type node: resolve it (ELite class or
    // compile-time-visible Java class); unresolvable falls back to Any.
    setType(e, resolveAnnotationType(e.base));
  }

  @Override
  public void visit(ELNode.CONS e) {
    super.visit(e);
    Type head = typeOf(e.head);
    Type tail = typeOf(e.tail);
    if (tail instanceof ListType lt) {
      setType(e, new ListType(merge(head, lt.element())));
    } else {
      setType(e, new ListType(head));
    }
  }

  @Override
  public void visit(ELNode.TUPLE e) {
    super.visit(e);
    Type[] elems = new Type[e.elems.length];
    for (int i = 0; i < e.elems.length; i++) {
      elems[i] = typeOf(e.elems[i]);
    }
    setType(e, new TupleType(elems));
  }

  @Override
  public void visit(ELNode.MAP e) {
    super.visit(e);
    Type key = AnyType.ANY;
    Type value = AnyType.ANY;
    boolean first = true;
    for (int i = 0; i < e.keys.length; i++) {
      if (first) {
        key = typeOf(e.keys[i]);
        value = typeOf(e.values[i]);
        first = false;
      } else {
        key = merge(key, typeOf(e.keys[i]));
        value = merge(value, typeOf(e.values[i]));
      }
    }
    setType(e, new MapType(key, value));
  }

  @Override
  public void visit(ELNode.ARRAY e) {
    super.visit(e);
    if (e.init != null) {
      // new X[n](elems...) form: array literal with initializer tuple
      Type merged = AnyType.ANY;
      for (int i = 0; i < e.init.elems.length; i++) {
        Type t = typeOf(e.init.elems[i]);
        merged = i == 0 ? t : merge(merged, t);
      }
      setType(e, new ArrayType(merged, 1));
      return;
    }
    Type element = e.type != null ? resolveAnnotationType(e.type) : AnyType.ANY;
    setType(e, new ArrayType(element, e.dims.length));
  }

  @Override
  public void visit(ELNode.RANGE e) {
    super.visit(e);
    setType(e, new JavaClassType(elite.lang.Range.class));
  }

  @Override
  public void visit(ELNode.ACCESS e) {
    super.visit(e);
    Type target = typeOf(e.right);
    if (target instanceof ArrayType at) {
      setType(e, at.element());
    } else if (target instanceof ListType lt) {
      setType(e, lt.element());
    } else {
      setType(e, AnyType.ANY);
    }
  }

  @Override
  public void visit(ELNode.COND e) {
    super.visit(e);
    setType(e, merge(typeOf(e.left), typeOf(e.right)));
  }

  @Override
  public void visit(ELNode.COALESCE e) {
    super.visit(e);
    setType(e, merge(typeOf(e.left), typeOf(e.right)));
  }

  @Override
  public void visitNode(ELNode e) {
    // Anything not covered above stays untyped (reads as Any).
  }
}
