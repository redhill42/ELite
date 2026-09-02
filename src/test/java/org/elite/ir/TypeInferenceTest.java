package org.elite.ir;

import java.util.Locale;
import javax.el.ELContext;
import org.elite.eval.ELEngine;
import org.elite.eval.ELProgram;
import org.elite.parser.ELNode;
import org.elite.parser.ParseException;
import org.elite.parser.Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypeInferenceTest {

  private static ELContext elctx;

  @BeforeAll
  static void createContext() {
    elctx = ELEngine.createELContext();
  }

  /** Parse only; returns the program (no symbol table, no inference). */
  private static ELProgram parse(String script) {
    try {
      return new Parser(elctx, script).parse();
    } catch (ParseException e) {
      throw new RuntimeException("parse failed: " + script, e);
    }
  }

  /**
   * Parse + build symbol table + infer; returns the last top-level node
   * (the last expression if any, else the last definition).
   */
  private static ELNode infer(String script) {
    ELProgram program = parse(script);
    SymbolTableBuilder.build(program);
    try {
      TypeInference.infer(elctx, program);
    } catch (ParseException e) {
      throw new RuntimeException("inference failed: " + script, e);
    }
    var exps = program.getExpressions();
    if (!exps.isEmpty()) {
      return exps.get(exps.size() - 1);
    }
    var defs = program.getDefinitions();
    assertFalse(defs.isEmpty(), "no top-level node in: " + script);
    return defs.get(defs.size() - 1);
  }

  private static String typeOf(String script) {
    return infer(script).inferredType.toString();
  }

  /** Parse + build + infer; returns the top-level DEFINE with the given id. */
  private static ELNode.DEFINE def(String script, String id) {
    ELProgram program = parse(script);
    SymbolTableBuilder.build(program);
    try {
      TypeInference.infer(elctx, program);
    } catch (ParseException e) {
      throw new RuntimeException("inference failed: " + script, e);
    }
    for (ELNode n : program.getDefinitions()) {
      if (n instanceof ELNode.DEFINE d && id.equals(d.id)) {
        return d;
      }
    }
    for (ELNode n : program.getExpressions()) {
      if (n instanceof ELNode.DEFINE d && id.equals(d.id)) {
        return d;
      }
    }
    throw new AssertionError("define not found: " + id);
  }

  @Test
  void annotatedVariable() {
    assertEquals("Int", typeOf("define x::Int = 42"));
  }

  @Test
  void annotatedAliases() {
    assertEquals("Int", typeOf("define x::Int = 1 + 2"));
    // Bare 'a' is a String; a Char initializer needs the #'x' literal token so
    // the annotation (Character) and the value type agree.
    assertEquals("Char", typeOf("define c::Char = #'a'"));
    assertEquals("BigInt", typeOf("define b::BigInt = 1"));
  }

  @Test
  void annotatedJavaClass() {
    assertEquals("Date", typeOf("define d::java.util.Date = null"));
  }

  @Test
  void annotatedEliteClass() {
    ELNode node = infer("class Point(x, y)\ndefine p::Point = new Point(1, 2)");
    assertEquals("Point", node.inferredType.toString());
  }

  @Test
  void unresolvableAnnotationIsError() throws Exception {
    // Class load failure reports an error and types the node ErrorType.
    // Messages resolve against the JVM default locale (Resources.getText /
    // ELUtils.getCurrentLocale), which varies per host. Pin English around
    // the inference and restore afterward so the text assertion below is
    // deterministic and does not leak into sibling test classes sharing this
    // forked JVM (e.g. ScopeCaptureTest, whose messages pin a zh locale).
    Locale prior = Locale.getDefault();
    Locale.setDefault(Locale.ENGLISH);
    ParseException ex;
    try {
      ELProgram program = parse("define z::NoSuchClass = 1");
      SymbolTableBuilder.build(program);
      ex = assertThrows(ParseException.class,
        () -> TypeInference.infer(elctx, program));
      ELNode def = program.getExpressions().get(0);
      assertEquals("Error", def.inferredType.toString());
    } finally {
      Locale.setDefault(prior);
    }
    assertTrue(ex.getMessage().contains("Class not found: NoSuchClass"),
      "unexpected message: " + ex.getMessage());
  }

  @Test
  void literals() {
    assertEquals("Boolean", typeOf("true"));
    // Bare 'x' is a one-char String; the character literal token is #'x'.
    // (DefaultLexer.scanCharacter is only reachable via the "#'" prefix.)
    assertEquals("String", typeOf("'x'"));
    assertEquals("Char", typeOf("#'x'"));
    assertEquals("String", typeOf("\"hi\""));
    assertEquals("Int", typeOf("42"));
    assertEquals("Long", typeOf("3000000000"));
    assertEquals("Double", typeOf("1.5"));
    assertEquals("Any", typeOf("null"));
  }

  @Test
  void listLiteral() {
    assertEquals("List[Any]", typeOf("[]"));
  }

  @Test
  void conditionalMerge() {
    assertEquals("Int", typeOf("true ? 1 : 2"));
  }

  @Test
  void unaryAndLogicalOps() {
    assertEquals("Boolean", typeOf("define b = true\n!b"));
    assertEquals("Boolean", typeOf("empty([])"));
    assertEquals("Boolean", typeOf("1 instanceof Int"));
  }

  @Test
  void comparisons() {
    assertEquals("Boolean", typeOf("define a = 1\ndefine b = 2\na < b"));
    assertEquals("Boolean", typeOf("define a = 1\ndefine b = 2\na == b"));
    assertEquals("Boolean", typeOf("define a = 1\ndefine b = 2\na <=> b"));   // CMP
    assertEquals("Boolean", typeOf("define a = 1\na in [1]"));
  }

  @Test
  void logicalOps() {
    assertEquals("Boolean", typeOf("define x = true\ndefine y = false\nx && y"));
    assertEquals("Boolean", typeOf("define x = true\ndefine y = false\nx || y"));
  }

  @Test
  void stringInterpolation() {
    assertEquals("String", typeOf("define n = 42\n\"x=\" ~ n"));
  }

  @Test
  void compoundLastExpression() {
    ELNode.DEFINE f = def("define f() { 1; 2 }", "f");
    ELNode body = ((ELNode.LAMBDA)f.expr).body;
    assertEquals("Int", body.inferredType.toString());
  }

  @Test
  void consList() {
    assertEquals("List[Int]", typeOf("[1, 2, 3]"));
  }

  @Test
  void tupleAndMap() {
    assertEquals("Tuple[String, Int]", typeOf("(\"Bob\", 42)"));
    assertEquals("Map[String, Int]", typeOf("{\"a\": 1, \"b\": 2}"));
  }

  @Test
  void arrayAndNewArray() {
    assertEquals("Array[Int, 2]", typeOf("new int[4][4]"));
    // ARRAY-with-initializer form exercises the element-merge branch:
    // [1,2]-style list literals are CONS chains (List[Int]), not arrays.
    assertEquals("Array[Int, 1]", typeOf("new int[2](1, 2)"));
  }

  @Test
  void rangeType() {
    assertEquals("Range", typeOf("[1..10]"));
  }

  @Test
  void functionType() {
    assertEquals("Function[Int, [Int, Int]]",
      typeOf("define add(x::Int, y::Int)::Int => x + y\ndefine _ = add"));
  }

  @Test
  void functionReturnAnnotationPinsBody() {
    ELNode.DEFINE add = def("define add(x::Int, y::Int)::Int => x + y", "add");
    ELNode body = ((ELNode.LAMBDA)add.expr).body;
    assertEquals("Int", body.inferredType.toString());
  }

  @Test
  void applyUserFunction() {
    assertEquals("Int", typeOf("define add(x::Int, y::Int)::Int => x + y\nadd(1, 2)"));
  }

  @Test
  void immutableVariableFixedByInitializer() {
    // NOTE: no arithmetic in the final expression — x + 1 would be AnyInt
    // once Task 6 lands; this test only asserts the variable typing rule.
    assertEquals("Int", typeOf("define x = 42\nx"));
  }

  @Test
  void mutableVariableMergedFromAssigns() {
    assertEquals("Int", typeOf("define x = 0\nx = 1\nx = 2\nx"));
  }

  @Test
  void mutableVariableConflictIsAny() {
    assertEquals("Any", typeOf("define x = 0\nx = 1\nx = \"s\"\nx"));
  }

  @Test
  void assignopDoesNotChangeType() {
    assertEquals("Int", typeOf("define x = 1\nx += 2\nx"));
  }

  @Test
  void foreachElementType() {
    // Body's last expression is the loop variable; the FOREACH node inherits
    // that type, so the element type of [1, 2] (List[Int]) is observable.
    assertEquals("Int",
      typeOf("for (i in [1, 2]) { print(i); i }"));
  }

  @Test
  void javaMethodReflection() {
    assertEquals("Int", typeOf("java.lang.Integer.parseInt(\"1\")"));
    assertEquals("String", typeOf("\"abc\".substring(1)"));
  }

  @Test
  void newEliteClass() {
    assertEquals("Point", typeOf("class Point(x, y)\nnew Point(1, 2)"));
  }

  @Test
  void listIndexAccess() {
    assertEquals("Int", typeOf("define l = [1, 2, 3]\nl[0]"));
  }

  @Test
  void annotatedAssignMismatchIsError() {
    // An annotated declaration needs an initializer for the parser to carry
    // the ::Int annotation onto the DEFINE; x = "hello" then mismatches.
    // Pin English around inference for a deterministic message.
    Locale prior = Locale.getDefault();
    Locale.setDefault(Locale.ENGLISH);
    ParseException ex;
    try {
      ELProgram program = parse("define x::Int = 0\nx = \"hello\"");
      SymbolTableBuilder.build(program);
      ex = assertThrows(ParseException.class,
        () -> TypeInference.infer(elctx, program));
      // exps[0] = define x::Int = 0, exps[1] = the ASSIGN
      ELNode assign = program.getExpressions().get(1);
      assertEquals("Error", assign.inferredType.toString());
    } finally {
      Locale.setDefault(prior);
    }
    assertTrue(ex.getMessage().contains("Type mismatch"),
      "unexpected message: " + ex.getMessage());
  }

  @Test
  void annotatedInitializerMismatchIsError() {
    Locale prior = Locale.getDefault();
    Locale.setDefault(Locale.ENGLISH);
    try {
      ELProgram program = parse("define x::Int = \"hello\"");
      SymbolTableBuilder.build(program);
      assertThrows(ParseException.class, () -> TypeInference.infer(elctx, program));
      assertEquals("Error", program.getExpressions().get(0).inferredType.toString());
    } finally {
      Locale.setDefault(prior);
    }
  }

  @Test
  void intArithmeticIsAnyInt() {
    // Variable operands: bare integer-literal expressions (1 + 2) are
    // constant-folded at parse time into a single Int NUMBER node, so no ADD
    // subtype survives to exercise the promotion rule. Non-foldable operands
    // (variables) keep the ADD node and yield AnyInt per "未标注的整型算术".
    assertEquals("AnyInt", typeOf("define a = 1\ndefine b = 2\na + b"));
    assertEquals("AnyInt", typeOf("define a = 1\ndefine b = 2\na * b"));
  }

  @Test
  void constantFoldWithinInt() {
    // Both operands are unannotated Ints (via variables, since literal-only
    // expressions fold at parse time); the unannotated int arithmetic rule
    // still yields AnyInt.
    assertEquals("AnyInt", typeOf("define a = 1\ndefine b = 2\na + b"));
  }

  @Test
  void annotatedArithmeticDoesNotPromote() {
    assertEquals("Int", typeOf("define add(x::Int, y::Int)::Int => x + y\nadd(1, 2)"));
  }

  @Test
  void bigintArithmeticIsBigInt() {
    assertEquals("BigInt", typeOf("define b::BigInt = 1\nb + 2"));
  }

  @Test
  void mixedIntLong() {
    assertEquals("Long", typeOf("define l::Long = 1\nl + 1"));
  }

  @Test
  void floatArithmeticIsAnyFloat() {
    // Variable operands (1.5 + 2.5 would constant-fold at parse to a Double).
    assertEquals("AnyFloat", typeOf("define a = 1.5\ndefine b = 2.5\na + b"));
  }

  @Test
  void intFloatMixIsAnyFloat() {
    assertEquals("AnyFloat", typeOf("define d::Double = 1.5\n1 + d"));
  }

  @Test
  void decimalStaysDecimal() {
    assertEquals("Decimal", typeOf("define m::elite.lang.Decimal = 1.5M\nm + 1"));
  }

  @Test
  void numericNarrowingAssignIsError() {
    // The parser only carries a :: annotation onto the DEFINE when it has an
    // initializer (define x::Int alone leaves x untyped), so exercise the
    // narrowing rejection on a later Double assignment once x is pinned Int.
    Locale prior = Locale.getDefault();
    Locale.setDefault(Locale.ENGLISH);
    ParseException ex;
    try {
      ELProgram program = parse("define x::Int = 0\nx = 2.5");
      SymbolTableBuilder.build(program);
      ex = assertThrows(ParseException.class,
        () -> TypeInference.infer(elctx, program));
    } finally {
      Locale.setDefault(prior);
    }
    assertTrue(ex.getMessage().contains("Type mismatch"),
      "unexpected message: " + ex.getMessage());
  }

  @Test
  void numericWideningAssignIsOk() {
    assertEquals("Long", typeOf("define x::Long = 1\nx"));
  }

  @Test
  void mutableVariableSandwichConflictIsAny() {
    assertEquals("Any", typeOf("define x = 0\nx = 1\nx = \"s\"\nx = 2\nx"));
  }

  @Test
  void negateBigIntStaysBigInt() {
    assertEquals("BigInt", typeOf("define b::BigInt = 1\n-b"));
  }

  @Test
  void bigIntWithConcreteFloatsKeepsFloatSide() {
    assertEquals("Double", typeOf("define b::BigInt = 1\ndefine d::Double = 1.5\nb + d"));
    assertEquals("Double", typeOf("define b::BigInt = 1\ndefine d::Double = 1.5\nd + b"));
    // Int initializer widens to the Float annotation (Double would be a
    // narrowing mismatch); the result keeps the concrete Float side.
    assertEquals("Float", typeOf("define b::BigInt = 1\ndefine f::Float = 1\nb + f"));
  }

  @Test
  void bigIntWithAnyFloatIsAnyFloat() {
    // Variable operands so the float arithmetic cannot constant-fold.
    assertEquals("AnyFloat",
      typeOf("define b::BigInt = 1\ndefine d1 = 1.5\ndefine d2 = 2.5\n"
             + "define x = d1 + d2\nb + x"));
  }
}
