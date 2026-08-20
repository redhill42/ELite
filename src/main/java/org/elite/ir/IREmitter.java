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

  public void emit(int opcode, int payload) {
    emit(opcode, payload, 0);
  }

  public void emit(int opcode, int payload, int operand) {
    if (!isDead())
      buf.add(pack(opcode, payload, operand));
  }

  // ── Stack ops ──

  public void emitNop() {
    if (!isDead()) {
      // Bypass peephole optimizer.
      buf.add(pack(NOP, 0, 0));
    }
  }

  public void emitPushConst(Object value) {
    emit(PUSH_CONST, 0, builder.putConstant(value));
  }

  public void emitPushTrue() {
    emit(PUSH_TRUE, 0);
  }

  public void emitPushFalse() {
    emit(PUSH_FALSE, 0);
  }

  public void emitPushNull() {
    emit(PUSH_NULL, 0);
  }

  public void emitPushThis() {
    emit(PUSH_THIS, 0);
  }

  public void emitPushEnv() {
    emit(PUSH_ENV, 0, 0);
  }

  public void emitPushCtx() {
    emit(PUSH_CTX, 0, 0);
  }

  public void emitPushVar(int varIndex) {
    emit(PUSH_VAR, 0, varIndex);
  }

  public void emitPushGlobal(int slot) {
    emit(PUSH_GLOBAL, 0, slot);
  }

  public void emitPushGlobal(String name) {
    emit(PUSH_GLOBAL, 0, builder.putConstant(name));
  }

  public void emitPop() {
    emit(POP, 0);
  }

  public void emitDup() {
    emit(DUP, 0);
  }

  // ── Typed arithmetic (with inline primitive type in kind field) ──

  public void emitAdd() {
    emit(ADD, K_DYNAMIC, 0);
  }

  public void emitAdd(int kind) {
    emit(ADD, kind, 0);
  }

  public void emitSub() {
    emit(SUB, K_DYNAMIC, 0);
  }

  public void emitSub(int kind) {
    emit(SUB, kind, 0);
  }

  public void emitMul() {
    emit(MUL, K_DYNAMIC, 0);
  }

  public void emitDiv() {
    emit(DIV, K_DYNAMIC, 0);
  }

  public void emitDiv(int kind) {
    emit(DIV, kind, 0);
  }

  public void emitIDiv() {
    emit(IDIV, K_DYNAMIC, 0);
  }

  public void emitRem() {
    emit(REM, K_DYNAMIC, 0);
  }

  public void emitPow() {
    emit(POW, K_DYNAMIC, 0);
  }

  public void emitNeg() {
    emit(NEG, K_DYNAMIC, 0);
  }

  public void emitCat() {
    emit(CAT, 0);
  }

  public void emitBitAnd() {
    emit(BITAND, K_DYNAMIC, 0);
  }

  public void emitBitOr() {
    emit(BITOR, K_DYNAMIC, 0);
  }

  public void emitBitNot() {
    emit(BITNOT, K_DYNAMIC, 0);
  }

  public void emitXor() {
    emit(XOR, K_DYNAMIC, 0);
  }

  public void emitShl() {
    emit(SHL, K_DYNAMIC, 0);
  }

  public void emitShr() {
    emit(SHR, K_DYNAMIC, 0);
  }

  public void emitUShr() {
    emit(USHR, K_DYNAMIC, 0);
  }

  public void emitEq() {
    emit(EQ, K_DYNAMIC, 0);
  }

  public void emitEq(int kind) {
    emit(EQ, kind, 0);
  }

  public void emitNe() {
    emit(NE, K_DYNAMIC, 0);
  }

  public void emitIdEq() {
    emit(IDEQ, 0);
  }

  public void emitIdNe() {
    emit(IDNE, 0);
  }

  public void emitLt() {
    emit(LT, K_DYNAMIC, 0);
  }

  public void emitLt(int kind) {
    emit(LT, kind, 0);
  }

  public void emitLe() {
    emit(LE, K_DYNAMIC, 0);
  }

  public void emitGt() {
    emit(GT, K_DYNAMIC, 0);
  }

  public void emitGe() {
    emit(GE, K_DYNAMIC, 0);
  }

  public void emitCmp() {
    emit(CMP, K_DYNAMIC, 0);
  }

  public void emitIn() {
    emit(IN, K_DYNAMIC, 0);
  }

  public void emitInstanceOf(Object cls) {
    emit(INSTANCEOF, 0, builder.putConstant(cls));
  }

  public void emitEmpty() {
    emit(EMPTY, K_DYNAMIC, 0);
  }

  public void emitNot() {
    emit(NOT, 0);
  }

  // ── Control flow ──

  public void emitJump(int blockId) {
    emit(JUMP, 0, blockId);
  }

  public void emitJumpIfTrue(int blockId) {
    emit(JUMP_IF_TRUE, 0, blockId);
  }

  public void emitJumpIfFalse(int blockId) {
    emit(JUMP_IF_FALSE, 0, blockId);
  }

  public void emitJumpIfNull(int blockId) {
    emit(JUMP_IF_NULL, 0, blockId);
  }

  public void emitJumpIfNonNull(int blockId) {
    emit(JUMP_IF_NONNULL, 0, blockId);
  }

  public void emitReturn() {
    emit(RETURN, 0);
  }

  public void emitTry(Descriptors.Try desc) {
    emit(TRY, 0, builder.putConstant(desc));
  }

  public void emitSynchronized(IRFunction body) {
    emit(SYNCHRONIZED, 0, builder.putConstant(body));
  }

  public void emitThrow() {
    emit(THROW, 0);
  }

  public void emitThrowException() {
    emit(THROW_EXCEPTION, 0, 0);
  }

  public void emitAssert(int count) {
    emit(ASSERT, count);
  }

  public void emitEnterScope() {
    emit(ENTER_SCOPE, 0);
  }

  public void emitLeaveScope() {
    emit(LEAVE_SCOPE, 0);
  }

  public void emitDefineGlobal(int slot) {
    emit(DEFINE_GLOBAL, 0, slot);
  }

  public void emitDefineGlobal(String name, boolean readonly) {
    emit(DEFINE_GLOBAL, readonly ? 1 : 0, builder.putConstant(name));
  }

  public void emitStoreGlobal(int slot) {
    emit(STORE_GLOBAL, 0, slot);
  }

  public void emitStoreGlobal(String name) {
    emit(STORE_GLOBAL, 0, builder.putConstant(name));
  }

  public void emitStoreVar(int varIndex) {
    emit(STORE_VAR, 0, varIndex);
  }

  public void emitStoreVarPop(int varIndex) {
    emit(STORE_VAR_POP, 0, varIndex);
  }

  public void emitClosure(IRFunction func) {
    emit(CLOSURE, 0, builder.putConstant(func));
  }

  public void emitInvokeDirect(IRFunction fn) {
    emit(INVOKE_DIRECT, 0, builder.putConstant(fn));
  }

  public void emitInvokeMethod(Method method) {
    emit(INVOKE_METHOD, 0, builder.putConstant(method));
  }

  public void emitInvokeDynamic(Descriptors.Indy desc) {
    emit(INVOKE_DYNAMIC, 0, builder.putConstant(desc));
  }

  public void emitNew(Class<?> c) {
    emit(NEW, 0, builder.putConstant(c));
  }

  public void emitNew(IRClass clazz) {
    emit(NEW, 0, builder.putConstant(clazz));
  }

  public void emitConstructor(Constructor<?> constructor) {
    emit(CONSTRUCTOR, 0, builder.putConstant(constructor));
  }

  public void emitConstructor(IRClass clazz) {
    emit(CONSTRUCTOR, 0, builder.putConstant(clazz));
  }

  public void emitConstructor(int arity, Class<?> baseClass) {
    emit(CONSTRUCTOR, arity, builder.putConstant(baseClass));
  }

  public void emitNewArray(Class<?> type) {
    emit(NEW_ARRAY, 0, builder.putConstant(type));
  }

  public void emitNewFixedArray(int count, Class<?> type) {
    emit(NEW_FIXED_ARRAY, count, builder.putConstant(type));
  }

  public void emitNewMultiArray(int dims, Class<?> type) {
    emit(NEW_MULTI_ARRAY, dims, builder.putConstant(type));
  }

  public void emitLoadArray(int index, Class<?> type) {
    emit(LOAD_ARRAY, index, builder.putConstant(type));
  }

  public void emitStoreArray(int index, Object type) {
    emit(STORE_ARRAY, index, builder.putConstant(type));
  }

  public void emitGetField(IRClass clazz, String field) {
    emit(GETFIELD, 0, builder.putConstant(new Descriptors.Field(clazz, field)));
  }

  public void emitGetField(Field field) {
    emit(GETFIELD, 0, builder.putConstant(field));
  }

  public void emitGetField(String field) {
    emit(GETFIELD, 0, builder.putConstant(field));
  }

  public void emitPutField(IRClass clazz, String field) {
    emit(PUTFIELD, 0, builder.putConstant(new Descriptors.Field(clazz, field)));
  }

  public void emitPutField(Field field) {
    emit(PUTFIELD, 0, builder.putConstant(field));
  }

  public void emitPutField(String field) {
    emit(PUTFIELD, 0, builder.putConstant(field));
  }

  public void emitGetStatic(IRClass clazz, String field) {
    emit(GETSTATIC, 0,
         builder.putConstant(new Descriptors.Field(clazz, field)));
  }

  public void emitGetStatic(Field field) {
    emit(GETSTATIC, 0, builder.putConstant(field));
  }

  public void emitPutStatic(IRClass clazz, String field) {
    emit(PUTSTATIC, 0, builder.putConstant(new Descriptors.Field(clazz, field)));
  }

  public void emitPutStatic(Field field) {
    emit(PUTSTATIC, 0, builder.putConstant(field));
  }

  public void emitCheckCast(Class<?> type) {
    emit(CHECKCAST, 0, builder.putConstant(type));
  }

  public void emitCheckCast(IRClass clazz) {
    emit(CHECKCAST, 0, builder.putConstant(clazz));
  }

  public void emitBox(Class<?> type) {
    emit(BOX, 0, builder.putConstant(type));
  }

  public void emitUnbox(Class<?> type) {
    emit(UNBOX, 0, builder.putConstant(type));
  }

  public void emitNewTuple(int count) {
    emit(NEW_TUPLE, count, 0);
  }

  public void emitDeclareNS(String prefix) {
    emit(DECLARE_NS, 0, builder.putConstant(prefix));
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
