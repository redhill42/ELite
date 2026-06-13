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

/**
 * Read-only cursor over a packed int[] instruction stream.
 *
 * Provides fast, allocation-free decoding of IR instructions for both
 * the interpreter loop and optimization passes.
 *
 * <p>Usage:
 * <pre>{@code
 * InstructionView v = new InstructionView(code, 0);
 * while (v.inBounds()) {
 *     switch (v.opcode()) {
 *         case Opcode.IADD: ... v.advance(); break;
 *         ...
 *     }
 * }
 * }</pre>
 */
public class InstructionView {
    private final int[] code;
    private int offset;

    public InstructionView(int[] code, int offset) {
        this.code = code;
        this.offset = offset;
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

    /** Get the jump target block ID from a jump instruction. */
    public int jumpTarget() {
        return IRFormat.opCount(code[offset]) == 0
            ? IRFormat.payload(code[offset]) : code[offset + 1];
    }

    /** Get the pool index from an instruction.
     *  For K_FN and K_DYN kinds (function refs, trampolines), the pool index
     *  is always in the 16-bit payload. For K_NONE, 1-word uses payload,
     *  2+-word uses the first operand. */
    public int constPoolIndex() {
        int kind = IRFormat.kind(code[offset]);
        if (kind == IRFormat.K_FN || kind == IRFormat.K_DYN) {
            return IRFormat.payload(code[offset]);
        }
        return IRFormat.opCount(code[offset]) == 0
            ? IRFormat.payload(code[offset]) : code[offset + 1];
    }

    /** Get the full 32-bit index split across payload (hi16) and operand(0) (lo16). */
    public int splitIndex() {
        return (IRFormat.payload(code[offset]) << 16) | (code[offset + 1] & 0xFFFF);
    }

    /** Get the variable index from a PUSH_VAR instruction. */
    public int varIndex() {
        return IRFormat.payload(code[offset]) & 0xFF;
    }

    // ── Navigation ──

    public boolean inBounds() {
        return offset < code.length;
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
        return new InstructionView(code, offset + totalWords());
    }

    /** Peek N instructions ahead without consuming. */
    public InstructionView peek(int n) {
        int o = offset;
        for (int i = 0; i < n && o < code.length; i++) {
            o += IRFormat.totalWords(code[o]);
        }
        return new InstructionView(code, o);
    }

    /** Create a fresh view at the same position. */
    public InstructionView dup() {
        return new InstructionView(code, offset);
    }

    // ── Type helpers ──

    /** Get the primitive type ID when kind is K_PRIM or K_GUARDED. */
    public int primTypeId() {
        int k = kind();
        if (k == IRFormat.K_PRIM || k == IRFormat.K_GUARDED) {
            return payload();
        }
        return -1;
    }

    /** Get the deoptimization target block ID from a GUARD_TYPE instruction. */
    public int deoptBlock() {
        return operand(0);
    }

    // ── Pretty printing ──

    @Override
    public String toString() {
        int op = opcode();
        String s = Opcode.name(op);
        int k = kind();
        if (k == IRFormat.K_PRIM || k == IRFormat.K_GUARDED) {
            s += "(" + IRFormat.primTypeName(payload()) + ")";
        } else if (k == IRFormat.K_DYN) {
            s += "(dynamic)";
        } else if (k == IRFormat.K_BOOL) {
            s += "(bool)";
        }
        if (Opcode.isJump(op)) {
            s += " -> B" + jumpTarget();
        }
        if (op == Opcode.PUSH_CONST) {
            s += " #" + constPoolIndex();
        }
        if (op == Opcode.PUSH_VAR) {
            s += " v" + varIndex();
        }
        return s;
    }
}
