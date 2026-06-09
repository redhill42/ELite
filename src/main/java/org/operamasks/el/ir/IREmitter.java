package org.operamasks.el.ir;

import static org.operamasks.el.ir.IRFormat.*;
import static org.operamasks.el.ir.Opcode.*;

/**
 * Mutable instruction stream builder.
 *
 * Writes a linear sequence of int-packed IR instructions into an internal buffer.
 * Each {@code emit*} method appends one instruction. The stream is finalized
 * with {@link #toArray()}.
 *
 * <p>Example:
 * <pre>{@code
 * IREmitter out = new IREmitter();
 * out.emitPushConstInt(42)
 *    .emitPushConstInt(58)
 *    .emitIAdd()
 *    .emitReturn(T_INT);
 * int[] code = out.toArray();
 * }</pre>
 */
public class IREmitter {
    private final IntList buf = new IntList();

    // ── Short operand helpers ──

    /** Whether a value fits in the 16-bit payload field. */
    private static boolean fits16(int v) { return (v & 0xFFFF) == (v & 0xFFFF) && v >= 0 && v <= 0xFFFF; }
    // Java's unsigned 16-bit range is 0..65535

    // ── Core emit methods ──

    /** Emit a 1-word instruction (op count = 0). */
    public IREmitter emit1(int opcode, int kind, int payload) {
        buf.add(pack1(opcode, kind, payload));
        return this;
    }

    /** Emit a 2-word instruction (op count = 1). */
    public IREmitter emit2(int opcode, int kind, int payload, int op1) {
        buf.add(pack2h(opcode, kind, payload));
        buf.add(op1);
        return this;
    }

    /** Emit a 3-word instruction (op count = 2). */
    public IREmitter emit3(int opcode, int kind, int payload, int op1, int op2) {
        buf.add(pack3h(opcode, kind, payload));
        buf.add(op1);
        buf.add(op2);
        return this;
    }

    /** Copy a raw instruction word sequence from a view. */
    public IREmitter emitCopy(InstructionView view) {
        int w = view.totalWords();
        for (int i = 0; i < w; i++) {
            buf.add(view.code()[view.offset() + i]);
        }
        return this;
    }

    // ── Stack ops ──

    public IREmitter emitPushConst(int poolIndex) {
        if (fits16(poolIndex)) {
            return emit1(PUSH_CONST, K_NONE, poolIndex);
        } else {
            return emit2(PUSH_CONST, K_NONE, poolIndex >>> 16, poolIndex & 0xFFFF);
        }
    }

    public IREmitter emitPushVar(int varIndex, int primTypeId) {
        return emit1(PUSH_VAR, K_PRIM, varIndex & 0xFFFF);
    }

    public IREmitter emitPushGlobal(int nameIndex) {
        if (fits16(nameIndex)) {
            return emit1(PUSH_GLOBAL, K_NONE, nameIndex);
        } else {
            return emit2(PUSH_GLOBAL, K_NONE, nameIndex >>> 16, nameIndex & 0xFFFF);
        }
    }

    public IREmitter emitPop()     { return emit1(POP, K_NONE, 0); }
    public IREmitter emitDup()     { return emit1(DUP, K_NONE, 0); }
    public IREmitter emitPopN(int count) { return emit1(POP_N, K_NONE, count); }

    // ── Typed arithmetic (with inline primitive type in kind field) ──

    public IREmitter emitIAdd() { return emit1(IADD, K_PRIM, T_INT); }
    public IREmitter emitISub() { return emit1(ISUB, K_PRIM, T_INT); }
    public IREmitter emitIMul() { return emit1(IMUL, K_PRIM, T_INT); }
    public IREmitter emitIDiv() { return emit1(IDIV, K_PRIM, T_INT); }
    public IREmitter emitIRem() { return emit1(IREM, K_PRIM, T_INT); }
    public IREmitter emitINeg() { return emit1(INEG, K_PRIM, T_INT); }

    public IREmitter emitLAdd() { return emit1(LADD, K_PRIM, T_LONG); }
    public IREmitter emitLSub() { return emit1(LSUB, K_PRIM, T_LONG); }
    public IREmitter emitLMul() { return emit1(LMUL, K_PRIM, T_LONG); }
    public IREmitter emitLDiv() { return emit1(LDIV, K_PRIM, T_LONG); }
    public IREmitter emitLNeg() { return emit1(LNEG, K_PRIM, T_LONG); }

    public IREmitter emitDAdd() { return emit1(DADD, K_PRIM, T_DOUBLE); }
    public IREmitter emitDSub() { return emit1(DSUB, K_PRIM, T_DOUBLE); }
    public IREmitter emitDMul() { return emit1(DMUL, K_PRIM, T_DOUBLE); }
    public IREmitter emitDDiv() { return emit1(DDIV, K_PRIM, T_DOUBLE); }
    public IREmitter emitDNeg() { return emit1(DNEG, K_PRIM, T_DOUBLE); }

    // ── Dynamic arithmetic ──

    public IREmitter emitDynAdd()  { return emit1(DYNADD,  K_DYN, 0); }
    public IREmitter emitDynSub()  { return emit1(DYNSUB,  K_DYN, 0); }
    public IREmitter emitDynMul()  { return emit1(DYNMUL,  K_DYN, 0); }
    public IREmitter emitDynDiv()  { return emit1(DYNDIV,  K_DYN, 0); }
    public IREmitter emitDynRem()  { return emit1(DYNREM,  K_DYN, 0); }
    public IREmitter emitDynNeg()  { return emit1(DYNNEG,  K_DYN, 0); }
    public IREmitter emitDynPow()  { return emit1(DYNPOW,  K_DYN, 0); }

    // ── Typed comparisons ──

    public IREmitter emitIEq() { return emit1(IEQ, K_BOOL, 0); }
    public IREmitter emitINe() { return emit1(INE, K_BOOL, 0); }
    public IREmitter emitILt() { return emit1(ILT, K_BOOL, 0); }
    public IREmitter emitILe() { return emit1(ILE, K_BOOL, 0); }
    public IREmitter emitIGt() { return emit1(IGT, K_BOOL, 0); }
    public IREmitter emitIGe() { return emit1(IGE, K_BOOL, 0); }

    public IREmitter emitLEq() { return emit1(LEQ, K_BOOL, 0); }
    public IREmitter emitLNe() { return emit1(LNE, K_BOOL, 0); }
    public IREmitter emitLLt() { return emit1(LLT, K_BOOL, 0); }
    public IREmitter emitLLe() { return emit1(LLE, K_BOOL, 0); }

    public IREmitter emitDEq() { return emit1(DEQ, K_BOOL, 0); }
    public IREmitter emitDNe() { return emit1(DNE, K_BOOL, 0); }
    public IREmitter emitDLt() { return emit1(DLT, K_BOOL, 0); }
    public IREmitter emitDLe() { return emit1(DLE, K_BOOL, 0); }
    public IREmitter emitDGt() { return emit1(DGT, K_BOOL, 0); }
    public IREmitter emitDGe() { return emit1(DGE, K_BOOL, 0); }
    public IREmitter emitLGt() { return emit1(LGT, K_BOOL, 0); }
    public IREmitter emitLGe() { return emit1(LGE, K_BOOL, 0); }

    // ── Dynamic comparisons ──

    public IREmitter emitDynEq() { return emit1(DYNEQ, K_DYN, 0); }
    public IREmitter emitDynLt() { return emit1(DYNLT, K_DYN, 0); }
    public IREmitter emitDynLe() { return emit1(DYNLE, K_DYN, 0); }

    // ── Boolean constants ──

    public IREmitter emitPushTrue()  { return emit1(PUSH_TRUE, K_PRIM, T_BOOL); }
    public IREmitter emitPushFalse() { return emit1(PUSH_FALSE, K_PRIM, T_BOOL); }
    public IREmitter emitPushNull()  { return emit1(PUSH_NULL, K_NONE, 0); }

    // ── Control flow ──

    public IREmitter emitJump(int blockId) {
        if (fits16(blockId)) {
            return emit1(JUMP, K_NONE, blockId);
        } else {
            return emit2(JUMP, K_NONE, blockId >>> 16, blockId & 0xFFFF);
        }
    }

    public IREmitter emitJumpIfTrue(int blockId) {
        if (fits16(blockId)) {
            return emit1(JUMP_IF_TRUE, K_NONE, blockId);
        } else {
            return emit2(JUMP_IF_TRUE, K_NONE, blockId >>> 16, blockId & 0xFFFF);
        }
    }

    public IREmitter emitJumpIfFalse(int blockId) {
        if (fits16(blockId)) {
            return emit1(JUMP_IF_FALSE, K_NONE, blockId);
        } else {
            return emit2(JUMP_IF_FALSE, K_NONE, blockId >>> 16, blockId & 0xFFFF);
        }
    }

    public IREmitter emitJumpIfNull(int blockId) {
        if (fits16(blockId)) {
            return emit1(JUMP_IF_NULL, K_NONE, blockId);
        } else {
            return emit2(JUMP_IF_NULL, K_NONE, blockId >>> 16, blockId & 0xFFFF);
        }
    }

    public IREmitter emitJumpIfNonNull(int blockId) {
        if (fits16(blockId)) {
            return emit1(JUMP_IF_NONNULL, K_NONE, blockId);
        } else {
            return emit2(JUMP_IF_NONNULL, K_NONE, blockId >>> 16, blockId & 0xFFFF);
        }
    }

    // ── Function ──

    public IREmitter emitReturn(int primTypeId) {
        return emit1(RETURN, K_PRIM, primTypeId);
    }

    public IREmitter emitReturnVoid() {
        return emit1(RETURN_VOID, K_NONE, 0);
    }

    public IREmitter emitInvoke(int funcIndex, int argCount) {
        return emit2(INVOKE, K_FN, funcIndex & 0xFFFF, argCount);
    }

    public IREmitter emitInvokeDyn(int argCount) {
        return emit2(INVOKE_DYN, K_DYN, argCount, 0);
    }

    /** Tail-recursive call: pops argCount args, stores to locals, jumps to entry. */
    public IREmitter emitInvokeTail(int argCount) {
        return emit1(INVOKE_TAIL, K_NONE, argCount);
    }

    // ── Memory ──

    public IREmitter emitStoreVar(int varIndex) {
        return emit2(STORE_VAR, K_NONE, varIndex & 0xFFFF, 0);
    }

    public IREmitter emitNewList(int capacity) {
        return emit1(NEW_LIST, K_NONE, capacity);
    }

    public IREmitter emitNewMap(int capacity) {
        return emit1(NEW_MAP, K_NONE, capacity);
    }

    // ── Type guards ──

    public IREmitter emitGuardType(int primTypeId, int deoptBlockId) {
        return emit2(GUARD_TYPE, K_GUARDED, primTypeId, deoptBlockId);
    }

    public IREmitter emitGuardNonNull(int deoptBlockId) {
        return emit2(GUARD_NONNULL, K_NONE, deoptBlockId, 0);
    }

    // ── Concatenation ──

    public IREmitter emitCat()    { return emit1(CAT, K_NONE, 0); }
    public IREmitter emitDynCat() { return emit1(DYNCAT, K_DYN, 0); }

    // ── Unary ──

    public IREmitter emitNot() { return emit1(NOT, K_BOOL, 0); }

    // ── NOP ──

    public IREmitter emitNop() { return emit1(NOP, K_NONE, 0); }

    // ── Finalization ──

    public int[] toArray() { return buf.toArray(); }
    public int size()      { return buf.size(); }
    public boolean isEmpty() { return buf.isEmpty(); }
    public void clear()    { buf.clear(); }

    /** Number of raw int words in the buffer (not instruction count). */
    public int wordCount() { return buf.size(); }
}
