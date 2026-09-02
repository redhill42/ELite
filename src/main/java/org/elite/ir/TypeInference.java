package org.elite.ir;

import javax.el.ELContext;
import org.elite.eval.ELProgram;
import org.elite.ir.infer.InferenceContext;
import org.elite.ir.infer.Pass1;
import org.elite.ir.infer.Pass2;
import org.elite.ir.infer.Pass3;
import org.elite.parser.ParseException;

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

  private static final int MAX_PASSES = 20;

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

    ctx.failIfErrors();
  }
}
