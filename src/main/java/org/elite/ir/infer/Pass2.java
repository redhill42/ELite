package org.elite.ir.infer;

import org.elite.eval.ELProgram;
import org.elite.ir.SymbolTable;
import org.elite.parser.DefaultVisitor;
import org.elite.parser.ELNode;
import org.elite.type.AnyType;
import org.elite.type.ArrayType;
import org.elite.type.ErrorType;
import org.elite.type.FunctionType;
import org.elite.type.JavaClassType;
import org.elite.type.ListType;
import org.elite.type.MapType;
import org.elite.type.PrimitiveType;
import org.elite.type.TupleType;
import org.elite.type.Type;

import static org.elite.resources.Resources.IR_TYPE_MISMATCH;
import static org.elite.resources.Resources._T;

/** Pass 2: operator and structural rules (extended by Task 4 semantics). */
public class Pass2 extends DefaultVisitor {

  private final InferenceContext ctx;

  public Pass2(InferenceContext ctx) {
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
  public void visit(ELNode.DEFINE e) {
    super.visit(e);

    // The un-annotated DECLARE tail reconstructed from the pre-refactor
    // visit(DEFINE): Pass 1 already owns annotated DEFINEs (typed or error)
    // and straight typed immutable variables, so those carry an inferredType
    // on their nodes and anything unprocessed here starts out null. Only the
    // remainder is typed here, in three shapes:
    //  1. function/class definitions -> the defining expression's type,
    //  2. mutable variables -> initializer folds into assigns, type = merge,
    //  3. immutable variables whose initializer was typed after Pass 1.
    Type annotated = ctx.resolveAnnotationType(e.type);
    if (annotated != AnyType.ANY) {
      if (e.inferredType == ErrorType.ERROR ||
          e.expr != null && e.expr.inferredType == ErrorType.ERROR) {
        // The annotated initializer genuinely mismatched in Pass 1 and was
        // typed (and reported) there — do not reseed a static type over the
        // error, or a later ASSIGN would silently "repair" it via the Any path.
        return;
      }
      // Reseed the annotation each pass (mirrors the pre-refactor visit) so a
      // subsequent ASSIGN against this symbol keeps re-detecting a mismatch
      // against the static annotation rather than against a stale ErrorType.
      ctx.setType(e, annotated);
      if (e.expr != null) ctx.setType(e.expr, annotated);
      if (e.symbol != null) ctx.varTypes.put(e.symbol, annotated);
      return;
    }
    if (e.expr == null) {
      return;   // bare define: type hangs off assigns via ASSIGN
    }
    if (e.expr instanceof ELNode.LAMBDA || e.expr instanceof ELNode.CLASSDEF) {
      // Set the type once the defining LAMBDA (Pass 2) / CLASSDEF (Pass 1)
      // body has produced one — never pin a still-untyped body to Any.
      if (ctx.hasType(e.expr)) {
        ctx.setType(e, ctx.valueTypeOf(e.expr));
      }
      return;
    }
    Type t = ctx.valueTypeOf(e.expr);
    if (e.symbol != null) {
      if (e.symbol.immutable) {
        // Straight-typed immutables are finalized in Pass 1; this branch only
        // catches an immutable whose initializer became typed after that pass.
        if (!ctx.hasType(e.expr)) {
          return;
        }
        ctx.setType(e, t);
        ctx.varTypes.put(e.symbol, t);
        return;
      }
      // Mutable: initializer participates as one "assignment".
      ctx.assigns.computeIfAbsent(e.symbol, k -> new java.util.ArrayList<>());
      if (!ctx.assigns.get(e.symbol).contains(t)) {
        ctx.assigns.get(e.symbol).add(t);
      }
      t = ctx.varTypes.containsKey(e.symbol) ? ctx.varTypes.get(e.symbol) : t;
    }
    ctx.setType(e, t);
  }

  @Override
  public void visit(ELNode.Composite e) {
    super.visit(e);
    ctx.setType(e, PrimitiveType.of(String.class));
  }

  @Override
  public void visit(ELNode.COMPOUND e) {
    super.visit(e);
    ctx.setType(e, e.exps.length == 0 ? AnyType.ANY : ctx.valueTypeOf(e.exps[e.exps.length - 1]));
  }

  @Override
  public void visit(ELNode.LET e) {
    super.visit(e);
    ctx.setType(e, ctx.valueTypeOf(e.right));
  }

  @Override
  public void visit(ELNode.EXPR e) {
    super.visit(e);
    ctx.setType(e, ctx.valueTypeOf(e.right));
  }

  @Override
  public void visit(ELNode.METADATA e) {
    super.visit(e);
    ctx.setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.METASET e) {
    super.visit(e);
    ctx.setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.IDENT e) {
    SymbolTable.Symbol sym = e.symbol;
    if (sym == null) {
      // No symbol attached (e.g. a function referenced from a variable
      // initializer): fall back to the same-named top-level definition.
      ELNode.DEFINE top = ctx.topDefs.get(e.id);
      ctx.setType(e, top != null ? ctx.typeOf(top) : AnyType.ANY);
    } else if (sym.isFunction()) {
      ctx.setType(e, ctx.typeOf(sym.def));
    } else {
      Type t = ctx.varType(sym);
      if (t == null) {
        t = ctx.forVars.get(e.id);   // reading a loop variable by name
      }
      ctx.setType(e, t != null ? t : AnyType.ANY);
    }
  }

  @Override
  public void visit(ELNode.NOT e) {
    super.visit(e);
    ctx.setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.EMPTY e) {
    super.visit(e);
    ctx.setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.INSTANCEOF e) {
    super.visit(e);
    ctx.setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.EQ e)  { super.visit(e); ctx.setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.NE e)  { super.visit(e); ctx.setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.IDEQ e) { super.visit(e); ctx.setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.IDNE e) { super.visit(e); ctx.setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.LT e)  { super.visit(e); ctx.setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.LE e)  { super.visit(e); ctx.setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.GT e)  { super.visit(e); ctx.setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.GE e)  { super.visit(e); ctx.setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.CMP e) { super.visit(e); ctx.setType(e, PrimitiveType.of(Boolean.class)); }
  @Override
  public void visit(ELNode.IN e)  { super.visit(e); ctx.setType(e, PrimitiveType.of(Boolean.class)); }

  @Override
  public void visit(ELNode.OR e) {
    super.visit(e);
    ctx.setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.AND e) {
    super.visit(e);
    ctx.setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.CAT e) {
    super.visit(e);
    ctx.setType(e, PrimitiveType.of(String.class));
  }

  @Override
  public void visit(ELNode.ASSIGN e) {
    super.visit(e);
    Type t = ctx.valueTypeOf(e.right);
    if (e.left instanceof ELNode.IDENT id && id.symbol != null &&
        !id.symbol.isFunction() && id.symbol.clazz == null) {
      Type expected = ctx.varType(id.symbol);
      if (expected != null && !ctx.compatible(expected, t)) {
        ctx.reportError(e.pos, _T(IR_TYPE_MISMATCH, expected, t));
        ctx.varTypes.put(id.symbol, ErrorType.ERROR);
        ctx.setType(e, ErrorType.ERROR);
        return;
      }
      ctx.assigns.computeIfAbsent(id.symbol, k -> new java.util.ArrayList<>());
      if (!ctx.assigns.get(id.symbol).contains(t)) {
        ctx.assigns.get(id.symbol).add(t);
      }
    }
    ctx.setType(e, t);
  }

  @Override
  public void visit(ELNode.ASSIGNOP e) {
    super.visit(e);
    // By convention a compound assignment does not change the variable type.
    ctx.setType(e, ctx.typeOf(e.left));
  }

  @Override
  public void visit(ELNode.LAMBDA e) {
    super.visit(e);

    Type[] params = new Type[e.vars.length];
    for (int i = 0; i < e.vars.length; i++) {
      Type p = ctx.resolveAnnotationType(e.vars[i].type);
      params[i] = p != AnyType.ANY ? p : AnyType.ANY;
    }

    Type ret = ctx.resolveAnnotationType(e.rtype);
    if (ret == AnyType.ANY) {
      // Unannotated: merge the body type with every RETURN expression.
      java.util.List<ELNode.RETURN> returns = new java.util.ArrayList<>();
      ctx.collectReturns(e.body, returns);
      ret = ctx.valueTypeOf(e.body);
      for (ELNode.RETURN r : returns) {
        ret = ctx.merge(ret, ctx.valueTypeOf(r.right));
      }
    } else {
      // Annotated return type pins the body (no promotion).
      ctx.setType(e.body, ret);
    }

    ctx.setType(e, new FunctionType(ret, params));
  }

  @Override
  public void visit(ELNode.RETURN e) {
    super.visit(e);
    ctx.setType(e, ctx.valueTypeOf(e.right));
  }

  @Override
  public void visit(ELNode.FOREACH e) {
    super.visit(e);
    Type range = ctx.valueTypeOf(e.range);
    Type element = AnyType.ANY;
    if (range instanceof ListType lt) {
      element = lt.element();
    } else if (range instanceof ArrayType at) {
      element = at.element();
    }
    if (e.var != null) {
      ctx.setType(e.var, element);
      if (e.var.symbol != null) {
        ctx.varTypes.put(e.var.symbol, element);
      }
      if (e.var.id != null) {
        ctx.forVars.put(e.var.id, element);   // name fallback for loop-var reads
      } else if (e.var.symbol != null) {
        ctx.forVars.put(e.var.symbol.name, element);
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
              ctx.setType(x, elemType);
            }
            super.visit(x);
          }
        }.scan(e.body);
      }
    }
    if (e.index != null) {
      ctx.setType(e.index, PrimitiveType.of(Integer.class));
      if (e.index.symbol != null) {
        ctx.varTypes.put(e.index.symbol, PrimitiveType.of(Integer.class));
      }
      if (e.index.id != null) {
        ctx.forVars.put(e.index.id, PrimitiveType.of(Integer.class));
      }
    }
    // Re-derive the body now that the loop variable is known, in case it was
    // visited (as a COMPOUND) before varTypes were seeded for this iteration.
    if (e.body instanceof ELNode.COMPOUND c && c.exps.length > 0) {
      Type lt = ctx.valueTypeOf(c.exps[c.exps.length - 1]);
      if (lt != AnyType.ANY) {
        ctx.setType(c, lt);
      }
    }
    ctx.setType(e, ctx.valueTypeOf(e.body));
  }

  @Override
  public void visit(ELNode.WHILE e) {
    super.visit(e);
    ctx.setType(e, ctx.valueTypeOf(e.body));
  }

  /**
   * Infix operator application such as {@code x + 1}. The result type is the
   * merged operand type; numeric widening/normalization arrives in Task 6.
   */
  @Override
  public void visit(ELNode.INFIX e) {
    super.visit(e);
    ctx.setType(e, ctx.merge(ctx.valueTypeOf(e.left), ctx.valueTypeOf(e.right)));
  }

  private void visitArithmetic(ELNode.Binary e) {
    Type lt = ctx.valueTypeOf(e.left);
    Type rt = ctx.valueTypeOf(e.right);
    ctx.setType(e, ctx.numericResult(e, lt, rt));
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
    Type lt = ctx.valueTypeOf(e.left), rt = ctx.valueTypeOf(e.right);
    if (lt.toString().equals("Boolean") && rt.toString().equals("Boolean")) {
      ctx.setType(e, PrimitiveType.of(Boolean.class));
    } else {
      ctx.setType(e, ctx.numericResult(e, lt, rt));
    }
  }

  @Override public void visit(ELNode.BITAND e) {
    super.visit(e);
    Type lt = ctx.valueTypeOf(e.left), rt = ctx.valueTypeOf(e.right);
    if (lt.toString().equals("Boolean") && rt.toString().equals("Boolean")) {
      ctx.setType(e, PrimitiveType.of(Boolean.class));
    } else {
      ctx.setType(e, ctx.numericResult(e, lt, rt));
    }
  }

  @Override public void visit(ELNode.XOR e) {
    super.visit(e);
    ctx.setType(e, ctx.numericResult(e, ctx.valueTypeOf(e.left), ctx.valueTypeOf(e.right)));
  }

  @Override
  public void visit(ELNode.SHL e) {
    super.visit(e);
    Type lt = ctx.valueTypeOf(e.left);
    ctx.setType(e, ctx.isIntFamily(lt) ? lt : AnyType.ANY);
  }

  @Override public void visit(ELNode.SHR e) {
    super.visit(e);
    Type lt = ctx.valueTypeOf(e.left);
    ctx.setType(e, ctx.isIntFamily(lt) ? lt : AnyType.ANY);
  }

  @Override public void visit(ELNode.USHR e) {
    super.visit(e);
    Type lt = ctx.valueTypeOf(e.left);
    ctx.setType(e, ctx.isIntFamily(lt) ? lt : AnyType.ANY);
  }

  @Override
  public void visit(ELNode.NEG e) {
    super.visit(e);
    Type t = ctx.valueTypeOf(e.right);
    if (ctx.isIntFamily(t)) {
      ctx.setType(e, t instanceof PrimitiveType pt &&
                     pt.javaClass() == java.math.BigInteger.class
        ? t : PrimitiveType.ANY_INT);
    } else if (ctx.isFloatFamily(t)) {
      ctx.setType(e, t instanceof PrimitiveType pt &&
                     pt.javaClass() == java.math.BigDecimal.class
        ? t : PrimitiveType.ANY_FLOAT);
    } else {
      ctx.setType(e, AnyType.ANY);
    }
  }

  @Override
  public void visit(ELNode.POS e) {
    super.visit(e);
    ctx.setType(e, ctx.valueTypeOf(e.right));
  }

  @Override
  public void visit(ELNode.BITNOT e) {
    super.visit(e);
    Type t = ctx.valueTypeOf(e.right);
    ctx.setType(e, ctx.isIntFamily(t) ? t : AnyType.ANY);
  }

  @Override
  public void visit(ELNode.INC e) {
    super.visit(e);
    Type t = ctx.valueTypeOf(e.right);
    ctx.setType(e, ctx.isIntFamily(t) ? PrimitiveType.ANY_INT : AnyType.ANY);
  }

  @Override
  public void visit(ELNode.DEC e) {
    super.visit(e);
    Type t = ctx.valueTypeOf(e.right);
    ctx.setType(e, ctx.isIntFamily(t) ? PrimitiveType.ANY_INT : AnyType.ANY);
  }

  @Override
  public void visit(ELNode.FOR e) {
    super.visit(e);
    ctx.setType(e, ctx.valueTypeOf(e.body));
  }

  @Override
  public void visit(ELNode.MATCH e) {
    super.visit(e);
    Type t = AnyType.ANY;
    boolean first = true;
    for (ELNode.CASE alt : e.alts) {
      t = first ? ctx.valueTypeOf(alt) : ctx.merge(t, ctx.valueTypeOf(alt));
      first = false;
    }
    if (e.deflt != null) {
      t = first ? ctx.valueTypeOf(e.deflt) : ctx.merge(t, ctx.valueTypeOf(e.deflt));
    }
    ctx.setType(e, t);
  }

  @Override
  public void visit(ELNode.CASE e) {
    super.visit(e);
    Type t = AnyType.ANY;
    boolean first = true;
    for (ELNode body : e.bodies) {
      t = first ? ctx.valueTypeOf(body) : ctx.merge(t, ctx.valueTypeOf(body));
      first = false;
    }
    ctx.setType(e, t);
  }

  @Override
  public void visit(ELNode.CONS e) {
    super.visit(e);
    Type head = ctx.valueTypeOf(e.head);
    Type tail = ctx.valueTypeOf(e.tail);
    if (tail instanceof ListType lt) {
      ctx.setType(e, new ListType(ctx.merge(head, lt.element())));
    } else {
      ctx.setType(e, new ListType(head));
    }
  }

  @Override
  public void visit(ELNode.TUPLE e) {
    super.visit(e);
    Type[] elems = new Type[e.elems.length];
    for (int i = 0; i < e.elems.length; i++) {
      elems[i] = ctx.valueTypeOf(e.elems[i]);
    }
    ctx.setType(e, new TupleType(elems));
  }

  @Override
  public void visit(ELNode.MAP e) {
    super.visit(e);
    Type key = AnyType.ANY;
    Type value = AnyType.ANY;
    boolean first = true;
    for (int i = 0; i < e.keys.length; i++) {
      if (first) {
        key = ctx.valueTypeOf(e.keys[i]);
        value = ctx.valueTypeOf(e.values[i]);
        first = false;
      } else {
        key = ctx.merge(key, ctx.valueTypeOf(e.keys[i]));
        value = ctx.merge(value, ctx.valueTypeOf(e.values[i]));
      }
    }
    ctx.setType(e, new MapType(key, value));
  }

  @Override
  public void visit(ELNode.ARRAY e) {
    super.visit(e);
    if (e.init != null) {
      // new X[n](elems...) form: array literal with initializer tuple
      Type merged = AnyType.ANY;
      for (int i = 0; i < e.init.elems.length; i++) {
        Type t = ctx.valueTypeOf(e.init.elems[i]);
        merged = i == 0 ? t : ctx.merge(merged, t);
      }
      ctx.setType(e, new ArrayType(merged, 1));
      return;
    }
    Type element = e.type != null ? ctx.resolveAnnotationType(e.type) : AnyType.ANY;
    ctx.setType(e, new ArrayType(element, e.dims.length));
  }

  @Override
  public void visit(ELNode.RANGE e) {
    super.visit(e);
    ctx.setType(e, new JavaClassType(elite.lang.Range.class));
  }

  @Override
  public void visit(ELNode.COND e) {
    super.visit(e);
    ctx.setType(e, ctx.merge(ctx.valueTypeOf(e.left), ctx.valueTypeOf(e.right)));
  }

  @Override
  public void visit(ELNode.COALESCE e) {
    super.visit(e);
    ctx.setType(e, ctx.merge(ctx.valueTypeOf(e.left), ctx.valueTypeOf(e.right)));
  }

  @Override
  public void visitNode(ELNode e) {
    // Anything not covered above stays untyped (reads as Any).
  }
}
