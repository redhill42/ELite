package org.elite.ir;

import javax.el.ELContext;
import org.elite.eval.ELProgram;
import org.elite.parser.ParseException;

/**
 * Static type inference over the ELite AST — orchestrator.
 * <p>
 * Runs after {@code SymbolTableBuilder} and annotates every
 * {@code ELNode.inferredType}.
 */
public final class TypeInference {

  private static final int MAX_ITERATIONS = 10;

  private TypeInference() {}

  public static void infer(ELContext elctx, ELProgram program,
                           SymbolTable.Scope scope)
    throws ParseException
  {
    int iteration = 0;
    do {
      var pass = new InferencePass(elctx);
      pass.run(program, scope);
      if (pass.finished())
        break;
    } while (++iteration < MAX_ITERATIONS);
  }
}
