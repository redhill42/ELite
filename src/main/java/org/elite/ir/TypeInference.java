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
    if (type != node.inferredType) {
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
    return AnyType.ANY;
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
    Type t = resolveAnnotationType(e.type);
    if (t != AnyType.ANY) {
      // Annotation is authoritative: pin the variable and its initializer.
      setType(e, t);
      if (e.expr != null) {
        setType(e.expr, t);
      }
      return;
    }
    setType(e, typeOf(e.expr));
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
    // Variable/function reference typing arrives in a later task.
    setType(e, AnyType.ANY);
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
      // [1, 2] style: array literal with initializer tuple
      Type[] elems = new Type[e.init.elems.length];
      Type merged = AnyType.ANY;
      for (int i = 0; i < e.init.elems.length; i++) {
        elems[i] = typeOf(e.init.elems[i]);
        merged = i == 0 ? elems[i] : merge(merged, elems[i]);
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
