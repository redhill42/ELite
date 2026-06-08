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

package org.operamasks.el.eval;

import static org.junit.Assert.*;

import javax.el.ELContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for ELEngine APIs and evaluation via ScriptEngine.
 */
public class ELEngineTest {

    private ScriptEngine engine;

    @Before
    public void setUp() {
        engine = new ScriptEngineManager().getEngineByName("ELite");
    }

    private long evalL(String expr) throws ScriptException {
        return ((Number) engine.eval(expr)).longValue();
    }

    // ---- ELContext lifecycle ----

    @Test
    public void testCreateDefaultELContext() {
        ELContext ctx = ELEngine.createELContext();
        assertNotNull(ctx);
        assertNotNull(ctx.getELResolver());
    }

    @Test
    public void testGetExpressionFactory() {
        assertNotNull(ELEngine.getExpressionFactory());
    }

    // ---- Arithmetic ----

    @Test
    public void testAddition() throws ScriptException {
        assertEquals(30L, evalL("10 + 20"));
    }

    @Test
    public void testSubtraction() throws ScriptException {
        assertEquals(63L, evalL("100 - 37"));
    }

    @Test
    public void testMultiplication() throws ScriptException {
        assertEquals(56L, evalL("7 * 8"));
    }

    @Test
    public void testFloatDivision() throws ScriptException {
        assertEquals(2.5, ((Number) engine.eval("20.0 / 8.0")).doubleValue(), 0.001);
    }

    @Test
    public void testPrecedence() throws ScriptException {
        assertEquals(14L, evalL("2 + 3 * 4"));
    }

    // ---- Comparison ----

    @Test
    public void testEquality() throws ScriptException {
        assertEquals(true, engine.eval("5 == 5"));
        assertEquals(false, engine.eval("5 == 6"));
    }

    @Test
    public void testStringEquality() throws ScriptException {
        assertEquals(true, engine.eval("\"abc\" == \"abc\""));
        assertEquals(false, engine.eval("\"abc\" == \"xyz\""));
    }

    @Test
    public void testRelational() throws ScriptException {
        assertEquals(true, engine.eval("3 < 5"));
        assertEquals(false, engine.eval("3 > 5"));
        assertEquals(true, engine.eval("5 >= 5"));
    }

    // ---- Logical ----

    @Test
    public void testLogicalAnd() throws ScriptException {
        assertEquals(true, engine.eval("true && true"));
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

    // ---- Conditional ----

    @Test
    public void testConditional() throws ScriptException {
        assertEquals(10L, evalL("true ? 10 : 20"));
        assertEquals(20L, evalL("false ? 10 : 20"));
    }

    // ---- String concatenation ----

    @Test
    public void testStringConcat() throws ScriptException {
        assertEquals("Hello, World", engine.eval("\"Hello\" ~ \", \" ~ \"World\""));
    }

    @Test
    public void testPower() throws ScriptException {
        assertEquals(1024L, evalL("2 ^ 10"));
    }

    // ---- Variable binding via ScriptEngine ----

    @Test
    public void testVariableFromEngine() throws ScriptException {
        engine.eval("define x = 42");
        assertEquals(42L, evalL("x"));
    }

    @Test
    public void testVariableInExpression() throws ScriptException {
        engine.eval("define a = 10");
        engine.eval("define b = 20");
        assertEquals(30L, evalL("a + b"));
    }

    // ---- Error cases ----

    @Test(expected = ScriptException.class)
    public void testDivisionByZero() throws ScriptException {
        engine.eval("1 / 0");
    }

    @Test(expected = ScriptException.class)
    public void testUndefinedVariable() throws ScriptException {
        new ScriptEngineManager().getEngineByName("ELite").eval("undefinedVar");
    }

    // ---- Engine isolation ----

    @Test
    public void testEngineStateIsolation() throws ScriptException {
        ScriptEngineManager mgr = new ScriptEngineManager();
        ScriptEngine e1 = mgr.getEngineByName("ELite");
        ScriptEngine e2 = mgr.getEngineByName("ELite");

        e1.eval("define x = 1");
        e2.eval("define x = 2");

        assertEquals(1L, ((Number) e1.eval("x")).longValue());
        assertEquals(2L, ((Number) e2.eval("x")).longValue());
    }
}
