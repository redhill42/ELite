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

package org.operamasks.el.integration;

import static org.junit.Assert.*;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end integration tests for the ELite language.
 */
public class ELIntegrationTest {

    private ScriptEngine engine;

    @Before
    public void setUp() {
        engine = new ScriptEngineManager().getEngineByName("ELite");
        assertNotNull("ELite ScriptEngine not found.", engine);
    }

    private long evalL(String expr) throws ScriptException {
        return ((Number) engine.eval(expr)).longValue();
    }

    // ---- Literals ----

    @Test
    public void testIntegerLiteral() throws ScriptException {
        assertEquals(42L, evalL("42"));
    }

    @Test
    public void testFloatLiteral() throws ScriptException {
        assertEquals(3.14, ((Number) engine.eval("3.14")).doubleValue(), 0.001);
    }

    @Test
    public void testStringLiteral() throws ScriptException {
        assertEquals("hello world", engine.eval("\"hello world\""));
    }

    @Test
    public void testBooleanLiteral() throws ScriptException {
        assertEquals(true, engine.eval("true"));
        assertEquals(false, engine.eval("false"));
    }

    @Test
    public void testNullLiteral() throws ScriptException {
        assertNull(engine.eval("null"));
    }

    // ---- Arithmetic ----

    @Test
    public void testAddition() throws ScriptException {
        assertEquals(7L, evalL("3 + 4"));
    }

    @Test
    public void testSubtraction() throws ScriptException {
        assertEquals(10L, evalL("15 - 5"));
    }

    @Test
    public void testMultiplication() throws ScriptException {
        assertEquals(56L, evalL("7 * 8"));
    }

    @Test
    public void testRemainder() throws ScriptException {
        assertEquals(1L, evalL("10 % 3"));
    }

    @Test
    public void testPrecedence() throws ScriptException {
        assertEquals(14L, evalL("2 + 3 * 4"));
    }

    @Test
    public void testParenthesizedExpression() throws ScriptException {
        assertEquals(20L, evalL("(2 + 3) * 4"));
    }

    @Test
    public void testNestedArithmetic() throws ScriptException {
        assertEquals(18L, evalL("(2 + 4) * (5 - 2)"));
    }

    @Test
    public void testUnaryNegation() throws ScriptException {
        assertEquals(-100L, evalL("-100"));
    }

    @Test
    public void testFloatingPointArithmetic() throws ScriptException {
        assertEquals(7.5, ((Number) engine.eval("3.0 * 2.5")).doubleValue(), 0.001);
    }

    // ---- Comparisons ----

    @Test
    public void testEquality() throws ScriptException {
        assertEquals(true, engine.eval("1 == 1"));
        assertEquals(false, engine.eval("1 == 2"));
    }

    @Test
    public void testInequality() throws ScriptException {
        assertEquals(true, engine.eval("1 != 2"));
        assertEquals(false, engine.eval("1 != 1"));
    }

    @Test
    public void testRelationalOperators() throws ScriptException {
        assertEquals(true, engine.eval("3 < 5"));
        assertEquals(true, engine.eval("5 > 3"));
        assertEquals(true, engine.eval("5 >= 5"));
        assertEquals(true, engine.eval("3 <= 5"));
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
        assertEquals(false, engine.eval("false || false"));
    }

    @Test
    public void testLogicalNot() throws ScriptException {
        assertEquals(false, engine.eval("!true"));
        assertEquals(true, engine.eval("!false"));
    }

    @Test
    public void testComplexLogical() throws ScriptException {
        assertEquals(true, engine.eval("(1 < 2) && (3 < 4)"));
        assertEquals(false, engine.eval("(1 > 2) || (3 > 4)"));
    }

    // ---- Conditional ----

    @Test
    public void testTernaryTrue() throws ScriptException {
        assertEquals(1L, evalL("true ? 1 : 2"));
    }

    @Test
    public void testTernaryFalse() throws ScriptException {
        assertEquals(2L, evalL("false ? 1 : 2"));
    }

    @Test
    public void testNestedTernary() throws ScriptException {
        assertEquals("yes", engine.eval("true ? (false ? \"no\" : \"yes\") : \"maybe\""));
    }

    // ---- String concatenation ----

    @Test
    public void testStringConcat() throws ScriptException {
        assertEquals("HelloWorld", engine.eval("\"Hello\" ~ \"World\""));
    }

    // ---- Variable definition ----

    @Test
    public void testVariableDefinition() throws ScriptException {
        engine.eval("define x = 42");
        assertEquals(42L, evalL("x"));
    }

    @Test
    public void testVariableInExpression() throws ScriptException {
        engine.eval("define a = 10");
        engine.eval("define b = 20");
        assertEquals(30L, evalL("a + b"));
    }

    @Test
    public void testVariableReassignment() throws ScriptException {
        engine.eval("define x = 5");
        assertEquals(5L, evalL("x"));
        engine.eval("x = 10");
        assertEquals(10L, evalL("x"));
    }

    // ---- Function definition and calls ----

    @Test
    public void testSimpleFunction() throws ScriptException {
        engine.eval("define add(a, b) => a + b");
        assertEquals(30L, evalL("add(10, 20)"));
    }

    @Test
    public void testFunctionWithExpressionBody() throws ScriptException {
        engine.eval("define square(x) => x * x");
        assertEquals(25L, evalL("square(5)"));
    }

    @Test
    public void testFunctionComposition() throws ScriptException {
        engine.eval("define double(x) => x * 2");
        engine.eval("define addOne(x) => x + 1");
        assertEquals(11L, evalL("addOne(double(5))"));
    }

    @Test
    public void testBlockFunction() throws ScriptException {
        engine.eval("define sumTo(n) { define result = n * (n + 1) / 2; result }");
        assertEquals(55L, evalL("sumTo(10)"));
    }

    // ---- Lambda expressions ----

    @Test
    public void testLambdaExpression() throws ScriptException {
        engine.eval("define double = \\x => x * 2");
        assertEquals(20L, evalL("double(10)"));
    }

    @Test
    public void testLambdaAsArgument() throws ScriptException {
        engine.eval("define apply(f, x) => f(x)");
        assertEquals(14L, evalL("apply(\\x => x * 2, 7)"));
    }

    // ---- Closures ----

    @Test
    public void testClosureCapturesEnvironment() throws ScriptException {
        engine.eval("define makeAdder(n) => \\x => x + n");
        engine.eval("define add5 = makeAdder(5)");
        assertEquals(15L, evalL("add5(10)"));
    }

    // ---- Pipe operator ----

    @Test
    public void testPipeOperator() throws ScriptException {
        engine.eval("define double(x) => x * 2");
        engine.eval("define addOne(x) => x + 1");
        assertEquals(11L, evalL("5 -> double -> addOne"));
    }

    // ---- String interpolation ----

    @Test
    public void testStringInterpolation() throws ScriptException {
        engine.eval("define name = \"World\"");
        assertEquals("Hello, World!", engine.eval("\"Hello, ${name}!\""));
    }

    // ---- List operations ----

    @Test
    public void testListLiteral() throws ScriptException {
        engine.eval("define lst = [1, 2, 3, 4, 5]");
    }

    // ---- Object-oriented: class definition ----

    @Test
    public void testSimpleClass() throws ScriptException {
        engine.eval("class Point(x, y) { toString() => \"(\" ~ x ~ \", \" ~ y ~ \")\" }");
        Object result = engine.eval("Point(3, 4)");
        assertNotNull(result);
    }

    // ---- Recursive function ----

    @Test
    public void testRecursiveFunction() throws ScriptException {
        engine.eval("define fib(n) { if (n <= 1) { n } else { fib(n-1) + fib(n-2) } }");
        assertEquals(55L, evalL("fib(10)"));
    }

    // ---- Sequence ----

    @Test
    public void testSimpleSequence() throws ScriptException {
        engine.eval("define r = [1, 2, 3, 4, 5]");
        Object result = engine.eval("r");
        assertNotNull(result);
        assertTrue(result instanceof java.util.List);
    }

    // ---- Error handling ----

    @Test(expected = ScriptException.class)
    public void testDivisionByZeroThrows() throws ScriptException {
        engine.eval("1 / 0");
    }

    @Test(expected = ScriptException.class)
    public void testUndefinedVariableThrows() throws ScriptException {
        engine.eval("undefinedVariable");
    }

    @Test(expected = ScriptException.class)
    public void testSyntaxErrorThrows() throws ScriptException {
        engine.eval("(1 + 2");
    }

    // ---- Import and Java interop ----

    @Test
    public void testImportJavaClass() throws ScriptException {
        engine.eval("import java.util.Date");
        Object result = engine.eval("new Date(0)");
        assertNotNull(result);
        assertTrue(result instanceof java.util.Date);
    }

    @Test
    public void testJavaMethodCall() throws ScriptException {
        Object result = engine.eval("\"hello\".length()");
        assertEquals(5, result);
    }

    @Test
    public void testJavaStaticMethodCall() throws ScriptException {
        assertEquals(42, engine.eval("Math.abs(-42)"));
    }

    @Test
    public void testSystemOutPrint() throws ScriptException {
        engine.eval("System.out.println(\"test from ELite\")");
    }

    // ---- Type annotations ----

    @Test
    public void testTypeAnnotationOnVariable() throws ScriptException {
        // define with type annotation should parse and evaluate
        engine.eval("define x::Integer = 42");
        assertEquals(42L, evalL("x"));
    }

    @Test
    public void testTypeAnnotationOnFunction() throws ScriptException {
        // Function with parameter and return type annotations
        engine.eval("define add(a::Integer, b::Integer)::Integer => a + b");
        assertEquals(30L, evalL("add(10, 20)"));
    }

    @Test
    public void testTypeAnnotationJavaClass() throws ScriptException {
        engine.eval("define d::java.util.Date = new Date(0)");
        Object result = engine.eval("d");
        assertTrue(result instanceof java.util.Date);
    }

    // ---- Hello World styles ----

    @Test
    public void testHelloWorldStyles() throws ScriptException {
        // Various Hello World styles — verify they don't throw
        engine.eval("print(\"Hello, World!\")");
        engine.eval("\"Hello, World!\".print()");
        engine.eval("\"Hello, World!\" -> print");
    }
}
