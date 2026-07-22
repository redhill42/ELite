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

  // ── Core emit methods ──

  public boolean isDead() {
    if (!buf.isEmpty()) {
      // Instruction after jump or return is dead.
      int op = IRFormat.opcode(buf.back());
      return op == JUMP || op == RETURN || op == THROW;
    }
    return false;
  }

  public IREmitter emit(int opcode, int payload) {
    return emit(opcode, payload, 0);
  }

  public IREmitter emit(int opcode, int payload, int operand) {
    if (!isDead())
      buf.add(pack(opcode, payload, operand));
    return this;
  }

  // ── Stack ops ──

  public IREmitter emitNop() {
    if (!isDead()) {
      // Bypass peephole optimizer.
      buf.add(pack(NOP, 0, 0));
    }
    return this;
  }

  public IREmitter emitPushConst(int poolIndex) {
    return emit(PUSH_CONST, 0, poolIndex);
  }

  public IREmitter emitPushTrue() {
    return emit(PUSH_TRUE, 0);
  }

  public IREmitter emitPushFalse() {
    return emit(PUSH_FALSE, 0);
  }

  public IREmitter emitPushNull() {
    return emit(PUSH_NULL, 0);
  }

  public IREmitter emitPushThis() {
    return emit(PUSH_THIS, 0);
  }

  public IREmitter emitPushEnv() {
    return emit(PUSH_ENV, 0, 0);
  }

  public IREmitter emitPushCtx() {
    return emit(PUSH_CTX, 0, 0);
  }

  public IREmitter emitPushVar(int varIndex) {
    return emit(PUSH_VAR, 0, varIndex);
  }

  public IREmitter emitPushGlobal(int poolIndex) {
    return emit(PUSH_GLOBAL, 0, poolIndex);
  }

  public IREmitter emitPop() {
    return emit(POP, 0);
  }

  public IREmitter emitDup() {
    return emit(DUP, 0);
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
    return emit(CAT, 0);
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
    return emit(IDEQ, 0);
  }

  public IREmitter emitIdNe() {
    return emit(IDNE, 0);
  }

  public IREmitter emitIn() {
    return emit(IN, K_DYNAMIC, 0);
  }

  public IREmitter emitInstanceOf(int poolIdx) {
    return emit(INSTANCEOF, 0, poolIdx);
  }

  public IREmitter emitEmpty() {
    return emit(EMPTY, K_DYNAMIC, 0);
  }

  public IREmitter emitNot() {
    return emit(NOT, 0);
  }

  // ── Control flow ──

  public IREmitter emitJump(int blockId) {
    return emit(JUMP, 0, blockId);
  }

  public IREmitter emitJumpIfTrue(int blockId) {
    return emit(JUMP_IF_TRUE, 0, blockId);
  }

  public IREmitter emitJumpIfFalse(int blockId) {
    return emit(JUMP_IF_FALSE, 0, blockId);
  }

  public IREmitter emitJumpIfNull(int blockId) {
    return emit(JUMP_IF_NULL, 0, blockId);
  }

  public IREmitter emitJumpIfNonNull(int blockId) {
    return emit(JUMP_IF_NONNULL, 0, blockId);
  }

  public IREmitter emitReturn() {
    return emit(RETURN, 0);
  }

  public IREmitter emitTry(int handlerCount) {
    return emit(TRY, handlerCount);
  }

  public IREmitter emitSynchronized() {
    return emit(SYNCHRONIZED, 0);
  }

  public IREmitter emitThrow() {
    return emit(THROW, 0);
  }

  public IREmitter emitAssert(int count) {
    return emit(ASSERT, count);
  }

  public IREmitter emitEnterScope() {
    return emit(ENTER_SCOPE, 0);
  }

  public IREmitter emitLeaveScope() {
    return emit(LEAVE_SCOPE, 0);
  }

  /**
   * Pop value, store to global variable (name in constant pool).
   */
  public IREmitter emitDefineGlobal(int poolIndex) {
    return emit(DEFINE_GLOBAL, 0, poolIndex);
  }

  /**
   * Store to global with full chain search — throws if variable not defined.
   */
  public IREmitter emitStoreGlobal(int poolIndex) {
    return emit(STORE_GLOBAL, 0, poolIndex);
  }

  public IREmitter emitStoreVar(int varIndex) {
    return emit(STORE_VAR, 0, varIndex);
  }

  public IREmitter emitStoreVarPop(int varIndex) {
    return emit(STORE_VAR_POP, 0, varIndex);
  }

  public IREmitter emitClosure(int poolIdx) {
    return emit(CLOSURE, 0, poolIdx);
  }

  /**
   * Direct call to a known IRFunction (pool index of the IRFunction).
   */
  public IREmitter emitInvokeDirect(int poolIdx) {
    return emit(INVOKE_DIRECT, 0, poolIdx);
  }

  public IREmitter emitInvokeMethod(int poolIdx) {
    return emit(INVOKE_METHOD, 0, poolIdx);
  }

  public IREmitter emitNew(int poolIdx) {
    return emit(NEW, 0, poolIdx);
  }

  public IREmitter emitConstructor(int poolIdx) {
    return emit(CONSTRUCTOR, 0, poolIdx);
  }

  public IREmitter emitNewArray(int count, int poolIdx) {
    return emit(NEW_ARRAY, count, poolIdx);
  }

  public IREmitter emitLoadArray(int index, int poolIdx) {
    return emit(LOAD_ARRAY, index, poolIdx);
  }

  public IREmitter emitStoreArray(int index, int poolIdx) {
    return emit(STORE_ARRAY, index, poolIdx);
  }

  public IREmitter emitGetField(int poolIdx) {
    return emit(GETFIELD, 0, poolIdx);
  }

  public IREmitter emitPutField(int poolIdx) {
    return emit(PUTFIELD, 0, poolIdx);
  }

  public IREmitter emitGetStatic(int poolIdx) {
    return emit(GETSTATIC, 0, poolIdx);
  }

  public IREmitter emitPutStatic(int poolIdx) {
    return emit(PUTSTATIC, 0, poolIdx);
  }

  public IREmitter emitCheckCast(int poolIdx) {
    return emit(CHECKCAST, 0, poolIdx);
  }

  public IREmitter emitBox(int poolIdx) {
    return emit(BOX, 0, poolIdx);
  }

  public IREmitter emitUnbox(int poolIdx) {
    return emit(UNBOX, 0, poolIdx);
  }

  public IREmitter emitNewCons() {
    return emit(NEW_CONS, 0);
  }

  public IREmitter emitNewDelayCons() {
    return emit(NEW_DELAY_CONS, 0);
  }

  public IREmitter emitNil() {
    return emit(NIL, 0);
  }

  public IREmitter emitNewTuple(int count) {
    return emit(NEW_TUPLE, count, 0);
  }

  public IREmitter emitDeclareNS(int nameIdx) {
    return emit(DECLARE_NS, 0, nameIdx);
  }

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
}
