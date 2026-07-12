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
        exec("define double(x) => x * 2");
        exec("define addOne(x) => x + 1");
        // The transform operator ->
        assertEquals(11L, evalL("5 -> double -> addOne"));
    }
}
