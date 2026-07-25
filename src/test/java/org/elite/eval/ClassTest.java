package org.elite.eval;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for class definitions, instantiation, methods, and inheritance.
 */
class ClassTest extends EliteTestBase {

    // ---- Simple class ----

    @Test
    void classDefinition() {
        Object result = eval(
          "class Point(x, y) { toString() => \"($x, $y)\" }\n" +
          "Point(3, 4)");
        assertNotNull(result);
    }

    @Test
    void classInstantiation() {
        exec("class Point(x, y) { getX() => x; getY() => y; }\n" +
             "define p = Point(10, 20)");
        assertEquals(10L, evalL("p.getX()"));
        assertEquals(20L, evalL("p.getY()"));
    }

    @Test
    void classFieldAccess() {
        exec("class Point(x, y) {} define p = Point(3, 4)");
        assertEquals(3L, evalL("p.x"));
        assertEquals(4L, evalL("p.y"));
    }

    @Test
    void classMethod() {
        exec("class Greeter(name) { greet() => \"Hello, \" ~ name }\n" +
             "define g = Greeter(\"World\")");
        assertEquals("Hello, World", eval("g.greet()"));
    }

    // ---- Operator overloading in class ----

    @Test
    void classOperatorPlus() {
        exec("""
               class Point(x, y) {
                 +(other) => Point(x + other.x, y + other.y);
                 toString() => "(" ~ x ~ ", " ~ y ~ ")"
               }
               define a = Point(1, 2)
               define b = Point(3, 4)""");
        Object result = eval("a + b");
        assertNotNull(result);
    }

    @Test
    void classOperatorEquals() {
        exec("""
             class Point(x, y) { ==(other) => x == other.x && y == other.y }
             define a = Point(1, 2)
             define b = Point(1, 2)
             define c = Point(3, 4)""");
        assertEquals(true, eval("a == b"));
        assertEquals(false, eval("a == c"));
    }

    @Test
    void classOperatorString() {
        String result = eval(
          "class Point(x, y) { toString() => \"pt(\" ~ x ~ \", \" ~ y ~ \")\" }\n" +
          "Point(3, 4).toString()").toString();
        assertTrue(result.contains("pt"));
    }

    // ---- Methods with block body ----

    @Test
    void classMethodWithBlockBody() {
        exec("class Counter(init) { inc() { init = init + 1; init } }\n" +
             "define c = Counter(0)");
        assertEquals(1L, evalL("c.inc()"));
        assertEquals(2L, evalL("c.inc()"));
    }

    // ---- Multi-methods (pattern-matched methods) ----

    @Test
    void patternMatchedMethod() {
        exec("class TreeSet { " +
             "  member(t, x) { | null, _ => false; | _, _ => true } " +
             "}\n" +
             "define ts = TreeSet()");
        assertNotNull(eval("ts"));
    }

    // ---- Nested class instantiation ----

    @Test
    void classFieldOfSameClass() {
        exec("class Pair(a, b) { first() => a; second() => b }\n" +
             "define p = Pair(1, 2)");
        assertEquals(1L, evalL("p.first()"));
        assertEquals(2L, evalL("p.second()"));
    }

    // ── Type checker regression tests ──

    @Test
    void userDefinedClassWithNewAndFieldAccess() {
        exec("class Point { define x, y }\n" +
             "define pt = new Point");
        exec("pt.x = 10");
        exec("pt.y = 20");
        assertEquals(30L, evalL("pt.x + pt.y"));
    }

    @Test
    void voidMethodNoReturnTypeMismatchError() {
        exec("""
              class Counter {
                define n
                public void increment() => n += 1
              }
              define c = new Counter""");
        exec("c.n = 5");
        exec("c.increment()");
        assertEquals(6L, evalL("c.n"));
    }
}
