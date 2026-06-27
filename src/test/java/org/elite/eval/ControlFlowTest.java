package org.elite.eval;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

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

    // ---- Compound assignment inside control flow ----
    // These tests verify that compound assignments (+=, *=, etc.) correctly
    // update local variable slots, not just global storage. The bug was that
    // buildAssignOp only emitted STORE_GLOBAL, missing STORE_VAR.

    @Test
    void ifWithCompoundMultiply() {
        exec("define testIf(x) { if (x % 2 == 0) { x *= 5 }; x }");
        assertEquals(10L, evalL("testIf(2)"));
        assertEquals(3L, evalL("testIf(3)"));
    }

    @Test
    void ifWithCompoundAdd() {
        exec("define testIf(x) { if (x > 0) { x += 10 }; x }");
        assertEquals(15L, evalL("testIf(5)"));
        assertEquals(-3L, evalL("testIf(-3)"));
    }

    @Test
    void ifElseWithCompoundAssign() {
        exec("define signMul(x) { if (x >= 0) { x *= 2 } else { x *= -1 }; x }");
        assertEquals(20L, evalL("signMul(10)"));
        assertEquals(3L, evalL("signMul(-3)"));
    }

    @Test
    void compoundAssignInBothBranches() {
        exec("define adjust(x) { if (x % 2 == 0) { x += 1 } else { x -= 1 }; x }");
        assertEquals(5L, evalL("adjust(4)"));
        assertEquals(4L, evalL("adjust(5)"));
    }

    @Test
    void compoundAssignInForLoop() {
        exec("define sumRange(n) { define s = 0; for (i in [1..n]) { s += i }; s }");
        assertEquals(55L, evalL("sumRange(10)"));
        assertEquals(1L, evalL("sumRange(1)"));
    }

    @Test
    void compoundAssignInWhileLoop() {
        exec("define pow2(n) { define r = 1; while (n > 0) { r *= 2; n -= 1 }; r }");
        assertEquals(8L, evalL("pow2(3)"));
        assertEquals(1L, evalL("pow2(0)"));
    }

    @Test
    void multipleCompoundAssignInSequence() {
        exec("define seq() { define x = 2; x *= 3; x += 4; x -= 1; x }");
        assertEquals(9L, evalL("seq()"));
    }

    @Test
    void compoundAssignWithMultipleReads() {
        // Verify that reading a variable before and after compound assignment
        // returns the correct values (tests local slot update).
        exec("define readTwice() { define x = 2; define a = x; x *= 5; define b = x; [a, b] }");
        assertEquals("[2, 10]", eval("readTwice()").toString());
    }

    @Test
    void nestedIfWithCompoundAssign() {
        exec("define nest(x) { if (x > 0) { if (x % 2 == 0) { x *= 3 } else { x *= 5 } }; x }");
        assertEquals(6L, evalL("nest(2)"));
        assertEquals(15L, evalL("nest(3)"));
        assertEquals(-1L, evalL("nest(-1)"));
    }

    @Test
    void compoundAssignWithStringEqualityCondition() {
        // Dynamic comparison on strings — forces DYNEQ in condition,
        // which exercises the tryDeoptSplit / specializeBlockSimple paths.
        exec("define f(x) { if (x == \"yes\") { x = \"got-it\" }; x }");
        assertEquals("got-it", eval("f(\"yes\")"));
        assertEquals("nope", eval("f(\"nope\")"));
    }

    // ---- Conditional with pipe ----

    @Test
    void conditionalAsExpression() {
        exec("define abs(x) => x >= 0 ? x : -x");
        assertEquals(10L, evalL("abs(10)"));
        assertEquals(10L, evalL("abs(-10)"));
    }
}
