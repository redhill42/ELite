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
 * <p>
 * Each opcode occupies the top 8 bits of a 32-bit instruction header word.
 * See {@link IRFormat} for the full encoding specification.
 */
public final class Opcode { 
  private Opcode() {}

  // ── Stack ops ──
  public static final int NOP               = 0x00;
  public static final int PUSH_CONST        = 0x01;
  public static final int PUSH_TRUE         = 0x02;
  public static final int PUSH_FALSE        = 0x03;
  public static final int PUSH_NULL         = 0x04;
  public static final int PUSH_THIS         = 0x05;
  public static final int PUSH_CTX          = 0x06;
  public static final int PUSH_ENV          = 0x07;
  public static final int PUSH_VAR          = 0x08;
  public static final int PUSH_GLOBAL       = 0x09;
  public static final int POP               = 0x0A;
  public static final int DUP               = 0x0B;

  // ── Operators ──
  public static final int ADD               = 0x10;
  public static final int SUB               = 0x11;
  public static final int MUL               = 0x12;
  public static final int DIV               = 0x13;
  public static final int IDIV              = 0x14;
  public static final int REM               = 0x15;
  public static final int POW               = 0x16;
  public static final int NEG               = 0x17;
  public static final int CAT               = 0x18;
  public static final int BITAND            = 0x19;
  public static final int BITOR             = 0x1A;
  public static final int BITNOT            = 0x1B;
  public static final int XOR               = 0x1C;
  public static final int SHL               = 0x1D;
  public static final int SHR               = 0x1E;
  public static final int USHR              = 0x1F;
  public static final int EQ                = 0x20;
  public static final int NE                = 0x21;
  public static final int LT                = 0x22;
  public static final int LE                = 0x23;
  public static final int GT                = 0x24;
  public static final int GE                = 0x25;
  public static final int IDEQ              = 0x26;
  public static final int IDNE              = 0x27;
  public static final int IN                = 0x28;
  public static final int INSTANCEOF        = 0x29;
  public static final int EMPTY             = 0x2A;
  public static final int NOT               = 0x2B;

  // ── Control flow ──
  public static final int JUMP              = 0x30;
  public static final int JUMP_IF_TRUE      = 0x31;
  public static final int JUMP_IF_FALSE     = 0x32;
  public static final int JUMP_IF_NULL      = 0x33;
  public static final int JUMP_IF_NONNULL   = 0x34;
  public static final int RETURN            = 0x35;
  public static final int TRY               = 0x36;
  public static final int SYNCHRONIZED      = 0x37;
  public static final int THROW             = 0x38;
  public static final int THROW_EXCEPTION   = 0x39;
  public static final int ASSERT            = 0x3A;
  public static final int ENTER_SCOPE       = 0x3B;
  public static final int LEAVE_SCOPE       = 0x3C;

  // ── Variable, Function ──
  public static final int DEFINE_GLOBAL     = 0x40;
  public static final int STORE_GLOBAL      = 0x41;
  public static final int STORE_VAR         = 0x42;
  public static final int STORE_VAR_POP     = 0x43;
  public static final int CLOSURE           = 0x44;

  // ── Direct Call ──
  public static final int INVOKE_DIRECT     = 0x45;

  // ── Java Interop ──
  public static final int INVOKE_METHOD     = 0x50;
  public static final int NEW               = 0x51;
  public static final int CONSTRUCTOR       = 0x52;
  public static final int NEW_ARRAY         = 0x53;
  public static final int LOAD_ARRAY        = 0x54;
  public static final int STORE_ARRAY       = 0x55;
  public static final int GETFIELD          = 0x56;
  public static final int PUTFIELD          = 0x57;
  public static final int GETSTATIC         = 0x58;
  public static final int PUTSTATIC         = 0x59;
  public static final int CHECKCAST         = 0x5A;
  public static final int BOX               = 0x5B;
  public static final int UNBOX             = 0x5C;

  // ── Data structure ──
  public static final int NEW_CONS          = 0x60;
  public static final int NEW_DELAY_CONS    = 0x61;
  public static final int NIL               = 0x62;
  public static final int NEW_TUPLE         = 0x63;
  public static final int DECLARE_NS        = 0x64;

  // ── Trampoline ──
  /** Evaluate an AST node directly (for features not yet compiled to IR). */
  public static final int TRAMPOLINE        = 0x7F;

  /** Human-readable name for debugging. */
  public static String name(int op) {
    return switch (op) {
      case NOP            -> "NOP";
      case PUSH_CONST     -> "PUSH_CONST";
      case PUSH_TRUE      -> "PUSH_TRUE";
      case PUSH_FALSE     -> "PUSH_FALSE";
      case PUSH_NULL      -> "PUSH_NULL";
      case PUSH_THIS      -> "PUSH_THIS";
      case PUSH_CTX       -> "PUSH_CTX";
      case PUSH_ENV       -> "PUSH_ENV";
      case PUSH_VAR       -> "PUSH_VAR";
      case PUSH_GLOBAL    -> "PUSH_GLOBAL";
      case POP            -> "POP";
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
      case BITAND         -> "BITAND";
      case BITOR          -> "BITOR";
      case BITNOT         -> "BITNOT";
      case XOR            -> "XOR";
      case SHL            -> "SHL";
      case SHR            -> "SHR";
      case USHR           -> "USHR";
      case EQ             -> "EQ";
      case NE             -> "NE";
      case LT             -> "LT";
      case LE             -> "LE";
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
      case JUMP_IF_NONNULL-> "JUMP_IF_NONNULL";

      case RETURN         -> "RETURN";
      case TRY            -> "TRY";
      case SYNCHRONIZED   -> "SYNCHRONIZED";
      case THROW          -> "THROW";
      case THROW_EXCEPTION-> "THROW_EXCEPTION";
      case ASSERT         -> "ASSERT";
      case ENTER_SCOPE    -> "ENTER_SCOPE";
      case LEAVE_SCOPE    -> "LEAVE_SCOPE";

      case DEFINE_GLOBAL  -> "DEFINE_GLOBAL";
      case STORE_GLOBAL   -> "STORE_GLOBAL";
      case STORE_VAR      -> "STORE_VAR";
      case STORE_VAR_POP  -> "STORE_VAR_POP";
      case CLOSURE        -> "CLOSURE";

      case INVOKE_DIRECT  -> "INVOKE_DIRECT";
      case INVOKE_METHOD  -> "INVOKE_METHOD";
      case NEW            -> "NEW";
      case CONSTRUCTOR    -> "CONSTRUCTOR";
      case NEW_ARRAY      -> "NEW_ARRAY";
      case LOAD_ARRAY     -> "LOAD_ARRAY";
      case STORE_ARRAY    -> "STORE_ARRAY";
      case GETFIELD       -> "GETFIELD";
      case PUTFIELD       -> "PUTFIELD";
      case GETSTATIC      -> "GETSTATIC";
      case PUTSTATIC      -> "PUTSTATIC";
      case CHECKCAST      -> "CHECKCAST";
      case BOX            -> "BOX";
      case UNBOX          -> "UNBOX";

      case NEW_CONS       -> "NEW_CONS";
      case NEW_DELAY_CONS -> "NEW_DELAY_CONS";
      case NIL            -> "NIL";
      case NEW_TUPLE      -> "NEW_TUPLE";
      case DECLARE_NS     -> "DECLARE_NS";

      case TRAMPOLINE     -> "TRAMPOLINE";

      default             -> "UNKNOWN(" + op + ")";
    };
  }
}
