package org.elite.parser;

import static org.junit.jupiter.api.Assertions.*;

import javax.script.ScriptException;

import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for parser error diagnostics: syntax errors, incomplete expressions,
 * and error position reporting.
 */
class ParserErrorTest extends EliteTestBase {

    // ---- Syntax errors ----

    @Test
    void unclosedString() {
        ScriptException ex = assertThrows(ScriptException.class, () -> engine.eval("\"unclosed"));
        assertNotNull(ex.getMessage());
    }

    @Test
    void unclosedParen() {
        ScriptException ex = assertThrows(ScriptException.class, () -> engine.eval("(1 + 2"));
        assertNotNull(ex.getMessage());
    }

    @Test
    void unclosedBrace() {
        assertEvalThrows("{ 1\n 2");
    }

    @Test
    void unexpectedToken() {
        assertEvalThrows(")unexpected");
    }

    // ---- Incomplete expressions ----

    @Test
    void incompleteExpression() {
        assertEvalThrows("1 +");
    }

    @Test
    void incompleteConditional() {
        assertEvalThrows("true ? 1");
    }

    // ---- Error position reporting ----

    @Test
    void parseErrorIncludesPosition() {
        try {
            engine.eval("(+ 1");
            fail("Should have thrown");
        } catch (ScriptException e) {
            String msg = e.getMessage();
            assertNotNull(msg);
            assertTrue(msg.contains("1") || msg.contains("line"),
                "Error message should reference the position: " + msg);
        }
    }

    @Test
    void undefinedVariableHasName() {
        try {
            freshEngine().eval("myUndefinedVar123");
            fail("Should have thrown");
        } catch (ScriptException e) {
            String msg = e.getMessage();
            assertTrue(msg.contains("myUndefinedVar123") || msg.contains("未定义"),
                "Error should name the undefined identifier: " + msg);
        }
    }

    // ---- Multiple error edge cases ----

    @Test
    void emptyInputOk() throws ScriptException {
        // Empty or whitespace input should be valid
        Object result = engine.eval("");
        // May return null or empty — just verify it doesn't throw
    }

    @Test
    void commentOnlyOk() throws ScriptException {
        engine.eval("// This is a comment");
    }
}
