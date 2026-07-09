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
    private final long[] code;
    private final int size;
    private int offset;
    private Object[] constantPool;

    public InstructionView(long[] code, int offset) {
        this(code, offset, null);
    }

    public InstructionView(long[] code, int offset, Object[] constantPool) {
        this.code = code;
        this.size = code.length;
        this.offset = offset;
        this.constantPool = constantPool;
    }

    // ── Raw access ──

    public long[] code()   { return code; }
    public int offset()   { return offset; }

    // ── Header decoding ──

    public long header()  { return code[offset]; }
    public int opcode()   { return IRFormat.opcode(header()); }
    public int kind()     { return IRFormat.kind(header()); }
    public int payload()  { return IRFormat.payload(header()); }
    public int operand()  { return IRFormat.operand(header()); }

    /** Get the variable index from a PUSH_VAR instruction. */
    public int varIndex() {
        return IRFormat.payload(code[offset]);
    }

    /** Get the pool index from an instruction. */
    public int poolIndex() {
        return IRFormat.operand(code[offset]);
    }

    /** Get the jump target block ID from a jump instruction. */
    public int jumpTarget() {
        return IRFormat.payload(code[offset]);
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

    /** Peek at the next instruction without consuming it. */
    public InstructionView peek() {
        return new InstructionView(code, offset + 1, constantPool);
    }

    /** Peek N instructions ahead without consuming. */
    public InstructionView peek(int n) {
        return new InstructionView(code, offset + n, constantPool);
    }
}
