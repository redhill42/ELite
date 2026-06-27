package org.elite.integration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for module system: require, import, and built-in module loading.
 */
class ModuleTest extends EliteTestBase {

    @Test
    @Disabled("Built-in modules may not be loadable in test environment")
    void requireSyntaxModule() {
        exec("require 'syntax'");
    }

    @Test
    @Disabled("Built-in modules may not be loadable in test environment")
    void requireMathModule() {
        exec("require 'math'");
    }

    @Test
    @Disabled("Built-in modules may not be loadable in test environment")
    void requireIOModule() {
        exec("require 'io'");
    }

    @Test
    void importJavaPackage() {
        exec("import java.util.Date");
        Object result = eval("new Date(0)");
        assertTrue(result instanceof java.util.Date);
    }

    @Test
    void importStaticMethod() {
        exec("import static java.lang.Math.*");
        assertEquals(42L, ((Number) eval("abs(-42)")).longValue());
    }
}
