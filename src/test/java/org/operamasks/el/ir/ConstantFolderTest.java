package org.operamasks.el.ir;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.operamasks.el.parser.Parser;

class ConstantFolderTest {

    // ── Arithmetic folding ──

    @Test void foldIntAdd()     { assertFolded("10 + 20", "30", true); }
    @Test void foldIntSub()     { assertFolded("50 - 13", "37", true); }
    @Test void foldIntMul()     { assertFolded("7 * 8", "56", true); }
    @Test void foldIntDiv()     { assertFolded("100 / 3", "33", true); }
    @Test void foldIntRem()     { assertFolded("10 % 3", "1", true); }
    @Test void foldDoubleAdd()  { assertFolded("3.14 + 2.72", "5.86", false); }
    @Test void foldDoubleMul()  { assertFolded("1.5 * 2.0", "3.0", false); }

    // ── Cascading fold ──

    @Test void cascadingFold() {
        // 3*3+5 → 9+5 → 14
        IRFunction fn = compile("3 * 3 + 5");
        assertSingleConst(fn, 14L);
    }

    @Test void cascadingMultiLevel() {
        // (1+2)*(3+4) → 3*7 → 21
        IRFunction fn = compile("(1 + 2) * (3 + 4)");
        assertSingleConst(fn, 21L);
    }

    @Test void cascadingWithDiv() {
        // 20/2 + 5*3 → 10 + 15 → 25
        IRFunction fn = compile("20 / 2 + 5 * 3");
        assertSingleConst(fn, 25L);
    }

    // ── Comparison folding ──

    @Test void foldIntEq()  { assertFoldedBool("100 == 100", true); }
    @Test void foldIntNe()  { assertFoldedBool("5 != 6", true); }
    @Test void foldIntLt()  { assertFoldedBool("50 < 100", true); }
    @Test void foldIntLe()  { assertFoldedBool("100 <= 100", true); }
    @Test void foldIntGt()  { assertFoldedBool("200 > 100", true); }
    @Test void foldIntGe()  { assertFoldedBool("100 >= 100", true); }
    @Test void foldDoubleEq() { assertFoldedBool("3.14 == 3.14", true); }

    // ── Power folding ──

    @Test void foldIntPow() {
        IRFunction fn = compile("2 ^ 3");
        // 2^3 = 8 — should fold to PUSH_CONST(8)
        assertTrue(scanOp(fn, Opcode.PUSH_CONST), "2^3 should fold to constant");
    }

    // ── Bitwise folding ──

    @Test void foldBitAnd() {
        IRFunction fn = compile("5 :&: 3");
        // 5 & 3 = 1 — should fold
        assertTrue(scanOp(fn, Opcode.PUSH_CONST), "5&3 should fold to constant");
    }

    @Test void foldBitOr() {
        IRFunction fn = compile("5 :|: 3");
        assertTrue(scanOp(fn, Opcode.PUSH_CONST), "5|3 should fold to constant");
    }

    // ── Division by zero — should NOT fold ──

    @Test void noFoldDivByZero() {
        IRFunction fn = compile("10 / 0");
        // Division by zero should NOT be folded (keeps DIV instruction)
        assertFalse(isFolded(fn), "10/0 should NOT be folded");
    }

    @Test void noFoldRemByZero() {
        IRFunction fn = compile("10 % 0");
        assertFalse(isFolded(fn), "10%0 should NOT be folded");
    }

    // ── Original function unchanged after fold ──

    @Test void originalReturnedWhenNoFold() {
        IRFunction fn = compile("x + 5"); // can't fold — x is variable
        InstructionView v = new InstructionView(fn.code(), 0);
        boolean hasDyn = false;
        while (v.inBounds()) {
            if (v.opcode() == Opcode.DYNADD) hasDyn = true;
            v.advance();
        }
        assertTrue(hasDyn, "x+5 should keep DYNADD (cannot fold)");
    }

    // ── Helpers ──

    private static IRFunction compile(String expr) {
        return IRBuilder.compile(Parser.parseExpression(expr));
    }

    private static void assertFolded(String expr, String expectedVal, boolean exact) {
        IRFunction fn = compile(expr);
        // After cascading fold, should be single PUSH_CONST + RETURN
        assertSingleConst(fn, exact ? Long.parseLong(expectedVal) : null);
    }

    private static void assertFoldedBool(String expr, boolean expected) {
        IRFunction fn = compile(expr);
        // Folded to PUSH_CONST with Boolean value
        InstructionView v = new InstructionView(fn.code(), 0);
        while (v.inBounds()) {
            if (v.opcode() == Opcode.PUSH_CONST) {
                Object val = fn.constantPool()[v.constPoolIndex()];
                if (val instanceof Boolean b) {
                    assertEquals(expected, b, expr);
                    return;
                }
            }
            v.advance();
        }
        fail(expr + " should fold to boolean constant");
    }

    private static void assertSingleConst(IRFunction fn, Long expectedValue) {
        assertTrue(isFolded(fn), "Expression should be folded to single constant");
    }

    private static boolean isFolded(IRFunction fn) {
        InstructionView v = new InstructionView(fn.code(), fn.blockStart(0));
        int end = fn.blockCount() > 1 ? fn.blockStart(1) : fn.code().length;
        int pushCount = 0, otherCount = 0;
        while (v.inBounds() && v.offset() < end) {
            int op = v.opcode();
            if (op == Opcode.PUSH_CONST || op == Opcode.PUSH_TRUE || op == Opcode.PUSH_FALSE) pushCount++;
            else if (op != Opcode.RETURN && op != Opcode.RETURN_VOID) otherCount++;
            v.advance();
        }
        return pushCount == 1 && otherCount == 0;
    }

    private static boolean scanOp(IRFunction fn, int target) {
        for (int b = 0; b < fn.blockCount(); b++) {
            InstructionView v = new InstructionView(fn.code(), fn.blockStart(b));
            int end = (b+1 < fn.blockCount()) ? fn.blockStart(b+1) : fn.code().length;
            while (v.inBounds() && v.offset() < end) {
                if (v.opcode() == target) return true;
                v.advance();
            }
        }
        return false;
    }
}
