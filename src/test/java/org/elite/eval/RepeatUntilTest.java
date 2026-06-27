package org.elite.eval;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for repeat/until control flow constructs.
 */
public class RepeatUntilTest extends EliteTestBase {

    // ── repeat ... while ──

    @Test
    void repeatWhileBasic() {
        assertEquals(5L, evalL(
            "define n = 0;" +
            "repeat {" +
            "  n = n + 1" +
            "} while (n < 5);" +
            "n"));
    }

    @Test
    void repeatWhileExecutesAtLeastOnce() {
        assertEquals(1L, evalL(
            "define n = 0;" +
            "repeat {" +
            "  n = n + 1" +
            "} while (false);" +
            "n"));
    }

    @Test
    void repeatWhileBreak() {
        assertEquals(3L, evalL(
            "define n = 0;" +
            "repeat {" +
            "  n = n + 1;" +
            "  if (n == 3) break" +
            "} while (n < 10);" +
            "n"));
    }

    @Test
    void repeatWhileContinue() {
        // continue jumps to condition check (matching C/Java semantics).
        // Even n's skip the sum via continue → condition → loop.
        assertEquals(25L, evalL(
            "define n = 0;" +
            "define sum = 0;" +
            "repeat {" +
            "  n = n + 1;" +
            "  if (n % 2 == 0) continue;" +
            "  sum = sum + n" +
            "} while (n < 10);" +
            "sum"));  // 1+3+5+7+9 = 25
    }

    // ── repeat ... until ──

    @Test
    void repeatUntilBasic() {
        assertEquals(5L, evalL(
            "define n = 0;" +
            "repeat {" +
            "  n = n + 1" +
            "} until (n >= 5);" +
            "n"));
    }

    @Test
    void repeatUntilExecutesAtLeastOnce() {
        assertEquals(1L, evalL(
            "define n = 0;" +
            "repeat {" +
            "  n = n + 1" +
            "} until (true);" +
            "n"));
    }

    // ── until (front-test, desugars to while-not) ──

    @Test
    void untilFrontBasic() {
        assertEquals(5L, evalL(
            "define n = 0;" +
            "until (n >= 5) {" +
            "  n = n + 1" +
            "};" +
            "n"));
    }

    @Test
    void untilFrontZeroIterations() {
        assertEquals(0L, evalL(
            "define n = 0;" +
            "until (n == 0) {" +
            "  n = 1" +
            "};" +
            "n"));
    }

    // ── Nested loops ──

    @Test
    void nestedWhileInsideRepeat() {
        // Outer repeat 3×, inner while 2× → sum increment = 6
        assertEquals(6L, evalL(
            "define n = 0;" +
            "define sum = 0;" +
            "repeat {" +
            "  n = n + 1;" +
            "  define m = 0;" +
            "  while (m < 2) {" +
            "    m = m + 1;" +
            "    sum = sum + 1" +
            "  }" +
            "} while (n < 3);" +
            "sum"));
    }

    @Test
    void nestedRepeatInsideRepeat() {
        // Outer repeat 3×, inner repeat 2× → sum increment = 6
        assertEquals(6L, evalL(
            "define n = 0;" +
            "define sum = 0;" +
            "repeat {" +
            "  n = n + 1;" +
            "  define m = 0;" +
            "  repeat {" +
            "    m = m + 1;" +
            "    sum = sum + 1" +
            "  } while (m < 2);" +
            "} while (n < 3);" +
            "sum"));
    }
}
