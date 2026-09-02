package org.elite.ir.infer;

import org.elite.eval.ELProgram;
import org.elite.parser.DefaultVisitor;
import org.elite.parser.ELNode;
import org.elite.type.AnyType;
import org.elite.type.ELiteClassType;
import org.elite.type.ErrorType;
import org.elite.type.ListType;
import org.elite.type.PrimitiveType;
import org.elite.type.Type;

import static org.elite.resources.Resources.IR_TYPE_MISMATCH;
import static org.elite.resources.Resources._T;

/**
 * Pass 1: annotation types, constant types, and immutable variable types.
 * Runs once. Nodes it cannot type keep inferredType == null.
 */
public class Pass1 extends DefaultVisitor {

  private final InferenceContext ctx;

  public Pass1(InferenceContext ctx) {
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
    Type annotated = ctx.resolveAnnotationType(e.type);
    if (annotated != AnyType.ANY) {
      if (e.expr != null && !ctx.compatible(annotated, ctx.typeOf(e.expr))) {
        ctx.reportError(e.pos, _T(IR_TYPE_MISMATCH, annotated, ctx.typeOf(e.expr)));
        ctx.setType(e, ErrorType.ERROR);
        if (e.expr != null) ctx.setType(e.expr, ErrorType.ERROR);
        if (e.symbol != null) ctx.varTypes.put(e.symbol, ErrorType.ERROR);
        return;
      }
      ctx.setType(e, annotated);
      if (e.expr != null) ctx.setType(e.expr, annotated);
      if (e.symbol != null) ctx.varTypes.put(e.symbol, annotated);
      return;
    }
    if (e.expr == null) {
      return;   // keep null; Pass 2 handles bare mutable defines
    }
    if (e.expr instanceof ELNode.LAMBDA || e.expr instanceof ELNode.CLASSDEF) {
      return;   // function/class definitions are Pass 2 / this pass respectively
    }
    if (e.symbol != null && e.symbol.immutable && ctx.hasType(e.expr)) {
      Type t = ctx.valueTypeOf(e.expr);
      ctx.varTypes.put(e.symbol, t);
      ctx.setType(e, t);
    }
    // Mutable variables (and immutable ones whose initializer is not yet
    // typed) stay null here; Pass 2 collects assigns and merges.
  }

  @Override
  public void visit(ELNode.BOOLEANVAL e) {
    ctx.setType(e, PrimitiveType.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.CHARVAL e) {
    ctx.setType(e, PrimitiveType.of(Character.class));
  }

  @Override
  public void visit(ELNode.STRINGVAL e) {
    ctx.setType(e, PrimitiveType.of(String.class));
  }

  @Override
  public void visit(ELNode.NUMBER e) {
    ctx.setType(e, ctx.numberType(e.value));
  }

  @Override
  public void visit(ELNode.NULL e) {
    ctx.setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.NIL e) {
    ctx.setType(e, new ListType(AnyType.ANY));
  }

  @Override
  public void visit(ELNode.SYMBOL e) {
    ctx.setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.CONST e) {
    ctx.setType(e, ctx.typeOfValue(e.value));
  }

  @Override
  public void visit(ELNode.CLASSDEF e) {
    super.visit(e);
    if (e.symbol != null && e.symbol.clazz != null) {
      ctx.setType(e, new ELiteClassType(e.symbol));
    }
  }
}
