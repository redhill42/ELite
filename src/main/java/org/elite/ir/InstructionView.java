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

/**
 * Read-only cursor over a packed int[] instruction stream.
 * <p>
 * Provides fast, allocation-free decoding of IR instructions for both
 * the interpreter loop and optimization passes.
 *
 * <p>Usage:
 * <pre>{@code
 * InstructionView v = new InstructionView(code, 0);
 * while (v.inBounds()) {
 *     switch (v.opcode()) {
 *         case Opcode.ADD: ... v.advance(); break;
 *         ...
 *     }
 * }
 * }</pre>
 */
final class InstructionView {
  private final int[] code;
  private final int size;
  private int offset;

  public InstructionView(int[] code, int offset) {
    this(code, offset, code.length);
  }

  public InstructionView(int[] code, int offset, int end) {
    this.code = code;
    this.offset = offset;
    this.size = end;
  }

  public InstructionView(IntList code) {
    this.code = code.data();
    this.size = code.size();
    this.offset = 0;
  }

  // ── Raw access ──

  public int[] code() {
    return code;
  }

  public int offset() {
    return offset;
  }

  // ── Header decoding ──

  public int inst() {
    return code[offset];
  }

  public int opcode() {
    return IRFormat.opcode(inst());
  }

  public int payload() {
    return IRFormat.payload(inst());
  }

  public int operand() {
    return IRFormat.operand(inst());
  }

  public int count() {
    return IRFormat.payload(code[offset]);
  }

  /**
   * Get the variable index from a PUSH_VAR instruction.
   */
  public int varIndex() {
    return IRFormat.operand(code[offset]);
  }

  /**
   * Get the pool index from an instruction.
   */
  public int poolIndex() {
    return IRFormat.operand(code[offset]);
  }

  /**
   * Get the jump target block ID from a jump instruction.
   */
  public int jumpTarget() {
    return IRFormat.operand(code[offset]);
  }

  public boolean isJump() {
    int op = opcode();
    return op == Opcode.JUMP || op == Opcode.JUMP_IF_TRUE ||
           op == Opcode.JUMP_IF_FALSE || op == Opcode.JUMP_IF_NULL ||
           op == Opcode.JUMP_IF_NONNULL;
  }

  // ── Mutation ──

  public void replace(int opcode, int payload, int operand) {
    code[offset] = IRFormat.pack(opcode, payload, operand);
  }

  // ── Navigation ──

  public boolean inBounds() {
    return offset < size;
  }

  public void advance() {
    offset += 1;
  }

  public void advance(int instructions) {
    offset += instructions;
  }

  /**
   * Peek at the next instruction without consuming it.
   */
  public InstructionView peek() {
    return new InstructionView(code, offset + 1);
  }

  /**
   * Peek N instructions ahead without consuming.
   */
  public InstructionView peek(int n) {
    return new InstructionView(code, offset + n);
  }
}
