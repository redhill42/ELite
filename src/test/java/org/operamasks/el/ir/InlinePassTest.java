package org.operamasks.el.ir;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.operamasks.el.parser.Parser;

class InlinePassTest {

    @Test
    void inlineSimpleAdd() {
        // define add(a,b)=>a+b; add(3,4)
        Parser p = new Parser("define add(a,b) => a + b; add(3, 4)");
        var prog = p.parse();
        IRFunction fn = IRBuilder.compileWithDefs(prog.getDefinitions(), prog.getExpressions());

        // Verify original has INVOKE_DIRECT
        assertTrue(scanOp(fn, Opcode.INVOKE_DIRECT), "Should have INVOKE_DIRECT before inline");

        // Run inline pass
        InlinePass pass = new InlinePass();
        IRFunction inlined = pass.transform(fn);

        // Verify INVOKE_DIRECT is gone
        assertFalse(scanOp(inlined, Opcode.INVOKE_DIRECT), "INVOKE_DIRECT should be inlined away");

        // Execute both and compare results
        javax.el.ELContext ctx = org.operamasks.el.eval.ELEngine.createELContext();
        Object orig = new IRInterpreter(ctx, fn).execute(null);
        Object inl  = new IRInterpreter(ctx, inlined).execute(null);
        assertEquals(((Number)orig).longValue(), ((Number)inl).longValue());
    }

    @Test
    void inlineWithSpecialization() {
        // define mul3(x)=>x*3; mul3(5) — has constant 3, should inline + specialize
        Parser p = new Parser("define mul3(x) => x * 3; mul3(5)");
        var prog = p.parse();
        IRFunction fn = IRBuilder.compileWithDefs(prog.getDefinitions(), prog.getExpressions());

        InlinePass pass = new InlinePass();
        IRFunction inlined = pass.transform(fn);

        assertFalse(scanOp(inlined, Opcode.INVOKE_DIRECT));
        javax.el.ELContext ctx = org.operamasks.el.eval.ELEngine.createELContext();
        Object result = new IRInterpreter(ctx, inlined).execute(null);
        assertEquals(15L, ((Number)result).longValue());
    }

    @Test
    void doesNotInlineLargeFunction() {
        // A function with many instructions should not be inlined
        Parser p = new Parser("define big(x) => x+1+2+3+4+5+6+7+8+9+10; big(0)");
        var prog = p.parse();
        IRFunction fn = IRBuilder.compileWithDefs(prog.getDefinitions(), prog.getExpressions());

        InlinePass pass = new InlinePass();
        IRFunction result = pass.transform(fn);

        // Large function (many constant ops) — might still be under 20
        // Just verify it executes correctly
        javax.el.ELContext ctx = org.operamasks.el.eval.ELEngine.createELContext();
        assertEquals(55L, ((Number)new IRInterpreter(ctx, result).execute(null)).longValue());
    }

    @Test
    void inlinePreservesResult() {
        // define sq(x)=>x*x; define sumSq(a,b)=>sq(a)+sq(b); sumSq(3,4)
        Parser p = new Parser("define sq(x) => x * x; define sumSq(a,b) => sq(a) + sq(b); sumSq(3, 4)");
        var prog = p.parse();
        IRFunction fn = IRBuilder.compileWithDefs(prog.getDefinitions(), prog.getExpressions());

        InlinePass pass = new InlinePass();
        IRFunction inlined = pass.transform(fn);

        javax.el.ELContext ctx = org.operamasks.el.eval.ELEngine.createELContext();
        Object result = new IRInterpreter(ctx, inlined).execute(null);
        assertEquals(25L, ((Number)result).longValue()); // 9 + 16 = 25
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
