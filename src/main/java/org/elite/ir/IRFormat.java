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
 * <p>
 * Header word (32 bits):
 * <pre>
 *     ┌──31───────────────────┬─15───8──┬─7────0─┐
 *     │       operand         │ payload │ opcode │
 *     │        16 bit         │ 8 bits  │ 8 bits │
 *     └───────────────────────┴─────────┴────────┘
 * </pre>
 *<p>
 */
final class IRFormat {
    private IRFormat() {}

    // Bit shifts
    public static final int PAYLOAD_SHIFT = 8;
    public static final int OPERAND_SHIFT = 16;

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
    public static int pack(int opcode, int payload, int operand) {
        return opcode | (payload << PAYLOAD_SHIFT) | (operand << OPERAND_SHIFT);
    }

    // ── Decoding helpers ──

    public static int opcode(int header)   { return header & 0xFF; }
    public static int payload(int header)  { return (header >>> PAYLOAD_SHIFT) & 0xFF; }
    public static int operand(int header)  { return header >>> OPERAND_SHIFT; }
}
