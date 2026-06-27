package org.elite.parser;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for ELite parsing and evaluation via ScriptEngine.
 * Exercises the full lexer → parser → AST → evaluator pipeline.
 */
class ParserTest extends EliteTestBase {

    // ---- Integer literals ----

    @Test
    void parseIntegerLiteral() {
        assertEquals(42L, evalL("42"));
    }

    @Test
    void parseNegativeInteger() {
        assertEquals(-10L, evalL("-10"));
    }

    @Test
    void parseHexLiteral() {
        assertEquals(255L, evalL("0xFF"));
    }

    @Test
    void parseLargeHexLiteral() {
        assertEquals(65535L, evalL("0xFFFF"));
    }

    // ---- Floating point literals ----

    @Test
    void parseFloatLiteral() {
        assertEquals(3.14, evalD("3.14"), 0.001);
    }

    @Test
    void parseScientificNotation() {
        assertEquals(1500.0, evalD("1.5e3"), 0.001);
    }

    @Test
    void parseNegativeFloat() {
        assertEquals(-2.5, evalD("-2.5"), 0.001);
    }

    // ---- String literals ----

    @Test
    void parseStringLiteral() {
        assertEquals("hello", eval("\"hello\""));
    }

    @Test
    void parseStringWithEscape() {
        assertEquals("hello\tworld", eval("\"hello\\tworld\""));
    }

    @Test
    void parseStringWithNewline() {
        assertEquals("line1\nline2", eval("\"line1\\nline2\""));
    }

    @Test
    void parseEmptyString() {
        assertEquals("", eval("\"\""));
    }

    @Test
    void parseStringWithUnicodeEscape() {
        assertEquals("A", eval("\"\\u0041\""));
    }

    // ---- Character literals ----

    @Test
    void parseCharLiteral() {
        Object result = eval("'a'");
        assertNotNull(result);
    }

    @Test
    void parseCharEscape() {
        Object result = eval("'\\n'");
        assertNotNull(result);
    }

    // ---- Boolean and null literals ----

    @Test
    void parseTrue() {
        assertEquals(true, eval("true"));
    }

    @Test
    void parseFalse() {
        assertEquals(false, eval("false"));
    }

    @Test
    void parseNull() {
        assertNull(eval("null"));
    }

    // ---- Arithmetic ----

    @Test
    void addition() {
        assertEquals(3L, evalL("1 + 2"));
    }

    @Test
    void subtraction() {
        assertEquals(7L, evalL("10 - 3"));
    }

    @Test
    void multiplication() {
        assertEquals(20L, evalL("4 * 5"));
    }

    @Test
    void remainder() {
        assertEquals(1L, evalL("10 % 3"));
    }

    @Test
    void floatingPointDivision() {
        assertEquals(3.333, evalD("10 / 3"), 0.001);
    }

    @Test
    void power() {
        assertEquals(256L, evalL("2 ^ 8"));
    }

    @Test
    void complexArithmetic() {
        assertEquals(14L, evalL("2 + 3 * 4"));
    }

    @Test
    void parenthesizedArithmetic() {
        assertEquals(20L, evalL("(2 + 3) * 4"));
    }

    @Test
    void unaryNegation() {
        assertEquals(-42L, evalL("-42"));
    }

    @Test
    void floatingPointArithmetic() {
        assertEquals(7.5, evalD("3.0 * 2.5"), 0.001);
    }

    @Test
    void unaryPlus() {
        assertEquals(42L, evalL("+42"));
    }

    // ---- Comparison ----

    @Test
    void equal() {
        assertEquals(true, eval("1 == 1"));
    }

    @Test
    void notEqual() {
        assertEquals(true, eval("1 != 2"));
    }

    @Test
    void lessThan() {
        assertEquals(true, eval("1 < 2"));
    }

    @Test
    void greaterThan() {
        assertEquals(true, eval("5 > 3"));
    }

    @Test
    void lessThanOrEqual() {
        assertEquals(true, eval("2 <= 2"));
    }

    @Test
    void greaterThanOrEqual() {
        assertEquals(false, eval("3 >= 5"));
    }

    // ---- Logical ----

    @Test
    void logicalAnd() {
        assertEquals(false, eval("true && false"));
    }

    @Test
    void logicalOr() {
        assertEquals(true, eval("true || false"));
    }

    @Test
    void logicalNot() {
        assertEquals(false, eval("!true"));
    }

    // ---- String concatenation ----

    @Test
    void stringConcat() {
        assertEquals("hello world", eval("\"hello\" ~ \" world\""));
    }

    // ---- Conditional ----

    @Test
    void conditionalTrue() {
        assertEquals(1L, evalL("true ? 1 : 2"));
    }

    @Test
    void conditionalFalse() {
        assertEquals(2L, evalL("false ? 1 : 2"));
    }

    // ---- List expressions ----

    @Test
    void emptyList() {
        Object result = eval("[]");
        assertTrue(result instanceof java.util.List);
        assertEquals(0, ((java.util.List<?>) result).size());
    }

    @Test
    void listLiteral() {
        Object result = eval("[1, 2, 3]");
        assertTrue(result instanceof java.util.List);
        assertEquals(3, ((java.util.List<?>) result).size());
    }

    // ---- Error cases ----

    @Test
    void parseUnclosedStringThrows() {
        assertEvalThrows("\"unclosed");
    }

    @Test
    void parseUnmatchedParenThrows() {
        assertEvalThrows("(1 + 2");
    }
}
