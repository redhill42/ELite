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

import org.elite.eval.ELProgram;

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
    private final InstList buf = new InstList();
    private int last = -1;

    // ── Core emit methods ──

    public IREmitter emit(int opcode, int kind, int payload) {
        return emit(opcode, kind, payload, 0);
    }

    public IREmitter emit(int opcode, int kind, int payload, int operand) {
        if (last != -1 && peepholeOpt(opcode, payload))
            return this;

        last = buf.size();
        buf.add(pack(opcode, kind, payload, operand));
        return this;
    }

    private boolean peepholeOpt(int opcode, int arg) {
        int lastOp = IRFormat.opcode(buf.get(last));
        int lastArg = IRFormat.payload(buf.get(last));

        // Instruction after jump or return is dead.
        if (lastOp == JUMP || lastOp == RETURN || lastOp == RETURN_VOID || lastOp == THROW)
            return true;

        switch (opcode) {
        case POP: {
            switch (lastOp) {
            case PUSH_CONST, PUSH_TRUE, PUSH_FALSE, PUSH_NULL,
                 PUSH_VAR, PUSH_GLOBAL, CLOSURE:
                // PUSH_CONST, POP -> NOP
                buf.reset(last); last--;
                return true;
            case STORE_VAR:
                // STORE_VAR, POP -> STORE_VAR_POP
                buf.set(last, IRFormat.pack(STORE_VAR_POP, K_NONE, lastArg, 0));
                return true;
            }
            break;
        }

        case PUSH_VAR:
            if (lastOp == STORE_VAR_POP && arg == lastArg) {
                // STORE_VAR_POP, PUSH_VAR -> STORE_VAR
                buf.set(last, IRFormat.pack(STORE_VAR, K_NONE, arg, 0));
                return true;
            }
            break;

        case NOT:
            if (lastOp == PUSH_TRUE) {
                // PUSH_TRUE, NOT -> PUSH_FALSE
                buf.set(last, IRFormat.pack(PUSH_FALSE, K_NONE, 0, 0));
                return true;
            }
            if (lastOp == PUSH_FALSE) {
                // PUSH_FALSE, NOT -> PUSH_TRUE
                buf.set(last, IRFormat.pack(PUSH_TRUE, K_NONE, 0, 0));
                return true;
            }
            break;

        case JUMP_IF_TRUE:
            if (lastOp == PUSH_TRUE) {
                // PUSH_TRUE, JUMP_IF_TRUE -> JUMP
                buf.set(last, IRFormat.pack(JUMP, K_NONE, arg, 0));
                return true;
            }
            if (lastOp == PUSH_FALSE) {
                // PUSH_FALSE, JUMP_IF_TRUE -> NOP
                buf.reset(last); last--;
                return true;
            }
            break;

        case JUMP_IF_FALSE:
            if (lastOp == PUSH_FALSE) {
                // PUSH_FALSE, JUMP_IF_FALSE -> JUMP
                buf.set(last, IRFormat.pack(JUMP, K_NONE, arg, 0));
                return true;
            }
            if (lastOp == PUSH_TRUE) {
                // PUSH_TRUE, JUMP_IF_FALSE -> NOP
                buf.reset(last); last--;
                return true;
            }
            break;

        case JUMP_IF_NULL:
            if (lastOp == PUSH_NULL) {
                // PUSH_NULL, JUMP_IF_NULL -> JUMP
                buf.set(last, IRFormat.pack(JUMP, K_NONE, arg, 0));
                return true;
            }
            break;

        case JUMP_IF_NONNULL:
            if (lastOp == PUSH_NULL) {
                // PUSH_NULL, JUMP_IF_NONNULL -> NOP
                buf.reset(last); last--;
                return true;
            }
            break;
        }

        return false;
    }

    // ── Stack ops ──

    public IREmitter emitNop() {
        return emit(NOP, K_NONE, 0);
    }

    public IREmitter emitPushConst(int poolIndex) {
        return emit(PUSH_CONST, K_NONE, 0, poolIndex);
    }

    public IREmitter emitPushVar(int varIndex) {
        return emit(PUSH_VAR, K_NONE, varIndex);
    }

    public IREmitter emitPushGlobal(int poolIndex) {
        return emit(PUSH_GLOBAL, K_NONE, 0, poolIndex);
    }

    public IREmitter emitPushTrue() {
        return emit(PUSH_TRUE, K_NONE, 0);
    }

    public IREmitter emitPushFalse() {
        return emit(PUSH_FALSE, K_NONE, 0);
    }

    public IREmitter emitPushNull() {
        return emit(PUSH_NULL, K_NONE, 0);
    }

    public IREmitter emitPop() {
        return emit(POP, K_NONE, 0);
    }

    public IREmitter emitPopN(int count) {
        return emit(POP_N, K_NONE, count);
    }

    public IREmitter emitDup() {
        return emit(DUP, K_NONE, 0);
    }

    // ── Typed arithmetic (with inline primitive type in kind field) ──

    public IREmitter emitAdd() {
        return emit(ADD, K_DYNAMIC, 0);
    }

    public IREmitter emitAdd(int kind) {
        return emit(ADD, kind, 0);
    }

    public IREmitter emitSub() {
        return emit(SUB, K_DYNAMIC, 0);
    }

    public IREmitter emitSub(int kind) {
        return emit(SUB, kind, 0);
    }

    public IREmitter emitMul() {
        return emit(MUL, K_DYNAMIC, 0);
    }

    public IREmitter emitMul(int kind) {
        return emit(MUL, kind, 0);
    }

    public IREmitter emitDiv() {
        return emit(DIV, K_DYNAMIC, 0);
    }

    public IREmitter emitDiv(int kind) {
        return emit(DIV, kind, 0);
    }

    public IREmitter emitIDiv() {
        return emit(IDIV, K_DYNAMIC, 0);
    }

    public IREmitter emitRem() {
        return emit(REM, K_DYNAMIC, 0);
    }

    public IREmitter emitPow() {
        return emit(POW, K_DYNAMIC, 0);
    }

    public IREmitter emitNeg() {
        return emit(NEG, K_DYNAMIC, 0);
    }

    public IREmitter emitCat() {
        return emit(CAT, K_NONE, 0);
    }

    public IREmitter emitJoin(int count) {
        return emit(JOIN, K_NONE, count);
    }

    public IREmitter emitBitAnd() {
        return emit(BITAND, K_DYNAMIC, 0);
    }

    public IREmitter emitBitOr() {
        return emit(BITOR, K_DYNAMIC, 0);
    }

    public IREmitter emitBitNot() {
        return emit(BITNOT, K_DYNAMIC, 0);
    }

    public IREmitter emitXor() {
        return emit(XOR, K_DYNAMIC, 0);
    }

    public IREmitter emitShl() {
        return emit(SHL, K_DYNAMIC, 0);
    }

    public IREmitter emitShr() {
        return emit(SHR, K_DYNAMIC, 0);
    }

    public IREmitter emitUShr() {
        return emit(USHR, K_DYNAMIC, 0);
    }

    public IREmitter emitEq() {
        return emit(EQ, K_DYNAMIC, 0);
    }

    public IREmitter emitEq(int kind) {
        return emit(EQ, kind, 0);
    }

    public IREmitter emitNe() {
        return emit(NE, K_DYNAMIC, 0);
    }

    public IREmitter emitNe(int kind) {
        return emit(NE, kind, 0);
    }

    public IREmitter emitLt() {
        return emit(LT, K_DYNAMIC, 0);
    }

    public IREmitter emitLt(int kind) {
        return emit(LT, kind, 0);
    }

    public IREmitter emitLe() {
        return emit(LE, K_DYNAMIC, 0);
    }

    public IREmitter emitGt() {
        return emit(GT, K_DYNAMIC, 0);
    }

    public IREmitter emitGe() {
        return emit(GE, K_DYNAMIC, 0);
    }

    public IREmitter emitIdEq() {
        return emit(IDEQ, K_NONE, 0);
    }

    public IREmitter emitIdNe() {
        return emit(IDNE, K_NONE, 0);
    }

    public IREmitter emitIn() {
        return emit(IN, K_DYNAMIC, 0);
    }

    public IREmitter emitInstanceOf(int poolIdx) {
        return emit(INSTANCEOF, K_NONE, 0, poolIdx);
    }

    public IREmitter emitEmpty() {
        return emit(EMPTY, K_DYNAMIC, 0);
    }

    public IREmitter emitNot() {
        return emit(NOT, K_NONE, 0);
    }

    // ── Control flow ──

    public IREmitter emitJump(int blockId) {
        return emit(JUMP, K_NONE, blockId);
    }

    public IREmitter emitJumpIfTrue(int blockId) {
        return emit(JUMP_IF_TRUE, K_NONE, blockId);
    }

    public IREmitter emitJumpIfFalse(int blockId) {
        return emit(JUMP_IF_FALSE, K_NONE, blockId);
    }

    public IREmitter emitJumpIfNull(int blockId) {
        return emit(JUMP_IF_NULL, K_NONE, blockId);
    }

    public IREmitter emitJumpIfNonNull(int blockId) {
        return emit(JUMP_IF_NONNULL, K_NONE, blockId);
    }

    public IREmitter emitReturn() {
        return emit(RETURN, K_NONE, 0);
    }

    public IREmitter emitReturnVoid() {
        return emit(RETURN_VOID, K_NONE, 0);
    }

    public IREmitter emitTry(int handlerCount) {
        return emit(TRY, K_NONE, handlerCount);
    }

    public IREmitter emitSynchronized() {
        return emit(SYNCHRONIZED, K_NONE, 0);
    }

    public IREmitter emitThrow() {
        return emit(THROW, K_NONE, 0);
    }

    public IREmitter emitAssert() {
        return emit(ASSERT, K_NONE, 0);
    }

    public IREmitter emitEnterScope() {
        return emit(ENTER_SCOPE, K_NONE, 0);
    }

    public IREmitter emitLeaveScope() {
        return emit(LEAVE_SCOPE, K_NONE, 0);
    }

    /**
     * Pop value, store to global variable (name in constant pool).
     */
    public IREmitter emitDefineGlobal(int poolIndex) {
        return emit(DEFINE_GLOBAL, K_NONE, 0, poolIndex);
    }

    /**
     * Store to global with full chain search — throws if variable not defined.
     */
    public IREmitter emitStoreGlobal(int poolIndex) {
        return emit(STORE_GLOBAL, K_NONE, 0, poolIndex);
    }

    public IREmitter emitStoreVar(int varIndex) {
        return emit(STORE_VAR, K_NONE, varIndex);
    }

    public IREmitter emitStoreVarPop(int varIndex) {
        return emit(STORE_VAR_POP, K_NONE, varIndex);
    }

    public IREmitter emitClosure(int poolIdx) {
        return emit(CLOSURE, K_NONE, 0, poolIdx);
    }

    /**
     * Direct call to a known IRFunction (pool index of the IRFunction).
     */
    public IREmitter emitInvokeDirect(int poolIdx, int argCount) {
        return emit(INVOKE_DIRECT, K_NONE, argCount, poolIdx);
    }

    public IREmitter emitInvokeOperator(int poolIdx, int argCount) {
        return emit(INVOKE_OPERATOR, K_NONE, argCount, poolIdx);
    }

    public IREmitter emitInvokeTarget(int poolIdx, int argCount) {
        return emit(INVOKE_TARGET, K_NONE, argCount, poolIdx);
    }

    public IREmitter emitInvokeDyn(int argCount) {
        return emit(INVOKE_DYN, K_NONE, argCount);
    }

    public IREmitter emitInvokeMethod(int poolIdx, int argCount) {
        return emit(INVOKE_METHOD, K_NONE, argCount, poolIdx);
    }

    public IREmitter emitInvokeStatic(int poolIdx, int argCount) {
        return emit(INVOKE_STATIC, K_NONE, argCount, poolIdx);
    }

    public IREmitter emitInvokeExpando(int poolIdx, int argCount) {
        return emit(INVOKE_EXPANDO, K_NONE, argCount, poolIdx);
    }

    public IREmitter emitLoadProperty() {
        return emit(LOAD_PROPERTY, K_NONE, 0);
    }

    public IREmitter emitStoreProperty() {
        return emit(STORE_PROPERTY, K_NONE, 0);
    }

    public IREmitter emitNewCons() {
        return emit(NEW_CONS, K_NONE, 0);
    }

    public IREmitter emitNewDelayCons() {
        return emit(NEW_DELAY_CONS, K_NONE, 0);
    }

    public IREmitter emitNil() {
        return emit(NIL, K_NONE, 0);
    }

    public IREmitter emitNewMap(int count) {
        return emit(NEW_MAP, K_NONE, count);
    }

    public IREmitter emitNewRange() {
        return emit(NEW_RANGE, K_NONE, 0);
    }

    public IREmitter emitNewTuple(int count) {
        return emit(NEW_TUPLE, K_NONE, count);
    }

    public IREmitter emitNewXML(int keyCount, int childCount) {
        return emit(NEW_XML, K_NONE, keyCount, childCount);
    }

    public IREmitter emitDeclareNS(int nameIdx) {
        return emit(DECLARE_NS, K_NONE, 0, nameIdx);
    }

    public long[] toArray() {
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
        last = -1;
    }
}
