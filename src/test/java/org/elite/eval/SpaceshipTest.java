package org.elite.eval;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for the spaceship operator {@code <=>} including class-based
 * overloading and Comparable auto-implementation.
 */
class SpaceshipTest extends EliteTestBase {

    // ---- Basic spaceship evaluation ----

    @Test
    void spaceshipLessThan() {
        assertEquals(-1L, evalL("3 <=> 5"));
    }

    @Test
    void spaceshipEqual() {
        assertEquals(0L, evalL("5 <=> 5"));
    }

    @Test
    void spaceshipGreaterThan() {
        assertEquals(1L, evalL("7 <=> 5"));
    }

    @Test
    void spaceshipString() {
        assertTrue(evalL("\"abc\" <=> \"abd\"") < 0);
        assertEquals(0L, evalL("\"hello\" <=> \"hello\""));
        assertTrue(evalL("\"xyz\" <=> \"abc\"") > 0);
    }

    @Test
    void spaceshipNonComparableThrows() {
        assertEvalThrows("[1,2,3] <=> [4,5,6]");
    }

    // ---- Spaceship precedence ----

    @Test
    void spaceshipBindsTighterThanComparison() {
        // CMP_PREC=115 > ORD_PREC=110, so 5 < 3 <=> 7 = 5 < (3 <=> 7) = 5 < -1 = false
        assertEquals(false, eval("5 < 3 <=> 7"));
    }

    @Test
    void spaceshipBindsLooserThanShift() {
        // CMP_PREC=115 < SHIFT_PREC=120, so 3 <=> 7 << 1 = 3 <=> (7 << 1) = 3 <=> 14 = -1
        assertEquals(-1L, evalL("3 <=> 7 << 1"));
    }

    // ---- Class-based spaceship overloading ----

    @Test
    void classOverloadedSpaceship() {
        // Class that defines <=> operator.
        assertEquals(1L, evalL(
            "class Version(major, minor) { <=>(other) => major != other.major ? major - other.major : minor - other.minor }\n" +
            "Version(2, 0) <=> Version(1, 5)"));
        assertEquals(0L, evalL(
            "class Version(major, minor) { <=>(other) => major != other.major ? major - other.major : minor - other.minor }\n" +
            "Version(1, 5) <=> Version(1, 5)"));
    }

    @Test
    void spaceshipTriggersComparable() {
        // Class with <=> should auto-implement Comparable.
        // Verify by checking the object's interfaces.
        Object result = eval(
            "class Item(id) { <=>(other) => id - other.id }\n" +
            "Item(5) instanceof java.lang.Comparable");
        assertEquals(true, result);
    }

    @Test
    void spaceshipPriorityOverEqualsPlusLess() {
        // <=> takes priority over ==/< for Comparable implementation.
        // == always returns false, < always returns true — if compareTo used
        // ==/<, it would misbehave. With <=>, it uses x - other.x.
        assertEquals(0L, evalL(
            "class Val(x) { ==(other) => false; <(other) => true; <=>(other) => x - other.x }\n" +
            "Val(5) <=> Val(5)"));
        assertTrue(evalL(
            "class Val(x) { ==(other) => false; <(other) => true; <=>(other) => x - other.x }\n" +
            "Val(3) <=> Val(5)") < 0);
    }

    @Test
    void equalsPlusLessTriggersComparable() {
        // Without <=>, the existing ==/< combo should still trigger Comparable.
        Object result = eval(
            "class Pair(a) { ==(other) => a == other.a; <(other) => a < other.a }\n" +
            "Pair(5) instanceof java.lang.Comparable");
        assertEquals(true, result);
    }

    @Test
    void noComparableWithoutOperators() {
        // A class without ==, <, or <=> should NOT implement Comparable.
        Object result = eval(
            "class Plain(v) { toString() => \"Plain(\" ~ v ~ \")\" }\n" +
            "Plain(5) instanceof java.lang.Comparable");
        assertEquals(false, result);
    }
}
