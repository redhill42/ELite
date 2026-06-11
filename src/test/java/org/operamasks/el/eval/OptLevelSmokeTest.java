package org.operamasks.el.eval;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests that run at each optimization level to catch regressions.
 *
 * These are the canaries — if they fail at any level, something is broken.
 * Run with:
 *   mvn test -Dtest=OptLevelSmokeTest -Delite.opt.level=N
 */
class OptLevelSmokeTest {

    private javax.script.ScriptEngine engine;

    @BeforeEach
    void setup() {
        engine = new javax.script.ScriptEngineManager().getEngineByName("ELite");
        assertNotNull(engine);
    }

    @Test void basicArithmetic() throws Exception {
        assertEquals(42L, ((Number) engine.eval("40 + 2")).longValue());
        assertEquals(15L, ((Number) engine.eval("5 * 3")).longValue());
        assertEquals(7L,  ((Number) engine.eval("10 - 3")).longValue());
    }

    @Test void variableDefineAndUse() throws Exception {
        assertEquals(42L, ((Number) engine.eval("define x = 40; x + 2")).longValue());
    }

    @Test void functionDefineAndCall() throws Exception {
        engine.eval("define add(a, b) => a + b");
        assertEquals(30L, ((Number) engine.eval("add(10, 20)")).longValue());
    }

    @Test void conditionalExpression() throws Exception {
        assertEquals(100L, ((Number) engine.eval("true ? 100 : 200")).longValue());
        assertEquals(200L, ((Number) engine.eval("false ? 100 : 200")).longValue());
    }

    @Test void stringConcat() throws Exception {
        assertEquals("helloworld", engine.eval("\"hello\" ~ \"world\""));
    }

    @Test void lambdaExpression() throws Exception {
        assertEquals(14L, ((Number) engine.eval("(\\x => x * 2)(7)")).longValue());
    }

    @Test void listLiteral() throws Exception {
        Object r = engine.eval("[1, 2, 3]");
        assertTrue(r instanceof java.util.List);
        assertEquals(3, ((java.util.List<?>) r).size());
    }

    @Test void mapLiteral() throws Exception {
        Object r = engine.eval("{a: 1, b: 2}");
        assertTrue(r instanceof java.util.Map);
    }

    @Test void closureCapturesVariable() throws Exception {
        engine.eval("define f(x) => \\y => x + y");
        assertEquals(5L, ((Number) engine.eval("f(2)(3)")).longValue());
    }

    @Test void errorDivByZero() {
        try {
            engine.eval("1 / 0");
            fail("Should throw");
        } catch (javax.script.ScriptException e) {
            // expected
        }
    }
}
