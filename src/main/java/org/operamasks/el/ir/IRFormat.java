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

    // Kind field values
    public static final int K_NONE    = 0;  // no type info
    public static final int K_PRIM    = 1;  // primitive type (payload = typeId)
    public static final int K_CLASS   = 2;  // class type (payload = pool index)
    public static final int K_FN      = 3;  // function type (payload = pool index)
    public static final int K_DYN     = 4;  // dynamic type
    public static final int K_GUARDED = 5;  // optimistic type guard (payload = expected typeId)
    public static final int K_VAR     = 6;  // type variable (payload = var index)
    public static final int K_BOOL    = 7;  // boolean result

    // Primitive type IDs for K_PRIM and K_GUARDED
    public static final int T_INT    = 0;
    public static final int T_LONG   = 1;
    public static final int T_DOUBLE = 2;
    public static final int T_FLOAT  = 3;
    public static final int T_BOOL   = 4;
    public static final int T_STRING = 5;
    public static final int T_CHAR   = 6;

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

    /** Get the primitive type name from a type ID. */
    public static String primTypeName(int typeId) {
        return switch (typeId) {
            case T_INT -> "int";
            case T_LONG -> "long";
            case T_DOUBLE -> "double";
            case T_FLOAT -> "float";
            case T_BOOL -> "boolean";
            case T_STRING -> "string";
            case T_CHAR -> "char";
            default -> "?";
        };
    }
}
