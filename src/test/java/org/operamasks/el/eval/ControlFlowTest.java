package org.operamasks.el.eval;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.operamasks.el.EliteTestBase;

/**
 * Tests for control flow: if/else, for/in, while, do/while, break, continue, return.
 */
class ControlFlowTest extends EliteTestBase {

    // ---- if/else ----

    @Test
    void ifWithParentheses() {
        exec("define testIf(x) { if (x > 0) { 1 } else { -1 } }");
        assertEquals(1L, evalL("testIf(5)"));
        assertEquals(-1L, evalL("testIf(-3)"));
    }

    @Test
    @org.junit.jupiter.api.Disabled("if without parentheses ('if x > 0 => ...') not supported")
    void ifWithoutParentheses() {
        exec("define testIf(x) { if x > 0 => 1 else => -1 }");
        assertEquals(1L, evalL("testIf(5)"));
    }

    @Test
    void ifWithBlockBody() {
        exec("define abs(x) { if (x >= 0) { x } else { -x } }");
        assertEquals(5L, evalL("abs(5)"));
        assertEquals(5L, evalL("abs(-5)"));
    }

    @Test
    void ifElseIfChain() {
        exec("define sign(x) { if (x > 0) { 1 } else if (x < 0) { -1 } else { 0 } }");
        assertEquals(1L, evalL("sign(10)"));
        assertEquals(-1L, evalL("sign(-10)"));
        assertEquals(0L, evalL("sign(0)"));
    }

    // ---- for/in loop ----

    @Test
    void forInRange() {
        exec("define sum(n) { define s = 0; for (i in [1..n]) { s = s + i }; s }");
        assertEquals(55L, evalL("sum(10)"));
    }

    @Test
    @org.junit.jupiter.api.Disabled("Multi-generator for-in syntax not supported")
    void forInWithIndex() {
        exec("define sum(n) { define s = 0; for (x in [1..n], i in [0..]) { s = s + x }; s }");
        assertEquals(55L, evalL("sum(10)"));
    }

    // ---- while loop ----

    @Test
    void whileLoop() {
        exec("define countdown(n) { define i = n; define s = 0; while (i > 0) { s = s + i; i = i - 1 }; s }");
        assertEquals(55L, evalL("countdown(10)"));
    }

    // ---- do/while loop ----

    @Test
    @org.junit.jupiter.api.Disabled("do/while loop not supported in current build")
    void doWhileLoop() {
        exec("define firstPositive() { define x = 0; do { x = x + 1 } while (x <= 0); x }");
        assertEquals(1L, evalL("firstPositive()"));
    }

    // ---- return from function ----

    @Test
    void earlyReturn() {
        exec("define early(x) { if (x > 10) { return x }; x + 1 }");
        assertEquals(20L, evalL("early(20)"));
        assertEquals(6L, evalL("early(5)"));
    }

    // ---- Break and continue ----

    @Test
    void breakInLoop() {
        exec("define findFirst(n) { define r = 0; for (i in [1..n]) { if (i > 3) { break }; r = i }; r }");
        assertEquals(3L, evalL("findFirst(10)"));
    }

    @Test
    void continueInLoop() {
        exec("define sumOdds(n) { define s = 0; for (i in [1..n]) { if (i % 2 == 0) { continue }; s = s + i }; s }");
        assertEquals(25L, evalL("sumOdds(9)"));
    }

    // ---- Nested loops ----

    @Test
    void nestedForLoops() {
        exec("define pairs(n) { define r = []; for (i in [1..n]) { for (j in [1..i]) { r = r ~ [i * j] } }; r.size() }");
        assertEquals(15L, evalL("pairs(5)"));
    }

    // ---- Conditional with pipe ----

    @Test
    void conditionalAsExpression() {
        exec("define abs(x) => x >= 0 ? x : -x");
        assertEquals(10L, evalL("abs(10)"));
        assertEquals(10L, evalL("abs(-10)"));
    }
}
