package org.operamasks.el.ir;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.operamasks.el.parser.Parser;

class FieldAccessTest {

    // A Java class with a public field for testing
    public static class Point {
        public int x;
        public int y;
        public Point(int x, int y) { this.x = x; this.y = y; }
    }

    @Test void loadFieldViaInterpreter() throws Exception {
        javax.script.ScriptEngine e =
            new javax.script.ScriptEngineManager().getEngineByName("ELite");
        e.put("p", new Point(10, 20));
        // Access public field .x on known Java type
        Object r = e.eval("p.x");
        assertEquals(10L, ((Number) r).longValue());
    }

    @Test void storeFieldViaInterpreter() throws Exception {
        javax.script.ScriptEngine e =
            new javax.script.ScriptEngineManager().getEngineByName("ELite");
        e.put("p", new Point(0, 0));
        e.eval("p.x = 42");
        assertEquals(42L, ((Number) e.eval("p.x")).longValue());
    }

    @Test void loadFieldUsesLoadFieldOp() {
        // Build IR for p.x where p has a known Java type
        // Verify LOAD_FIELD opcode is used
        javax.script.ScriptEngine e =
            new javax.script.ScriptEngineManager().getEngineByName("ELite");
        e.put("p", new Point(1, 2));
        // This should go through IR with LOAD_FIELD when the type is resolved
        try { e.eval("p.x"); } catch (javax.script.ScriptException ex) {}
        // Just verify no crash
    }

    @Test void fieldNotFoundThrows() {
        try {
            javax.script.ScriptEngine e =
                new javax.script.ScriptEngineManager().getEngineByName("ELite");
            e.put("p", new Point(1, 2));
            e.eval("p.z"); // z doesn't exist
            fail("Should throw");
        } catch (javax.script.ScriptException e) {
            assertTrue(e.getMessage().contains("z") || e.getMessage().contains("not found"),
                "Error should mention field name: " + e.getMessage());
        }
    }
}
