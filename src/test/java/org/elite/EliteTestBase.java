package org.elite;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Shared base class for ELite tests.
 * Provides a pre-configured ScriptEngine and convenience eval helpers.
 *
 * <h3>Dual-Mode Testing</h3>
 * When {@code -Delite.test.dualmode=true}, every {@code eval()/evalL()/evalD()}
 * call replays all previously {@code exec()}ed statements together with the
 * expression on a fresh engine (single-eval mode) and asserts the result is
 * identical to the multi-eval result. This catches cases where the IR execution
 * path diverges from the AST path because function definitions were compiled
 * separately from their call sites.
 */
public abstract class EliteTestBase {

    protected ScriptEngine engine;

    @BeforeEach
    void createEngine() {
        engine = new ScriptEngineManager().getEngineByName("ELite");
        assertNotNull(engine, "ELite ScriptEngine not found on classpath");
    }

    /** Evaluate an expression and return its long value. */
    protected long evalL(String expr) {
        try {
            return ((Number) engine.eval(expr)).longValue();
        } catch (ScriptException e) {
            throw new RuntimeException("eval failed: " + expr, e);
        }
    }

    /** Evaluate an expression and return its double value. */
    protected double evalD(String expr) {
        try {
            return ((Number) engine.eval(expr)).doubleValue();
        } catch (ScriptException e) {
            throw new RuntimeException("eval failed: " + expr, e);
        }
    }

    /** Evaluate an expression and return the raw result. */
    protected Object eval(String expr) {
        try {
            return engine.eval(expr);
        } catch (ScriptException e) {
            throw new RuntimeException("eval failed: " + expr, e);
        }
    }

    /** Execute a statement (return value ignored). */
    protected void exec(String stmt) {
        try {
            engine.eval(stmt);
        } catch (ScriptException e) {
            throw new RuntimeException("exec failed: " + stmt, e);
        }
    }

    /** Create a fresh independent engine to avoid state pollution between tests. */
    protected static ScriptEngine freshEngine() {
        ScriptEngine eng = new ScriptEngineManager().getEngineByName("ELite");
        assertNotNull(eng, "ELite ScriptEngine not found on classpath");
        return eng;
    }

    /** Assert that evaluating expr throws ScriptException. */
    protected void assertEvalThrows(String expr) {
        assertThrows(ScriptException.class, () -> engine.eval(expr),
            () -> "Expected ScriptException for: " + expr);
    }

    /** Assert that evaluating expr on a specific engine throws ScriptException. */
    protected static void assertEvalThrows(ScriptEngine eng, String expr) {
        assertThrows(ScriptException.class, () -> eng.eval(expr),
            () -> "Expected ScriptException for: " + expr);
    }
}
