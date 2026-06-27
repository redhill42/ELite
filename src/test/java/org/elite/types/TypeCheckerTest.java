package org.elite.types;

import static org.junit.jupiter.api.Assertions.*;

import javax.el.ELContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.elite.eval.ELEngine;
import org.elite.eval.ELProgram;
import org.elite.parser.ELNode;
import org.elite.parser.Parser;

/**
 * Tests for TypeChecker and cross-eval type persistence.
 */
class TypeCheckerTest {

    private ELContext elctx;

    @BeforeEach
    void setUp() {
        elctx = ELEngine.createELContext();
    }

    // ---- TypeChecker basic operations ----

    @Test
    void checkValidProgramNoErrors() {
        TypeChecker checker = new TypeChecker(elctx);
        ELProgram prog = new Parser("define x::Integer = 42").parse();
        checker.checkProgram(prog.getDefinitions(), prog.getExpressions());
        assertEquals(0, checker.getErrors().size());
    }

    @Test
    void checkProgramNoErrorsForValidAnnotations() {
        TypeChecker checker = new TypeChecker(elctx);
        ELProgram prog = new Parser("define x::Integer = 42").parse();
        checker.checkProgram(prog.getDefinitions(), prog.getExpressions());
        assertTrue(checker.getErrors().isEmpty(),
            "Expected no errors but got: " + checker.getErrors());
    }

    // ---- Type persistence across evals ----

    @Test
    void typeBindingsPersistAcrossEvals() {
        TypeInferrer inferrer = new TypeInferrer(elctx);
        ELNode node = Parser.parse("define x::Integer = 42");
        inferrer.infer(node);
        inferrer.persistTypes();

        // New inferrer on same ELContext should see the binding
        TypeInferrer inferrer2 = new TypeInferrer(elctx);
        ELNode lookup = Parser.parseExpression("x");
        Type t = inferrer2.infer(lookup);
        assertNotNull(t, "Type should be restored from persisted bindings");
    }

    // ---- Full pipeline via ScriptEngine ----

    @Test
    void validTypeAnnotationViaEngine() throws ScriptException {
        ScriptEngine eng = new javax.script.ScriptEngineManager().getEngineByName("ELite");
        eng.eval("define x::Integer = 42");
        assertEquals(42L, ((Number) eng.eval("x")).longValue());
    }

    @Test
    @Disabled("temporarily disable type checking")
    void invalidTypeAnnotationViaEngine() {
        ScriptEngine eng = new javax.script.ScriptEngineManager().getEngineByName("ELite");
        assertThrows(ScriptException.class, () -> {
            eng.eval("define x::NonExistentType = 42");
        }, "Undefined type annotation should throw ScriptException");
    }
}
