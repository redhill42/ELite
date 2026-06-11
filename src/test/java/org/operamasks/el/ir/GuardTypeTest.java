package org.operamasks.el.ir;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.operamasks.el.parser.Parser;

class GuardTypeTest {

    static IRFunction buildTypedLambda(String name, String[] params,
                                        int[] flags, String body) {
        IRBuilder b = new IRBuilder();
        b.lambdaName = name;
        b.inTailPosition = true;
        for (int i = 0; i < params.length; i++) b.ensureVar(params[i], flags[i]);
        b.build(Parser.parseExpression(body));
        if (!IRBuilder.endsWithReturn(b)) b.current.emitReturnVoid();
        return b.finish(name != null ? name : "lambda", params.length);
    }

    @Test void explicitParamGetsGuard() {
        IRFunction fn = buildTypedLambda("add", new String[]{"x"},
            new int[]{IRFunction.PARAM_EXPLICIT_TYPE}, "x + 1");
        IRFunction spec = IRSpeclializer.specialize(fn, new int[]{IRFormat.T_INT});
        assertTrue(scanOp(spec, Opcode.GUARD_TYPE));
    }

    @Test void inferredParamNowGetsGuard() {
        IRFunction fn = IRBuilder.compileLambda("add",
            new String[]{"x"}, Parser.parseExpression("x + 1"));
        IRFunction spec = IRSpeclializer.specialize(fn, new int[]{IRFormat.T_INT});
        // Inferred params now get strict guards too (Phase 7 will add deopt)
        assertTrue(scanOp(spec, Opcode.GUARD_TYPE),
            "Inferred param should now get GUARD_TYPE (strict for now)");
        assertFalse(scanOp(spec, Opcode.DYNADD));
    }

    @Test void guardWithStrictSentinel() {
        IRFunction fn = buildTypedLambda("sq", new String[]{"x"},
            new int[]{IRFunction.PARAM_EXPLICIT_TYPE}, "x * x");
        IRFunction spec = IRSpeclializer.specialize(fn, new int[]{IRFormat.T_INT});
        assertTrue(scanOpWithPayload(spec, Opcode.GUARD_TYPE, Opcode.STRICT_GUARD));
    }

    @Test void guardPassesForCorrectType() {
        try {
            javax.script.ScriptEngine e =
                new javax.script.ScriptEngineManager().getEngineByName("ELite");
            assertEquals(42L, ((Number)e.eval("(\\x::Integer => x + 2)(40)")).longValue());
        } catch (javax.script.ScriptException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test void guardThrowsForWrongType() {
        try {
            javax.script.ScriptEngine e =
                new javax.script.ScriptEngineManager().getEngineByName("ELite");
            e.eval("(\\x::Integer => x + 2)(3.14)");
            fail("Should throw");
        } catch (javax.script.ScriptException ex) {
            String m = ex.getMessage() != null ? ex.getMessage() : "";
            assertTrue(m.contains("mismatch") || m.contains("Type"), m);
        }
    }

    @Test void guardEliminationForRepeatedUse() {
        IRFunction fn = buildTypedLambda("f",
            new String[]{"x", "y"},
            new int[]{IRFunction.PARAM_EXPLICIT_TYPE, IRFunction.PARAM_EXPLICIT_TYPE},
            "x + y + x");
        IRFunction spec = IRSpeclializer.specialize(fn,
            new int[]{IRFormat.T_INT, IRFormat.T_INT});
        int n = countOp(spec, Opcode.GUARD_TYPE);
        assertTrue(n <= 2, "Redundant guard not eliminated: " + n);
    }

    @Test void guardEliminationWithTwoParams() {
        IRFunction fn = buildTypedLambda("f",
            new String[]{"x", "y"},
            new int[]{IRFunction.PARAM_EXPLICIT_TYPE, IRFunction.PARAM_EXPLICIT_TYPE},
            "x + y + x");
        IRFunction spec = IRSpeclializer.specialize(fn,
            new int[]{IRFormat.T_INT, IRFormat.T_INT});
        int n = countOp(spec, Opcode.GUARD_TYPE);
        assertEquals(2, n, "Only x and y should be guarded once each");
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

    static boolean scanOpWithPayload(IRFunction fn, int target, int expectedPayload) {
        for (int b = 0; b < fn.blockCount(); b++) {
            InstructionView v = new InstructionView(fn.code(), fn.blockStart(b));
            int end = (b+1 < fn.blockCount()) ? fn.blockStart(b+1) : fn.code().length;
            while (v.inBounds() && v.offset() < end) {
                if (v.opcode() == target && v.opCount() > 0
                    && v.operand(0) == expectedPayload) return true;
                v.advance();
            }
        }
        return false;
    }

    static int countOp(IRFunction fn, int target) {
        int count = 0;
        for (int b = 0; b < fn.blockCount(); b++) {
            InstructionView v = new InstructionView(fn.code(), fn.blockStart(b));
            int end = (b+1 < fn.blockCount()) ? fn.blockStart(b+1) : fn.code().length;
            while (v.inBounds() && v.offset() < end) {
                if (v.opcode() == target) count++;
                v.advance();
            }
        }
        return count;
    }
}
