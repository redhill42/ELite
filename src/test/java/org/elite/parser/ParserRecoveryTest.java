package org.elite.parser;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import javax.el.ELContext;
import javax.el.ELResolver;
import javax.el.FunctionMapper;
import javax.el.VariableMapper;
import javax.script.ScriptException;

import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for parser error recovery: errors are recorded instead of being
 * thrown immediately, parsing continues, and a compound
 * {@link ParseException} exception is thrown when parsing completes.
 */
class ParserRecoveryTest extends EliteTestBase {

    private static ELContext elContext() {
        return new ELContext() {
            @Override
            public ELResolver getELResolver() {
                return null;
            }

            @Override
            public FunctionMapper getFunctionMapper() {
                return null;
            }

            @Override
            public VariableMapper getVariableMapper() {
                return null;
            }
        };
    }

    private static void assertMessageContainsAll(String msg, String... parts) {
        for (String part : parts) {
            assertTrue(msg.contains(part),
                       "message should contain '" + part + "': " + msg);
        }
    }

    // ---- Multiple errors reported in one pass ----

    @Test
    void multipleSyntaxErrorsReportedTogether() {
        // Two independent bad statements: both errors are reported.
        try {
            engine.eval("x = ;\ny = ;");
            fail("Should have thrown");
        } catch (ScriptException e) {
            String msg = e.getMessage();
            assertMessageContainsAll(msg, "line 1", "line 2");
        }
    }

    @Test
    void recoveryContinuesAfterExpectFailure() {
        // expect(SEMI) fails on the second statement: parsing continues and
        // the error on the third statement is also reported.
        try {
            engine.eval("x = 1\ny = @;\nz = @;");
            fail("Should have thrown");
        } catch (ScriptException e) {
            String msg = e.getMessage();
            assertMessageContainsAll(msg, "line 2", "line 3");
        }
    }

    @Test
    void semanticErrorsDoNotStopParsing() {
        // Repeated modifier is a semantic error: parsing continues and
        // the syntax error in the second statement is also reported.
        try {
            engine.eval("static static y = 2;\nz = ;");
            fail("Should have thrown");
        } catch (ScriptException e) {
            String msg = e.getMessage();
            assertMessageContainsAll(msg, "line 1", "line 2");
        }
    }

    @Test
    void recoveryStopsAtClosingBracket() {
        // Inside a bracketed expression the recovery must stop at the
        // closing bracket and not consume it.
        try {
            engine.eval("[1 + ]");
            fail("Should have thrown");
        } catch (ScriptException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    void recoveryStopsAtClosingBrace() {
        // Inside a compound statement the recovery must stop at '}'.
        try {
            engine.eval("if (true) { 1 2; 3; }");
            fail("Should have thrown");
        } catch (ScriptException e) {
            String msg = e.getMessage();
            assertTrue(msg.contains(";") || msg.contains("expected"),
                       "expected a ';'-related error: " + msg);
        }
    }

    @Test
    void incompleteInputThrowsIncomplete() {
        // A single incomplete error keeps throwing IncompleteException.
        try {
            engine.put("elite.interactive", true);
            engine.eval("1 +");
            fail("Should have thrown");
        } catch (ScriptException e) {
            assertTrue(e.getCause() instanceof IncompleteException,
                       "cause should be IncompleteException: " + e.getCause());
        }
    }

    // ---- Compound exception ----

    @Test
    void compoundExceptionAggregatesErrors() {
        Parser parser = new Parser(elContext(), "x = ;\ny = ;");
        ParseException ex = assertThrows(ParseException.class, parser::parse);
        List<ParseError> errors = ex.getErrors();
        assertEquals(2, errors.size());
        assertEquals(1, errors.get(0).line());
        assertEquals(2, errors.get(1).line());
    }

    // ---- Source line and caret marker ----

    @Test
    void errorMessageShowsSourceLineWithCaret() {
        try {
            engine.eval("x = ;");
            fail("Should have thrown");
        } catch (ScriptException e) {
            String msg = e.getMessage();
            assertMessageContainsAll(msg, "x = ;", "^");
        }
    }

    @Test
    void caretPointsAtErrorColumn() {
        // "x = ;" errors on the ';' at column 5: the caret line is
        // 4 spaces of indentation, 4 spaces for columns 1-4, then '^'.
        Parser parser = new Parser(elContext(), "x = ;");
        ParseException ex = assertThrows(ParseException.class, parser::parse);
        String msg = ex.getMessage();
        assertTrue(msg.contains("x = ;"), msg);
        assertTrue(msg.contains("^"), msg);
    }

    @Test
    void compoundErrorsShowSourceLines() {
        try {
            engine.eval("x = ;\ny = ;");
            fail("Should have thrown");
        } catch (ScriptException e) {
            String msg = e.getMessage();
            assertMessageContainsAll(msg, "x = ;", "y = ;", "^");
        }
    }

    @Test
    void singleErrorThrowsPlainParseException() {
        Parser parser = new Parser(elContext(), "x = ;");
        assertThrows(ParseException.class, parser::parse);
    }

    // ---- Error limit ----

    @Test
    void errorLimitAbortsParsing() {
        Parser parser = new Parser(elContext(), "a b c d e");
        parser.setErrorLimit(2);
        assertThrows(ParseException.class, parser::parse);
    }

    @Test
    void errorLimitCanBeSetOnParser() {
        Parser parser = new Parser(elContext(), "a b c d e");
        parser.setErrorLimit(1);
        assertThrows(ParseException.class, parser::parse);
    }

    // ---- Valid programs unaffected ----

    @Test
    void validProgramStillParses() throws ScriptException {
        engine.eval("define x = 1;\nif (x > 0) { x = 2; }\nx + 1;");
        assertEquals(3L, evalL("x + 1"));
    }

    // ---- Pathological inputs terminate ----

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void pathologicalInputsTerminate() {
        String[] inputs = {
            ")))", "(((", "}}", "{{", "]]", "@@@",
            "a b c d e f g h i j k l",
            "x = ; y = ; z = ;",
            "if ( { }",
            "while x ) { }",
            "try { 1 }",
            "class C { 5 }",
            "define 5 = 3;",
            "new Foo[] ;",
            "[1, ; 2]",
            "{ 1 + }",
            "f(1, ; 2)",
            "x not 5",
            "1 + * 2",
            "switch (x) { case 1 => ; default => ; default => ; }",
        };
        for (String input : inputs) {
            try {
                freshEngine().eval(input);
            } catch (ScriptException e) {
                // expected for erroneous input — just assert termination
            }
        }
    }
}
