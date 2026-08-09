package org.elite.integration;

import static org.junit.jupiter.api.Assertions.*;

import org.elite.EliteTestBase;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Tests for invokedynamic-based property access (getValue/setValue)
 * on Java objects, covering getters, setters, public fields, static
 * members, and argument type coercion.
 */
class PropertyAccessTest extends EliteTestBase {

    private static final String IMPORT = "import org.elite.integration.testdata.PersonBean";

    // ── Setup helpers ──

    private void setupPerson() {
        exec(IMPORT);
        exec("define p = new PersonBean()");
    }

    private void setupPersonWithData() {
        exec(IMPORT);
        exec("define p = new PersonBean(42, \"Alice\")");
    }

    // ── Simple getter ──

    @Test
    void getStringProperty() {
        setupPersonWithData();
        assertEquals("Alice", eval("p.name"));
    }

    @Test
    void getIntProperty() {
        setupPersonWithData();
        assertEquals(42L, evalL("p.id"));
    }

    @Test
    void getDefaultIntProperty() {
        setupPerson();
        assertEquals(0L, evalL("p.age"));
    }

    @Test
    void getBooleanIsProperty() {
        setupPerson();
        assertFalse((Boolean) eval("p.active"));
    }

    @Test
    void getDoubleProperty() {
        setupPerson();
        assertEquals(0.0, evalD("p.salary"), 0.001);
    }

    // ── Simple setter ──

    @Test
    void setStringProperty() {
        setupPerson();
        exec("p.name = \"Bob\"");
        assertEquals("Bob", eval("p.name"));
    }

    @Test
    void setIntProperty() {
        setupPerson();
        exec("p.age = 25");
        assertEquals(25L, evalL("p.age"));
    }

    @Test
    void setBooleanProperty() {
        setupPerson();
        exec("p.active = true");
        assertTrue((Boolean) eval("p.active"));
    }

    @Test
    void setDoubleProperty() {
        setupPerson();
        exec("p.salary = 75000.50");
        assertEquals(75000.50, evalD("p.salary"), 0.01);
    }

    // ── Public field access ──

    @Test
    void getPublicField() {
        setupPersonWithData();
        assertNull(eval("p.tag"));
    }

    @Test
    void setPublicField() {
        setupPersonWithData();
        exec("p.tag = \"developer\"");
        assertEquals("developer", eval("p.tag"));
    }

    @Test
    void overwritePublicField() {
        setupPersonWithData();
        exec("p.tag = \"first\"");
        exec("p.tag = \"second\"");
        assertEquals("second", eval("p.tag"));
    }

    // ── Read-only property ──

    @Test
    void readOnlyProperty() {
        setupPersonWithData();
        assertEquals(42L, evalL("p.id"));
        assertEvalThrows("p.id = 99");
    }

    // ── Chained property access ──

    @Test
    void chainedGetSet() {
        setupPerson();
        exec("p.name = \"Charlie\"");
        exec("p.age = 30");
        assertEquals("Charlie", eval("p.name"));
        assertEquals(30L, evalL("p.age"));
    }

    // ── Multiple instances ──

    @Test
    void multipleInstances() {
        exec(IMPORT);
        exec("define p1 = new PersonBean(1, \"Alice\")");
        exec("define p2 = new PersonBean(2, \"Bob\")");
        assertEquals("Alice", eval("p1.name"));
        assertEquals("Bob", eval("p2.name"));
        exec("p1.name = \"Alice Updated\"");
        assertEquals("Alice Updated", eval("p1.name"));
        assertEquals("Bob", eval("p2.name"));
    }

    // ── Round-trip set/get ──

    @Test
    void setThenGetAllProperties() {
        setupPerson();
        exec("p.name = \"Diana\"");
        exec("p.age = 28");
        exec("p.active = true");
        exec("p.salary = 95000.0");
        exec("p.tag = \"engineer\"");

        assertEquals("Diana", eval("p.name"));
        assertEquals(28L, evalL("p.age"));
        assertTrue((Boolean) eval("p.active"));
        assertEquals(95000.0, evalD("p.salary"), 0.01);
        assertEquals("engineer", eval("p.tag"));
    }

    // ── Property access on null ──

    @Test
    void nullPropertyGet() {
        assertNull(eval("null.name"));
    }

    // ══════════════════════════════════════════════════════════════
    // Type coercion tests — need filterArgument for TypeCoercion.coerce
    // ══════════════════════════════════════════════════════════════

    @Test
    void coerceIntToSetString() {
        // Setting String property with an integer needs coercion
        setupPerson();
        exec("p.name = 12345");
        assertEquals("12345", eval("p.name"));
    }

    @Test
    void coerceDoubleToSetInt() {
        // Setting int property with a double needs truncation coercion
        setupPerson();
        exec("p.age = 25.9");
        assertEquals(25L, evalL("p.age"));
    }

    @Test
    void writeOnlyPropertyAccess() {
        setupPersonWithData();
        exec("p.secret = \"classified\"");
        assertEvalThrows("p.secret");
    }
}
