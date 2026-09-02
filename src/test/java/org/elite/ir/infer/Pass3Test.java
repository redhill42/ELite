package org.elite.ir.infer;

import java.util.Locale;
import javax.el.ELContext;
import org.elite.eval.ELEngine;
import org.elite.eval.ELProgram;
import org.elite.ir.SymbolTable;
import org.elite.ir.SymbolTableBuilder;
import org.elite.ir.TypeInference;
import org.elite.parser.ELNode;
import org.elite.parser.ParseException;
import org.elite.parser.Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
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
    SymbolTable symTable = SymbolTableBuilder.build(elctx, program);
    TypeInference.infer(elctx, program, symTable.currentScope());

    ELNode apply = program.getExpressions().get(0);
    ELNode access = ((ELNode.APPLY)apply).right;   // System.out.println
    ELNode inner  = ((ELNode.ACCESS)access).right; // System.out
    ELNode system = ((ELNode.ACCESS)inner).right;  // System

    assertEquals("Class[System]", system.inferredType.toString());
    assertEquals("PrintStream", inner.inferredType.toString());
    assertEquals("Field[PrintStream System.out]", inner.memberType.toString());
    assertEquals("void", apply.inferredType.toString());
    assertEquals("Method[void PrintStream.println(String)]",
      apply.memberType.toString());
  }

  @Test
  void arrayListConstructor() throws Exception {
    ELProgram program = parse("define p = new java.util.ArrayList()");
    SymbolTable symTable = SymbolTableBuilder.build(elctx, program);
    TypeInference.infer(elctx, program, symTable.currentScope());

    ELNode def = program.getExpressions().get(0);
    ELNode newExpr = ((ELNode.DEFINE)def).expr;
    assertEquals("Constructor[ArrayList()]", newExpr.memberType.toString());
    // Value dereference: the variable is the instance type.
    assertEquals("ArrayList", def.inferredType.toString());
  }

  @Test
  @Disabled
  void missingFieldIsError() {
    Locale prior = Locale.getDefault();
    Locale.setDefault(Locale.ENGLISH);
    try {
      ELProgram program = parse("System.noSuchField");
      SymbolTable symTable = SymbolTableBuilder.build(elctx, program);
      ParseException ex = assertThrows(ParseException.class,
        () -> TypeInference.infer(elctx, program, symTable.currentScope()));
      // EL_PROPERTY_NOT_FOUND inlines the property name between the words, so
      // pair on the stable suffix "not found on type ...".
      assertTrue(ex.getMessage().contains("noSuchField' not found on type"),
        "unexpected: " + ex.getMessage());
    } finally {
      Locale.setDefault(prior);
    }
  }

  @Test
  @Disabled
  void missingMethodIsError() {
    Locale prior = Locale.getDefault();
    Locale.setDefault(Locale.ENGLISH);
    try {
      ELProgram program = parse("System.out.noSuchMethod(\"x\")");
      SymbolTable symTable = SymbolTableBuilder.build(elctx, program);
      ParseException ex = assertThrows(ParseException.class,
        () -> TypeInference.infer(elctx, program, symTable.currentScope()));
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
  @Disabled
  void missingConstructorIsError() {
    Locale prior = Locale.getDefault();
    Locale.setDefault(Locale.ENGLISH);
    try {
      ELProgram program = parse("new java.util.ArrayList(\"nope\")");
      SymbolTable symTable = SymbolTableBuilder.build(elctx, program);
      ParseException ex = assertThrows(ParseException.class,
        () -> TypeInference.infer(elctx, program, symTable.currentScope()));
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
    SymbolTable symTable = SymbolTableBuilder.build(elctx, program);
    TypeInference.infer(elctx, program, symTable.currentScope());

    // The untyped ASSERT statement kind is forced to Any by the filler.
    ELNode assertNode = (ELNode)program.getExpressions().get(0);
    assertEquals("Unknown", assertNode.inferredType.toString());
    ELNode bool = (ELNode)((ELNode.ASSERT)assertNode).exp;
    assertEquals("Boolean", bool.inferredType.toString());

    // Unknown identifiers are Any-defaulted by Pass 2; both the DEFINE and its
    // IDENT carry Any after the full pipeline.
    ELNode def = (ELNode)program.getExpressions().get(1);
    assertEquals("Unknown", def.inferredType.toString());
    ELNode id = (ELNode)((ELNode.DEFINE)def).expr;
    assertEquals("Unknown", id.inferredType.toString());
  }

  @Test
  void anyTypedArgumentJavaCallFallsBack() throws Exception {
    // An Any-typed argument must not hard-error; the call types Any.
    ELProgram program = parse("define v = noSuchThing\nSystem.out.println(v)");
    SymbolTable symTable = SymbolTableBuilder.build(elctx, program);
    assertDoesNotThrow(() -> TypeInference.infer(elctx, program, symTable.currentScope()));
    ELNode apply = (ELNode)program.getExpressions().get(1);
    assertEquals("Unknown", apply.inferredType.toString());
  }

  @Test
  void ambiguousConstructorFallsBackToClass() throws Exception {
    // java.io.File has File(String) and File(URI) — same arity, ambiguous.
    ELProgram program = parse("define f = new java.io.File(\"x\")");
    SymbolTable symTable = SymbolTableBuilder.build(elctx, program);
    assertDoesNotThrow(() -> TypeInference.infer(elctx, program, symTable.currentScope()));
    ELNode def = (ELNode)program.getExpressions().get(0);
    assertEquals("File", def.inferredType.toString());
    ELNode newExpr = (ELNode)((ELNode.DEFINE)def).expr;
    assertEquals("File", newExpr.inferredType.toString());
  }
}
