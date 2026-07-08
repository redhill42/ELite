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
 * IR opcodes for ELite's linear intermediate representation.
 *
 * Each opcode occupies the top 8 bits of a 32-bit instruction header word.
 * See {@link IRFormat} for the full encoding specification.
 */
public final class Opcode {
    private Opcode() {}

    // ── Stack ops (0x00-0x0F) ──
    public static final int NOP               = 0x00;
    public static final int PUSH_CONST        = 0x01;
    public static final int PUSH_VAR          = 0x02;
    public static final int PUSH_GLOBAL       = 0x03;
    public static final int PUSH_TRUE         = 0x04;
    public static final int PUSH_FALSE        = 0x05;
    public static final int PUSH_NULL         = 0x06;
    public static final int POP               = 0x07;
    public static final int POP_N             = 0x08;
    public static final int DUP               = 0x09;

    // ── Operators ──
    public static final int ADD               = 0x20;
    public static final int SUB               = 0x21;
    public static final int MUL               = 0x22;
    public static final int DIV               = 0x23;
    public static final int IDIV              = 0x24;
    public static final int REM               = 0x25;
    public static final int POW               = 0x26;
    public static final int NEG               = 0x27;
    public static final int CAT               = 0x28;
    public static final int JOIN              = 0x29;
    public static final int BITAND            = 0x2A;
    public static final int BITOR             = 0x2B;
    public static final int BITNOT            = 0x2C;
    public static final int XOR               = 0x2D;
    public static final int SHL               = 0x2E;
    public static final int SHR               = 0x2F;
    public static final int USHR              = 0x30;
    public static final int EQ                = 0x31;
    public static final int NE                = 0x32;
    public static final int LT                = 0x33;
    public static final int LE                = 0x34;
    public static final int GT                = 0x35;
    public static final int GE                = 0x36;
    public static final int IDEQ              = 0x37;
    public static final int IDNE              = 0x38;
    public static final int IN                = 0x39;
    public static final int INSTANCEOF        = 0x3A;
    public static final int EMPTY             = 0x3B;
    public static final int NOT               = 0x3C;

    // ── Control flow ──
    public static final int JUMP              = 0x50;
    public static final int JUMP_IF_TRUE      = 0x51;
    public static final int JUMP_IF_FALSE     = 0x52;
    public static final int JUMP_IF_NULL      = 0x53;
    public static final int JUMP_IF_NONNULL   = 0x54;
    public static final int RETURN            = 0x55;
    public static final int RETURN_VOID       = 0x56;
    public static final int TRY               = 0x57;
    public static final int SYNCHRONIZED      = 0x58;
    public static final int THROW             = 0x59;
    public static final int ASSERT            = 0x5A;
    public static final int ENTER_SCOPE       = 0x5B;
    public static final int LEAVE_SCOPE       = 0x5C;

    // ── Variable, Function and Property ──
    public static final int DEFINE_GLOBAL     = 0x70;
    public static final int STORE_GLOBAL      = 0x71;
    public static final int STORE_VAR         = 0x72;
    public static final int CLOSURE           = 0x73;
    public static final int INVOKE_DIRECT     = 0x74;
    public static final int INVOKE_OPERATOR   = 0x75;
    public static final int INVOKE_TARGET     = 0x76;
    public static final int INVOKE_DYN        = 0x77;
    public static final int INVOKE_METHOD     = 0x78;
    public static final int INVOKE_STATIC     = 0x79;
    public static final int INVOKE_EXPANDO    = 0x7A;
    public static final int INVOKE_DYN_METHOD = 0x7B;
    public static final int LOAD_PROPERTY     = 0x7C;
    public static final int STORE_PROPERTY    = 0x7D;
    public static final int INVOKE_GETTER     = 0x7E;
    public static final int INVOKE_SETTER     = 0x7F;
    public static final int LOAD_FIELD        = 0x80;
    public static final int STORE_FIELD       = 0x81;

    // ── Data structure ──
    public static final int NEW_CONS          = 0xA0;
    public static final int NEW_DELAY_CONS    = 0xA1;
    public static final int NIL               = 0xA2;
    public static final int NEW_MAP           = 0xA3;
    public static final int NEW_TUPLE         = 0xA4;
    public static final int NEW_RANGE         = 0xA5;
    public static final int NEW_XML           = 0xA6;
    public static final int DECLARE_NS        = 0xA7;

    // ── Trampoline ──
    /** Evaluate an AST node directly (for features not yet compiled to IR). */
    public static final int TRAMPOLINE        = 0xE0;

    /** Human-readable name for debugging. */
    public static String name(int op) {
        return switch (op) {
            case NOP            -> "NOP";
            case PUSH_CONST     -> "PUSH_CONST";
            case PUSH_VAR       -> "PUSH_VAR";
            case PUSH_GLOBAL    -> "PUSH_GLOBAL";
            case PUSH_TRUE      -> "PUSH_TRUE";
            case PUSH_FALSE     -> "PUSH_FALSE";
            case PUSH_NULL      -> "PUSH_NULL";
            case POP            -> "POP";
            case POP_N          -> "POP_N";
            case DUP            -> "DUP";

            case ADD            -> "ADD";
            case SUB            -> "SUB";
            case MUL            -> "MUL";
            case DIV            -> "DIV";
            case IDIV           -> "IDIV";
            case REM            -> "REM";
            case POW            -> "POW";
            case NEG            -> "NEG";
            case CAT            -> "CAT";
            case JOIN           -> "JOIN";
            case BITAND         -> "BITAND";
            case BITOR          -> "BITOR";
            case XOR            -> "XOR";
            case SHL            -> "SHL";
            case SHR            -> "SHR";
            case USHR           -> "USHR";
            case EQ             -> "EQ";
            case NE             -> "NE";
            case LT             -> "LT";
            case GT             -> "GT";
            case GE             -> "GE";
            case IDEQ           -> "IDEQ";
            case IDNE           -> "IDNE";
            case IN             -> "IN";
            case INSTANCEOF     -> "INSTANCEOF";
            case EMPTY          -> "EMPTY";
            case NOT            -> "NOT";

            case JUMP           -> "JUMP";
            case JUMP_IF_TRUE   -> "JUMP_IF_TRUE";
            case JUMP_IF_FALSE  -> "JUMP_IF_FALSE";
            case JUMP_IF_NULL   -> "JUMP_IF_NULL";
            case JUMP_IF_NONNULL -> "JUMP_IF_NONNULL";
            case RETURN         -> "RETURN";
            case RETURN_VOID    -> "RETURN_VOID";
            case TRY            -> "TRY";
            case SYNCHRONIZED   -> "SYNCHRONIZED";
            case THROW          -> "THROW";
            case ASSERT         -> "ASSERT";
            case ENTER_SCOPE    -> "ENTER_SCOPE";
            case LEAVE_SCOPE    -> "LEAVE_SCOPE";

            case DEFINE_GLOBAL  -> "DEFINE_GLOBAL";
            case STORE_GLOBAL   -> "STORE_GLOBAL";
            case STORE_VAR      -> "STORE_VAR";
            case CLOSURE        -> "CLOSURE";
            case INVOKE_DIRECT  -> "INVOKE_DIRECT";
            case INVOKE_OPERATOR -> "INVOKE_OPERATOR";
            case INVOKE_TARGET  -> "INVOKE_TARGET";
            case INVOKE_DYN     -> "INVOKE_DYN";
            case INVOKE_METHOD  -> "INVOKE_METHOD";
            case INVOKE_STATIC  -> "INVOKE_STATIC";
            case INVOKE_EXPANDO -> "INVOKE_EXPANDO";
            case INVOKE_DYN_METHOD -> "INVOKE_DYN_METHOD";
            case LOAD_PROPERTY  -> "LOAD_PROPERTY";
            case STORE_PROPERTY -> "STORE_PROPERTY";
            case INVOKE_GETTER  -> "INVOKE_GETTER";
            case INVOKE_SETTER  -> "INVOKE_SETTER";
            case LOAD_FIELD     -> "LOAD_FIELD";
            case STORE_FIELD    -> "STORE_FIELD";

            case NEW_CONS       -> "NEW_CONS";
            case NEW_DELAY_CONS -> "NEW_DELAY_CONS";
            case NIL            -> "NIL";
            case NEW_MAP        -> "NEW_MAP";
            case NEW_TUPLE      -> "NEW_TUPLE";
            case NEW_RANGE      -> "NEW_RANGE";
            case NEW_XML        -> "NEW_XML";
            case DECLARE_NS     -> "DECLARE_NS";

            case TRAMPOLINE     -> "TRAMPOLINE";

            default             -> "UNKNOWN(" + op + ")";
        };
    }
}
