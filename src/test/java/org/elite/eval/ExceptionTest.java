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
        // Regression: try body may modify variables. Compiled code must
        // sync local slots from the global VariableMapper so subsequent
        // PUSH_VAR sees the updated value.
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
        // PUSH_VAR and STORE_VAR work correctly across try boundaries.
        exec("define tryReadWrite() { define a = 2; define before = a; try { a *= 5 } finally {}; define after = a; [before, after] }");
        assertEquals("[2, 10]", eval("tryReadWrite()").toString());
    }

    @Test
    void tryCatchFinally() {
        exec("define test() { define x = 0; try { throw \"err\" } catch (e) { x = 1 } finally { x = x + 1 }; x }");
        assertEquals(2L, evalL("test()"));
    }

    // ---- Try with outer variable reads and writes (IR closure compilation) ----

    @Test
    void tryBodyReadsOuterVariable() {
        exec("define test() { define x = 42; define r = 0; try { r = x } catch (e) { r = -1 }; r }");
        assertEquals(42L, evalL("test()"));
    }

    @Test
    void tryBodyMutatesOuterVariable() {
        exec("define test() { define x = 0; try { x = x + 1 } catch (e) { x = -1 }; x }");
        assertEquals(1L, evalL("test()"));
    }

    @Test
    void catchBlockReadsAndMutatesOuterVariable() {
        exec("define test() { define x = 0; try { throw \"err\" } catch (e) { x = x + 5 }; x }");
        assertEquals(5L, evalL("test()"));
    }

    @Test
    void finallyBlockReadsAndMutatesOuterVariable() {
        exec("define test() { define x = 1; try { x = 10 } finally { x = x * 2 }; x }");
        assertEquals(20L, evalL("test()"));
    }

    @Test
    void tryBodyWithMultipleOuterVariables() {
        exec("define test() { define a = 1; define b = 2; define r = 0; try { r = a + b } catch (e) { r = 0 }; r }");
        assertEquals(3L, evalL("test()"));
    }

    @Test
    void tryBodyCapturesCapturedVariable() {
        // Variable captured by inner function, accessed in try block
        exec("define test() { define x = 0; define inc() => x += 1; try { inc() } finally { }; x }");
        assertEquals(1L, evalL("test()"));
    }

    @Test
    void tryReturnValueFromNestedCatch() {
        exec("define test(x) { try { 1 / x } catch (e) { -1 } }");
        assertEquals(-1L, evalL("test(0)"));
        assertEquals(1L, evalL("test(1)"));
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
