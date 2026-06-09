package org.operamasks.el.eval;

import static org.junit.jupiter.api.Assertions.*;

import javax.script.ScriptException;

import org.junit.jupiter.api.Test;
import org.operamasks.el.EliteTestBase;

/**
 * Tests for exception handling: try/catch/finally, throw.
 */
class ExceptionTest extends EliteTestBase {

    // ---- Throw ----

    @Test
    void throwException() {
        assertEvalThrows("throw \"error\"");
    }

    // ---- Try/catch ----

    @Test
    void tryCatchCatchesException() {
        exec("define safe(x) { try { 1 / x } catch (e) { 0 } }");
        assertEquals(0L, evalL("safe(0)"));
    }

    @Test
    void tryCatchReturnsResultWhenNoError() {
        exec("define safe(x) { try { 10 / x } catch (e) { 0 } }");
        assertEquals(5L, evalL("safe(2)"));
    }

    // ---- Try/finally ----

    @Test
    void tryFinally() {
        exec("define test() { define x = 0; try { x = 1 } finally { x = 2 }; x }");
        assertEquals(2L, evalL("test()"));
    }

    @Test
    void tryCatchFinally() {
        exec("define test() { define x = 0; try { throw \"err\" } catch (e) { x = 1 } finally { x = x + 1 }; x }");
        assertEquals(2L, evalL("test()"));
    }

    // ---- Error messages ----

    @Test
    void divisionByZeroMessage() {
        ScriptException ex = assertThrows(ScriptException.class, () -> engine.eval("1 / 0"));
        assertNotNull(ex.getMessage());
    }

    @Test
    void syntaxErrorMessage() {
        ScriptException ex = assertThrows(ScriptException.class, () -> engine.eval("(1 + 2"));
        assertNotNull(ex.getMessage());
    }

    @Test
    void undefinedVariableMessage() {
        ScriptException ex = assertThrows(ScriptException.class,
            () -> freshEngine().eval("nonexistentVariable"));
        assertNotNull(ex.getMessage());
    }
}
