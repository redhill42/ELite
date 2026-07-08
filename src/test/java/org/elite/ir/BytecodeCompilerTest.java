package org.elite.ir;

import static org.junit.jupiter.api.Assertions.*;

import javax.el.ELContext;

import org.junit.jupiter.api.Test;
import org.elite.eval.ELEngine;
import org.elite.parser.ELNode;
import org.elite.parser.Parser;

class BytecodeCompilerTest {

    private static final ELContext elctx = ELEngine.createELContext();

    private Object bcEval(String expr) {
        ELNode node = Parser.parseExpression(elctx, expr);
        IRFunction fn = IRBuilder.compile(elctx, node);
        IRCompiledFunction cf = IRBytecodeCompiler.compile(fn);
        return cf.execute(elctx, null);
    }

    @Test void intAdd()  { assertEquals(30L, ((Number)bcEval("10 + 20")).longValue()); }
    @Test void intSub()  { assertEquals(63L, ((Number)bcEval("100 - 37")).longValue()); }
    @Test void intMul()  { assertEquals(56L, ((Number)bcEval("7 * 8")).longValue()); }
    @Test void intDiv()  { assertEquals(33L, ((Number)bcEval("100 / 3")).longValue()); }
    @Test void intRem()  { assertEquals(1L, ((Number)bcEval("10 % 3")).longValue()); }
    @Test void intNeg()  { assertEquals(-5, ((Number)bcEval("-5")).intValue()); }
    @Test void doubleAdd() { assertEquals(5.86, ((Number)bcEval("3.14 + 2.72")).doubleValue(), 0.001); }
    @Test void doubleMul() { assertEquals(3.0, ((Number)bcEval("1.5 * 2.0")).doubleValue(), 0.001); }

    @Test void intEq()  { assertEquals(true, bcEval("100 == 100")); }
    @Test void intNe()  { assertEquals(true, bcEval("5 != 6")); }
    @Test void intLt()  { assertEquals(true, bcEval("50 < 100")); }
    @Test void intLe()  { assertEquals(true, bcEval("100 <= 100")); }
    @Test void intGt()  { assertEquals(true, bcEval("200 > 100")); }
    @Test void intGe()  { assertEquals(true, bcEval("100 >= 100")); }

    @Test void doubleEq()  { assertEquals(true, bcEval("3.14 == 3.14")); }
    @Test void doubleLt()  { assertEquals(true, bcEval("1.0 < 2.0")); }

    @Test void arithmeticSeq() {
        assertEquals(14L, ((Number)bcEval("2 + 3 * 4")).longValue());
    }

    // ─── Control flow ───

    @Test void conditionalTrue()  { assertEquals(100L, ((Number)bcEval("true ? 100 : 200")).longValue()); }
    @Test void conditionalFalse() { assertEquals(200L, ((Number)bcEval("false ? 100 : 200")).longValue()); }
    @Test void intNot()  { assertEquals(false, bcEval("!true")); }
    @Test void intAnd()  { assertEquals(true, bcEval("true && true")); assertEquals(false, bcEval("true && false")); }
    @Test void intOr()   { assertEquals(true, bcEval("true || false")); assertEquals(false, bcEval("false || false")); }
    @Test void coalesce() { assertEquals(100L, ((Number)bcEval("100 ?? 200")).longValue()); }
    @Test void nullCoalesce() { assertEquals(200L, ((Number)bcEval("null ?? 200")).longValue()); }

    // ─── Function calls ───
    @Test void simpleCall() {
        Parser p = new Parser(elctx, "define add(x,y) => x + y; add(3, 4)");
        var prog = p.parse();
        IRFunction fn = IRBuilder.compile(elctx, prog);
        IRCompiledFunction cf = IRBytecodeCompiler.compile(fn);
        assertEquals(7L, ((Number)cf.execute(elctx, null)).longValue());
    }

    @Test void callWithSingleArg() {
        Parser p = new Parser(elctx, "define sq(x) => x * x; sq(5)");
        var prog = p.parse();
        IRFunction fn = IRBuilder.compile(elctx, prog);
        IRCompiledFunction cf = IRBytecodeCompiler.compile(fn);
        assertEquals(25L, ((Number)cf.execute(elctx, null)).longValue());
    }

    // ─── Default params with explicit null (P0-2) ───
}
