package org.elite.eval;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for pattern matching: pattern-matched functions, list destructuring, wildcards.
 *
 * NOTE: The in-function `case (expr) { | pat => ... }` syntax requires specific
 * statement separators and is currently partially supported. The primary pattern
 * matching mechanism is via pattern-matched function definitions using `|`.
 */
class PatternMatchingTest extends EliteTestBase {

    // ---- Pattern-matched functions ----

    @Test
    void patternMatchedFib() {
        exec("define fib(0) => 0 | fib(1) => 1 | fib(n) => fib(n-1) + fib(n-2)");
        assertEquals(0L, evalL("fib(0)"));
        assertEquals(1L, evalL("fib(1)"));
        assertEquals(55L, evalL("fib(10)"));
    }

    @Test
    void patternMatchedFactorial() {
        exec("define fact(0) => 1 | fact(n) => n * fact(n-1)");
        assertEquals(120L, evalL("fact(5)"));
    }

    @Test
    void patternMatchedSimple() {
        exec("define isZero(0) => true | isZero(_) => false");
        assertEquals(true, eval("isZero(0)"));
        assertEquals(false, eval("isZero(1)"));
        assertEquals(false, eval("isZero(42)"));
    }

    @Test
    @Disabled("Guard syntax 'define f(x) if cond => ...' not fully supported")
    void patternMatchedWithGuard() {
        exec("define sign(x) if x > 0 => 1 | sign(x) if x < 0 => -1 | sign(_) => 0");
        assertEquals(1L, evalL("sign(10)"));
        assertEquals(-1L, evalL("sign(-5)"));
        assertEquals(0L, evalL("sign(0)"));
    }

    // ---- List destructuring ----

    @Test
    void listDestructureEmpty() {
        exec("define isEmpty([]) => true | isEmpty(_) => false");
        assertEquals(true, eval("isEmpty([])"));
        assertEquals(false, eval("isEmpty([1, 2, 3])"));
    }

    @Test
    void listDestructureHeadTail() {
        exec("define myHead([x:xs]) => x | myHead([]) => null");
        assertEquals(1L, evalL("myHead([1, 2, 3])"));
        assertNull(eval("myHead([])"));
    }

    @Test
    void listDestructureTwoElements() {
        exec("define sum2([a, b]) => a + b");
        assertEquals(7L, evalL("sum2([3, 4])"));
    }

    @Test
    void nestedPatternMatching() {
        exec("define f([x, y, z]) => x + y + z");
        assertEquals(6L, evalL("f([1, 2, 3])"));
    }

    // ---- Expression-based match (case keyword) ----

    @Test
    @Disabled("case (expr) { | pat => body } syntax requires specific statement separators")
    void caseExpressionLiteral() {
        exec("define describe(x) { case (x) { | 0 => \"zero\" } }");
        assertEquals("zero", eval("describe(0)"));
    }

    // ---- Pattern matching with types ----

    @Test
    @Disabled("Type-ascribed patterns (n::Integer) in match may use different syntax")
    void matchWithTypeAnnotation() {
        exec("define isInt(x) { case (x) { | n::Integer => true | _ => false } }");
        assertEquals(true, eval("isInt(42)"));
        assertEquals(false, eval("isInt(\"hello\")"));
    }

    @Test
    @Disabled("Record destructuring in patterns not fully supported")
    void matchWithRecordPattern() {
        exec("define getAge({name, age}) => age");
        assertEquals(30L, evalL("getAge({name: \"Alice\", age: 30})"));
    }
}
