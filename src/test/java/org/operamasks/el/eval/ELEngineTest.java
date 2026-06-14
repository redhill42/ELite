package org.operamasks.el.eval;

import static org.junit.jupiter.api.Assertions.*;

import javax.el.ELContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.junit.jupiter.api.Test;
import org.operamasks.el.EliteTestBase;

/**
 * Tests for ELEngine APIs and evaluation via ScriptEngine.
 */
class ELEngineTest extends EliteTestBase {

    // ---- ELContext lifecycle ----

    @Test
    void createDefaultELContext() {
        ELContext ctx = ELEngine.createELContext();
        assertNotNull(ctx);
        assertNotNull(ctx.getELResolver());
    }

    @Test
    void getExpressionFactory() {
        assertNotNull(ELEngine.getExpressionFactory());
    }

    // ---- Arithmetic ----

    @Test
    void addition() {
        assertEquals(30L, evalL("10 + 20"));
    }

    @Test
    void subtraction() {
        assertEquals(63L, evalL("100 - 37"));
    }

    @Test
    void multiplication() {
        assertEquals(56L, evalL("7 * 8"));
    }

    @Test
    void floatDivision() {
        assertEquals(2.5, evalD("20.0 / 8.0"), 0.001);
    }

    @Test
    void precedence() {
        assertEquals(14L, evalL("2 + 3 * 4"));
    }

    @Test
    void integerDivision() {
        assertEquals(3L, evalL("10 div 3"));
    }

    @Test
    void remainder() {
        assertEquals(1L, evalL("10 % 3"));
    }

    // ---- Comparison ----

    @Test
    void equality() {
        assertEquals(true, eval("5 == 5"));
        assertEquals(false, eval("5 == 6"));
    }

    @Test
    void stringEquality() {
        assertEquals(true, eval("\"abc\" == \"abc\""));
        assertEquals(false, eval("\"abc\" == \"xyz\""));
    }

    @Test
    void relational() {
        assertEquals(true, eval("3 < 5"));
        assertEquals(false, eval("3 > 5"));
        assertEquals(true, eval("5 >= 5"));
    }

    @Test
    void identityEquality() {
        assertEquals(true, eval("true === true"));
        assertEquals(false, eval("true !== true"));
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
    }

    @Test
    void logicalNot() {
        assertEquals(false, eval("!true"));
    }

    @Test
    void logicalAliases() {
        assertEquals(true, eval("true and true"));
        assertEquals(true, eval("false or true"));
        assertEquals(false, eval("not true"));
    }

    // ---- Conditional ----

    @Test
    void conditionalTrue() {
        assertEquals(10L, evalL("true ? 10 : 20"));
    }

    @Test
    void conditionalFalse() {
        assertEquals(20L, evalL("false ? 10 : 20"));
    }

    // ---- String concatenation ----

    @Test
    void stringConcat() {
        assertEquals("Hello, World", eval("\"Hello\" ~ \", \" ~ \"World\""));
    }

    // ---- Power ----

    @Test
    void power() {
        assertEquals(1024L, evalL("2 ^ 10"));
    }

    // ---- Variable binding via ScriptEngine ----

    @Test
    void variableFromDefine() {
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
    void compoundAssignSameUnit() {
        // Multiple reads/writes in a single compilation unit — verifies that
        // compound assignment updates local variable slots (STORE_VAR), not
        // just global storage (STORE_GLOBAL).
        exec("define compoundTest() { define x = 2; define a = x; x *= 5; define b = x; [a, b] }");
        assertEquals("[2, 10]", eval("compoundTest()").toString());
    }

    @Test
    void compoundAssignWithIntermediateRead() {
        // Read variable before and after compound assignment in same block.
        // This forces the IR to use PUSH_VAR (local slot) for the second read.
        exec("define test() { define x = 3; define before = x; x *= 4; define after = x; [before, after] }");
        assertEquals("[3, 12]", eval("test()").toString());
    }

    @Test
    void compoundAssignAllOpsSameUnit() {
        // Test all compound assignment operators in one function.
        exec("define allOps() { define x = 10; x += 5; x -= 3; x *= 2; x /= 4; [x] }");
        assertEquals("[6]", eval("allOps()").toString());
    }

    @Test
    void compoundAssignWithIfCondition() {
        // Compound assignment inside if-then with dynamic modulo condition.
        // This exercises deopt splitting with compound assignment in then block.
        exec("define condOp(x) { if (x % 2 == 0) { x *= 5 }; x }");
        assertEquals(10L, evalL("condOp(2)"));
        assertEquals(3L, evalL("condOp(3)"));
    }

    @Test
    void compoundAssignWithForLoop() {
        // Accumulator pattern with compound assignment in loop.
        exec("define loopSum(n) { define s = 0; for (i in [1..n]) { s += i }; s }");
        assertEquals(55L, evalL("loopSum(10)"));
    }

    // ---- Default parameter values ----

    @Test
    void defaultParametersInIR() {
        // Regression: default parameter values were not applied in IR mode
        // (INVOKE_DIRECT doesn't fill in defaults). The locals array must
        // be populated with default values for missing arguments.
        exec("define fib(n, a = 1, b = 1) => n <= 2 ? b : fib(n-1, b, a+b)");
        assertEquals(144L, evalL("fib(12)"));
        assertEquals(1L, evalL("fib(1)"));
        assertEquals(2L, evalL("fib(3)"));
    }

    @Test
    void defaultParametersSimpleFunction() {
        exec("define add(a, b = 10) => a + b");
        assertEquals(15L, evalL("add(5)"));
        assertEquals(7L, evalL("add(5, 2)"));
    }

    @Test
    void defaultParametersMultipleDefaults() {
        exec("define f(a, b = 10, c = 100) => [a, b, c]");
        assertEquals("[1, 10, 100]", eval("f(1)").toString());
        assertEquals("[1, 2, 100]", eval("f(1, 2)").toString());
        assertEquals("[1, 2, 3]", eval("f(1, 2, 3)").toString());
    }

    @Test
    void defaultParametersWithControlFlow() {
        // Default params with recursion and INVOKE_TAIL (TCO) in the else branch.
        // Exercises IRSpecializer deopt splitting around tail-call instructions.
        exec("define sum(n, acc = 0) => n <= 0 ? acc : sum(n-1, acc+n)");
        assertEquals(55L, evalL("sum(10)"));
        assertEquals(0L, evalL("sum(0)"));
        assertEquals(15L, evalL("sum(5)"));
    }

    @Test
    void defaultParameterZeroValue() {
        // Zero is a valid default — must not be confused with null/missing
        exec("define inc(a, delta = 0) => a + delta");
        assertEquals(5L, evalL("inc(5)"));
        assertEquals(7L, evalL("inc(5, 2)"));
    }

    // ---- Default params with explicit null (P0-2) ----

    @Test
    void defaultParameterExplicitNullNotOverwritten() {
        // Explicitly passing null must NOT be replaced by the default value.
        // Bug: IFNONNULL check in bytecode and execute() couldn't distinguish
        // "not provided" from "explicitly passed null".
        exec("define f(x = 42) => x");
        assertEquals(42L, evalL("f()"), "missing arg → default");
        assertEquals(10L, evalL("f(10)"), "explicit arg → arg");
        assertNull(eval("f(null)"), "explicit null → null, not 42");
    }

    @Test
    void defaultParameterNullWithMultipleDefaults() {
        exec("define f(a, b = 10, c = 100) => [a, b, c]");
        // Passing null for first param, relying on defaults for others
        Object r = eval("f(null, 2)");
        assertEquals("[null, 2, 100]", r.toString());
    }

    @Test
    void defaultParameterNullThenDefault() {
        exec("define f(a = 1, b = 2) => [a, b]");
        assertEquals("[null, 2]", eval("f(null)").toString(),
            "null for a, default for b");
        assertEquals("[null, null]", eval("f(null, null)").toString(),
            "explicit null for both, no defaults");
    }

    @Test
    void defaultParameterExplicitNullStringDefault() {
        exec("define greet(name = \"World\") => \"Hello, \" ~ name");
        assertEquals("Hello, World", eval("greet()"));
        assertEquals("Hello, Alice", eval("greet(\"Alice\")"));
        // Explicit null must NOT be replaced by default "World"
        assertEquals("Hello, null", eval("greet(null)"),
            "explicit null must stay null, not be replaced by default 'World'");
    }

    // ---- Error cases ----

    @Test
    void divisionByZeroThrows() {
        assertEvalThrows("1 / 0");
    }

    @Test
    void undefinedVariableThrows() {
        ScriptEngine eng = freshEngine();
        assertEvalThrows(eng, "undefinedVar");
    }

    // ---- Null handling: arithmetic NPE, string null→"null" (Java convention) ----

    @Test
    void nullAdditionThrowsNPE() {
        assertEvalThrows("null + 5");
    }

    @Test
    void nullSubtractionThrowsNPE() {
        assertEvalThrows("10 - null");
    }

    @Test
    void nullMultiplicationThrowsNPE() {
        assertEvalThrows("null * 3");
    }

    @Test
    void nullDivisionThrowsNPE() {
        assertEvalThrows("100 / null");
    }

    @Test
    void nullStringConcat() {
        assertEquals("nullWorld", eval("null ~ \"World\""));
    }

    @Test
    void stringConcatWithNullRight() {
        assertEquals("Hellonull", eval("\"Hello\" ~ null"));
    }

    // ---- Engine isolation ----

    @Test
    void engineStateIsolation() throws ScriptException {
        ScriptEngine e1 = freshEngine();
        ScriptEngine e2 = freshEngine();

        e1.eval("define x = 1");
        e2.eval("define x = 2");

        assertEquals(1L, ((Number) e1.eval("x")).longValue());
        assertEquals(2L, ((Number) e2.eval("x")).longValue());
    }

    // ---- Block body function ----

    @Test
    void functionWithBlockBody() {
        exec("define sumTo(n) { define result = n * (n + 1) / 2\n result }");
        assertEquals(55L, evalL("sumTo(10)"));
    }

    // ---- Coalescing ----

    @Test
    void coalescingReturnsLeftIfNotNull() {
        assertEquals(42L, evalL("42 ?? 99"));
    }

    @Test
    void coalescingReturnsRightIfLeftIsNull() {
        assertEquals(99L, evalL("null ?? 99"));
    }

}
