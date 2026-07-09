package org.elite.ir;

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
           .emitAdd()
           .emitReturn();

        long[] code = out.toArray();
        assertEquals(4, code.length, "should be 4 words: 2x PUSH_CONST(1w each) + IADD(1w) + RETURN(1w)");

        InstructionView v = new InstructionView(code, 0);
        assertTrue(v.inBounds());
        assertEquals(Opcode.PUSH_CONST, v.opcode());
        assertEquals(42, v.poolIndex());
        v.advance();

        assertEquals(Opcode.PUSH_CONST, v.opcode());
        assertEquals(58, v.poolIndex());
        v.advance();

        assertEquals(Opcode.ADD, v.opcode());
        v.advance();

        assertEquals(Opcode.RETURN, v.opcode());
        v.advance();

        assertFalse(v.inBounds());
    }

    @Test
    void packAndDecodeControlFlow() {
        IREmitter out = new IREmitter();
        out.emitPushTrue()
           .emitJumpIfFalse(99)
           .emitPushConst(1)
           .emitReturn();

        long[] code = out.toArray();

        InstructionView v = new InstructionView(code, 0);
        assertEquals(Opcode.PUSH_TRUE, v.opcode());
        v.advance();

        assertEquals(Opcode.JUMP_IF_FALSE, v.opcode());
        assertEquals(99, v.jumpTarget());
        v.advance();

        assertEquals(Opcode.PUSH_CONST, v.opcode());
        assertEquals(1, v.poolIndex());
        v.advance();

        assertEquals(Opcode.RETURN, v.opcode());
    }

    @Test
    void dynamicArith() {
        IREmitter out = new IREmitter();
        out.emitPushVar(0)
           .emitPushVar(1)
           .emitAdd()
           .emitReturn();

        long[] code = out.toArray();

        InstructionView v = new InstructionView(code, 0);
        assertEquals(Opcode.PUSH_VAR, v.opcode());
        v.advance();
        assertEquals(Opcode.PUSH_VAR, v.opcode());
        v.advance();
        assertEquals(Opcode.ADD, v.opcode());
        v.advance();
        assertEquals(Opcode.RETURN, v.opcode());
    }

    @Test
    void peekAndNavigation() {
        IREmitter out = new IREmitter();
        out.emitPushConst(1)
           .emitPushConst(2)
           .emitAdd();

        long[] code = out.toArray();
        InstructionView v = new InstructionView(code, 0);

        // Peek ahead without advancing
        InstructionView peek1 = v.peek();
        assertEquals(Opcode.PUSH_CONST, peek1.opcode());
        assertEquals(2, peek1.poolIndex());

        InstructionView peek2 = v.peek(2);
        assertEquals(Opcode.ADD, peek2.opcode());

        // Original position unchanged
        assertEquals(Opcode.PUSH_CONST, v.opcode());
        assertEquals(1, v.poolIndex());

        // Advance through all
        v.advance(3);
        assertFalse(v.inBounds());
    }

    @Test
    void toStringDoesNotThrow() {
        IREmitter out = new IREmitter();
        out.emitPushConst(1)
           .emitAdd()
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
