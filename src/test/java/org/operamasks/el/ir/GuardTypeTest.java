package org.operamasks.el.ir;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.operamasks.el.parser.Parser;
import org.operamasks.el.parser.ELNode;

class GuardTypeTest {

    /** Build a lambda body IRFunction directly with param flags. */
    private static IRFunction buildTypedLambda(String name, String[] params,
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
        assertTrue(scanOp(spec, Opcode.GUARD_TYPE),
            "Explicit param should get GUARD_TYPE");
    }

    @Test void inferredParamNoGuard() {
        IRFunction fn = IRBuilder.compileLambda("add",
            new String[]{"x"}, Parser.parseExpression("x + 1"));
        IRFunction spec = IRSpeclializer.specialize(fn,
            new int[]{IRFormat.T_INT});
        assertFalse(scanOp(spec, Opcode.GUARD_TYPE),
            "Inferred param should NOT get GUARD_TYPE (yet)");
        assertFalse(scanOp(spec, Opcode.DYNADD),
            "Inferred param should still be specialized");
    }

    @Test void guardWithStrictSentinel() {
        IRFunction fn = buildTypedLambda("sq", new String[]{"x"},
            new int[]{IRFunction.PARAM_EXPLICIT_TYPE}, "x * x");
        IRFunction spec = IRSpeclializer.specialize(fn, new int[]{IRFormat.T_INT});
        assertTrue(scanOpWithPayload(spec, Opcode.GUARD_TYPE, Opcode.STRICT_GUARD),
            "Explicit param guard should use STRICT_GUARD sentinel");
    }

    @Test void guardTypeInInterpreterPassesForCorrectType() {
        try {
            javax.script.ScriptEngine engine =
                new javax.script.ScriptEngineManager().getEngineByName("ELite");
            Object r = engine.eval("(\\x::Integer => x + 2)(40)");
            assertEquals(42L, ((Number)r).longValue());
        } catch (javax.script.ScriptException e) {
            throw new RuntimeException(e);
        }
    }

    @Test void guardTypeInInterpreterThrowsForWrongType() {
        try {
            javax.script.ScriptEngine engine =
                new javax.script.ScriptEngineManager().getEngineByName("ELite");
            engine.eval("(\\x::Integer => x + 2)(3.14)");
            fail("Should have thrown for type mismatch");
        } catch (javax.script.ScriptException e) {
            String msg = e.getMessage() != null ? e.getMessage() :
                (e.getCause() != null ? e.getCause().getMessage() : "");
            assertTrue(msg.contains("mismatch") || msg.contains("Type"),
                "Error should mention type mismatch: " + msg);
        }
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
}
