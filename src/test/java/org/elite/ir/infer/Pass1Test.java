package org.elite.ir.infer;

import javax.el.ELContext;
import org.elite.eval.ELEngine;
import org.elite.eval.ELProgram;
import org.elite.ir.SymbolTableBuilder;
import org.elite.ir.TypeInference;
import org.elite.parser.ELNode;
import org.elite.parser.ParseException;
import org.elite.parser.Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Pass1Test {

  private static ELContext elctx;

  @BeforeAll
  static void createContext() {
    elctx = ELEngine.createELContext();
  }

  private static ELProgram parse(String script) {
    try {
      return new Parser(elctx, script).parse();
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void pass1TypesConstantsAndAnnotationsAndImmutable() throws Exception {
    ELProgram program = parse("define x = 42\ndefine y::Int = 1");
    SymbolTableBuilder.build(program);
    TypeInference.infer(elctx, program);

    ELNode xDef = (ELNode)program.getExpressions().get(0);
    ELNode yDef = (ELNode)program.getExpressions().get(1);
    // Constants and annotated/immutable defines are typed in Pass 1.
    assertEquals("Int", xDef.inferredType.toString());
    assertEquals("Int", yDef.inferredType.toString());
  }

  @Test
  void pass2OnlyNodesStayNullAfterPass1() throws Exception {
    // x + 1 needs Pass 2 (operator); after the full pipeline it is typed,
    // but Pass 1 alone must leave it null. Run Pass 1 directly.
    ELProgram program = parse("define x = 42\nx + 1");
    SymbolTableBuilder.build(program);
    InferenceContext ctx = new InferenceContext(elctx, program);
    ctx.registerEliteClasses(program);
    new Pass1(ctx).run(program);

    ELNode add = (ELNode)program.getExpressions().get(1);
    assertNull(add.inferredType, "operator node must stay null after Pass 1 only");
  }
}
