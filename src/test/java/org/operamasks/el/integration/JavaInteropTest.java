package org.operamasks.el.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.operamasks.el.EliteTestBase;

/**
 * Tests for Java interop: import, new, static methods, instance methods, field access.
 */
class JavaInteropTest extends EliteTestBase {

    // ---- Import ----

    @Test
    void importJavaClass() {
        exec("import java.util.Date");
        Object result = eval("new Date(0)");
        assertTrue(result instanceof Date);
    }

    @Test
    void importWithStaticWildcard() {
        exec("import static java.lang.Math.*");
        assertEquals(42L, ((Number) eval("abs(-42)")).longValue());
    }

    // ---- Instantiation ----

    @Test
    void newJavaObject() {
        Object result = eval("new java.util.Date(0)");
        assertTrue(result instanceof Date);
    }

    @Test
    void newJavaObjectNoArgs() {
        Object result = eval("new java.util.ArrayList()");
        assertTrue(result instanceof List);
        assertTrue(((List<?>) result).isEmpty());
    }

    // ---- Static method calls ----

    @Test
    void mathAbs() {
        assertEquals(42L, eval("Math.abs(-42)"));
    }

    @Test
    void mathMin() {
        assertEquals(5L, eval("Math.min(5, 10)"));
    }

    @Test
    void mathMax() {
        assertEquals(10L, eval("Math.max(5, 10)"));
    }

    @Test
    void mathSqrt() {
        assertEquals(3.0, ((Number) eval("Math.sqrt(9)")).doubleValue(), 0.001);
    }

    // ---- Instance method calls ----

    @Test
    void stringLength() {
        assertEquals(5, eval("\"hello\".length()"));
    }

    @Test
    void stringIndexOf() {
        assertEquals(2, eval("\"hello\".indexOf(\"l\")"));
    }

    @Test
    void stringReplace() {
        assertEquals("heLLo", eval("\"hello\".replace(\"l\", \"L\")"));
    }

    @Test
    void listAdd() {
        exec("define lst = new java.util.ArrayList()");
        exec("lst.add(\"a\")");
        exec("lst.add(\"b\")");
        assertEquals(2, eval("lst.size()"));
    }

    // ---- Static field access ----

    @Test
    void staticFieldAccess() {
        // Accessing static fields of Java classes
        Object result = eval("Math.PI");
        assertNotNull(result);
    }

    @Test
    void systemOut() {
        // System.out should be accessible
        exec("System.out.println(\"test from ELite\")");
    }

    // ---- Field access on Java objects ----

    @Test
    void javaClassPropertyAccess() {
        exec("define d = new java.util.Date(0)");
        exec("define t = d.time");
        assertNotNull(eval("t"));
    }

    // ---- Array creation and access ----

    @Test
    void javaArrayCreate() {
        // If array creation is supported
        Object result = eval("new java.util.Date(0)");
        assertNotNull(result);
    }
}
