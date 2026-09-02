package org.elite.ir.infer;

import org.elite.eval.ELProgram;
import org.elite.parser.DefaultVisitor;
import org.elite.parser.ELNode;
import org.elite.type.AnyType;
import org.elite.type.ArrayType;
import org.elite.type.FunctionType;
import org.elite.type.JavaClassType;
import org.elite.type.ListType;
import org.elite.type.PrimitiveType;
import org.elite.type.Type;

/** Pass 3: reflective complex nodes, e.g. Java calls (extended in Task 4). */
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

    Type target = ctx.typeOf(e.right);
    if (target instanceof FunctionType ft) {
      ctx.setType(e, ft.returnType());
      return;
    }

    // Java method call: obj.method(args) — target is an ACCESS whose
    // receiver type carries the class.
    Type receiver = target;
    String method = null;
    if (e.right instanceof ELNode.ACCESS acc) {
      receiver = ctx.typeOf(acc.right);
      method = acc.index instanceof ELNode.STRINGVAL s
        ? s.value : acc.index instanceof ELNode.IDENT id ? id.id : null;
    }
    Class<?> cls = receiver instanceof JavaClassType jc ? jc.javaClass()
      : receiver instanceof PrimitiveType pt && pt.javaClass() != null
        ? pt.javaClass() : null;
    if (cls != null && method != null && (e.keys == null || e.keys.length == 0)) {
      Type rt = ctx.methodReturnType(cls, method, e.args.length);
      if (rt != null) {
        ctx.setType(e, rt);
        return;
      }
    }

    // Fully-qualified static call such as java.lang.Integer.parseInt(args):
    // the ACCESS subtree names both package/class and the trailing method.
    if (e.keys == null || e.keys.length == 0) {
      Type fs = ctx.staticAccessReturnType(e.right, e.args.length);
      if (fs != null) {
        ctx.setType(e, fs);
        return;
      }
    }
    ctx.setType(e, AnyType.ANY);
  }

  @Override
  public void visit(ELNode.NEW e) {
    super.visit(e);
    // NEW.base is an annotated type node: resolve it (ELite class or
    // compile-time-visible Java class); unresolvable falls back to Any.
    ctx.setType(e, ctx.resolveAnnotationType(e.base));
  }

  @Override
  public void visit(ELNode.ACCESS e) {
    super.visit(e);
    Type target = ctx.typeOf(e.right);
    if (target instanceof ArrayType at) {
      ctx.setType(e, at.element());
    } else if (target instanceof ListType lt) {
      ctx.setType(e, lt.element());
    } else {
      ctx.setType(e, AnyType.ANY);
    }
  }

  @Override
  public void visitNode(ELNode e) {
    // Anything not covered above stays untyped (reads as Any).
  }
}
