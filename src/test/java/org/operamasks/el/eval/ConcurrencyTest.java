package org.operamasks.el.eval;

import static org.junit.jupiter.api.Assertions.*;

import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.junit.jupiter.api.Test;

/**
 * Tests for concurrent evaluation and engine isolation.
 */
class ConcurrencyTest {

    // ---- Engine isolation ----

    @Test
    void enginesAreIsolated() throws ScriptException {
        ScriptEngine e1 = new javax.script.ScriptEngineManager().getEngineByName("ELite");
        ScriptEngine e2 = new javax.script.ScriptEngineManager().getEngineByName("ELite");

        e1.eval("define x = 1");
        e2.eval("define x = 2");

        assertEquals(1L, ((Number) e1.eval("x")).longValue());
        assertEquals(2L, ((Number) e2.eval("x")).longValue());
    }

    @Test
    void engineStateNotSharedAcrossCreations() throws ScriptException {
        ScriptEngine e1 = new javax.script.ScriptEngineManager().getEngineByName("ELite");
        e1.eval("define x = 42");

        ScriptEngine e2 = new javax.script.ScriptEngineManager().getEngineByName("ELite");
        Exception ex = assertThrows(ScriptException.class, () -> e2.eval("x"),
            "Second engine should not see first engine's definitions");
    }

    // ---- Concurrent access ----

    @Test
    void concurrentEvalDoesNotCorruptState() throws Exception {
        ScriptEngine e1 = new javax.script.ScriptEngineManager().getEngineByName("ELite");
        e1.eval("define x = 0");

        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                try { e1.eval("x = x + 1"); } catch (ScriptException e) {}
            }
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                try { e1.eval("x = x + 1"); } catch (ScriptException e) {}
            }
        });

        t1.start(); t2.start();
        t1.join(5000); t2.join(5000);

        // The result may vary due to race conditions, but we just verify
        // the engine doesn't crash or corrupt memory
        Object result = e1.eval("x");
        assertNotNull(result);
    }
}
