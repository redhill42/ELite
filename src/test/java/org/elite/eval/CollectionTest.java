package org.elite.eval;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for collections: lists, maps, tuples, ranges, list comprehensions, 'in' operator.
 */
class CollectionTest extends EliteTestBase {

    // ---- Lists ----

    @Test
    void emptyList() {
        Object result = eval("[]");
        assertTrue(result instanceof List, "[] should return a List");
        assertTrue(((List<?>) result).isEmpty());
    }

    @Test
    void listWithValues() {
        Object result = eval("[1, 2, 3]");
        List<?> list = (List<?>) result;
        assertEquals(3, list.size());
    }

    @Test
    void listLength() {
        assertEquals(3L, evalL("[1, 2, 3].size()"));
    }

    @Test
    void listIndexAccess() {
        assertEquals(2L, evalL("[1, 2, 3][1]"));
    }

    @Test
    void listFirstLast() {
        assertEquals(1L, evalL("[1, 2, 3].first"));
        assertEquals(3L, evalL("[1, 2, 3].last"));
    }

    @Test
    void listTail() {
        Object result = eval("[1, 2, 3].tail");
        assertNotNull(result);
    }

    @Test
    void listInit() {
        Object result = eval("[1, 2, 3].head");
        assertNotNull(result);
    }

    @Test
    void listConcatenation() {
        Object result = eval("[1, 2] ~ [3, 4]");
        List<?> list = (List<?>) result;
        assertEquals(4, list.size());
    }

    // ---- Cons ----

    @Test
    void consPrepend() {
        Object result = eval("[0 : [1, 2, 3]]");
        List<?> list = (List<?>) result;
        assertEquals(4, list.size());
    }

    // ---- Range ----

    @Test
    void rangeExpression() {
        Object result = eval("[1..5]");
        List<?> list = (List<?>) result;
        assertEquals(5, list.size());
        assertEquals(1L, ((Number) list.get(0)).longValue());
        assertEquals(5L, ((Number) list.get(4)).longValue());
    }

    @Test
    void rangeWithExplicitBounds() {
        assertEquals(55L, evalL("foldl([1..10], 0, (+))"));
    }

    // ---- List comprehensions ----

    @Test
    void simpleComprehension() {
        Object result = eval("[x * 2 | x <- [1..5]]");
        List<?> list = (List<?>) result;
        assertEquals(5, list.size());
    }

    @Test
    void comprehensionWithFilter() {
        Object result = eval("[x | x <- [1..10], x % 2 == 0]");
        List<?> list = (List<?>) result;
        assertEquals(5, list.size());
    }

    // ---- Maps ----

    @Test
    void mapLiteral() {
        Object result = eval("{a: 1, b: 2}");
        assertNotNull(result);
    }

    @Test
    void mapAccess() {
        exec("define m = {name: \"Alice\", age: 30}");
        assertEquals("Alice", eval("m.name"));
        assertEquals(30L, evalL("m.age"));
    }

    // ---- Tuples ----

    @Test
    void tupleLiteral() {
        Object result = eval("(1, \"hello\", true)");
        assertNotNull(result);
    }

    @Test
    void tupleElementAccess() {
        exec("define t = (10, 20, 30)");
        assertEquals(10L, evalL("t[0]"));
    }

    // ---- 'in' operator ----

    @Test
    void inOperatorList() {
        assertEquals(true, eval("3 in [1, 2, 3, 4, 5]"));
        assertEquals(false, eval("9 in [1, 2, 3]"));
    }

    @Test
    void notInOperator() {
        assertEquals(true, eval("9 not in [1, 2, 3]"));
    }

    @Test
    void inOperatorRange() {
        assertEquals(true, eval("5 in [1..10]"));
        assertEquals(false, eval("15 in [1..10]"));
    }

    // ---- Slice ----

    @Test
    void listSlice() {
        exec("define lst = [10, 20, 30, 40, 50]");
        Object result = eval("lst[1..3]");
        assertNotNull(result);
    }

    // ---- Structural equality ----

    @Test
    void listEquality() {
        assertEquals(true, eval("[1, 2, 3] == [1, 2, 3]"));
        assertEquals(false, eval("[1, 2, 3] == [1, 2]"));
    }
}
