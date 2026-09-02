package org.elite.ir;

import javax.el.ELContext;
import org.elite.eval.ELProgram;
import org.elite.ir.infer.InferenceContext;
import org.elite.ir.infer.Pass1;
import org.elite.ir.infer.Pass2;
import org.elite.ir.infer.Pass3;
import org.elite.parser.DefaultVisitor;
import org.elite.parser.ELNode;
import org.elite.parser.ParseException;
import org.elite.type.AnyType;

/**
 * Static type inference over the ELite AST — orchestrator.
 *
 * Runs after {@code SymbolTableBuilder} and annotates every
 * {@code ELNode.inferredType} in a fixed sub-pass structure:
 * Pass 1 (annotations, constants, immutable variables, once), then
 * Pass 2 (operators) and Pass 3 (reflective complex nodes) repeated
 * until no progress or the iteration cap; remaining nulls become Any.
 * See docs/spec/2026-09-02-type-inference-subpass-design.md.
 */
public final class TypeInference {

  /** Iteration cap: the reflective Pass 3 chain can span a few levels. */
  private static final int MAX_PASSES = 50;

  private TypeInference() {}

  public static void infer(ELContext elctx, ELProgram program)
    throws ParseException
  {
    InferenceContext ctx = new InferenceContext(elctx, program);
    ctx.registerEliteClasses(program);

    new Pass1(ctx).run(program);

    int passes = 0;
    boolean progress;
    do {
      progress = false;
      ctx.resetProgress();
      new Pass2(ctx).run(program);
      ctx.recomputeVarTypes();
      new Pass3(ctx).run(program);
      progress = ctx.takeProgress();
    } while (progress && ++passes < MAX_PASSES);

    // Postcondition: every node carries a type. Unknown identifiers, bare
    // literals never reached by a pass, and any interior node the sub-passes
    // left null are forced to Any here. Overriding the single-argument
    // DefaultVisitor.scan sets the current node before accept() recurses, so a
    // node is forced exactly once wherever it sits in the tree — leaves whose
    // visit methods DefaultVisitor leaves empty (IDENT, BREAK, CONTINUE and
    // the constants under visitConstant) AND interior nodes whose recursive
    // visit never sets a type are both covered without enumerating each kind.
    DefaultVisitor filler = new DefaultVisitor() {
      @Override
      public void scan(ELNode e) {
        if (e == null) {
          return;
        }
        if (e.inferredType == null) {
          e.inferredType = AnyType.ANY;
        }
        super.scan(e);   // accept() + DefaultVisitor recursion into children
      }
    };
    for (ELNode node : program.getDefinitions()) {
      filler.scan(node);
    }
    for (ELNode node : program.getExpressions()) {
      filler.scan(node);
    }

    ctx.failIfErrors();
  }
}
