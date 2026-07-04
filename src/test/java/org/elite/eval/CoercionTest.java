package org.elite.eval;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for TypeCoercion — type conversion between ELite and Java types.
 */
class CoercionTest extends EliteTestBase {

    // ---- Number to string ----

    @Test
    void implicitNumberToStringInConcat() {
        assertEquals("x=42", eval("\"x=\" ~ 42"));
    }

    // ---- Boolean coercion ----

    @Test
    void booleanCoercionInIf() {
        exec("define test(x) { if (x) { 1 } else { 0 } }");
        assertEquals(1L, evalL("test(true)"));
        assertEquals(0L, evalL("test(false)"));
    }

    @Test
    void integerAsBooleanInConditional() {
        assertEquals(1L, evalL("(1 != 0) ? 1 : 0"));
        assertEquals(0L, evalL("(0 != 0) ? 1 : 0"));
    }

    // ---- Null handling ----

    @Test
    void nullComparison() {
        assertEquals(true, eval("null == null"));
        assertEquals(false, eval("null == 0"));
    }

    @Test
    void nullStringConcat() {
        // null ~ "World" → "nullWorld" (Java-compatible: null → "null")
        assertEquals("nullWorld", eval("null ~ \"World\""));
    }

    @Test
    void nullStringConcatRight() {
        // "Hello" ~ null → "Hellonull"
        assertEquals("Hellonull", eval("\"Hello\" ~ null"));
    }

    @Test
    void nullStringConcatBoth() {
        assertEquals("nullnull", eval("null ~ null"));
    }

    // ---- Null arithmetic (NPE per Java convention) ----

    @Test
    void nullAdditionThrowsNPE() {
        assertEvalThrows("null + 5");
    }

    @Test
    void nullSubtractionThrowsNPE() {
        assertEvalThrows("null - 5");
    }

    @Test
    void nullMultiplicationThrowsNPE() {
        assertEvalThrows("null * 5");
    }

    @Test
    void nullDivisionThrowsNPE() {
        assertEvalThrows("null / 5");
    }

    @Test
    void nullNegationThrowsNPE() {
        assertEvalThrows("-null");
    }

    // ---- BigDecimal / BigInteger ----

    @Test
    void bigIntegerOperations() {
        Object result = eval("9999999999999999999 + 1");
        assertNotNull(result);
    }

    // ---- Java type mapping ----

    @Test
    void javaStringIsELString() {
        exec("define s::String = \"hello\"");
        assertEquals("hello", eval("s"));
    }

    @Test
    void javaIntegerIsELInteger() {
        exec("define i::Integer = 42");
        assertEquals(42L, evalL("i"));
    }
}
