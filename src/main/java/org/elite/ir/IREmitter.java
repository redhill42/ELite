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

import org.elite.eval.EvaluationContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
  private final IRBuilder builder;

  IREmitter(IRBuilder builder) {
    this.builder = builder;
  }

  // ── Core emit methods ──

  public boolean isDead() {
    if (!buf.isEmpty()) {
      // Instruction after jump or return is dead.
      int op = IRFormat.opcode(buf.back());
      return op == JUMP || op == RETURN || op == THROW || op == THROW_EXCEPTION;
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

  public IREmitter emitPushConst(Object value) {
    return emit(PUSH_CONST, 0, builder.putConstant(value));
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

  public IREmitter emitPushGlobal(int slot) {
    return emit(PUSH_GLOBAL, 0, slot);
  }

  public IREmitter emitPushGlobal(String name) {
    return emit(PUSH_GLOBAL, 0, builder.putConstant(name));
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

  public IREmitter emitInstanceOf(Class<?> cls) {
    return emit(INSTANCEOF, 0, builder.putConstant(cls));
  }

  public IREmitter emitInstanceOf(IRClass cls) {
    return emit(INSTANCEOF, 0, builder.putConstant(cls));
  }

  public IREmitter emitInstanceOf(String clsid) {
    return emit(INSTANCEOF, 0, builder.putConstant(clsid));
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

  public IREmitter emitTry(IRFunction.TryDescriptor desc) {
    return emit(TRY, 0, builder.putConstant(desc));
  }

  public IREmitter emitSynchronized(IRFunction body) {
    return emit(SYNCHRONIZED, 0, builder.putConstant(body));
  }

  public IREmitter emitThrow() {
    return emit(THROW, 0);
  }

  public IREmitter emitThrowException() {
    return emit(THROW_EXCEPTION, 0, 0);
  }

  public IREmitter emitThrowException(String message) {
    return emit(THROW_EXCEPTION, 1, builder.putConstant(message));
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

  public IREmitter emitDefineGlobal(int slot) {
    return emit(DEFINE_GLOBAL, 0, slot);
  }

  public IREmitter emitDefineGlobal(String name) {
    return emit(DEFINE_GLOBAL, 0, builder.putConstant(name));
  }

  public IREmitter emitStoreGlobal(int slot) {
    return emit(STORE_GLOBAL, 0, slot);
  }

  public IREmitter emitStoreGlobal(String name) {
    return emit(STORE_GLOBAL, 0, builder.putConstant(name));
  }

  public IREmitter emitStoreVar(int varIndex) {
    return emit(STORE_VAR, 0, varIndex);
  }

  public IREmitter emitStoreVarPop(int varIndex) {
    return emit(STORE_VAR_POP, 0, varIndex);
  }

  public IREmitter emitClosure(IRFunction func) {
    return emit(CLOSURE, 0, builder.putConstant(func));
  }

  public IREmitter emitInvokeDirect(IRFunction fn) {
    return emit(INVOKE_DIRECT, 0, builder.putConstant(fn));
  }

  public IREmitter emitInvokeMethod(Method method) {
    return emit(INVOKE_METHOD, 0, builder.putConstant(method));
  }

  public IREmitter emitNew(Class<?> c) {
    return emit(NEW, 0, builder.putConstant(c));
  }

  public IREmitter emitNew(IRClass clazz) {
    return emit(NEW, 0, builder.putConstant(clazz));
  }

  public IREmitter emitConstructor(Constructor<?> constructor) {
    return emit(CONSTRUCTOR, 0, builder.putConstant(constructor));
  }

  public IREmitter emitConstructor(IRClass clazz) {
    return emit(CONSTRUCTOR, 0, builder.putConstant(clazz));
  }

  public IREmitter emitNewArray(int count, Class<?> type) {
    return emit(NEW_ARRAY, count, builder.putConstant(type));
  }

  public IREmitter emitLoadArray(int index, Class<?> type) {
    return emit(LOAD_ARRAY, index, builder.putConstant(type));
  }

  public IREmitter emitStoreArray(int index, Class<?> type) {
    return emit(STORE_ARRAY, index, builder.putConstant(type));
  }

  public IREmitter emitGetField(IRClass clazz, String field) {
    return emit(GETFIELD, 0, builder.putConstant(new IRClass.Field(clazz, field)));
  }

  public IREmitter emitGetField(String field) {
    return emit(GETFIELD, 0, builder.putConstant(field));
  }

  public IREmitter emitPutField(IRClass clazz, String field) {
    return emit(PUTFIELD, 0, builder.putConstant(new IRClass.Field(clazz, field)));
  }

  public IREmitter emitPutField(String field) {
    return emit(PUTFIELD, 0, builder.putConstant(field));
  }

  public IREmitter emitGetStatic(IRClass clazz, String field) {
    return emit(GETSTATIC, 0, builder.putConstant(new IRClass.Field(clazz, field)));
  }

  public IREmitter emitGetStatic(String field) {
    return emit(GETSTATIC, 0, builder.putConstant(field));
  }

  public IREmitter emitGetStatic(Field field) {
    return emit(GETSTATIC, 0, builder.putConstant(field));
  }

  public IREmitter emitPutStatic(IRClass clazz, String field) {
    return emit(PUTSTATIC, 0, builder.putConstant(new IRClass.Field(clazz, field)));
  }

  public IREmitter emitPutStatic(String field) {
    return emit(PUTSTATIC, 0, builder.putConstant(field));
  }

  public IREmitter emitPutStatic(Field field) {
    return emit(PUTSTATIC, 0, builder.putConstant(field));
  }

  public IREmitter emitCheckCast(Class<?> type) {
    return emit(CHECKCAST, 0, builder.putConstant(type));
  }

  public IREmitter emitCheckCast(IRClass clazz) {
    return emit(CHECKCAST, 0, builder.putConstant(clazz));
  }

  public IREmitter emitBox(Class<?> type) {
    return emit(BOX, 0, builder.putConstant(type));
  }

  public IREmitter emitUnbox(Class<?> type) {
    return emit(UNBOX, 0, builder.putConstant(type));
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

  public IREmitter emitDeclareNS(String prefix) {
    return emit(DECLARE_NS, 0, builder.putConstant(prefix));
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
