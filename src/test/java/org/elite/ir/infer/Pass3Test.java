package org.elite.ir.infer;

import java.util.Locale;
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

/**
 * Pass 3 reflective rule tests: Java static fields/method references resolved
 * entirely at type-inference time (System.out.println), plus the reflective
 * constructor resolution and the three semantic-error checks.
 *
 * Java class nodes carry the bare simple-name JavaClassType render ("System",
 * "ArrayList", matching the ASTDumper/annotated-class convention), whereas the
 * reflective Field/Method/Constructor types render their own JavaX[...] form.
 */
class Pass3Test {

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
  void systemOutPrintlnFullyStatic() throws Exception {
    ELProgram program = parse("System.out.println(\"hello\")");
    SymbolTableBuilder.build(program);
    TypeInference.infer(elctx, program);

    ELNode apply = (ELNode)program.getExpressions().get(0);
    ELNode access = (ELNode)((ELNode.APPLY)apply).right;   // System.out.println
    ELNode inner  = (ELNode)((ELNode.ACCESS)access).right; // System.out
    ELNode system = (ELNode)((ELNode.ACCESS)inner).right;  // System

    assertEquals("System", system.inferredType.toString());
    assertEquals("JavaField[PrintStream]", inner.inferredType.toString());
    assertEquals("JavaMethod[void PrintStream.println(String)]",
      access.inferredType.toString());
    assertEquals("void", apply.inferredType.toString());
  }

  @Test
  void arrayListConstructor() throws Exception {
    ELProgram program = parse("define p = new java.util.ArrayList()");
    SymbolTableBuilder.build(program);
    TypeInference.infer(elctx, program);

    ELNode def = (ELNode)program.getExpressions().get(0);
    ELNode newExpr = (ELNode)((ELNode.DEFINE)def).expr;
    assertEquals("JavaConstructor[ArrayList()]", newExpr.inferredType.toString());
    // Value dereference: the variable is the instance type.
    assertEquals("ArrayList", def.inferredType.toString());
  }

  @Test
  void missingFieldIsError() {
    Locale prior = Locale.getDefault();
    Locale.setDefault(Locale.ENGLISH);
    try {
      ELProgram program = parse("System.noSuchField");
      SymbolTableBuilder.build(program);
      ParseException ex = assertThrows(ParseException.class,
        () -> TypeInference.infer(elctx, program));
      // EL_PROPERTY_NOT_FOUND inlines the property name between the words, so
      // pair on the stable suffix "not found on type ...".
      assertTrue(ex.getMessage().contains("noSuchField' not found on type"),
        "unexpected: " + ex.getMessage());
    } finally {
      Locale.setDefault(prior);
    }
  }

  @Test
  void missingMethodIsError() {
    Locale prior = Locale.getDefault();
    Locale.setDefault(Locale.ENGLISH);
    try {
      ELProgram program = parse("System.out.noSuchMethod(\"x\")");
      SymbolTableBuilder.build(program);
      ParseException ex = assertThrows(ParseException.class,
        () -> TypeInference.infer(elctx, program));
      // EL_METHOD_NOT_FOUND inlines the method name; pair on the stable
      // occurrence of the missing name under PrintStream.
      assertTrue(
        ex.getMessage().contains("noSuchMethod' not found"),
        "unexpected: " + ex.getMessage());
      assertTrue(ex.getMessage().contains("java.io.PrintStream"),
        "unexpected: " + ex.getMessage());
    } finally {
      Locale.setDefault(prior);
    }
  }

  @Test
  void missingConstructorIsError() {
    Locale prior = Locale.getDefault();
    Locale.setDefault(Locale.ENGLISH);
    try {
      ELProgram program = parse("new java.util.ArrayList(\"nope\")");
      SymbolTableBuilder.build(program);
      ParseException ex = assertThrows(ParseException.class,
        () -> TypeInference.infer(elctx, program));
      assertTrue(ex.getMessage().contains("Constructor not found"),
        "unexpected: " + ex.getMessage());
    } finally {
      Locale.setDefault(prior);
    }
  }

  @Test
  void everyNodeTypedAfterInference() throws Exception {
    // This guard proves the force-Any filler (TypeInference.infer) does real
    // work. ASSERT has no visit rule in any sub-pass, so its node would stay
    // null without the filler — a genuinely-nullable assertion target. (An
    // unknown IDENT would NOT be: Pass 2 Any-defaults unknown identifiers, so
    // the old define/IDENT-only guard passed even with the filler removed.)
    ELProgram program = parse("assert true\ndefine x = noSuchVariable");
    SymbolTableBuilder.build(program);
    TypeInference.infer(elctx, program);

    // The untyped ASSERT statement kind is forced to Any by the filler.
    ELNode assertNode = (ELNode)program.getExpressions().get(0);
    assertEquals("Any", assertNode.inferredType.toString());
    ELNode bool = (ELNode)((ELNode.ASSERT)assertNode).exp;
    assertEquals("Boolean", bool.inferredType.toString());

    // Unknown identifiers are Any-defaulted by Pass 2; both the DEFINE and its
    // IDENT carry Any after the full pipeline.
    ELNode def = (ELNode)program.getExpressions().get(1);
    assertEquals("Any", def.inferredType.toString());
    ELNode id = (ELNode)((ELNode.DEFINE)def).expr;
    assertEquals("Any", id.inferredType.toString());
  }

  @Test
  void anyTypedArgumentJavaCallFallsBack() throws Exception {
    // An Any-typed argument must not hard-error; the call types Any.
    ELProgram program = parse("define v = noSuchThing\nSystem.out.println(v)");
    SymbolTableBuilder.build(program);
    assertDoesNotThrow(() -> TypeInference.infer(elctx, program));
    ELNode apply = (ELNode)program.getExpressions().get(1);
    assertEquals("Any", apply.inferredType.toString());
  }

  @Test
  void ambiguousConstructorFallsBackToClass() throws Exception {
    // java.io.File has File(String) and File(URI) — same arity, ambiguous.
    ELProgram program = parse("define f = new java.io.File(\"x\")");
    SymbolTableBuilder.build(program);
    assertDoesNotThrow(() -> TypeInference.infer(elctx, program));
    ELNode def = (ELNode)program.getExpressions().get(0);
    assertEquals("File", def.inferredType.toString());
    ELNode newExpr = (ELNode)((ELNode.DEFINE)def).expr;
    assertEquals("File", newExpr.inferredType.toString());
  }
}
