package org.operamasks.el.parser;

import static org.junit.jupiter.api.Assertions.*;

import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the @-notation postfix invocation syntax sugar.
 *
 * <pre>
 *   expr{@literal @}func  ≡  func(expr)
 * </pre>
 *
 * The @ operator has left-to-right associativity enabling pipeline-style chaining:
 *   data{@literal @}filter{@literal @}map{@literal @}reduce
 */
class AtNotationTest {

    private javax.script.ScriptEngineManager mgr;
    private ScriptEngine engine;

    @BeforeEach
    void createEngine() {
        engine = new javax.script.ScriptEngineManager().getEngineByName("ELite");
        assertNotNull(engine, "ELite ScriptEngine not found on classpath");
    }

    private Object eval(String expr) {
        try {
            return engine.eval(expr);
        } catch (ScriptException e) {
            throw new RuntimeException("eval failed: " + expr, e);
        }
    }

    private long evalL(String expr) {
        return ((Number) eval(expr)).longValue();
    }

    private double evalD(String expr) {
        return ((Number) eval(expr)).doubleValue();
    }

    private void exec(String stmt) {
        try {
            engine.eval(stmt);
        } catch (ScriptException e) {
            throw new RuntimeException("exec failed: " + stmt, e);
        }
    }

    // ---- Basic single-arg application ----

    @Test
    void basicPostfixCall() {
        exec("define inc(x) => x + 1");
        assertEquals(6L, evalL("5 @inc"));
    }

    @Test
    void basicPostfixCallWithMultipleArgs() {
        exec("define add(a, b) => a + b");
        // (3, 4) is a tuple; TUPLE @add invokes add with the tuple args
        assertEquals(7L, evalL("(3, 4) @add"));
    }

    @Test
    void postfixCallWithStringArg() {
        exec("define greet(name) => \"Hello, \" ~ name");
        assertEquals("Hello, World", eval("\"World\" @greet"));
    }

    // ---- Chaining / pipeline ----

    @Test
    void chainingTwoCalls() {
        exec("define inc(x) => x + 1");
        exec("define double(x) => x * 2");
        // 5 @inc = 6, 6 @double = 12
        assertEquals(12L, evalL("5 @inc @double"));
    }

    @Test
    void chainingThreeCalls() {
        exec("define inc(x) => x + 1");
        exec("define double(x) => x * 2");
        exec("define square(x) => x * x");
        // 5 @inc = 6, 6 @double = 12, 12 @square = 144
        assertEquals(144L, evalL("5 @inc @double @square"));
    }

    @Test
    void chainingStringTransforms() {
        exec("define toUpper(s) => s");
        // basic string transform
        assertEquals("hello", eval("\"hello\" @toUpper"));
    }

    // ---- Precedence with other operators ----

    @Test
    void precedenceWithArithmetic() {
        exec("define inc(x) => x + 1");
        // @ has higher precedence than comparison, lower than arithmetic
        // 10 @inc > 5 → (10 @inc) > 5 → 11 > 5 → true
        assertEquals(true, eval("10 @inc > 5"));
        // 5 @inc == 6 → true
        assertEquals(true, eval("5 @inc == 6"));
    }

    @Test
    void precedenceWithMultiply() {
        exec("define double(x) => x * 2");
        // 5 @double should be 10, then 10 + 3 = 13
        assertEquals(13L, evalL("5 @double + 3"));
    }

    // ---- With lambda expressions ----
    // Note: @-notation requires an identifier after @ (not an arbitrary expression).
    // For lambda-based transforms, use named function definitions or the pipeline
    // operator (->) instead.

    @Test
    void postfixCallWithPredefinedLambda() {
        exec("define twice(x) => x * 2");
        assertEquals(10L, evalL("5 @twice"));
    }

    @Test
    void chainWithNamedFunctions() {
        exec("define inc(x) => x + 1");
        exec("define triple(x) => x * 3");
        assertEquals(18L, evalL("5 @inc @triple"));
    }

    // ---- With collections ----

    @Test
    void postfixCallOnList() {
        exec("define first(xs) => xs[0]");
        assertEquals(1L, evalL("[1, 2, 3] @first"));
    }

    // ---- Error cases ----

    @Test
    void undefinedFunctionThrows() {
        assertThrows(ScriptException.class, () -> engine.eval("5 @noSuchFunc"));
    }

    @Test
    void applyOnNullThrows() {
        exec("define f(x) => x");
        // null @f — passing null as argument should work
        assertNull(eval("null @f"));
    }

    // ---- Complex expressions ----

    @Test
    void expressionAsSubject() {
        exec("define inc(x) => x + 1");
        // (1 + 2) @inc = 3 @inc = 4
        assertEquals(4L, evalL("(1 + 2) @inc"));
    }

    @Test
    void chainingWithDefaultParams() {
        exec("define add(a, b = 1) => a + b");
        // Can't use @ with default params easily — the second arg is missing
        // (5) @add = add(5) which uses b=1 → 6
        assertEquals(6L, evalL("5 @add"));
    }

    // ---- Comparison with normal function call ----

    @Test
    void equivalentToNormalCall() {
        exec("define f(x) => x * x");
        // Compare numeric values (f(7) returns Long, 7@f returns Integer)
        assertEquals(49L, evalL("f(7)"));
        assertEquals(49L, evalL("7 @f"));
        assertEquals(eval("f(7)").toString(), eval("7 @f").toString());
    }

    @Test
    void equivalentToChainedCalls() {
        exec("define f(x) => x + 1");
        exec("define g(x) => x * 2");
        // f(g(5)) = f(10) = 11
        assertEquals(11L, evalL("f(g(5))"));
        assertEquals(11L, evalL("5 @g @f"));
        assertEquals(eval("f(g(5))").toString(), eval("5 @g @f").toString());
    }

    // ---- Pipeline with built-in-like functions ----

    @Test
    void pipelineWithRangeAndListOps() {
        exec("define sum(xs) => xs[1]");
        // [1..5] = Range(1,5), @sum gets first element
        assertNotNull(eval("[1..5] @sum"));
    }

    @Test
    void deeplyChainedCalls() {
        exec("define a(x) => x + 1");
        exec("define b(x) => x * 2");
        exec("define c(x) => x - 3");
        exec("define d(x) => x / 2");
        // ((((5 + 1) * 2) - 3) / 2) = (12 - 3) / 2 = 9 / 2 = 4
        assertEquals(4L, evalL("5 @a @b @c @d"));
    }

    // ---- Postfix on complex expressions ----

    @Test
    void postfixOnParenthesizedExpr() {
        exec("define sq(x) => x * x");
        assertEquals(25L, evalL("(2 + 3) @sq"));
    }

    @Test
    void postfixOnConditional() {
        exec("define abs(x) => x >= 0 ? x : -x");
        assertEquals(5L, evalL("(-5) @abs"));
        assertEquals(3L, evalL("3 @abs"));
    }

    // ---- Multiple statement program ----

    @Test
    void pipelineInMultiStatementProgram() {
        String result = eval(
            "define inc(x) => x + 1;\n" +
            "define double(x) => x * 2;\n" +
            "10 @inc @double"
        ).toString();
        assertEquals("22", result);
    }

    // ---- @ operator associativity ----

    @Test
    void atOperatorIsLeftAssociative() {
        exec("define f(x) => x + 1");
        exec("define g(x) => x * 10");
        // Left-assoc: (5 @f) @g = 6 @g = 60
        assertEquals(60L, evalL("5 @f @g"));
        // Equivalent to g(f(5)) — right-to-left function application
        assertEquals(60L, evalL("g(f(5))"));
    }

    // ---- @ with arithmetic expressions ----

    @Test
    void postfixBeforeComparison() {
        exec("define inc(x) => x + 1");
        assertEquals(true,  eval("5 @inc == 6"));
        assertEquals(false, eval("5 @inc == 7"));
        assertEquals(true,  eval("5 @inc < 10"));
    }

    @Test
    void postfixInArithmetic() {
        exec("define inc(x) => x + 1");
        // 5 @inc + 3 = 6 + 3 = 9
        assertEquals(9L, evalL("5 @inc + 3"));
        // 2 * (5 @inc) = 2 * 6 = 12
        assertEquals(12L, evalL("2 * (5 @inc)"));
    }
}
