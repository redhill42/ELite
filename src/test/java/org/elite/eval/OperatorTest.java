package org.elite.eval;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for operator overloading, compound assignment, bitwise operators, and reverse resolution.
 */
class OperatorTest extends EliteTestBase {

    // ---- Bitwise operators ----

    @Test
    void bitwiseOr() {
        assertEquals(7L, evalL("3 `| 4"));
    }

    @Test
    void bitwiseAnd() {
        assertEquals(1L, evalL("5 `& 3"));
    }

    @Test
    void bitwiseXor() {
        assertEquals(6L, evalL("3 `^ 5"));
    }

    @Test
    void bitwiseShiftLeft() {
        assertEquals(8L, evalL("1 << 3"));
    }

    @Test
    void bitwiseShiftRight() {
        assertEquals(2L, evalL("16 >> 3"));
    }

    @Test
    void bitwiseUnsignedShiftRight() {
        assertEquals(2L, evalL("16 >>> 3"));
    }

    @Test
    void bitwiseNot() {
        assertEquals(-6L, evalL("`!5"));
    }

    // ---- Compound assignment ----

    @Test
    void compoundAdd() {
        exec("define x = 10");
        exec("x += 5");
        assertEquals(15L, evalL("x"));
    }

    @Test
    void compoundSubtract() {
        exec("define x = 10");
        exec("x -= 3");
        assertEquals(7L, evalL("x"));
    }

    @Test
    void compoundMultiply() {
        exec("define x = 4");
        exec("x *= 3");
        assertEquals(12L, evalL("x"));
    }

    @Test
    void compoundDivide() {
        exec("define x = 12");
        exec("x /= 3");
        assertEquals(4L, evalL("x"));
    }

    @Test
    void compoundModulo() {
        exec("define x = 10");
        exec("x %= 3");
        assertEquals(1L, evalL("x"));
    }

    // ---- Unary operators ----

    @Test
    void unaryPlus() {
        assertEquals(42L, evalL("+42"));
    }

    @Test
    void unaryMinus() {
        assertEquals(-42L, evalL("-42"));
    }

    @Test
    void logicalNot() {
        assertEquals(true, eval("!false"));
    }

    // ---- String concatenation (custom operator) ----

    @Test
    void stringConcatOperator() {
        assertEquals("Hello, World", eval("\"Hello\" ~ \", \" ~ \"World\""));
    }

    // ---- Stream operator ----

    @Test
    void streamOperator() {
        exec("\"hello, world\" -> print");
    }

    // ---- String/number concat via plus (if operator-overloaded) ----

    @Test
    void operatorResolution() {
        exec("""
             define double(x) => x * 2
             define addOne(x) => x + 1
             // The transform operator ->
             assert (5 -> double -> addOne) == 11
             """);
    }

    // ---- Spaceship operator ----

    @Test
    void spaceshipLessThan() {
        assertEquals(-1L, evalL("3 <=> 5"));
    }

    @Test
    void spaceshipEqual() {
        assertEquals(0L, evalL("5 <=> 5"));
    }

    @Test
    void spaceshipGreaterThan() {
        assertEquals(1L, evalL("7 <=> 5"));
    }

    @Test
    void spaceshipString() {
        // String.compareTo returns the difference between characters,
        // not just -1/0/1.
        assertTrue(evalL("\"abc\" <=> \"abd\"") < 0);
        assertEquals(0L, evalL("\"hello\" <=> \"hello\""));
        assertTrue(evalL("\"xyz\" <=> \"abc\"") > 0);
    }

    @Test
    void spaceshipNonComparableThrows() {
        assertEvalThrows("[1,2,3] <=> [4,5,6]");
    }

    // ---- Spaceship precedence (CMP_PREC=115, between ORD_PREC=110 and SHIFT_PREC=120) ----

    @Test
    void spaceshipBindsTighterThanComparison() {
        // CMP_PREC=115 > ORD_PREC=110
        // 5 < 3 <=> 7 = 5 < (3 <=> 7) = 5 < -1 = false
        assertEquals(false, eval("5 < 3 <=> 7"));
    }

    @Test
    void spaceshipBindsLooserThanShift() {
        // CMP_PREC=115 < SHIFT_PREC=120
        // 3 <=> 7 << 1 = 3 <=> (7 << 1) = 3 <=> 14 = -1
        assertEquals(-1L, evalL("3 <=> 7 << 1"));
    }
}
