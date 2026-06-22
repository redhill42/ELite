/*
 * Copyright 2006-2026 Daniel Yuan.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.operamasks.el.ir;

import static org.operamasks.el.ir.IRFormat.*;
import static org.operamasks.el.ir.Opcode.*;

/**
 * Mutable instruction stream builder.
 * <p>
 * Writes a linear sequence of int-packed IR instructions into an internal
 * buffer.
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

    /**
     * Whether a value fits in the 16-bit payload field (unsigned 0..65535).
     */
    private static boolean fits16(int v) {
        return v >= 0 && v <= 0xFFFF;
    }

    // ── Core emit methods ──

    /**
     * Emit a 1-word instruction (op count = 0).
     */
    public IREmitter emit1(int opcode, int kind, int payload) {
        buf.add(pack1(opcode, kind, payload));
        return this;
    }

    /**
     * Emit a 2-word instruction (op count = 1).
     */
    public IREmitter emit2(int opcode, int kind, int payload, int op1) {
        buf.add(pack2h(opcode, kind, payload));
        buf.add(op1);
        return this;
    }

    /**
     * Emit a 3-word instruction (op count = 2).
     */
    public IREmitter emit3(int opcode, int kind, int payload, int op1,
                           int op2) {
        buf.add(pack3h(opcode, kind, payload));
        buf.add(op1);
        buf.add(op2);
        return this;
    }

    /**
     * Copy a raw instruction word sequence from a view.
     */
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
            return emit2(PUSH_CONST, K_NONE, poolIndex >>> 16,
                    poolIndex & 0xFFFF);
        }
    }

    public IREmitter emitPushVar(int varIndex) {
        return emit1(PUSH_VAR, K_PRIM, varIndex & 0xFFFF);
    }

    public IREmitter emitPushGlobal(int nameIndex) {
        if (fits16(nameIndex)) {
            return emit1(PUSH_GLOBAL, K_NONE, nameIndex);
        } else {
            return emit2(PUSH_GLOBAL, K_NONE, nameIndex >>> 16,
                    nameIndex & 0xFFFF);
        }
    }

    public IREmitter emitPop() {
        return emit1(POP, K_NONE, 0);
    }

    public IREmitter emitDup() {
        return emit1(DUP, K_NONE, 0);
    }

    public IREmitter emitPopN(int count) {
        return emit1(POP_N, K_NONE, count);
    }

    // ── Typed arithmetic (with inline primitive type in kind field) ──

    public IREmitter emitIAdd() {
        return emit1(IADD, K_PRIM, T_INT);
    }

    public IREmitter emitISub() {
        return emit1(ISUB, K_PRIM, T_INT);
    }

    public IREmitter emitIMul() {
        return emit1(IMUL, K_PRIM, T_INT);
    }

    public IREmitter emitIDiv() {
        return emit1(IDIV, K_PRIM, T_INT);
    }

    public IREmitter emitIRem() {
        return emit1(IREM, K_PRIM, T_INT);
    }

    public IREmitter emitINeg() {
        return emit1(INEG, K_PRIM, T_INT);
    }

    public IREmitter emitLAdd() {
        return emit1(LADD, K_PRIM, T_LONG);
    }

    public IREmitter emitLSub() {
        return emit1(LSUB, K_PRIM, T_LONG);
    }

    public IREmitter emitLMul() {
        return emit1(LMUL, K_PRIM, T_LONG);
    }

    public IREmitter emitLDiv() {
        return emit1(LDIV, K_PRIM, T_LONG);
    }

    public IREmitter emitLNeg() {
        return emit1(LNEG, K_PRIM, T_LONG);
    }

    public IREmitter emitDAdd() {
        return emit1(DADD, K_PRIM, T_DOUBLE);
    }

    public IREmitter emitDSub() {
        return emit1(DSUB, K_PRIM, T_DOUBLE);
    }

    public IREmitter emitDMul() {
        return emit1(DMUL, K_PRIM, T_DOUBLE);
    }

    public IREmitter emitDDiv() {
        return emit1(DDIV, K_PRIM, T_DOUBLE);
    }

    public IREmitter emitDNeg() {
        return emit1(DNEG, K_PRIM, T_DOUBLE);
    }

    // ── Dynamic arithmetic ──

    public IREmitter emitDynAdd() {
        return emit1(DYNADD, K_DYN, 0);
    }

    public IREmitter emitDynSub() {
        return emit1(DYNSUB, K_DYN, 0);
    }

    public IREmitter emitDynMul() {
        return emit1(DYNMUL, K_DYN, 0);
    }

    public IREmitter emitDynDiv() {
        return emit1(DYNDIV, K_DYN, 0);
    }

    public IREmitter emitDynRem() {
        return emit1(DYNREM, K_DYN, 0);
    }

    public IREmitter emitDynNeg() {
        return emit1(DYNNEG, K_DYN, 0);
    }

    public IREmitter emitDynPow() {
        return emit1(DYNPOW, K_DYN, 0);
    }

    public IREmitter emitDynBitAnd() {
        return emit1(DYNAND, K_DYN, 0);
    }

    public IREmitter emitDynBitOr() {
        return emit1(DYNOR, K_DYN, 0);
    }

    public IREmitter emitDynXor() {
        return emit1(DYNXOR, K_DYN, 0);
    }

    public IREmitter emitDynBitNot() {
        return emit1(DYNNOT, K_DYN, 0);
    }

    public IREmitter emitDynShl() {
        return emit1(DYNSHL, K_DYN, 0);
    }

    public IREmitter emitDynShr() {
        return emit1(DYNSHR, K_DYN, 0);
    }

    public IREmitter emitDynUShr() {
        return emit1(DYNUSHR, K_DYN, 0);
    }

    // ── Typed comparisons ──

    public IREmitter emitIEq() {
        return emit1(IEQ, K_BOOL, 0);
    }

    public IREmitter emitINe() {
        return emit1(INE, K_BOOL, 0);
    }

    public IREmitter emitILt() {
        return emit1(ILT, K_BOOL, 0);
    }

    public IREmitter emitILe() {
        return emit1(ILE, K_BOOL, 0);
    }

    public IREmitter emitIGt() {
        return emit1(IGT, K_BOOL, 0);
    }

    public IREmitter emitIGe() {
        return emit1(IGE, K_BOOL, 0);
    }

    public IREmitter emitLEq() {
        return emit1(LEQ, K_BOOL, 0);
    }

    public IREmitter emitLNe() {
        return emit1(LNE, K_BOOL, 0);
    }

    public IREmitter emitLLt() {
        return emit1(LLT, K_BOOL, 0);
    }

    public IREmitter emitLLe() {
        return emit1(LLE, K_BOOL, 0);
    }

    public IREmitter emitDEq() {
        return emit1(DEQ, K_BOOL, 0);
    }

    public IREmitter emitDNe() {
        return emit1(DNE, K_BOOL, 0);
    }

    public IREmitter emitDLt() {
        return emit1(DLT, K_BOOL, 0);
    }

    public IREmitter emitDLe() {
        return emit1(DLE, K_BOOL, 0);
    }

    public IREmitter emitDGt() {
        return emit1(DGT, K_BOOL, 0);
    }

    public IREmitter emitDGe() {
        return emit1(DGE, K_BOOL, 0);
    }

    public IREmitter emitLGt() {
        return emit1(LGT, K_BOOL, 0);
    }

    public IREmitter emitLGe() {
        return emit1(LGE, K_BOOL, 0);
    }

    // ── Dynamic comparisons ──


    public IREmitter emitIdEq() {
        return emit1(IDEQ, K_DYN, 0);
    }

    public IREmitter emitIdNe() {
        return emit1(IDNE, K_DYN, 0);
    }

    public IREmitter emitDynEq() {
        return emit1(DYNEQ, K_DYN, 0);
    }

    public IREmitter emitDynNe() {
        return emit1(DYNNE, K_DYN, 0);
    }

    public IREmitter emitDynLt() {
        return emit1(DYNLT, K_DYN, 0);
    }

    public IREmitter emitDynLe() {
        return emit1(DYNLE, K_DYN, 0);
    }

    public IREmitter emitDynGt() {
        return emit1(DYNGT, K_DYN, 0);
    }

    public IREmitter emitDynGe() {
        return emit1(DYNGE, K_DYN, 0);
    }

    // ── Boolean constants ──

    public IREmitter emitPushTrue() {
        return emit1(PUSH_TRUE, K_PRIM, T_BOOL);
    }

    public IREmitter emitPushFalse() {
        return emit1(PUSH_FALSE, K_PRIM, T_BOOL);
    }

    public IREmitter emitPushNull() {
        return emit1(PUSH_NULL, K_NONE, 0);
    }

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
            return emit2(JUMP_IF_TRUE, K_NONE, blockId >>> 16,
                    blockId & 0xFFFF);
        }
    }

    public IREmitter emitJumpIfFalse(int blockId) {
        if (fits16(blockId)) {
            return emit1(JUMP_IF_FALSE, K_NONE, blockId);
        } else {
            return emit2(JUMP_IF_FALSE, K_NONE, blockId >>> 16,
                    blockId & 0xFFFF);
        }
    }

    public IREmitter emitJumpIfNull(int blockId) {
        if (fits16(blockId)) {
            return emit1(JUMP_IF_NULL, K_NONE, blockId);
        } else {
            return emit2(JUMP_IF_NULL, K_NONE, blockId >>> 16,
                    blockId & 0xFFFF);
        }
    }

    public IREmitter emitJumpIfNonNull(int blockId) {
        if (fits16(blockId)) {
            return emit1(JUMP_IF_NONNULL, K_NONE, blockId);
        } else {
            return emit2(JUMP_IF_NONNULL, K_NONE, blockId >>> 16,
                    blockId & 0xFFFF);
        }
    }

    // ── Function ──

    public IREmitter emitReturn(int primTypeId) {
        return emit1(RETURN, K_PRIM, primTypeId);
    }

    public IREmitter emitReturnVoid() {
        return emit1(RETURN_VOID, K_NONE, 0);
    }

    /**
     * Pop value from stack and throw (wrapping as UserException if needed).
     */
    public IREmitter emitThrow() {
        return emit1(THROW, K_NONE, 0);
    }

    public IREmitter emitInvoke(int funcIndex, int argCount) {
        return emit2(INVOKE, K_FN, funcIndex & 0xFFFF, argCount);
    }

    public IREmitter emitInvokeDyn(int argCount) {
        return emit2(INVOKE_DYN, K_DYN, argCount, 0);
    }

    /**
     * Tail-recursive call: pops argCount args, stores to locals, jumps to
     * entry.
     */
    public IREmitter emitInvokeTail(int argCount) {
        return emit1(INVOKE_TAIL, K_NONE, argCount);
    }

    /**
     * Direct call to a known IRFunction (pool index of the IRFunction).
     */
    public IREmitter emitInvokeDirect(int funcPoolIdx, int argCount) {
        if (fits16(funcPoolIdx))
            return emit2(INVOKE_DIRECT, K_NONE, funcPoolIdx, argCount);
        else
            return emit3(INVOKE_DIRECT, K_NONE, funcPoolIdx >>> 16,
                    funcPoolIdx & 0xFFFF, argCount);
    }

    // ── Memory ──

    public IREmitter emitStoreVar(int varIndex) {
        return emit2(STORE_VAR, K_NONE, varIndex & 0xFFFF, 0);
    }

    /**
     * Pop `count` values off the stack and create a list.
     */
    public IREmitter emitNewList(int count) {
        return emit1(NEW_LIST, K_NONE, count);
    }

    /**
     * Pop `count*2` values (key,val pairs) off the stack and create a map.
     */
    public IREmitter emitNewMap(int count) {
        return emit1(NEW_MAP, K_NONE, count);
    }

    /**
     * Pop begin, end off the stack and create a Range.
     */
    public IREmitter emitNewRange() {
        return emit1(NEW_RANGE, K_NONE, 0);
    }

    /**
     * Pop `count` values off the stack and create a tuple.
     */
    public IREmitter emitNewTuple(int count) {
        return emit1(NEW_TUPLE, K_NONE, count);
    }

    /**
     * Pop key, base → push base[key].
     */
    public IREmitter emitLoadProperty() {
        return emit1(LOAD_PROPERTY, K_DYN, 0);
    }

    /**
     * Pop value, key, base → base[key]=value, push value.
     */
    public IREmitter emitStoreProperty() {
        return emit1(STORE_PROPERTY, K_DYN, 0);
    }

    /**
     * Direct field load: pops base, pushes field value. Field name in
     * constant pool.
     */
    public IREmitter emitLoadField(int fieldNameIdx) {
        if (fits16(fieldNameIdx))
            return emit1(LOAD_FIELD, K_NONE, fieldNameIdx);
        else
            return emit2(LOAD_FIELD, K_NONE, fieldNameIdx >>> 16,
                    fieldNameIdx & 0xFFFF);
    }

    /**
     * Direct field store: pops value then base, pushes value. Field name in
     * pool.
     */
    public IREmitter emitStoreField(int fieldNameIdx) {
        if (fits16(fieldNameIdx))
            return emit1(STORE_FIELD, K_NONE, fieldNameIdx);
        else
            return emit2(STORE_FIELD, K_NONE, fieldNameIdx >>> 16,
                    fieldNameIdx & 0xFFFF);
    }

    /**
     * Direct method call: pops argc args + base, calls Method.invoke, pushes
     * result.
     */
    public IREmitter emitInvokeMethod(int methodPoolIdx, int argCount) {
        if (fits16(methodPoolIdx))
            return emit2(INVOKE_METHOD, K_FN, methodPoolIdx, argCount);
        else
            return emit3(INVOKE_METHOD, K_FN, methodPoolIdx >>> 16,
                    methodPoolIdx & 0xFFFF, argCount);
    }

    /**
     * Dynamic method call by name: pops argc args + base, resolves key on base,
     * calls MethodClosure.invoke(elctx, base, args). Handles ELContext
     * injection.
     */
    public IREmitter emitInvokeDynMethod(int keyPoolIdx, int argCount) {
        if (fits16(keyPoolIdx))
            return emit2(INVOKE_DYN_METHOD, K_NONE, keyPoolIdx, argCount);
        else
            return emit3(INVOKE_DYN_METHOD, K_NONE, keyPoolIdx >>> 16,
                    keyPoolIdx & 0xFFFF, argCount);
    }

    /**
     * Create closure: pops captureCount values + IRFunction, pushes
     * ClosureObject.
     */
    public IREmitter emitClosure(int funcPoolIdx, int captureCount) {
        if (fits16(funcPoolIdx))
            return emit2(CLOSURE, K_FN, funcPoolIdx, captureCount);
        else
            return emit3(CLOSURE, K_FN, funcPoolIdx >>> 16,
                    funcPoolIdx & 0xFFFF, captureCount);
    }

    /**
     * Pop value, store to global variable (name in constant pool).
     */
    public IREmitter emitDefineGlobal(int namePoolIndex) {
        if (fits16(namePoolIndex))
            return emit1(DEFINE_GLOBAL, K_NONE, namePoolIndex);
        else
            return emit2(DEFINE_GLOBAL, K_NONE, namePoolIndex >>> 16,
                    namePoolIndex & 0xFFFF);
    }

    /**
     * Store to global with full chain search — throws if variable not defined.
     */
    public IREmitter emitStoreGlobal(int namePoolIndex) {
        if (fits16(namePoolIndex))
            return emit1(STORE_GLOBAL, K_NONE, namePoolIndex);
        else
            return emit2(STORE_GLOBAL, K_NONE, namePoolIndex >>> 16,
                    namePoolIndex & 0xFFFF);
    }

    /**
     * Pop collection → push iterator.
     */
    public IREmitter emitGetIter() {
        return emit1(GET_ITER, K_NONE, 0);
    }

    /**
     * Pop iterator → push next value (or null if done).
     */
    public IREmitter emitIterNext() {
        return emit1(ITER_NEXT, K_NONE, 0);
    }

    /**
     * Pop value → if null (iteration done), jump to target block.
     */
    public IREmitter emitIterDone(int doneBlock) {
        if (fits16(doneBlock))
            return emit1(ITER_DONE, K_NONE, doneBlock);
        else
            return emit2(ITER_DONE, K_NONE, doneBlock >>> 16,
                    doneBlock & 0xFFFF);
    }

    /**
     * Pop container, element → push boolean (element in container).
     */
    public IREmitter emitDynIn() {
        return emit1(DYNIN, K_BOOL, 0);
    }

    // ── Type guards ──

    /**
     * Emit a type guard: check stack top type, deopt or throw on mismatch.
     *
     * @param primTypeId   T_INT, T_LONG, T_DOUBLE, etc.
     * @param deoptBlockId fallback block, or Opcode.STRICT_GUARD to throw
     *                     error.
     */
    public IREmitter emitGuardType(int primTypeId, int deoptBlockId) {
        return emit2(GUARD_TYPE, K_GUARDED, primTypeId, deoptBlockId);
    }

    // ── Concatenation ──

    public IREmitter emitCat() {
        return emit1(CAT, K_NONE, 0);
    }

    public IREmitter emitDynCat() {
        return emit1(DYNCAT, K_DYN, 0);
    }

    // ── Unary ──

    public IREmitter emitNot() {
        return emit1(NOT, K_BOOL, 0);
    }

    // ── NOP ──

    public IREmitter emitNop() {
        return emit1(NOP, K_NONE, 0);
    }

    // ── Finalization ──

    public int[] toArray() {
        return buf.toArray();
    }

    public int size() {
        return buf.size();
    }

    public boolean isEmpty() {
        return buf.isEmpty();
    }

    public void clear() {
        buf.clear();
    }

    /**
     * Number of raw int words in the buffer (not instruction count).
     */
    public int wordCount() {
        return buf.size();
    }
}
