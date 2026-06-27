package org.elite.ir;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.elite.parser.Parser;

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
        IRFunction spec = IRSpecializer.specialize(fn, new int[]{IRFormat.T_INT});
        assertTrue(scanOp(spec, Opcode.GUARD_TYPE));
    }

    @Test void inferredParamNowGetsDeoptGuard() {
        IRFunction fn = IRBuilder.compileLambda("add",
            new String[]{"x"}, Parser.parseExpression("x + 1"));
        IRFunction spec = IRSpecializer.specialize(fn, new int[]{IRFormat.T_INT});
        // Inferred single-op block: should be deopt-split with 3 blocks
        assertTrue(spec.blockCount() >= 2, "Deopt split should create extra blocks");
        assertTrue(scanOp(spec, Opcode.GUARD_TYPE), "Should have GUARD_TYPE");
        assertTrue(scanOp(spec, Opcode.DYNADD), "DYNADD should be in deopt block");
    }

    @Test void explicitParamStillUsesStrictGuard() {
        IRFunction fn = buildTypedLambda("add", new String[]{"x"},
            new int[]{IRFunction.PARAM_EXPLICIT_TYPE}, "x + 1");
        IRFunction spec = IRSpecializer.specialize(fn, new int[]{IRFormat.T_INT});
        // Explicit type: strict guard, no block splitting
        assertTrue(scanOpWithPayload(spec, Opcode.GUARD_TYPE, Opcode.STRICT_GUARD));
        assertFalse(scanOp(spec, Opcode.DYNADD)); // fully specialized, no fallback
    }

    @Test void guardWithStrictSentinel() {
        IRFunction fn = buildTypedLambda("sq", new String[]{"x"},
            new int[]{IRFunction.PARAM_EXPLICIT_TYPE}, "x * x");
        IRFunction spec = IRSpecializer.specialize(fn, new int[]{IRFormat.T_INT});
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
        IRFunction spec = IRSpecializer.specialize(fn,
            new int[]{IRFormat.T_INT, IRFormat.T_INT});
        int n = countOp(spec, Opcode.GUARD_TYPE);
        assertTrue(n <= 2, "Redundant guard not eliminated: " + n);
    }

    @Test void deoptFallbackPreservesCorrectResult() {
        // Deopt should produce same result as dynamic path when guard passes
        try {
            javax.script.ScriptEngine e =
                new javax.script.ScriptEngineManager().getEngineByName("ELite");
            // Without type annotation — inferred types with deopt fallback
            assertEquals(42L, ((Number)e.eval("(\\x => x + 2)(40)")).longValue());
            assertEquals(7L, ((Number)e.eval("(\\a => \\b => a + b)(3)(4)")).longValue());
        } catch (javax.script.ScriptException ex) {
            throw new RuntimeException(ex);
        }
    }

    // P0-5: Tests that exercise the deopt path when inferred types mismatch
    @Test void deoptTriggeredByTypeMismatchSingleOp() {
        // Inferred type is int (from literal 2), but passing Double triggers deopt
        try {
            javax.script.ScriptEngine e =
                new javax.script.ScriptEngineManager().getEngineByName("ELite");
            // x inferred as int, passing 40.0 (Double) triggers GUARD_TYPE mismatch → deopt
            assertEquals(42.0, ((Number)e.eval("(\\x => x + 2)(40.0)")).doubleValue(), 0.001);
        } catch (javax.script.ScriptException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test void deoptTriggeredByTypeMismatchBinaryOp() {
        try {
            javax.script.ScriptEngine e =
                new javax.script.ScriptEngineManager().getEngineByName("ELite");
            // Define a function that takes two inferred-int params
            e.eval("define add(a, b) => a + b");
            // Passing mixed int + double triggers deopt on the inferred types
            assertEquals(7.0, ((Number)e.eval("add(3.0, 4.0)")).doubleValue(), 0.001);
            assertEquals(35.0, ((Number)e.eval("add(10, 25.0)")).doubleValue(), 0.001);
        } catch (javax.script.ScriptException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test void deoptPreservesStackForMultiOpExpression() {
        // Multiple operations after deopt should have correct stack
        try {
            javax.script.ScriptEngine e =
                new javax.script.ScriptEngineManager().getEngineByName("ELite");
            e.eval("define f(x) => (x + 1) * 2");
            // x inferred as int, passing 40.0 triggers deopt in x+1
            assertEquals(82.0, ((Number)e.eval("f(40.0)")).doubleValue(), 0.001);
        } catch (javax.script.ScriptException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test void deoptProducesCorrectResultInConditional() {
        try {
            javax.script.ScriptEngine e =
                new javax.script.ScriptEngineManager().getEngineByName("ELite");
            e.eval("define g(a) => a > 0 ? a + 1 : 0");
            // a inferred as int, passing 41.0 triggers deopt
            assertEquals(42.0, ((Number)e.eval("g(41.0)")).doubleValue(), 0.001);
        } catch (javax.script.ScriptException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test void guardEliminationWithTwoParams() {
        IRFunction fn = buildTypedLambda("f",
            new String[]{"x", "y"},
            new int[]{IRFunction.PARAM_EXPLICIT_TYPE, IRFunction.PARAM_EXPLICIT_TYPE},
            "x + y + x");
        IRFunction spec = IRSpecializer.specialize(fn,
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
