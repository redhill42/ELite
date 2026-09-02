package org.elite.ir.infer;

import org.elite.eval.ELProgram;
import org.elite.parser.DefaultVisitor;
import org.elite.parser.ELNode;

/** Pass 2: operator and structural rules (filled in Task 3). */
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
}
