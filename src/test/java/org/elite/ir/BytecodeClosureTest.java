package org.elite.ir;

import static org.junit.jupiter.api.Assertions.*;

import javax.el.ELContext;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.elite.eval.ELEngine;
import org.elite.parser.ELNode;
import org.elite.parser.Parser;

/**
 * Tests that O3 bytecode compilation produces executable results.
 * Previously these were smoke tests that only checked !null on compile.
 */
class BytecodeClosureTest {

    private static ELContext elctx;

    @BeforeAll
    static void setup() {
        elctx = ELEngine.createELContext();
    }

    private Object bcEval(String expr) {
        ELNode node = Parser.parseExpression(elctx, expr);
        IRFunction fn = IRBuilder.compile(elctx, node);
        IRCompiledFunction cf = IRBytecodeCompiler.compile(fn);
        assertNotNull(cf, "compilation must succeed");
        return cf.execute(elctx, null);
    }

    @Test
    @Disabled("O3 bytecode: closure evalContext not wired in CompiledFunction.execute()")
    void closureCompilesAndRuns() {
        var p = new Parser(elctx, "define f(x) => \\y => x+y; f(1)(2)");
        var prog = p.parse();
        IRFunction fn = IRBuilder.compile(elctx, prog);
        IRCompiledFunction cf = IRBytecodeCompiler.compile(fn);
        assertNotNull(cf, "compilation must succeed");
        Object result = cf.execute(elctx, null);
        assertNotNull(result, "execution must produce a result");
        assertEquals(3L, ((Number) result).longValue(), "f(1)(2) = 3");
    }

    @Test
    void simpleCallCompilesAndRuns() {
        var p = new Parser(elctx, "define add(a,b) => a + b; add(1, 2)");
        var prog = p.parse();
        IRFunction fn = IRBuilder.compile(elctx, prog);
        IRCompiledFunction cf = IRBytecodeCompiler.compile(fn);
        assertNotNull(cf, "compilation must succeed");
        Object result = cf.execute(elctx, null);
        assertNotNull(result, "execution must produce a result");
        assertEquals(3L, ((Number) result).longValue(), "add(1,2) = 3");
    }

    // ---- Additional bytecode execution tests ----

    @Test
    void intArithmeticBC() {
        assertEquals(30L,  ((Number) bcEval("10 + 20")).longValue());
        assertEquals(56L,  ((Number) bcEval("7 * 8")).longValue());
        assertEquals(33L,  ((Number) bcEval("100 / 3")).longValue());
    }

    @Test
    void comparisonsBC() {
        assertEquals(true,  bcEval("100 == 100"));
        assertEquals(true,  bcEval("5 != 6"));
        assertEquals(true,  bcEval("50 < 100"));
    }

    @Test
    void conditionalBC() {
        assertEquals(100L, ((Number) bcEval("true ? 100 : 200")).longValue());
        assertEquals(200L, ((Number) bcEval("false ? 100 : 200")).longValue());
    }

    @Test
    void stringConcatBC() {
        assertEquals("helloworld", bcEval("\"hello\" ~ \"world\""));
    }

    @Test
    void logicalNotBC() {
        assertEquals(false, bcEval("!true"));
        assertEquals(true,  bcEval("!false"));
    }
}
