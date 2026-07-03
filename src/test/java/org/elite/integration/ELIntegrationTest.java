package org.elite.integration;

import static org.junit.jupiter.api.Assertions.*;

import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * End-to-end integration tests for the ELite language.
 */
class ELIntegrationTest extends EliteTestBase {

    // ---- Literals ----

    @Test
    void integerLiteral() {
        assertEquals(42L, evalL("42"));
    }

    @Test
    void floatLiteral() {
        assertEquals(3.14, evalD("3.14"), 0.001);
    }

    @Test
    void stringLiteral() {
        assertEquals("hello world", eval("\"hello world\""));
    }

    @Test
    void booleanLiteral() {
        assertEquals(true, eval("true"));
        assertEquals(false, eval("false"));
    }

    @Test
    void nullLiteral() {
        assertNull(eval("null"));
    }

    // ---- Arithmetic ----

    @Test
    void addition() {
        assertEquals(7L, evalL("3 + 4"));
    }

    @Test
    void subtraction() {
        assertEquals(10L, evalL("15 - 5"));
    }

    @Test
    void multiplication() {
        assertEquals(56L, evalL("7 * 8"));
    }

    @Test
    void remainder() {
        assertEquals(1L, evalL("10 % 3"));
    }

    @Test
    void precedence() {
        assertEquals(14L, evalL("2 + 3 * 4"));
    }

    @Test
    void parenthesizedExpression() {
        assertEquals(20L, evalL("(2 + 3) * 4"));
    }

    @Test
    void nestedArithmetic() {
        assertEquals(18L, evalL("(2 + 4) * (5 - 2)"));
    }

    @Test
    void unaryNegation() {
        assertEquals(-100L, evalL("-100"));
    }

    @Test
    void floatingPointArithmetic() {
        assertEquals(7.5, evalD("3.0 * 2.5"), 0.001);
    }

    // ---- Comparisons ----

    @Test
    void equality() {
        assertEquals(true, eval("1 == 1"));
        assertEquals(false, eval("1 == 2"));
    }

    @Test
    void inequality() {
        assertEquals(true, eval("1 != 2"));
        assertEquals(false, eval("1 != 1"));
    }

    @Test
    void relationalOperators() {
        assertEquals(true, eval("3 < 5"));
        assertEquals(true, eval("5 > 3"));
        assertEquals(true, eval("5 >= 5"));
        assertEquals(true, eval("3 <= 5"));
    }

    // ---- Logical ----

    @Test
    void logicalAnd() {
        assertEquals(true, eval("true && true"));
        assertEquals(false, eval("true && false"));
    }

    @Test
    void logicalOr() {
        assertEquals(true, eval("true || false"));
        assertEquals(false, eval("false || false"));
    }

    @Test
    void logicalNot() {
        assertEquals(false, eval("!true"));
        assertEquals(true, eval("!false"));
    }

    @Test
    void complexLogical() {
        assertEquals(true, eval("(1 < 2) && (3 < 4)"));
        assertEquals(false, eval("(1 > 2) || (3 > 4)"));
    }

    // ---- Conditional ----

    @Test
    void ternaryTrue() {
        assertEquals(1L, evalL("true ? 1 : 2"));
    }

    @Test
    void ternaryFalse() {
        assertEquals(2L, evalL("false ? 1 : 2"));
    }

    @Test
    void nestedTernary() {
        assertEquals("yes", eval("true ? (false ? \"no\" : \"yes\") : \"maybe\""));
    }

    // ---- String concatenation ----

    @Test
    void stringConcat() {
        assertEquals("HelloWorld", eval("\"Hello\" ~ \"World\""));
    }

    // ---- Variable definition ----

    @Test
    void variableDefinition() {
        exec("define x = 42");
        assertEquals(42L, evalL("x"));
    }

    @Test
    void variableInExpression() {
        exec("define a = 10");
        exec("define b = 20");
        assertEquals(30L, evalL("a + b"));
    }

    @Test
    void variableReassignment() {
        exec("define x = 5");
        assertEquals(5L, evalL("x"));
        exec("x = 10");
        assertEquals(10L, evalL("x"));
    }

    // ---- Function definition and calls ----

    @Test
    void simpleFunction() {
        exec("define add(a, b) => a + b");
        assertEquals(30L, evalL("add(10, 20)"));
    }

    @Test
    void functionWithExpressionBody() {
        exec("define square(x) => x * x");
        assertEquals(25L, evalL("square(5)"));
    }

    @Test
    void functionComposition() {
        exec("define double(x) => x * 2");
        exec("define addOne(x) => x + 1");
        assertEquals(11L, evalL("addOne(double(5))"));
    }

    @Test
    void blockFunction() {
        exec("define sumTo(n) { define result = n * (n + 1) / 2; result }");
        assertEquals(55L, evalL("sumTo(10)"));
    }

    // ---- Lambda expressions ----

    @Test
    void lambdaExpression() {
        exec("define double = \\x => x * 2");
        assertEquals(20L, evalL("double(10)"));
    }

    @Test
    void lambdaAsArgument() {
        exec("define apply(f, x) => f(x)");
        assertEquals(14L, evalL("apply(\\x => x * 2, 7)"));
    }

    @Test
    void thunkLambda() {
        exec("define answer = \\ => 42");
        assertEquals(42L, evalL("answer()"));
    }

    // ---- Closures ----

    @Test
    void closureCapturesEnvironment() {
        exec("define makeAdder(n) => \\x => x + n");
        exec("define add5 = makeAdder(5)");
        assertEquals(15L, evalL("add5(10)"));
    }

    // ---- Pipe operator ----

    @Test
    void pipeOperator() {
        exec("define double(x) => x * 2");
        exec("define addOne(x) => x + 1");
        assertEquals(11L, evalL("5 -> double -> addOne"));
    }

    // ---- String interpolation ----

    @Test
    void stringInterpolation() {
        exec("define name = \"World\"");
        assertEquals("Hello, World!", eval("\"Hello, ${name}!\""));
    }

    // ---- List operations ----

    @Test
    void listLiteral() {
        exec("define lst = [1, 2, 3, 4, 5]");
        Object result = eval("lst");
        assertTrue(result instanceof java.util.List);
    }

    // ---- Object-oriented: class definition ----

    @Test
    void simpleClass() {
        exec("class Point(x, y) { toString() => \"(\" ~ x ~ \", \" ~ y ~ \")\" }");
        Object result = eval("Point(3, 4)");
        assertNotNull(result);
    }

    // ---- Recursive function ----

    @Test
    void recursiveFunction() {
        exec("define fib(n) { if (n <= 1) { n } else { fib(n-1) + fib(n-2) } }");
        assertEquals(55L, evalL("fib(10)"));
    }

    // ---- Sequence ----

    @Test
    void simpleSequence() {
        exec("define r = [1, 2, 3, 4, 5]");
        Object result = eval("r");
        assertNotNull(result);
        assertTrue(result instanceof java.util.List);
    }

    // ---- Error handling ----

    @Test
    void divisionByZeroThrows() {
        assertEvalThrows("1 / 0");
    }

    @Test
    void undefinedVariableThrows() {
        assertEvalThrows(freshEngine(), "undefinedVariable");
    }

    @Test
    void syntaxErrorThrows() {
        assertEvalThrows("(1 + 2");
    }

    // ---- Import and Java interop ----

    @Test
    void importJavaClass() {
        exec("import java.util.Date");
        Object result = eval("new Date(0)");
        assertNotNull(result);
        assertTrue(result instanceof java.util.Date);
    }

    @Test
    void javaMethodCall() {
        Object result = eval("\"hello\".length()");
        assertEquals(5, result);
    }

    @Test
    void javaStaticMethodCall() {
        assertEquals(42, eval("Math.abs(-42)"));
    }

    @Test
    void systemOutPrint() {
        exec("System.out.println(\"test from ELite\")");
    }

    // ---- Type annotations ----

    @Test
    void typeAnnotationOnVariable() {
        exec("define x::Integer = 42");
        assertEquals(42L, evalL("x"));
    }

    @Test
    void typeAnnotationOnFunction() {
        exec("define add(a::Integer, b::Integer)::Integer => a + b");
        assertEquals(30L, evalL("add(10, 20)"));
    }

    @Test
    void typeAnnotationJavaClass() {
        exec("define d::java.util.Date = new Date(0)");
        Object result = eval("d");
        assertTrue(result instanceof java.util.Date);
    }

    @Test
    void validParamTypesPass() throws ScriptException {
        ScriptEngine eng = freshEngine();
        eng.eval("define add(a::Integer, b::Integer)::Integer => a + b");
        assertEquals(3L, ((Number) eng.eval("add(1, 2)")).longValue());
    }

    @Test
    void argTypeMismatchCrossEval() {
        ScriptEngine eng = freshEngine();
        assertEvalThrows(eng, "define add(a::Integer, b::Integer)::Integer => a + b; print(add(\"1\", 2))");
    }

    // ---- Hello World styles ----

    @Test
    void helloWorldStyles() {
        exec("print(\"Hello, World!\")");
        exec("\"Hello, World!\" -> print");
    }
}
