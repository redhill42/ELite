package org.operamasks.el.ir;

import static org.junit.jupiter.api.Assertions.*;

import javax.el.ELContext;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Parser;

/**
 * End-to-end tests for the IR compilation + interpretation pipeline.
 * Verifies that the IRInterpreter produces the same results as the AST evaluator.
 */
class IRInterpreterTest {

    private static ELContext elctx;

    @BeforeAll
    static void setup() {
        elctx = ELEngine.createELContext();
    }

    private Object interpret(String expr) {
        ELNode node = Parser.parseExpression(expr);
        IRFunction fn = IRBuilder.compile(node);
        IRInterpreter interp = new IRInterpreter(elctx, fn);
        return interp.execute(null);
    }

    private Object eval(String expr) {
        ELNode node = Parser.parseExpression(expr);
        return node.getValue(new org.operamasks.el.eval.EvaluationContext(elctx));
    }

    // ── Arithmetic ──

    @Test
    void intAddition() {
        assertEquals(30L, ((Number)interpret("10 + 20")).longValue());
    }

    @Test
    void intMultiplication() {
        assertEquals(56L, ((Number)interpret("7 * 8")).longValue());
    }

    @Test
    void doubleAddition() {
        assertEquals(5.86, ((Number)interpret("3.14 + 2.72")).doubleValue(), 0.001);
    }

    @Test
    void doubleDivision() {
        assertEquals(2.5, ((Number)interpret("20.0 / 8.0")).doubleValue(), 0.001);
    }

    @Test
    void precedence() {
        assertEquals(14L, ((Number)interpret("2 + 3 * 4")).longValue());
    }

    @Test
    void remainder() {
        assertEquals(1L, ((Number)interpret("10 % 3")).longValue());
    }

    @Test
    void negation() {
        assertEquals(-5, ((Number)interpret("-5")).intValue());
    }

    @Test
    void complexArith() {
        Object result = interpret("((10 + 5) * 3 - 8) / 2 + 100 * 4");
        assertNotNull(result);
        assertTrue(result instanceof Number);
        assertEquals(eval("((10 + 5) * 3 - 8) / 2 + 100 * 4"), result,
            "IR and AST should produce same result");
    }

    // ── Comparisons ──

    @Test
    void intEquality() {
        assertEquals(true, interpret("100 == 100"));
        assertEquals(false, interpret("5 == 6"));
    }

    @Test
    void intLessThan() {
        assertEquals(true, interpret("50 < 100"));
        assertEquals(false, interpret("100 < 50"));
    }

    @Test
    void intLessEqual() {
        assertEquals(true, interpret("100 <= 100"));
    }

    // ── Booleans ──

    @Test
    void booleanTrue() {
        assertEquals(true, interpret("true"));
    }

    @Test
    void booleanFalse() {
        assertEquals(false, interpret("false"));
    }

    // ── Conditional (ternary ?:) ──

    @Test
    void conditionalTrue() {
        assertEquals(100L, ((Number)interpret("true ? 100 : 200")).longValue());
    }

    @Test
    void conditionalFalse() {
        assertEquals(200L, ((Number)interpret("false ? 100 : 200")).longValue());
    }

    // ── Concatenation ──

    @Test
    void stringConcat() {
        assertEquals("helloworld", interpret("\"hello\" ~ \"world\""));
    }

    // ── Null ──

    @Test
    void nullLiteral() {
        assertNull(interpret("null"));
    }

    // ── Compare IR vs AST results ──

    @Test
    void irMatchesAst() {
        String[] exprs = {
            "10 + 20",
            "7 * 8",
            "100.0 / 3.0",
            "1 + 2 * 3 + 4 * 5",
            "100 == 100",
            "((10 + 5) * 3 - 8) / 2 + 100 * 4",
            "true ? 100 : 200",
            "\"hello\" ~ \" \" ~ \"world\"",
        };

        for (String expr : exprs) {
            Object astResult = eval(expr);
            Object irResult = interpret(expr);
            // Handle Number type differences (e.g., Integer vs Double)
            if (astResult instanceof Number a && irResult instanceof Number b) {
                assertEquals(a.doubleValue(), b.doubleValue(), 0.0001,
                    "Numeric mismatch for: " + expr);
            } else {
                assertEquals(astResult, irResult, "Mismatch for: " + expr);
            }
        }
    }
}
