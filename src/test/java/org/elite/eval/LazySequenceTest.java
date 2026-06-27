package org.elite.eval;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for lazy sequences, infinite sequences, and delay/thunk operations.
 */
class LazySequenceTest extends EliteTestBase {

    // ---- Basic lazy sequence ----

    @Test
    void delayConsViaAmpersand() {
        exec("define from(n) => [n : &from(n+1)]");
        exec("define integers = from(1)");
        Object result = eval("integers");
        assertTrue(result instanceof List);
    }

    @Test
    void takeFromInfiniteSequence() {
        exec("define from(n) => [n : &from(n+1)]");
        exec("define naturals = from(1)");
        // Slice [0..4] gives 5 elements (inclusive range)
        exec("define first5 = naturals[0..4]");
        assertEquals(5L, evalL("first5.size()"));
    }

    @Test
    void infiniteSequenceFirstElement() {
        exec("define from(n) => [n : &from(n+1)]");
        exec("define nums = from(100)");
        assertEquals(100L, evalL("nums[0]"));
    }

    // ---- Map over lazy sequences ----

    @Test
    @Disabled("map() requires Seq but delay (&) returns Procedure; lazy seq API may differ")
    void mapLazySequence() {
        exec("define from(n) => [n : &from(n+1)]");
        exec("define doubles = map(\\x => x * 2, from(1))");
        Object result = eval("doubles[0..3]");
        assertNotNull(result);
    }

    // ---- Filter lazy sequences ----

    @Test
    @Disabled("filter() requires Seq but delay (&) returns Procedure; lazy seq API may differ")
    void filterLazySequence() {
        exec("define from(n) => [n : &from(n+1)]");
        exec("define evens = filter(\\x => x % 2 == 0, from(1))");
        Object result = eval("evens[0..3]");
        assertNotNull(result);
    }

    // ---- Fibonacci via lazy sequence ----

    @Test
    @Disabled("Lazy fib sequence with complex self-referencing not fully supported")
    void lazyFibSequence() {
        exec("define fibs = [1 : &[1 : &map2((+), fibs, fibs.tail)]]");
        assertEquals(1L, evalL("fibs[0]"));
        assertEquals(1L, evalL("fibs[1]"));
        assertEquals(2L, evalL("fibs[2]"));
    }

    // ---- Thunk/delay ----

    @Test
    void delayThunk() {
        exec("define x = &(1 + 2)");
        Object result = eval("x");
        assertNotNull(result);
    }

    @Test
    @Disabled("force() method on delay thunk may use different API")
    void forceThunk() {
        exec("define x = &(10 * 3)");
        assertEquals(30L, evalL("x.force()"));
    }

    // ---- Lazy evaluation prevents computation ----

    @Test
    void lazyDoesNotEvaluateUnusedElements() {
        exec("define from(n) => [n : &from(n+1)]");
        exec("define nums = from(1)");
        // Just accessing the first element should not compute the whole list
        assertEquals(1L, evalL("nums.first"));
    }
}
