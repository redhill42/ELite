package org.operamasks.el.ir;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Tests for the core IR primitives: IREmitter, InstructionView, and encoding.
 */
class IRCoreTest {

    @Test
    void packAndDecodeSimpleArith() {
        IREmitter out = new IREmitter();
        // Emit: PUSH_CONST(42), PUSH_CONST(58), IADD, RETURN
        out.emitPushConst(42)
           .emitPushConst(58)
           .emitIAdd()
           .emitReturn(IRFormat.T_INT);

        int[] code = out.toArray();
        assertEquals(4, code.length, "should be 4 words: 2x PUSH_CONST(1w each) + IADD(1w) + RETURN(1w)");

        InstructionView v = new InstructionView(code, 0);
        assertTrue(v.inBounds());
        assertEquals(Opcode.PUSH_CONST, v.opcode());
        assertEquals(42, v.constPoolIndex());
        v.advance();

        assertEquals(Opcode.PUSH_CONST, v.opcode());
        assertEquals(58, v.constPoolIndex());
        v.advance();

        assertEquals(Opcode.IADD, v.opcode());
        v.advance();

        assertEquals(Opcode.RETURN, v.opcode());
        assertEquals(IRFormat.T_INT, v.primTypeId());
        v.advance();

        assertFalse(v.inBounds());
    }

    @Test
    void packAndDecodeControlFlow() {
        IREmitter out = new IREmitter();
        out.emitPushTrue()
           .emitJumpIfFalse(99)
           .emitPushConst(1)
           .emitReturn(IRFormat.T_INT);

        int[] code = out.toArray();

        InstructionView v = new InstructionView(code, 0);
        assertEquals(Opcode.PUSH_TRUE, v.opcode());
        v.advance();

        assertEquals(Opcode.JUMP_IF_FALSE, v.opcode());
        assertEquals(99, v.jumpTarget());
        v.advance();

        assertEquals(Opcode.PUSH_CONST, v.opcode());
        assertEquals(1, v.constPoolIndex());
        v.advance();

        assertEquals(Opcode.RETURN, v.opcode());
    }

    @Test
    void dynamicArith() {
        IREmitter out = new IREmitter();
        out.emitPushVar(0)
           .emitPushVar(1)
           .emitDynAdd()
           .emitReturn(IRFormat.T_INT);

        int[] code = out.toArray();

        InstructionView v = new InstructionView(code, 0);
        assertEquals(Opcode.PUSH_VAR, v.opcode());
        v.advance();
        assertEquals(Opcode.PUSH_VAR, v.opcode());
        v.advance();
        assertEquals(Opcode.DYNADD, v.opcode());
        assertEquals(IRFormat.K_DYN, v.kind());
        v.advance();
        assertEquals(Opcode.RETURN, v.opcode());
    }

    @Test
    void guardTypeInstruction() {
        IREmitter out = new IREmitter();
        out.emitGuardType(IRFormat.T_INT, 5);

        int[] code = out.toArray();
        assertEquals(2, code.length, "GUARD_TYPE is 2 words");

        InstructionView v = new InstructionView(code, 0);
        assertEquals(Opcode.GUARD_TYPE, v.opcode());
        assertEquals(IRFormat.K_GUARDED, v.kind());
        assertEquals(IRFormat.T_INT, v.primTypeId());
        assertEquals(5, v.deoptBlock());
    }

    @Test
    void peekAndNavigation() {
        IREmitter out = new IREmitter();
        out.emitPushConst(1)
           .emitPushConst(2)
           .emitIAdd();

        int[] code = out.toArray();
        InstructionView v = new InstructionView(code, 0);

        // Peek ahead without advancing
        InstructionView peek1 = v.peek();
        assertEquals(Opcode.PUSH_CONST, peek1.opcode());
        assertEquals(2, peek1.constPoolIndex());

        InstructionView peek2 = v.peek(2);
        assertEquals(Opcode.IADD, peek2.opcode());

        // Original position unchanged
        assertEquals(Opcode.PUSH_CONST, v.opcode());
        assertEquals(1, v.constPoolIndex());

        // Advance through all
        v.advance(3);
        assertFalse(v.inBounds());
    }

    @Test
    void emitCopyPreservesInstruction() {
        IREmitter src = new IREmitter();
        src.emitPushConst(100).emitPushConst(200).emitIAdd();

        int[] srcCode = src.toArray();
        InstructionView v = new InstructionView(srcCode, 0);

        IREmitter dst = new IREmitter();
        while (v.inBounds()) {
            dst.emitCopy(v);
            v.advance();
        }

        int[] dstCode = dst.toArray();
        assertArrayEquals(srcCode, dstCode);
    }

    @Test
    void toStringDoesNotThrow() {
        IREmitter out = new IREmitter();
        out.emitPushConst(1)
           .emitIAdd()
           .emitJump(3)
           .emitReturnVoid();

        InstructionView v = new InstructionView(out.toArray(), 0);
        while (v.inBounds()) {
            assertNotNull(v.toString());
            assertFalse(v.toString().isEmpty());
            v.advance();
        }
    }
}
