package org.elite.eval;

import static org.junit.jupiter.api.Assertions.*;

import javax.el.ELContext;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.elite.ir.IRBuilder;
import org.elite.ir.IRBytecodeCompiler;
import org.elite.ir.IRFunction;
import org.elite.parser.ELNode;
import org.elite.parser.Parser;

/**
 * End-to-end tests for the O3 (JVM bytecode) path.
 *
 * These tests compile IR via IRBuilder then execute via
 * IRBytecodeCompiler.CompiledFunction, covering the full
 * IR → bytecode → execution pipeline without going through
 * ScriptEngine (which caches OPT_LEVEL as a static final).
 */
class BytecodeE2ETest {

    private static ELContext elctx;

    @BeforeAll
    static void setup() {
        elctx = ELEngine.createELContext();
    }

    /** Compile and execute a single expression through the bytecode path. */
    private Object bcEval(String expr) {
        ELNode node = Parser.parseExpression(expr);
        IRFunction fn = IRBuilder.compile(elctx, node);
        IRBytecodeCompiler.CompiledFunction cf = IRBytecodeCompiler.compile(fn);
        assertNotNull(cf);
        return cf.execute(elctx, null);
    }

    private long bcEvalL(String expr) {
        return ((Number) bcEval(expr)).longValue();
    }

    /** Compile and execute a program with definitions through the bytecode path. */
    private Object bcEvalProgram(String src) {
        var p = new Parser(src);
        var prog = p.parse();
        IRFunction fn = IRBuilder.compile(elctx, prog);
        IRBytecodeCompiler.CompiledFunction cf = IRBytecodeCompiler.compile(fn);
        assertNotNull(cf);
        return cf.execute(elctx, null);
    }

    // ---- Arithmetic ----

    @Test void intAdd() { assertEquals(30L, bcEvalL("10 + 20")); }
    @Test void intSub() { assertEquals(63L, bcEvalL("100 - 37")); }
    @Test void intMul() { assertEquals(56L, bcEvalL("7 * 8")); }
    @Test void intDiv() { assertEquals(33L, bcEvalL("100 / 3")); }
    @Test void doubleAdd() { assertEquals(5.86, ((Number) bcEval("3.14 + 2.72")).doubleValue(), 0.001); }

    // ---- Comparisons ----

    @Test void intEq()  { assertEquals(true, bcEval("100 == 100")); }
    @Test void intNe()  { assertEquals(true, bcEval("5 != 6")); }
    @Test void intLt()  { assertEquals(true, bcEval("50 < 100")); }
    @Test void intLe()  { assertEquals(true, bcEval("100 <= 100")); }
    @Test void intGt()  { assertEquals(true, bcEval("200 > 100")); }
    @Test void intGe()  { assertEquals(true, bcEval("100 >= 100")); }
    @Test void stringEq() { assertEquals(true, bcEval("\"x\" == \"x\"")); }
    @Test void stringNe() { assertEquals(true, bcEval("\"a\" != \"b\"")); }

    // ---- Strings ----

    @Test void stringConcat() { assertEquals("helloworld", bcEval("\"hello\" ~ \"world\"")); }

    // ---- Booleans / Logic ----

    @Test void boolTrue()  { assertEquals(true, bcEval("true")); }
    @Test void boolFalse() { assertEquals(false, bcEval("false")); }
    @Test void logicalNot() { assertEquals(false, bcEval("!true")); }

    // ---- Conditional ----

    @Test void conditionalTrue()  { assertEquals(100L, bcEvalL("true ? 100 : 200")); }
    @Test void conditionalFalse() { assertEquals(200L, bcEvalL("false ? 100 : 200")); }

    // ---- Functions (compileWithDefs) ----

    @Test void simpleFunction() {
        Object r = bcEvalProgram("define add(a, b) => a + b; add(3, 4)");
        assertEquals(7L, ((Number) r).longValue());
    }

    @Test void functionWithDefaultParams() {
        Object r = bcEvalProgram("define f(x = 42) => x; f()");
        assertEquals(42L, ((Number) r).longValue());
        // explicit null must stay null
        Object r2 = bcEvalProgram("define f(x = 42) => x; f(null)");
        assertNull(r2);
    }

    @Test
    @Disabled("O3 bytecode: closure evalContext not wired in CompiledFunction.execute()")
    void closureCapture() {
        // f(1)(2) = 1 + 2 = 3
        Object r = bcEvalProgram("define f(x) => \\y => x + y; f(1)(2)");
        assertEquals(3L, ((Number) r).longValue());
    }

    // ---- Collections ----

    @Test void listLiteral() {
        assertEquals("[1, 2, 3]", bcEval("[1, 2, 3]").toString());
    }

    @Test void rangeLiteral() {
        assertNotNull(bcEval("[1..5]"));
    }

    // ---- Variable assignment ----

    @Test void sequentialDefine() {
        Object r = bcEvalProgram("define x = 10; define y = 20; x + y");
        assertEquals(30L, ((Number) r).longValue());
    }
}
