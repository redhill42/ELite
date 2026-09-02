package org.elite.parser;

import javax.el.ELContext;
import org.elite.eval.ELEngine;
import org.elite.eval.ELProgram;
import org.elite.ir.SymbolTableBuilder;
import org.elite.ir.TypeInference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ASTDumperTest {

  private static ELContext elctx;

  @BeforeAll
  static void createContext() {
    elctx = ELEngine.createELContext();
  }

  private static String dumpTyped(String script) throws ParseException {
    ELProgram program = new Parser(elctx, script).parse();
    SymbolTableBuilder.build(program);
    TypeInference.infer(elctx, program);
    return ASTDumper.dumpTyped(program);
  }

  @Test
  void typedDumpShowsInferredTypes() throws ParseException {
    String out = dumpTyped("define x::Int = 42");
    assertTrue(out.contains(" :: Int"), "missing type suffix, got:\n" + out);
  }

  @Test
  void plainDumpUnchanged() throws ParseException {
    ELProgram program = new Parser(elctx, "define x::Int = 42").parse();
    SymbolTableBuilder.build(program);
    String out = ASTDumper.dump(program);
    assertFalse(out.contains(" :: "), "plain dump must not contain type suffixes");
  }
}
