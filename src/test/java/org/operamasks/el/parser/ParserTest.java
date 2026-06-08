/*
 * Copyright (c) 2006-2011 Daniel Yuan.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see http://www.gnu.org/licenses.
 */

package org.operamasks.el.parser;

import static org.junit.Assert.*;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for ELite parsing and evaluation via ScriptEngine.
 * Exercises the full lexer → parser → AST → evaluator pipeline.
 */
public class ParserTest {

    private ScriptEngine engine;

    @Before
    public void setUp() {
        engine = new ScriptEngineManager().getEngineByName("ELite");
    }

    private long evalL(String expr) throws ScriptException {
        return ((Number) engine.eval(expr)).longValue();
    }

    private double evalD(String expr) throws ScriptException {
        return ((Number) engine.eval(expr)).doubleValue();
    }

    // ---- Integer literals ----

    @Test
    public void testParseIntegerLiteral() throws ScriptException {
        assertEquals(42L, evalL("42"));
    }

    @Test
    public void testParseNegativeInteger() throws ScriptException {
        assertEquals(-10L, evalL("-10"));
    }

    @Test
    public void testParseHexLiteral() throws ScriptException {
        assertEquals(255L, evalL("0xFF"));
    }

    // ---- Floating point literals ----

    @Test
    public void testParseFloatLiteral() throws ScriptException {
        assertEquals(3.14, evalD("3.14"), 0.001);
    }

    @Test
    public void testParseScientificNotation() throws ScriptException {
        assertEquals(1500.0, evalD("1.5e3"), 0.001);
    }

    // ---- String literals ----

    @Test
    public void testParseStringLiteral() throws ScriptException {
        assertEquals("hello", engine.eval("\"hello\""));
    }

    @Test
    public void testParseStringWithEscape() throws ScriptException {
        assertEquals("hello\tworld", engine.eval("\"hello\\tworld\""));
    }

    // ---- Boolean and null literals ----

    @Test
    public void testParseTrue() throws ScriptException {
        assertEquals(true, engine.eval("true"));
    }

    @Test
    public void testParseFalse() throws ScriptException {
        assertEquals(false, engine.eval("false"));
    }

    @Test
    public void testParseNull() throws ScriptException {
        assertNull(engine.eval("null"));
    }

    // ---- Arithmetic ----

    @Test
    public void testAddition() throws ScriptException {
        assertEquals(3L, evalL("1 + 2"));
    }

    @Test
    public void testSubtraction() throws ScriptException {
        assertEquals(7L, evalL("10 - 3"));
    }

    @Test
    public void testMultiplication() throws ScriptException {
        assertEquals(20L, evalL("4 * 5"));
    }

    @Test
    public void testRemainder() throws ScriptException {
        assertEquals(1L, evalL("10 % 3"));
    }

    @Test
    public void testFloatingPointDivision() throws ScriptException {
        assertEquals(3.333, evalD("10 / 3"), 0.001);
    }

    @Test
    public void testPower() throws ScriptException {
        assertEquals(256, evalL("2 ^ 8"));
    }

    @Test
    public void testComplexArithmetic() throws ScriptException {
        assertEquals(14L, evalL("2 + 3 * 4"));
    }

    @Test
    public void testParenthesizedArithmetic() throws ScriptException {
        assertEquals(20L, evalL("(2 + 3) * 4"));
    }

    @Test
    public void testUnaryNegation() throws ScriptException {
        assertEquals(-42L, evalL("-42"));
    }

    @Test
    public void testFloatingPointArithmetic() throws ScriptException {
        assertEquals(7.5, evalD("3.0 * 2.5"), 0.001);
    }

    // ---- Comparison ----

    @Test
    public void testEqual() throws ScriptException {
        assertEquals(true, engine.eval("1 == 1"));
    }

    @Test
    public void testNotEqual() throws ScriptException {
        assertEquals(true, engine.eval("1 != 2"));
    }

    @Test
    public void testLessThan() throws ScriptException {
        assertEquals(true, engine.eval("1 < 2"));
    }

    @Test
    public void testGreaterThan() throws ScriptException {
        assertEquals(true, engine.eval("5 > 3"));
    }

    @Test
    public void testLessThanOrEqual() throws ScriptException {
        assertEquals(true, engine.eval("2 <= 2"));
    }

    @Test
    public void testGreaterThanOrEqual() throws ScriptException {
        assertEquals(false, engine.eval("3 >= 5"));
    }

    // ---- Logical ----

    @Test
    public void testLogicalAnd() throws ScriptException {
        assertEquals(false, engine.eval("true && false"));
    }

    @Test
    public void testLogicalOr() throws ScriptException {
        assertEquals(true, engine.eval("true || false"));
    }

    @Test
    public void testLogicalNot() throws ScriptException {
        assertEquals(false, engine.eval("!true"));
    }

    // ---- String concatenation ----

    @Test
    public void testStringConcat() throws ScriptException {
        assertEquals("hello world", engine.eval("\"hello\" ~ \" world\""));
    }

    // ---- Conditional ----

    @Test
    public void testConditionalTrue() throws ScriptException {
        assertEquals(1L, evalL("true ? 1 : 2"));
    }

    @Test
    public void testConditionalFalse() throws ScriptException {
        assertEquals(2L, evalL("false ? 1 : 2"));
    }

    // ---- List expressions ----

    @Test
    public void testEmptyList() throws ScriptException {
        Object result = engine.eval("[]");
        assertTrue(result instanceof java.util.List);
        assertEquals(0, ((java.util.List<?>) result).size());
    }

    @Test
    public void testListLiteral() throws ScriptException {
        Object result = engine.eval("[1, 2, 3]");
        assertTrue(result instanceof java.util.List);
        assertEquals(3, ((java.util.List<?>) result).size());
    }

    // ---- Error cases ----

    @Test(expected = ScriptException.class)
    public void testParseUnclosedString() throws ScriptException {
        engine.eval("\"unclosed");
    }

    @Test(expected = ScriptException.class)
    public void testParseUnmatchedParen() throws ScriptException {
        engine.eval("(1 + 2");
    }
}
