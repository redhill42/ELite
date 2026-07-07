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
 * Bit-level encoding constants for IR instructions.
 *
 * Header word (32 bits):
 * ┌─31──24─┬─23──20─┬─19──16─┬─15───0─┐
 * │ opcode │  kind  │ op cnt │ payload │
 * │ 8 bits │ 4 bits │ 4 bits │ 16 bits │
 * └────────┴────────┴────────┴─────────┘
 *
 * Additional operand words follow the header (op cnt words).
 */
public final class IRFormat {
    private IRFormat() {}

    // Bit shifts
    public static final int OPCODE_SHIFT = 24;
    public static final int KIND_SHIFT   = 20;
    public static final int OPCNT_SHIFT  = 16;
    public static final int PAYLOAD_MASK = 0xFFFF;

    // Primitive type IDs
    public static final int K_NONE    = 0;
    public static final int K_INT     = 1;
    public static final int K_LONG    = 2;
    public static final int K_DOUBLE  = 3;
    public static final int K_STRING  = 4;
    public static final int K_BOOL    = 5;
    public static final int K_DYNAMIC = 6;

    // ── Packing helpers ──

    /** Pack a 1-word instruction (op cnt = 0). */
    public static int pack1(int opcode, int kind, int payload) {
        return (opcode << OPCODE_SHIFT) | (kind << KIND_SHIFT) | (0 << OPCNT_SHIFT) | (payload & PAYLOAD_MASK);
    }

    /** Pack a 2-word instruction (op cnt = 1). */
    public static int pack2h(int opcode, int kind, int payload) {
        return (opcode << OPCODE_SHIFT) | (kind << KIND_SHIFT) | (1 << OPCNT_SHIFT) | (payload & PAYLOAD_MASK);
    }

    /** Pack a 3-word instruction header (op cnt = 2). */
    public static int pack3h(int opcode, int kind, int payload) {
        return (opcode << OPCODE_SHIFT) | (kind << KIND_SHIFT) | (2 << OPCNT_SHIFT) | (payload & PAYLOAD_MASK);
    }

    // ── Decoding helpers ──

    public static int opcode(int header)   { return (header >>> OPCODE_SHIFT) & 0xFF; }
    public static int kind(int header)     { return (header >>> KIND_SHIFT) & 0x0F; }
    public static int opCount(int header)  { return (header >>> OPCNT_SHIFT) & 0x0F; }
    public static int payload(int header)  { return header & PAYLOAD_MASK; }
    public static int totalWords(int header) { return 1 + opCount(header); }
}
