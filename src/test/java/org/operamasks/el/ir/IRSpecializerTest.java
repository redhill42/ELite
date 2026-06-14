package org.operamasks.el.ir;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.operamasks.el.parser.Parser;

class IRSpecializerTest {

    @Test
    void specializeMulWhenBothArgsInt() {
        IRFunction fn = IRBuilder.compileLambda("mul",
                new String[]{"x","y"}, Parser.parseExpression("x * y"));
        int[] types = {IRFormat.T_INT, IRFormat.T_INT};
        IRFunction spec = IRSpecializer.specialize(fn, types);
        // Inferred types → deopt-split: IMUL in prefix, DYNMUL in deopt block
        assertTrue(scanOp(spec, Opcode.IMUL), "Should have IMUL");
        assertTrue(scanOp(spec, Opcode.GUARD_TYPE), "Should have deopt GUARD_TYPE");
    }

    @Test
    void specializeAddWhenBothArgsInt() {
        IRFunction fn = IRBuilder.compileLambda("add",
                new String[]{"a","b"}, Parser.parseExpression("a + b"));
        int[] types = {IRFormat.T_INT, IRFormat.T_INT};
        IRFunction spec = IRSpecializer.specialize(fn, types);
        assertTrue(scanOp(spec, Opcode.IADD), "Should have IADD");
        assertTrue(scanOp(spec, Opcode.GUARD_TYPE), "Should have deopt GUARD_TYPE");
    }

    @Test
    void specializeMixedTypes() {
        IRFunction fn = IRBuilder.compileLambda("add",
                new String[]{"a","b"}, Parser.parseExpression("a + b"));
        int[] types = {IRFormat.T_INT, IRFormat.T_DOUBLE};
        IRFunction spec = IRSpecializer.specialize(fn, types);
        assertTrue(scanOp(spec, Opcode.DADD), "int+double should specialize to DADD");
    }

    @Test
    void specializeMultiOpExpression() {
        IRFunction fn = IRBuilder.compileLambda("calc",
                new String[]{"a","b","c"}, Parser.parseExpression("a * b + c"));
        int[] types = {IRFormat.T_INT, IRFormat.T_INT, IRFormat.T_INT};
        IRFunction spec = IRSpecializer.specialize(fn, types);
        // Multi-op block with inferred types: deopt-split with IMUL+IADD in prefix
        assertTrue(scanOp(spec, Opcode.IMUL), "a*b should be IMUL");
        assertTrue(scanOp(spec, Opcode.IADD), "result+c should be IADD");
        assertTrue(scanOp(spec, Opcode.GUARD_TYPE), "Should have deopt guards");
    }

    @Test
    void originalFunctionUnchanged() {
        IRFunction fn = IRBuilder.compileLambda("add",
                new String[]{"a","b"}, Parser.parseExpression("a + b"));
        String orig = fn.toString();
        int[] types = {IRFormat.T_INT, IRFormat.T_INT};
        IRSpecializer.specialize(fn, types);
        assertEquals(orig, fn.toString(), "Original must not be mutated");
    }

    static boolean scanOp(IRFunction fn, int target) {
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
