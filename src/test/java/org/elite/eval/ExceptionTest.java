package org.elite.eval;

import static org.junit.jupiter.api.Assertions.*;

import javax.script.ScriptException;

import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

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
    void tryFinallyPreservesVariableMutation() {
        // Regression: TRAMPOLINE evaluates try body via AST, which may modify
        // global variables. The IR's local slots must sync from the global
        // VariableMapper so subsequent PUSH_VAR sees the updated value.
        exec("define tryMutate(x) { define i = x; try { i *= 5 } finally {}; i }");
        assertEquals(10L, evalL("tryMutate(2)"));
        assertEquals(15L, evalL("tryMutate(3)"));
    }

    @Test
    void tryCatchPreservesVariableMutation() {
        exec("define tryCatchMutate(x) { define i = x; try { if (i > 10) { throw \"big\" }; i *= 3 } catch (e) { i = -1 }; i }");
        assertEquals(6L, evalL("tryCatchMutate(2)"));
        assertEquals(-1L, evalL("tryCatchMutate(20)"));
    }

    @Test
    void tryWithCompoundAssignAndReadBack() {
        // Multiple reads in same compilation unit — tests that both
        // PUSH_VAR and STORE_VAR work correctly across the TRAMPOLINE boundary.
        exec("define tryReadWrite() { define a = 2; define before = a; try { a *= 5 } finally {}; define after = a; [before, after] }");
        assertEquals("[2, 10]", eval("tryReadWrite()").toString());
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
