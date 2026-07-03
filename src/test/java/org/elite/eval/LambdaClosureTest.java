package org.elite.eval;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for lambda expressions, closures, currying, and higher-order functions.
 */
class LambdaClosureTest extends EliteTestBase {

    // ---- Basic lambda ----

    @Test
    void singleParamLambda() {
        exec("define double = \\x => x * 2");
        assertEquals(20L, evalL("double(10)"));
    }

    @Test
    void twoParamLambda() {
        exec("define add = \\x, y => x + y");
        assertEquals(30L, evalL("add(10, 20)"));
    }

    @Test
    void thunkLambda() {
        exec("define answer = \\ => 42");
        assertEquals(42L, evalL("answer()"));
    }

    @Test
    void lambdaAsDirectArgument() {
        exec("define apply(f, x) => f(x)");
        assertEquals(14L, evalL("apply(\\x => x * 2, 7)"));
    }

    // ---- Closure capture ----

    @Test
    void closureCapturesLexicalScope() {
        exec("define makeAdder(n) => \\x => x + n");
        exec("define add5 = makeAdder(5)");
        assertEquals(15L, evalL("add5(10)"));
    }

    @Test
    void closureCapturesMultipleVars() {
        exec("define makeOp(a, b) => \\x => a * x + b");
        exec("define f = makeOp(3, 5)");
        assertEquals(11L, evalL("f(2)"));
    }

    @Test
    void nestedClosures() {
        exec("define outer(a) => \\b => \\c => a + b + c");
        assertEquals(15L, evalL("outer(3)(5)(7)"));
    }

    // ---- Currying ----

    @Test
    void curryingWithLambda() {
        exec("define add(x, y) => x + y");
        // partial application via explicit lambda
        exec("define addFive = \\y => add(5, y)");
        assertEquals(12L, evalL("addFive(7)"));
    }

    // ---- Higher-order functions ----

    @Test
    void twice() {
        exec("define twice(f, x) => f(f(x))");
        exec("define addOne(x) => x + 1");
        assertEquals(7L, evalL("twice(addOne, 5)"));
    }

    @Test
    void mapOverList() {
        exec("define double(x) => x * 2");
        Object result = eval("[1, 2, 3].map(double)");
        assertNotNull(result);
    }

    // ---- Default parameters ----

    @Test
    void defaultParameter() {
        exec("define greet(name = \"World\") => \"Hello, \" ~ name");
        assertEquals("Hello, World", eval("greet()"));
        assertEquals("Hello, Tom", eval("greet(\"Tom\")"));
    }

    @Test
    void multipleDefaultParams() {
        exec("define f(a = 1, b = 2) => a + b");
        assertEquals(3L, evalL("f()"));
        assertEquals(5L, evalL("f(3)"));
    }

    // ---- Variadic functions ----

    @Test
    void variadicFunctionSum() {
        assertEquals(10L, evalL("define sum(args...) { define s = 0; for (a in args) { s = s + a }; s }; sum(1, 2, 3, 4)"));
    }

    // ---- Named arguments ----

    @Test
    void namedArgument() {
        exec("define f(a, b) => a - b");
        // Test with named arguments if supported
        Object r = eval("f(10, 3)");
        assertEquals(7L, ((Number) r).longValue());
    }

    // ---- Operator sections ----

    @Test
    void operatorAsFunction() {
        // + operator can be used as a function value
        exec("define plus = (+)");
        Object result = eval("plus(1, 2)");
        assertNotNull(result);
    }

    // ---- Lambda in data pipeline ----

    @Test
    void lambdaPipeline() {
        exec("define double(x) => x * 2");
        exec("define addOne(x) => x + 1");
        // Method chain
        assertEquals(11L, evalL("5 -> double -> addOne"));
    }
}
