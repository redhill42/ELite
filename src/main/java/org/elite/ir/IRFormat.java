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
 * ┌──63───────────────────────────32──┬─31──24─┬─23──20─┬─19────────────0─┐
 * │             operand               │ opcode │  kind  │     payload     │
 * │             32 bits               │ 8 bits │ 4 bits │     20 bits     │
 * └───────────────────────────────────┴────────┴────────┴─────────────────┘
 *
 * Additional operand words follow the header (op cnt words).
 */
final class IRFormat {
    private IRFormat() {}

    // Bit shifts
    public static final int OPERAND_SHIFT = 32;
    public static final int OPCODE_SHIFT  = 24;
    public static final int KIND_SHIFT    = 20;
    public static final int PAYLOAD_MASK  = 0xFFFFF;

    // Primitive type IDs
    public static final int K_NONE    = 0;
    public static final int K_INT     = 1;
    public static final int K_LONG    = 2;
    public static final int K_DOUBLE  = 3;
    public static final int K_STRING  = 4;
    public static final int K_BOOL    = 5;
    public static final int K_DYNAMIC = 6;

    // ── Packing helpers ──

    /** Pack a 1-word instruction. */
    public static long pack(int opcode, int kind, int payload, int operand) {
        return ((long)operand << OPERAND_SHIFT) |
               ((long)opcode << OPCODE_SHIFT) |
               ((long)kind << KIND_SHIFT) |
               (payload & PAYLOAD_MASK);
    }

    // ── Decoding helpers ──

    public static int opcode(long header)   { return (int)((header >>> OPCODE_SHIFT) & 0xFF); }
    public static int kind(long header)     { return (int)((header >>> KIND_SHIFT) & 0x0F); }
    public static int payload(long header)  { return (int)(header & PAYLOAD_MASK); }
    public static int operand(long header)  { return (int)(header >> OPERAND_SHIFT); }
}
