package org.operamasks.el;

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

    /** Enable dual-mode (multi-eval vs single-eval) result comparison. */
    private static final boolean DUAL_MODE = Boolean.getBoolean("elite.test.dualmode");

    /** Recorded exec() statements for dual-mode replay. */
    private final List<String> recorded = new ArrayList<>();

    @BeforeEach
    void createEngine() {
        engine = new ScriptEngineManager().getEngineByName("ELite");
        assertNotNull(engine, "ELite ScriptEngine not found on classpath");
    }

    @AfterEach
    void clearRecording() {
        recorded.clear();
    }

    /** Evaluate an expression and return its long value. */
    protected long evalL(String expr) {
        long result;
        try {
            result = ((Number) engine.eval(expr)).longValue();
        } catch (ScriptException e) {
            throw new RuntimeException("eval failed: " + expr, e);
        }
        if (DUAL_MODE && !recorded.isEmpty()) {
            String script = String.join("\n", recorded) + "\n" + expr;
            ScriptEngine f = freshEngine();
            try {
                long singleResult = ((Number) f.eval(script)).longValue();
                assertEquals(result, singleResult,
                    "DUAL-MODE mismatch in " + getClass().getSimpleName() + ":\n" + script);
            } catch (ScriptException e) {
                throw new AssertionError(
                    "DUAL-MODE script failed in " + getClass().getSimpleName() + ":\n" + script, e);
            }
        }
        return result;
    }

    /** Evaluate an expression and return its double value. */
    protected double evalD(String expr) {
        double result;
        try {
            result = ((Number) engine.eval(expr)).doubleValue();
        } catch (ScriptException e) {
            throw new RuntimeException("eval failed: " + expr, e);
        }
        if (DUAL_MODE && !recorded.isEmpty()) {
            String script = String.join("\n", recorded) + "\n" + expr;
            ScriptEngine f = freshEngine();
            try {
                double singleResult = ((Number) f.eval(script)).doubleValue();
                assertEquals(result, singleResult, 0.0,
                    "DUAL-MODE mismatch in " + getClass().getSimpleName() + ":\n" + script);
            } catch (ScriptException e) {
                throw new AssertionError(
                    "DUAL-MODE script failed in " + getClass().getSimpleName() + ":\n" + script, e);
            }
        }
        return result;
    }

    /** Evaluate an expression and return the raw result. */
    protected Object eval(String expr) {
        Object result;
        try {
            result = engine.eval(expr);
        } catch (ScriptException e) {
            throw new RuntimeException("eval failed: " + expr, e);
        }
        if (DUAL_MODE && !recorded.isEmpty()) {
            String script = String.join("\n", recorded) + "\n" + expr;
            ScriptEngine f = freshEngine();
            try {
                Object singleResult = f.eval(script);
                assertEquals(result, singleResult,
                    "DUAL-MODE mismatch in " + getClass().getSimpleName() + ":\n" + script);
            } catch (ScriptException e) {
                throw new AssertionError(
                    "DUAL-MODE script failed in " + getClass().getSimpleName() + ":\n" + script, e);
            }
        }
        return result;
    }

    /** Execute a statement (return value ignored). */
    protected void exec(String stmt) {
        if (DUAL_MODE)
            recorded.add(stmt);
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
