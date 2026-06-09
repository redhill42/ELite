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

    // ── Typed arithmetic (0x10-0x1F) ──
    public static final int IADD = 0x10;  // int add
    public static final int ISUB = 0x11;
    public static final int IMUL = 0x12;
    public static final int IDIV = 0x13;
    public static final int IREM = 0x14;
    public static final int INEG = 0x15;
    public static final int IPOS = 0x16;

    public static final int LADD = 0x17;  // long add
    public static final int LSUB = 0x18;
    public static final int LMUL = 0x19;
    public static final int LDIV = 0x1A;
    public static final int LREM = 0x1B;
    public static final int LNEG = 0x1C;

    public static final int DADD = 0x1D;  // double add
    public static final int DSUB = 0x1E;
    public static final int DMUL = 0x1F;
    public static final int DDIV = 0x20;
    public static final int DNEG = 0x21;

    public static final int IPOW = 0x22;  // int power
    public static final int LPOW = 0x23;  // long power
    public static final int DPOW = 0x24;  // double power

    // ── Dynamic arithmetic (0x25-0x2F) ──
    public static final int DYNADD  = 0x25;
    public static final int DYNSUB  = 0x26;
    public static final int DYNMUL  = 0x27;
    public static final int DYNDIV  = 0x28;
    public static final int DYNREM  = 0x29;
    public static final int DYNNEG  = 0x2A;
    public static final int DYNPOW  = 0x2B;

    // ── Bitwise ops (0x30-0x3F) ──
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

    // ── Typed comparisons (0x40-0x4F) ──
    public static final int IEQ = 0x40;  public static final int INE = 0x41;
    public static final int ILT = 0x42;  public static final int ILE = 0x43;
    public static final int IGT = 0x44;  public static final int IGE = 0x45;

    public static final int LEQ = 0x46;  public static final int LNE = 0x47;
    public static final int LLT = 0x48;  public static final int LLE = 0x49;
    public static final int LGT = 0x4A;  public static final int LGE = 0x4B;

    public static final int DEQ = 0x4C;  public static final int DNE = 0x4D;
    public static final int DLT = 0x4E;  public static final int DLE = 0x4F;

    // ── Dynamic comparisons (0x50-0x53) ──
    public static final int DYNEQ = 0x50;
    public static final int DYNLT = 0x51;
    public static final int DYNLE = 0x52;
    public static final int DYNIN = 0x53;

    // ── Control flow (0x54-0x5F) ──
    public static final int JUMP            = 0x54;
    public static final int JUMP_IF_TRUE    = 0x55;
    public static final int JUMP_IF_FALSE   = 0x56;
    public static final int JUMP_IF_NULL    = 0x57;
    public static final int JUMP_IF_NONNULL = 0x58;
    public static final int TABLE_SWITCH    = 0x59;

    // ── Function (0x60-0x6F) ──
    public static final int INVOKE       = 0x60;
    public static final int INVOKE_DYN   = 0x61;
    public static final int CLOSURE      = 0x62;
    public static final int RETURN       = 0x63;
    public static final int RETURN_VOID  = 0x64;

    // ── Memory / allocation (0x70-0x7F) ──
    public static final int STORE_VAR    = 0x70;
    public static final int LOAD_FIELD   = 0x71;
    public static final int STORE_FIELD  = 0x72;
    public static final int CAPTURE      = 0x73;
    public static final int NEW_LIST     = 0x74;
    public static final int NEW_MAP      = 0x75;
    public static final int NEW_TUPLE    = 0x76;
    public static final int NEW_RANGE    = 0x77;
    public static final int NEW_OBJECT   = 0x78;

    // ── Type guards (0x80-0x8F) ──
    public static final int GUARD_TYPE      = 0x80;
    public static final int GUARD_NONNULL   = 0x81;
    public static final int DEOPT           = 0x82;

    // ── Concatenation (0x90-0x91) ──
    public static final int CAT    = 0x90;
    public static final int DYNCAT = 0x91;

    // ── Unary (0xA0-0xAF) ──
    public static final int NOT = 0xA0;
    public static final int POS = 0xA1;
    public static final int NEG = 0xA2;
    public static final int INC = 0xA3;
    public static final int DEC = 0xA4;

    // ── Boolean constants (0xB0-0xB1) ──
    public static final int PUSH_TRUE  = 0xB0;
    public static final int PUSH_FALSE = 0xB1;
    public static final int PUSH_NULL  = 0xB2;

    // ── NOP (for deleted instructions after folding) ──
    public static final int NOP = 0xFE;

    // ── Binary decoders ──

    public static boolean isTypedArith(int op) { return op >= 0x10 && op <= 0x24; }
    public static boolean isDynamicArith(int op) { return op >= 0x25 && op <= 0x2B; }
    public static boolean isJump(int op) { return op >= 0x54 && op <= 0x58; }
    public static boolean isComparison(int op) { return op >= 0x40 && op <= 0x53; }
    public static boolean isGuard(int op) { return op >= 0x80 && op <= 0x82; }

    /** Human-readable name for debugging. */
    public static String name(int op) {
        switch (op) {
            case PUSH_CONST:  return "PUSH_CONST";
            case PUSH_VAR:    return "PUSH_VAR";
            case PUSH_GLOBAL: return "PUSH_GLOBAL";
            case POP:         return "POP";
            case DUP:         return "DUP";
            case POP_N:       return "POP_N";
            case IADD: return "IADD"; case ISUB: return "ISUB";
            case IMUL: return "IMUL"; case IDIV: return "IDIV";
            case IREM: return "IREM"; case INEG: return "INEG";
            case IPOS: return "IPOS";
            case LADD: return "LADD"; case LSUB: return "LSUB";
            case LMUL: return "LMUL"; case LDIV: return "LDIV";
            case LREM: return "LREM"; case LNEG: return "LNEG";
            case DADD: return "DADD"; case DSUB: return "DSUB";
            case DMUL: return "DMUL"; case DDIV: return "DDIV";
            case DNEG: return "DNEG";
            case IPOW: return "IPOW"; case LPOW: return "LPOW";
            case DPOW: return "DPOW";
            case DYNADD:  return "DYNADD";  case DYNSUB:  return "DYNSUB";
            case DYNMUL:  return "DYNMUL";  case DYNDIV:  return "DYNDIV";
            case DYNREM:  return "DYNREM";  case DYNNEG:  return "DYNNEG";
            case DYNPOW:  return "DYNPOW";
            case IAND: return "IAND"; case IOR:  return "IOR";
            case IXOR: return "IXOR"; case ISHL: return "ISHL";
            case ISHR: return "ISHR"; case IUSHR: return "IUSHR";
            case IBITNOT: return "IBITNOT";
            case LAND: return "LAND"; case LOR: return "LOR";
            case LXOR: return "LXOR"; case LSHL: return "LSHL";
            case LSHR: return "LSHR"; case LUSHR: return "LUSHR";
            case LBITNOT: return "LBITNOT";
            case IEQ: return "IEQ"; case INE: return "INE";
            case ILT: return "ILT"; case ILE: return "ILE";
            case IGT: return "IGT"; case IGE: return "IGE";
            case LEQ: return "LEQ"; case LNE: return "LNE";
            case LLT: return "LLT"; case LLE: return "LLE";
            case LGT: return "LGT"; case LGE: return "LGE";
            case DEQ: return "DEQ"; case DNE: return "DNE";
            case DLT: return "DLT"; case DLE: return "DLE";
            case DYNEQ: return "DYNEQ"; case DYNLT: return "DYNLT";
            case DYNLE: return "DYNLE"; case DYNIN: return "DYNIN";
            case JUMP:            return "JUMP";
            case JUMP_IF_TRUE:    return "JUMP_IF_TRUE";
            case JUMP_IF_FALSE:   return "JUMP_IF_FALSE";
            case JUMP_IF_NULL:    return "JUMP_IF_NULL";
            case JUMP_IF_NONNULL: return "JUMP_IF_NONNULL";
            case TABLE_SWITCH:    return "TABLE_SWITCH";
            case INVOKE:       return "INVOKE";
            case INVOKE_DYN:   return "INVOKE_DYN";
            case CLOSURE:      return "CLOSURE";
            case RETURN:       return "RETURN";
            case RETURN_VOID:  return "RETURN_VOID";
            case STORE_VAR:   return "STORE_VAR";
            case LOAD_FIELD:  return "LOAD_FIELD";
            case STORE_FIELD: return "STORE_FIELD";
            case CAPTURE:     return "CAPTURE";
            case NEW_LIST:    return "NEW_LIST";
            case NEW_MAP:     return "NEW_MAP";
            case NEW_TUPLE:   return "NEW_TUPLE";
            case NEW_RANGE:   return "NEW_RANGE";
            case NEW_OBJECT:  return "NEW_OBJECT";
            case GUARD_TYPE:    return "GUARD_TYPE";
            case GUARD_NONNULL: return "GUARD_NONNULL";
            case DEOPT:         return "DEOPT";
            case CAT:    return "CAT";
            case DYNCAT: return "DYNCAT";
            case NOT: return "NOT";
            case POS: return "POS"; case NEG: return "NEG";
            case INC: return "INC"; case DEC: return "DEC";
            case PUSH_TRUE:  return "PUSH_TRUE";
            case PUSH_FALSE: return "PUSH_FALSE";
            case PUSH_NULL:  return "PUSH_NULL";
            case NOP: return "NOP";
            default: return "UNKNOWN(" + op + ")";
        }
    }
}
