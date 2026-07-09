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

package org.elite.ir;

import static org.elite.ir.IRFormat.*;
import static org.elite.ir.Opcode.*;

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
final class IREmitter {
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
    public IREmitter emit3(int opcode, int kind, int payload, int op1, int op2) {
        buf.add(pack3h(opcode, kind, payload));
        buf.add(op1);
        buf.add(op2);
        return this;
    }

    /**
     * Copy a raw instruction word sequence from a view.
     */
    public IREmitter copyFrom(InstructionView view) {
        int w = view.totalWords();
        for (int i = 0; i < w; i++) {
            buf.add(view.code()[view.offset() + i]);
        }
        return this;
    }

    // ── Stack ops ──

    public IREmitter emitNop() {
        return emit1(NOP, K_NONE, 0);
    }

    public IREmitter emitPushConst(int poolIndex) {
        if (fits16(poolIndex)) {
            return emit1(PUSH_CONST, K_NONE, poolIndex);
        } else {
            return emit2(PUSH_CONST, K_NONE, 0, poolIndex);
        }
    }

    public IREmitter emitPushVar(int varIndex) {
        return emit1(PUSH_VAR, K_NONE, varIndex & 0xFFFF);
    }

    public IREmitter emitPushGlobal(int nameIndex) {
        if (fits16(nameIndex)) {
            return emit1(PUSH_GLOBAL, K_NONE, nameIndex);
        } else {
            return emit2(PUSH_GLOBAL, K_NONE, 0, nameIndex);
        }
    }

    public IREmitter emitPushTrue() {
        return emit1(PUSH_TRUE, K_NONE, 0);
    }

    public IREmitter emitPushFalse() {
        return emit1(PUSH_FALSE, K_NONE, 0);
    }

    public IREmitter emitPushNull() {
        return emit1(PUSH_NULL, K_NONE, 0);
    }

    public IREmitter emitPop() {
        return emit1(POP, K_NONE, 0);
    }

    public IREmitter emitPopN(int count) {
        return emit1(POP_N, K_NONE, count);
    }

    public IREmitter emitDup() {
        return emit1(DUP, K_NONE, 0);
    }

    // ── Typed arithmetic (with inline primitive type in kind field) ──

    public IREmitter emitAdd() {
        return emit1(ADD, K_DYNAMIC, 0);
    }

    public IREmitter emitAdd(int kind) {
        return emit1(ADD, kind, 0);
    }

    public IREmitter emitSub() {
        return emit1(SUB, K_DYNAMIC, 0);
    }

    public IREmitter emitSub(int kind) {
        return emit1(SUB, kind, 0);
    }

    public IREmitter emitMul() {
        return emit1(MUL, K_DYNAMIC, 0);
    }

    public IREmitter emitMul(int kind) {
        return emit1(MUL, kind, 0);
    }

    public IREmitter emitDiv() {
        return emit1(DIV, K_DYNAMIC, 0);
    }

    public IREmitter emitDiv(int kind) {
        return emit1(DIV, kind, 0);
    }

    public IREmitter emitIDiv() {
        return emit1(IDIV, K_DYNAMIC, 0);
    }

    public IREmitter emitRem() {
        return emit1(REM, K_DYNAMIC, 0);
    }

    public IREmitter emitPow() {
        return emit1(POW, K_DYNAMIC, 0);
    }

    public IREmitter emitNeg() {
        return emit1(NEG, K_DYNAMIC, 0);
    }

    public IREmitter emitCat() {
        return emit1(CAT, K_NONE, 0);
    }

    public IREmitter emitJoin(int count) {
        return emit1(JOIN, K_NONE, count);
    }

    public IREmitter emitBitAnd() {
        return emit1(BITAND, K_DYNAMIC, 0);
    }

    public IREmitter emitBitOr() {
        return emit1(BITOR, K_DYNAMIC, 0);
    }

    public IREmitter emitBitNot() {
        return emit1(BITNOT, K_DYNAMIC, 0);
    }

    public IREmitter emitXor() {
        return emit1(XOR, K_DYNAMIC, 0);
    }

    public IREmitter emitShl() {
        return emit1(SHL, K_DYNAMIC, 0);
    }

    public IREmitter emitShr() {
        return emit1(SHR, K_DYNAMIC, 0);
    }

    public IREmitter emitUShr() {
        return emit1(USHR, K_DYNAMIC, 0);
    }

    public IREmitter emitEq() {
        return emit1(EQ, K_DYNAMIC, 0);
    }

    public IREmitter emitEq(int kind) {
        return emit1(EQ, kind, 0);
    }

    public IREmitter emitNe() {
        return emit1(NE, K_DYNAMIC, 0);
    }

    public IREmitter emitNe(int kind) {
        return emit1(NE, kind, 0);
    }

    public IREmitter emitLt() {
        return emit1(LT, K_DYNAMIC, 0);
    }

    public IREmitter emitLt(int kind) {
        return emit1(LT, kind, 0);
    }

    public IREmitter emitLe() {
        return emit1(LE, K_DYNAMIC, 0);
    }

    public IREmitter emitGt() {
        return emit1(GT, K_DYNAMIC, 0);
    }

    public IREmitter emitGe() {
        return emit1(GE, K_DYNAMIC, 0);
    }

    public IREmitter emitIdEq() {
        return emit1(IDEQ, K_NONE, 0);
    }

    public IREmitter emitIdNe() {
        return emit1(IDNE, K_NONE, 0);
    }

    public IREmitter emitIn() {
        return emit1(IN, K_DYNAMIC, 0);
    }

    public IREmitter emitInstanceOf(int classIdx) {
        if (fits16(classIdx))
            return emit1(INSTANCEOF, K_NONE, classIdx);
        else
            return emit2(INSTANCEOF, K_NONE, 0, classIdx);
    }

    public IREmitter emitEmpty() {
        return emit1(EMPTY, K_DYNAMIC, 0);
    }

    public IREmitter emitNot() {
        return emit1(NOT, K_NONE, 0);
    }

    // ── Control flow ──

    public IREmitter emitJump(int blockId) {
        if (fits16(blockId)) {
            return emit1(JUMP, K_NONE, blockId);
        } else {
            return emit2(JUMP, K_NONE, 0, blockId);
        }
    }

    public IREmitter emitJumpIfTrue(int blockId) {
        if (fits16(blockId)) {
            return emit1(JUMP_IF_TRUE, K_NONE, blockId);
        } else {
            return emit2(JUMP_IF_TRUE, K_NONE, 0, blockId);
        }
    }

    public IREmitter emitJumpIfFalse(int blockId) {
        if (fits16(blockId)) {
            return emit1(JUMP_IF_FALSE, K_NONE, blockId);
        } else {
            return emit2(JUMP_IF_FALSE, K_NONE, 0, blockId);
        }
    }

    public IREmitter emitJumpIfNull(int blockId) {
        if (fits16(blockId)) {
            return emit1(JUMP_IF_NULL, K_NONE, blockId);
        } else {
            return emit2(JUMP_IF_NULL, K_NONE, 0, blockId);
        }
    }

    public IREmitter emitJumpIfNonNull(int blockId) {
        if (fits16(blockId)) {
            return emit1(JUMP_IF_NONNULL, K_NONE, blockId);
        } else {
            return emit2(JUMP_IF_NONNULL, K_NONE, 0, blockId);
        }
    }

    public IREmitter emitReturn() {
        return emit1(RETURN, K_NONE, 0);
    }

    public IREmitter emitReturnVoid() {
        return emit1(RETURN_VOID, K_NONE, 0);
    }

    public IREmitter emitTry(int handlerCount) {
        return emit1(TRY, K_NONE, handlerCount);
    }

    public IREmitter emitSynchronized() {
        return emit1(SYNCHRONIZED, K_NONE, 0);
    }

    public IREmitter emitThrow() {
        return emit1(THROW, K_NONE, 0);
    }

    public IREmitter emitAssert() {
        return emit1(ASSERT, K_NONE, 0);
    }

    public IREmitter emitEnterScope() {
        return emit1(ENTER_SCOPE, K_NONE, 0);
    }

    public IREmitter emitLeaveScope() {
        return emit1(LEAVE_SCOPE, K_NONE, 0);
    }

    /**
     * Pop value, store to global variable (name in constant pool).
     */
    public IREmitter emitDefineGlobal(int namePoolIndex) {
        if (fits16(namePoolIndex))
            return emit1(DEFINE_GLOBAL, K_NONE, namePoolIndex);
        else
            return emit2(DEFINE_GLOBAL, K_NONE, 0, namePoolIndex);
    }

    /**
     * Store to global with full chain search — throws if variable not defined.
     */
    public IREmitter emitStoreGlobal(int namePoolIndex) {
        if (fits16(namePoolIndex))
            return emit1(STORE_GLOBAL, K_NONE, namePoolIndex);
        else
            return emit2(STORE_GLOBAL, K_NONE, 0, namePoolIndex);
    }

    public IREmitter emitStoreVar(int varIndex) {
        return emit2(STORE_VAR, K_NONE, varIndex & 0xFFFF, 0);
    }

    public IREmitter emitClosure(int funcPoolIdx) {
        if (fits16(funcPoolIdx))
            return emit1(CLOSURE, K_NONE, funcPoolIdx);
        else
            return emit2(CLOSURE, K_NONE, 0, funcPoolIdx);
    }

    /**
     * Direct call to a known IRFunction (pool index of the IRFunction).
     */
    public IREmitter emitInvokeDirect(int funcPoolIdx, int argCount) {
        return emit2(INVOKE_DIRECT, K_NONE, argCount, funcPoolIdx);
    }

    public IREmitter emitInvokeOperator(int namePoolIdx, int argCount) {
        return emit2(INVOKE_OPERATOR, K_NONE, argCount, namePoolIdx);
    }

    public IREmitter emitInvokeTarget(int nameIdx, int argCount) {
        return emit2(INVOKE_TARGET, K_NONE, argCount, nameIdx);
    }

    public IREmitter emitInvokeDyn(int argCount) {
        return emit1(INVOKE_DYN, K_NONE, argCount);
    }

    public IREmitter emitInvokeMethod(int methodPoolIdx, int argCount) {
        return emit2(INVOKE_METHOD, K_NONE, argCount, methodPoolIdx);
    }

    public IREmitter emitInvokeStatic(int methodPoolIdx, int argCount) {
        return emit2(INVOKE_STATIC, K_NONE, argCount, methodPoolIdx);
    }

    public IREmitter emitInvokeExpando(int methodPoolIdx, int argCount) {
        return emit2(INVOKE_EXPANDO, K_NONE, argCount, methodPoolIdx);
    }

    public IREmitter emitLoadProperty() {
        return emit1(LOAD_PROPERTY, K_NONE, 0);
    }

    public IREmitter emitStoreProperty() {
        return emit1(STORE_PROPERTY, K_NONE, 0);
    }

    public IREmitter emitNewCons() {
        return emit1(NEW_CONS, K_NONE, 0);
    }

    public IREmitter emitNewDelayCons() {
        return emit1(NEW_DELAY_CONS, K_NONE, 0);
    }

    public IREmitter emitNil() {
        return emit1(NIL, K_NONE, 0);
    }

    public IREmitter emitNewMap(int count) {
        return emit1(NEW_MAP, K_NONE, count);
    }

    public IREmitter emitNewRange() {
        return emit1(NEW_RANGE, K_NONE, 0);
    }

    public IREmitter emitNewTuple(int count) {
        return emit1(NEW_TUPLE, K_NONE, count);
    }

    public IREmitter emitNewXML(int keyCount, int childCount) {
        return emit2(NEW_XML, K_NONE, keyCount, childCount);
    }

    public IREmitter emitDeclareNS(int nameIdx) {
        if (fits16(nameIdx))
            return emit1(DECLARE_NS, K_NONE, nameIdx);
        else
            return emit2(DECLARE_NS, K_NONE, 0, nameIdx);
    }

    public int[] toArray() {
        return buf.toArray();
    }

    public int size() {
        return buf.size();
    }

    public InstructionView view() {
        return new InstructionView(buf);
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
