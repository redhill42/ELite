package org.operamasks.el.ir;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.operamasks.el.parser.Parser;

class IRSpeclializerTest {

    @Test
    void specializeAddWhenBothArgsInt() {
        // define add(a,b) => a + b — no types, compiles to DYNADD
        IRFunction fn = IRBuilder.compileLambda("add",
                new String[]{"a","b"},
                Parser.parseExpression("a + b"));

        // Verify original has DYNADD
        assertTrue(scanOp(fn, Opcode.DYNADD), "Original should have DYNADD");

        // Specialize for (T_INT, T_INT)
        int[] types = {IRFormat.T_INT, IRFormat.T_INT};
        IRFunction spec = IRSpeclializer.specialize(fn, types);

        // Verify specialized has IADD instead of DYNADD
        assertFalse(scanOp(spec, Opcode.DYNADD), "Specialized should NOT have DYNADD");
        assertTrue(scanOp(spec, Opcode.IADD), "Specialized should have IADD");
    }

    @Test
    void specializeMulWhenBothArgsInt() {
        IRFunction fn = IRBuilder.compileLambda("mul",
                new String[]{"x","y"},
                Parser.parseExpression("x * y"));

        int[] types = {IRFormat.T_INT, IRFormat.T_INT};
        IRFunction spec = IRSpeclializer.specialize(fn, types);

        assertFalse(scanOp(spec, Opcode.DYNMUL), "Specialized should NOT have DYNMUL");
        assertTrue(scanOp(spec, Opcode.IMUL), "Specialized should have IMUL");
    }

    @Test
    void noSpecializationWhenArgsUnknown() {
        IRFunction fn = IRBuilder.compileLambda("add",
                new String[]{"a","b"},
                Parser.parseExpression("a + b"));

        int[] types = {-1, -1}; // both unknown
        IRFunction spec = IRSpeclializer.specialize(fn, types);

        // Should still have DYNADD
        assertTrue(scanOp(spec, Opcode.DYNADD),
                "Unknown types should keep DYNADD");
    }

    @Test
    void specializeMixedTypes() {
        // a + b where a=int, b=double
        IRFunction fn = IRBuilder.compileLambda("add",
                new String[]{"a","b"},
                Parser.parseExpression("a + b"));

        int[] types = {IRFormat.T_INT, IRFormat.T_DOUBLE};
        IRFunction spec = IRSpeclializer.specialize(fn, types);

        // Should have DADD (wider type)
        assertTrue(scanOp(spec, Opcode.DADD),
                "int+double should specialize to DADD");
    }

    @Test
    void specializeMultiOpExpression() {
        // a * b + c
        IRFunction fn = IRBuilder.compileLambda("calc",
                new String[]{"a","b","c"},
                Parser.parseExpression("a * b + c"));

        int[] types = {IRFormat.T_INT, IRFormat.T_INT, IRFormat.T_INT};
        IRFunction spec = IRSpeclializer.specialize(fn, types);

        // Should have IMUL (for a*b) and IADD (for result+c)
        assertTrue(scanOp(spec, Opcode.IMUL), "a*b should be IMUL");
        assertTrue(scanOp(spec, Opcode.IADD), "result+c should be IADD");
        assertFalse(scanOp(spec, Opcode.DYNADD) || scanOp(spec, Opcode.DYNMUL),
                "Should have no DYN ops");
    }

    @Test
    void originalFunctionUnchanged() {
        IRFunction fn = IRBuilder.compileLambda("add",
                new String[]{"a","b"},
                Parser.parseExpression("a + b"));

        String orig = fn.toString();
        int[] types = {IRFormat.T_INT, IRFormat.T_INT};
        IRSpeclializer.specialize(fn, types);

        // Original should be unchanged
        assertEquals(orig, fn.toString(), "Original IRFunction must not be mutated");
    }

    private static boolean scanOp(IRFunction fn, int op) {
        for (int b = 0; b < fn.blockCount(); b++) {
            InstructionView v = new InstructionView(fn.code(), fn.blockStart(b));
            int end = (b+1 < fn.blockCount()) ? fn.blockStart(b+1) : fn.code().length;
            while (v.inBounds() && v.offset() < end) {
                if (v.opcode() == op) return true;
                v.advance();
            }
        }
        return false;
    }
}
