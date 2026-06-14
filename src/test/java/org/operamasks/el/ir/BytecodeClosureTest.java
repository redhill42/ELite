package org.operamasks.el.ir;

import static org.junit.jupiter.api.Assertions.*;

import javax.el.ELContext;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Parser;

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
        ELNode node = Parser.parseExpression(expr);
        IRFunction fn = IRBuilder.compile(node);
        IRBytecodeCompiler.CompiledFunction cf = IRBytecodeCompiler.compile(fn);
        assertNotNull(cf, "compilation must succeed");
        return cf.execute(elctx, null);
    }

    @Test
    void closureCompilesAndRuns() {
        var p = new Parser("define f(x) => \\y => x+y; f(1)(2)");
        var prog = p.parse();
        IRFunction fn = IRBuilder.compileWithDefs(prog.getDefinitions(), prog.getExpressions());
        IRBytecodeCompiler.CompiledFunction cf = IRBytecodeCompiler.compile(fn);
        assertNotNull(cf, "compilation must succeed");
        Object result = cf.execute(elctx, null);
        assertNotNull(result, "execution must produce a result");
        assertEquals(3L, ((Number) result).longValue(), "f(1)(2) = 3");
    }

    @Test
    void simpleCallCompilesAndRuns() {
        var p = new Parser("define add(a,b) => a + b; add(1, 2)");
        var prog = p.parse();
        IRFunction fn = IRBuilder.compileWithDefs(prog.getDefinitions(), prog.getExpressions());
        IRBytecodeCompiler.CompiledFunction cf = IRBytecodeCompiler.compile(fn);
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
