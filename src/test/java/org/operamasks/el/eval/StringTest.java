package org.operamasks.el.eval;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.operamasks.el.EliteTestBase;

/**
 * Tests for string interpolation, multiline strings, stream operator, and string operations.
 */
class StringTest extends EliteTestBase {

    // ---- String interpolation ----

    @Test
    void simpleInterpolation() {
        exec("define name = \"World\"");
        assertEquals("Hello, World!", eval("\"Hello, ${name}!\""));
    }

    @Test
    void expressionInterpolation() {
        assertEquals("1 + 2 = 3", eval("\"1 + 2 = ${1 + 2}\""));
    }

    @Test
    void multipleInterpolations() {
        exec("define a = 10");
        exec("define b = 20");
        assertEquals("10 + 20 = 30", eval("\"${a} + ${b} = ${a + b}\""));
    }

    // ---- String concatenation ----

    @Test
    void stringConcatOperator() {
        assertEquals("HelloWorld", eval("\"Hello\" ~ \"World\""));
    }

    @Test
    void stringConcatMultiple() {
        assertEquals("abc", eval("\"a\" ~ \"b\" ~ \"c\""));
    }

    @Test
    void stringConcatWithNumber() {
        assertEquals("x=42", eval("\"x=\" ~ 42"));
    }

    // ---- String escape sequences ----

    @Test
    void stringWithTab() {
        assertEquals("a\tb", eval("\"a\\tb\""));
    }

    @Test
    void stringWithNewline() {
        assertEquals("line1\nline2", eval("\"line1\\nline2\""));
    }

    @Test
    void stringWithQuote() {
        assertEquals("say \"hi\"", eval("\"say \\\"hi\\\"\""));
    }

    // ---- Java string methods ----

    @Test
    void stringLength() {
        assertEquals(5, eval("\"hello\".length()"));
    }

    @Test
    void stringToUpperCase() {
        assertEquals("HELLO", eval("\"hello\".toUpperCase()"));
    }

    @Test
    void stringSubstring() {
        assertEquals("ell", eval("\"hello\".substring(1, 4)"));
    }
}
