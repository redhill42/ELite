package org.elite.ir;

import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MATCH/CONST_MATCH IR compilation.
 */
public class ConstMatchTest extends EliteTestBase {

    // ── Simple literals ──

    @Test
    void matchNumberLiteral() {
        exec("define fib(0) => 0 | fib(1) => 1 | fib(n) => fib(n-1) + fib(n-2)");
        assertEquals(0L, evalL("fib(0)"));
        assertEquals(1L, evalL("fib(1)"));
        assertEquals(55L, evalL("fib(10)"));
    }

    @Test
    void matchStringLiteral() {
        exec("define describe(\"hello\") => 10 | describe(\"world\") => 20 | describe(_) => 0");
        assertEquals(10L, evalL("describe(\"hello\")"));
        assertEquals(20L, evalL("describe(\"world\")"));
        assertEquals(0L, evalL("describe(\"other\")"));
    }

    @Test
    void matchNull() {
        exec("define test(null) => 1 | test(_) => 0");
        assertEquals(1L, evalL("test(null)"));
        assertEquals(0L, evalL("test(42)"));
    }

    @Test
    void matchSymbol() {
        exec("define describe(:red) => 10 | describe(:blue) => 20 | describe(_) => 0");
        assertEquals(10L, evalL("describe(:red)"));
        assertEquals(20L, evalL("describe(:blue)"));
        assertEquals(0L, evalL("describe(:green)"));
    }

    @Test
    void matchBoolean() {
        exec("define describe(true) => 1 | describe(false) => 0 | describe(_) => -1");
        assertEquals(1L, evalL("describe(true)"));
        assertEquals(0L, evalL("describe(false)"));
    }

    // ── Variable binding ──

    @Test
    void matchVariableBinding() {
        // Pattern with variable binding: n captures the matched value.
        exec("define fact(0) => 1 | fact(n) => n * fact(n-1)");
        assertEquals(6L, evalL("fact(3)"));
        assertEquals(120L, evalL("fact(5)"));
    }

    @Test
    void matchWildcard() {
        exec("define isZero(0) => true | isZero(_) => false");
        assertEquals(true, eval("isZero(0)"));
        assertEquals(false, eval("isZero(1)"));
    }

    // ── NOT pattern ──

    @Test
    void matchNotPattern() {
        exec("define describe(!0) => \"non-zero\" | describe(_) => \"zero\"");
        // !0 pattern matches anything that is not 0
        assertEquals("non-zero", eval("describe(42)"));
        assertEquals("zero", eval("describe(0)"));
    }
}
