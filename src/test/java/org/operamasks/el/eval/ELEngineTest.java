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
