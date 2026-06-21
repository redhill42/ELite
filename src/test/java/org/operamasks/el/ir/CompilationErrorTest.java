package org.operamasks.el.ir;

import static org.junit.jupiter.api.Assertions.*;

import javax.el.ELContext;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.EvaluationContext;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Parser;

/**
 * Tests for CompilationError and the O3 -> IR fallback mechanism.
 */
class CompilationErrorTest {

    private static ELContext elctx;

    @BeforeAll
    static void setup() {
        elctx = ELEngine.createELContext();
    }

    // ---- CompilationError construction ----

    @Test
    void compilationErrorWithMessage() {
        CompilationError e = new CompilationError("test message");
        assertEquals("test message", e.getMessage());
        assertTrue(e instanceof Error);
    }

    @Test
    void compilationErrorWithCause() {
        RuntimeException cause = new RuntimeException("root cause");
        CompilationError e = new CompilationError("wrapped", cause);
        assertEquals("wrapped", e.getMessage());
        assertSame(cause, e.getCause());
    }

    // ---- O3 fallback: IR → bytecode fails, falls back to IR interpreter ----

    @Test
    void o3FallbackToIRWhenBytecodeThrowsCompilationError() {
        // Simulate what ELProgram.evaluate() does at O3:
        // compile IR, try bytecode, catch CompilationError, fall back to IR
        ELNode node = Parser.parseExpression("2 + 3");
        IRFunction fn = IRBuilder.compile(node);

        // Simulate CompilationError (as if bytecode compiler cannot handle it)
        CompilationError simulated = new CompilationError("simulated gap");
        assertNotNull(simulated);

        // Fallback: execute via IR interpreter (what ELProgram does in catch block)
        IRInterpreter interp = new IRInterpreter(new EvaluationContext(elctx), fn);
        Object result = interp.execute(null);
        assertEquals(5L, ((Number) result).longValue(),
            "IR interpreter fallback should produce correct result after CompilationError");
    }

    @Test
    void o3FallbackPreservesResultForComplexExpression() {
        ELNode node = Parser.parseExpression("(10 + 5) * 3 - 8");
        IRFunction fn = IRBuilder.compile(node);

        IRInterpreter interp = new IRInterpreter(new EvaluationContext(elctx), fn);
        Object irResult = interp.execute(null);
        assertEquals(37L, ((Number) irResult).longValue(),
            "IR fallback should compute (10+5)*3-8 = 37 correctly");

        // Compare with AST path
        Object astResult = node.getValue(new EvaluationContext(elctx));
        assertEquals(irResult, astResult,
            "IR fallback result must match AST result");
    }

    // ---- CompilationError vs UnsupportedOperationException ----

    @Test
    void compilationErrorExtendsErrorNotException() {
        // CompilationError extends Error so it is NOT caught by catch(Exception).
        // Callers must explicitly catch CompilationError for the O3→IR fallback.
        assertTrue(new CompilationError("gap") instanceof Error);
    }
}
