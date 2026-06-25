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
 * IR opcodes for ELite's linear intermediate representation.
 *
 * Each opcode occupies the top 8 bits of a 32-bit instruction header word.
 * See {@link IRFormat} for the full encoding specification.
 */
public final class Opcode {
    private Opcode() {}

    // ── Stack ops (0x00-0x0F) ──
    public static final int PUSH_CONST   = 0x00;
    public static final int PUSH_VAR     = 0x01;
    public static final int PUSH_GLOBAL  = 0x02;
    public static final int POP          = 0x03;
    public static final int DUP          = 0x04;
    public static final int POP_N        = 0x05;

    public static final int PUSH_TRUE  = 0x06;
    public static final int PUSH_FALSE = 0x07;
    public static final int PUSH_NULL  = 0x08;

    // ── Typed arithmetic (0x10-0x2F) ──
    public static final int IADD = 0x10;  // int add
    public static final int ISUB = 0x11;
    public static final int IMUL = 0x12;
    public static final int IDIV = 0x13;
    public static final int IREM = 0x14;
    public static final int INEG = 0x15;

    public static final int LADD = 0x16;  // long add
    public static final int LSUB = 0x17;
    public static final int LMUL = 0x18;
    public static final int LDIV = 0x19;
    public static final int LREM = 0x1A;
    public static final int LNEG = 0x1B;

    public static final int DADD = 0x1C;  // double add
    public static final int DSUB = 0x1D;
    public static final int DMUL = 0x1E;
    public static final int DDIV = 0x1F;
    public static final int DNEG = 0x20;

    public static final int IPOW = 0x21;  // int power
    public static final int LPOW = 0x22;  // long power
    public static final int DPOW = 0x23;  // double power

    // ── Dynamic arithmetic (0x25-0x2F) ──
    public static final int DYNADD  = 0x25;
    public static final int DYNSUB  = 0x26;
    public static final int DYNMUL  = 0x27;
    public static final int DYNDIV  = 0x28;
    public static final int DYNREM  = 0x29;
    public static final int DYNNEG  = 0x2A;
    public static final int DYNPOW  = 0x2B;
    public static final int DYNIN   = 0x2C;

    // ── Concatenation (0x2C-2D) ──
    public static final int CAT    = 0x2D;
    public static final int DYNCAT = 0x2E;

    // ── Bitwise ops (0x30-0x4F) ──
    public static final int IAND    = 0x30;
    public static final int IOR     = 0x31;
    public static final int IXOR    = 0x32;
    public static final int ISHL    = 0x33;
    public static final int ISHR    = 0x34;
    public static final int IUSHR   = 0x35;
    public static final int IBITNOT = 0x36;

    public static final int LAND    = 0x37;
    public static final int LOR     = 0x38;
    public static final int LXOR    = 0x39;
    public static final int LSHL    = 0x3A;
    public static final int LSHR    = 0x3B;
    public static final int LUSHR   = 0x3C;
    public static final int LBITNOT = 0x3D;

    public static final int DYNAND  = 0x3E;
    public static final int DYNOR   = 0x3F;
    public static final int DYNXOR  = 0x40;
    public static final int DYNSHL  = 0x41;
    public static final int DYNSHR  = 0x42;
    public static final int DYNUSHR = 0x43;
    public static final int DYNNOT  = 0x44;
    public static final int DYNEMPTY = 0x45;

    // ── Unary (0x46-0x4F) ──
    public static final int NOT = 0x46;

    // ── Typed comparisons (0x50-0x63) ──
    public static final int IEQ = 0x50;
    public static final int INE = 0x51;
    public static final int ILT = 0x52;
    public static final int ILE = 0x53;
    public static final int IGT = 0x54;
    public static final int IGE = 0x55;

    public static final int LEQ = 0x56;
    public static final int LNE = 0x57;
    public static final int LLT = 0x58;
    public static final int LLE = 0x59;
    public static final int LGT = 0x5A;
    public static final int LGE = 0x5B;

    public static final int DEQ = 0x5C;
    public static final int DNE = 0x5D;
    public static final int DLT = 0x5E;
    public static final int DLE = 0x5F;
    public static final int DGT = 0x60;
    public static final int DGE = 0x61;

    public static final int IDEQ = 0x62;  // reference/identity equality (===)
    public static final int IDNE = 0x63;  // reference/identity inequality (!==)

    // ── Dynamic comparisons (0x64-0x66) ──
    public static final int DYNEQ = 0x64;
    public static final int DYNNE = 0x65;
    public static final int DYNLT = 0x66;
    public static final int DYNLE = 0x67;
    public static final int DYNGT = 0x68;
    public static final int DYNGE = 0x69;

    // ── Control flow (0x70-0x7F) ──
    public static final int JUMP            = 0x70;
    public static final int JUMP_IF_TRUE    = 0x71;
    public static final int JUMP_IF_FALSE   = 0x72;
    public static final int JUMP_IF_NULL    = 0x73;
    public static final int JUMP_IF_NONNULL = 0x74;
    public static final int TABLE_SWITCH    = 0x75;

    // ── Thunk / lazy (0x76-0x77) ──
    public static final int DELAY         = 0x76;  // create DelayEvalClosure wrapping IRClosure thunk
    public static final int PUSH_VAR_RAW  = 0x77;  // push local without forcing lazy thunk

    // ── Function (0x80-0x8F) ──
    public static final int INVOKE        = 0x80;
    public static final int INVOKE_DYN    = 0x81;
    public static final int INVOKE_TAIL   = 0x82;
    public static final int INVOKE_DIRECT = 0x83;  // direct IRFunction call
    public static final int INVOKE_TARGET = 0x84;
    public static final int CLOSURE       = 0x85;
    public static final int RETURN        = 0x86;
    public static final int RETURN_VOID   = 0x87;
    public static final int THROW         = 0x88;

    // ── Memory / allocation (0x90-0x9F) ──
    public static final int DEFINE_GLOBAL  = 0x90;  // define in current scope, create if new
    public static final int STORE_GLOBAL   = 0x91;  // assign to global, full chain search, throw if undefined
    public static final int STORE_VAR      = 0x92;
    public static final int LOAD_FIELD     = 0x93;
    public static final int STORE_FIELD    = 0x94;
    public static final int LOAD_PROPERTY  = 0x95;  // pops key, base → base[key]
    public static final int STORE_PROPERTY = 0x96;  // pops val, key, base → base[key]=val
    public static final int GET_ITER       = 0x97;
    public static final int ITER_NEXT      = 0x98;
    public static final int ITER_DONE      = 0x99;
    public static final int CAPTURE        = 0x9A;

    // ── Data structure ──
    public static final int NEW_CONS       = 0xA0;
    public static final int NEW_DELAY_CONS = 0xA1;
    public static final int NIL            = 0xA2;
    public static final int NEW_MAP        = 0xA3;
    public static final int NEW_TUPLE      = 0xA4;
    public static final int NEW_RANGE      = 0xA5;

    // ── Type guards (0xA0-0xAF) ──
    // GUARD_TYPE typeId, deoptBlockId: check stack top type.
    //   Match → continue. Mismatch → jump to deoptBlockId.
    //   deoptBlockId == STRICT_GUARD (0xFFFF): throw TypeMismatchError instead of deopt.
    public static final int GUARD_TYPE      = 0xB0;
    public static final int STRICT_GUARD    = 0xFFFF;  // sentinel: throw error on mismatch

    // ── Trampoline (0xE0) ──
    /** Evaluate an AST node directly (for features not yet compiled to IR). */
    public static final int TRAMPOLINE = 0xE0;

    // ── Java interop (0xE1-0xEF) ──
    public static final int INVOKE_GETTER     = 0xE1; // call getter method (pool idx → Method)
    public static final int INVOKE_SETTER     = 0xE2; // call setter method (pool idx → Method)
    public static final int INVOKE_METHOD     = 0xE3; // call method reflectively (pool→Method, argc)
    public static final int INVOKE_EXPANDO    = 0xE4; // call expand method
    public static final int INVOKE_DYN_METHOD = 0xE5; // dynamic method by name (pool→key, argc)

    // ── NOP (for deleted instructions after folding) ──
    public static final int NOP = 0xFE;

    // ── Binary decoders ──

    public static boolean isJump(int op)          { return op >= JUMP && op <= TABLE_SWITCH; }

    /** Human-readable name for debugging. */
    public static String name(int op) {
        return switch (op) {
            case PUSH_CONST -> "PUSH_CONST";
            case PUSH_VAR -> "PUSH_VAR";
            case PUSH_GLOBAL -> "PUSH_GLOBAL";
            case POP -> "POP";
            case DUP -> "DUP";
            case POP_N -> "POP_N";
            case PUSH_TRUE -> "PUSH_TRUE";
            case PUSH_FALSE -> "PUSH_FALSE";
            case PUSH_NULL -> "PUSH_NULL";
            case IADD -> "IADD";
            case ISUB -> "ISUB";
            case IMUL -> "IMUL";
            case IDIV -> "IDIV";
            case IREM -> "IREM";
            case INEG -> "INEG";
            case LADD -> "LADD";
            case LSUB -> "LSUB";
            case LMUL -> "LMUL";
            case LDIV -> "LDIV";
            case LREM -> "LREM";
            case LNEG -> "LNEG";
            case DADD -> "DADD";
            case DSUB -> "DSUB";
            case DMUL -> "DMUL";
            case DDIV -> "DDIV";
            case DNEG -> "DNEG";
            case IPOW -> "IPOW";
            case LPOW -> "LPOW";
            case DPOW -> "DPOW";
            case DYNADD -> "DYNADD";
            case DYNSUB -> "DYNSUB";
            case DYNMUL -> "DYNMUL";
            case DYNDIV -> "DYNDIV";
            case DYNREM -> "DYNREM";
            case DYNNEG -> "DYNNEG";
            case DYNPOW -> "DYNPOW";
            case DYNIN -> "DYNIN";
            case CAT -> "CAT";
            case DYNCAT -> "DYNCAT";
            case IAND -> "IAND";
            case IOR -> "IOR";
            case IXOR -> "IXOR";
            case ISHL -> "ISHL";
            case ISHR -> "ISHR";
            case IUSHR -> "IUSHR";
            case IBITNOT -> "IBITNOT";
            case LAND -> "LAND";
            case LOR -> "LOR";
            case LXOR -> "LXOR";
            case LSHL -> "LSHL";
            case LSHR -> "LSHR";
            case LUSHR -> "LUSHR";
            case LBITNOT -> "LBITNOT";
            case DYNAND -> "DYNAND";
            case DYNOR -> "DYNOR";
            case DYNXOR -> "DYNXOR";
            case DYNSHL -> "DYNSHL";
            case DYNSHR -> "DYNSHR";
            case DYNUSHR -> "DYNUSHR";
            case DYNNOT -> "DYNNOT";
            case DYNEMPTY -> "DYNEMPTY";
            case NOT -> "NOT";
            case IEQ -> "IEQ";
            case INE -> "INE";
            case ILT -> "ILT";
            case ILE -> "ILE";
            case IGT -> "IGT";
            case IGE -> "IGE";
            case LEQ -> "LEQ";
            case LNE -> "LNE";
            case LLT -> "LLT";
            case LLE -> "LLE";
            case LGT -> "LGT";
            case LGE -> "LGE";
            case DEQ -> "DEQ";
            case DNE -> "DNE";
            case DLT -> "DLT";
            case DLE -> "DLE";
            case DGT -> "DGT";
            case DGE -> "DGE";
            case IDEQ -> "REFEQ";
            case IDNE -> "REFNE";
            case DYNEQ -> "DYNEQ";
            case DYNNE -> "DYNNE";
            case DYNLT -> "DYNLT";
            case DYNLE -> "DYNLE";
            case DYNGT -> "DYNGT";
            case DYNGE -> "DYNGE";
            case JUMP -> "JUMP";
            case JUMP_IF_TRUE -> "JUMP_IF_TRUE";
            case JUMP_IF_FALSE -> "JUMP_IF_FALSE";
            case JUMP_IF_NULL -> "JUMP_IF_NULL";
            case JUMP_IF_NONNULL -> "JUMP_IF_NONNULL";
            case TABLE_SWITCH -> "TABLE_SWITCH";
            case DELAY -> "DELAY";
            case PUSH_VAR_RAW -> "PUSH_VAR_RAW";
            case INVOKE -> "INVOKE";
            case INVOKE_DYN -> "INVOKE_DYN";
            case INVOKE_TAIL -> "INVOKE_TAIL";
            case INVOKE_DIRECT -> "INVOKE_DIRECT";
            case INVOKE_TARGET -> "INVOKE_TARGET";
            case CLOSURE -> "CLOSURE";
            case RETURN -> "RETURN";
            case RETURN_VOID -> "RETURN_VOID";
            case THROW -> "THROW";
            case DEFINE_GLOBAL -> "DEFINE_GLOBAL";
            case STORE_GLOBAL -> "STORE_GLOBAL";
            case STORE_VAR -> "STORE_VAR";
            case LOAD_FIELD -> "LOAD_FIELD";
            case STORE_FIELD -> "STORE_FIELD";
            case LOAD_PROPERTY -> "LOAD_PROPERTY";
            case STORE_PROPERTY -> "STORE_PROPERTY";
            case GET_ITER -> "GET_ITER";
            case ITER_NEXT -> "ITER_NEXT";
            case ITER_DONE -> "ITER_DONE";
            case CAPTURE -> "CAPTURE";
            case NEW_CONS -> "NEW_CONS";
            case NEW_DELAY_CONS -> "NEW_DELAY_CONS";
            case NIL -> "NIL";
            case NEW_MAP -> "NEW_MAP";
            case NEW_TUPLE -> "NEW_TUPLE";
            case NEW_RANGE -> "NEW_RANGE";
            case GUARD_TYPE -> "GUARD_TYPE";
            case INVOKE_GETTER -> "INVOKE_GETTER";
            case INVOKE_SETTER -> "INVOKE_SETTER";
            case INVOKE_METHOD -> "INVOKE_METHOD";
            case INVOKE_EXPANDO -> "INVOKE_EXPANDO";
            case INVOKE_DYN_METHOD -> "INVOKE_DYN_METHOD";
            case TRAMPOLINE -> "TRAMPOLINE";
            case NOP -> "NOP";
            default -> "UNKNOWN(" + op + ")";
        };
    }
}
