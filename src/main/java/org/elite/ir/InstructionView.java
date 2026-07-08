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
    private Object[] constantPool;

    InstructionView(IntList ilist) {
        this.code = ilist.data();
        this.size = ilist.size();
        this.offset = 0;
        this.constantPool = null;
    }

    public InstructionView(int[] code, int offset) {
        this(code, offset, null);
    }

    public InstructionView(int[] code, int offset, Object[] constantPool) {
        this.code = code;
        this.size = code.length;
        this.offset = offset;
        this.constantPool = constantPool;
    }

    /** Set the constant pool for disassembly display. */
    public void setConstantPool(Object[] pool) {
        this.constantPool = pool;
    }

    // ── Raw access ──

    public int[] code()   { return code; }
    public int offset()   { return offset; }
    public int raw(int i) { return code[offset + i]; }

    // ── Header decoding ──

    public int header()  { return code[offset]; }
    public int opcode()  { return IRFormat.opcode(code[offset]); }
    public int kind()    { return IRFormat.kind(code[offset]); }
    public int opCount() { return IRFormat.opCount(code[offset]); }
    public int payload() { return IRFormat.payload(code[offset]); }
    public int totalWords() { return IRFormat.totalWords(code[offset]); }

    // ── Operand access ──

    public int operand(int i) {
        return code[offset + 1 + i];
    }

    /** Get the variable index from a PUSH_VAR instruction. */
    public int varIndex() {
        return IRFormat.payload(code[offset]) & 0xFFFF;
    }

    /** Get the pool index from an instruction. */
    public int constPoolIndex() {
        return IRFormat.opCount(code[offset]) == 0
            ? IRFormat.payload(code[offset]) : code[offset + 1];
    }

    public int methodIndex() {
        return code[offset + 1];
    }

    /** Get the jump target block ID from a jump instruction. */
    public int jumpTarget() {
        return IRFormat.opCount(code[offset]) == 0
               ? IRFormat.payload(code[offset]) : code[offset + 1];
    }

    // ── Navigation ──

    public boolean inBounds() {
        return offset < size;
    }

    public void advance() {
        offset += totalWords();
    }

    public void advance(int instructions) {
        for (int i = 0; i < instructions; i++) {
            offset += totalWords();
        }
    }

    /** Peek at the next instruction without consuming it. */
    public InstructionView peek() {
        return new InstructionView(code, offset + totalWords(), constantPool);
    }

    /** Peek N instructions ahead without consuming. */
    public InstructionView peek(int n) {
        int o = offset;
        for (int i = 0; i < n && o < code.length; i++) {
            o += IRFormat.totalWords(code[o]);
        }
        return new InstructionView(code, o, constantPool);
    }
}
