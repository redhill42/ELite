package org.operamasks.el.ir;

import java.util.*;

import static org.operamasks.el.ir.Opcode.*;
import static org.operamasks.el.ir.IRFormat.*;

/**
 * Generates a type-specialized copy of an IRFunction for a specific argument type signature.
 *
 * When the caller knows concrete types for the function's parameters,
 * this pass replaces dynamic operations (DYNADD, DYNSUB, etc.) with typed
 * operations (IADD, DADD, etc.) and inserts GUARD_TYPE instructions to
 * verify runtime types.
 *
 * The result is a function that runs faster when the type assumptions hold,
 * and falls back via GUARD_TYPE deoptimization when they don't.
 */
public class IRSpeclializer implements IRPass {

    private int[] argTypes;    // type IDs for each parameter (-1 = unknown)
    private int[] varTypes;    // tracked types for local variables
    private int[] typeStack;   // simulated stack: type ID for each slot
    private boolean[] explicitStack; // whether each stack slot has explicit type
    private int sp;            // stack pointer
    private Object[] pool;
    private IRFunction fn;     // current function being specialized

    /**
     * Create a specialized version of fn for the given argument types.
     *
     * @param fn the original function
     * @param argTypes type IDs (T_INT, T_DOUBLE, etc.) for each parameter,
     *                 or -1 for unknown
     * @return specialized function, or the original if no specialization possible
     */
    public static IRFunction specialize(IRFunction fn, int[] argTypes) {
        return new IRSpeclializer().transform(fn, argTypes);
    }

    @Override
    public IRFunction transform(IRFunction input) {
        return input; // use the parameterized version instead
    }

    IRFunction transform(IRFunction fn, int[] argTypes) {
        this.fn = fn;
        this.argTypes = argTypes;
        this.varTypes = new int[Math.max(argTypes.length, 32)];
        System.arraycopy(argTypes, 0, varTypes, 0, argTypes.length);
        for (int i = argTypes.length; i < varTypes.length; i++) varTypes[i] = -1;
        this.pool = fn.constantPool();
        this.typeStack = new int[64];
        this.explicitStack = new boolean[64];
        this.sp = 0;

        boolean changed = false;
        int[] oldCode = fn.code();
        IntList[] newBlocks = new IntList[fn.blockCount()];

        for (int b = 0; b < fn.blockCount(); b++) {
            int start = fn.blockStart(b);
            int end = (b + 1 < fn.blockCount()) ? fn.blockStart(b + 1) : oldCode.length;
            newBlocks[b] = specializeBlock(oldCode, start, end);
            if (newBlocks[b] != null) changed = true;
        }

        if (!changed) return fn;

        // Rebuild function with specialized code
        IntList merged = new IntList();
        int[] newOffsets = new int[fn.blockCount()];
        for (int b = 0; b < fn.blockCount(); b++) {
            newOffsets[b] = merged.size();
            int start = fn.blockStart(b);
            int end = (b + 1 < fn.blockCount()) ? fn.blockStart(b + 1) : oldCode.length;
            if (newBlocks[b] != null) merged.addAll(newBlocks[b].toArray());
            else for (int i = start; i < end; i++) merged.add(oldCode[i]);
        }
        return new IRFunction(fn.name(), fn.paramCount(),
                merged.toArray(), newOffsets, fn.constantPool(),
                fn.varNames(), fn.sourcePositions(), fn.paramFlags());
    }

    private IntList specializeBlock(int[] code, int start, int end) {
        sp = 0;
        boolean changed = false;
        IntList out = new IntList();
        InstructionView v = new InstructionView(code, start);
        while (v.inBounds() && v.offset() < end) {
            int op = v.opcode();

            // Track types on the simulated stack and copy to output
            switch (op) {
                case PUSH_CONST -> {
                    Object val = getConst(v.constPoolIndex());
                    pushType(typeOf(val), false); // constants are never explicit
                    copyInst(out, code, v);
                }
                case PUSH_VAR -> {
                    int varIdx = v.varIndex();
                    int t = varIdx < varTypes.length ? varTypes[varIdx] : -1;
                    boolean expl = fn.isExplicitParamType(varIdx);
                    pushType(t, expl);
                    copyInst(out, code, v);
                }
                case PUSH_TRUE, PUSH_FALSE -> { pushType(T_BOOL, false); copyInst(out, code, v); }
                case PUSH_NULL -> { pushType(-1, false); copyInst(out, code, v); }

                // Typed arithmetic: pop 2, push result type
                case IADD, ISUB, IMUL, IDIV, IREM -> { pop2(); pushType(T_INT, false); copyInst(out, code, v); }
                case LADD, LSUB, LMUL, LDIV, LREM -> { pop2(); pushType(T_LONG, false); copyInst(out, code, v); }
                case DADD, DSUB, DMUL, DDIV ->     { pop2(); pushType(T_DOUBLE, false); copyInst(out, code, v); }
                case INEG -> { pop1(); pushType(T_INT, false); copyInst(out, code, v); }
                case LNEG -> { pop1(); pushType(T_LONG, false); copyInst(out, code, v); }
                case DNEG -> { pop1(); pushType(T_DOUBLE, false); copyInst(out, code, v); }

                // Dynamic ops: check if we can specialize
                case DYNADD, DYNSUB, DYNMUL, DYNDIV, DYNREM -> {
                    boolean e2 = explicitAt(0), e1 = explicitAt(1);
                    int t2 = popType(), t1 = popType();
                    int wider = wider(t1, t2);
                    if (t1 >= 0 && t2 >= 0) {
                        int newOp = mapBinaryOp(op, wider);
                        if (newOp >= 0) {
                            // Emit guards for explicit-type operands
                            if (e1) emitGuard(out, t1);
                            if (e2) emitGuard(out, t2);
                            out.add(pack1(newOp, K_PRIM, wider));
                            changed = true;
                        } else {
                            copyInst(out, code, v);
                        }
                    } else {
                        copyInst(out, code, v);
                    }
                    pushType(wider >= 0 ? wider : wider(t1, t2), e1 || e2);
                }
                case DYNNEG -> {
                    boolean e = explicitAt(0);
                    int t = popType();
                    if (t >= 0) {
                        int newOp = mapNegOp(t);
                        if (newOp >= 0) {
                            if (e) emitGuard(out, t);
                            out.add(pack1(newOp, K_PRIM, t));
                            changed = true;
                        } else {
                            copyInst(out, code, v);
                        }
                    } else {
                        copyInst(out, code, v);
                    }
                    pushType(t, e);
                }

                // Comparisons
                case IEQ, INE, ILT, ILE, IGT, IGE -> { pop2(); pushType(T_BOOL, false); copyInst(out, code, v); }
                case LEQ, LNE, LLT, LLE, LGT, LGE -> { pop2(); pushType(T_BOOL, false); copyInst(out, code, v); }
                case DEQ, DNE, DLT, DLE, DGT, DGE -> { pop2(); pushType(T_BOOL, false); copyInst(out, code, v); }
                case DYNEQ, DYNLT, DYNLE -> {
                    boolean e2 = explicitAt(0), e1 = explicitAt(1);
                    int t2 = popType(), t1 = popType();
                    int newOp = specializeCmpOp(op, t1, t2);
                    if (newOp >= 0) {
                        if (e1) emitGuard(out, t1);
                        if (e2) emitGuard(out, t2);
                        out.add(pack1(newOp, K_BOOL, 0));
                        changed = true;
                    } else {
                        copyInst(out, code, v);
                    }
                    pushType(T_BOOL, e1 || e2);
                }

                // Ops that don't need type-stack tracking: copy as-is
                case INVOKE_DIRECT, INVOKE_DYN, INVOKE, INVOKE_TAIL,
                     JUMP, JUMP_IF_TRUE, JUMP_IF_FALSE, JUMP_IF_NULL, JUMP_IF_NONNULL,
                     LOAD_PROPERTY, STORE_PROPERTY,
                     NEW_LIST, NEW_MAP, NEW_TUPLE, NEW_RANGE,
                     GET_ITER, ITER_NEXT, ITER_DONE,
                     IAND, IOR, IXOR, ISHL, ISHR, IUSHR, IBITNOT,
                     LAND, LOR, LXOR, LSHL, LSHR, LUSHR, LBITNOT,
                     DYNPOW, DYNCAT, DYNIN,
                     CAT, CONTAINS, IPOW, LPOW, DPOW,
                     GUARD_TYPE, NOP -> copyInst(out, code, v);

                case NOT -> { pop1(); pushType(T_BOOL, false); copyInst(out, code, v); }
                case DUP -> { boolean e = peekExplicit(); pushType(peekType(), e); copyInst(out, code, v); }
                case POP -> { pop1(); copyInst(out, code, v); }
                case POP_N -> { for (int i=0; i<v.payload(); i++) pop1(); copyInst(out, code, v); }

                case RETURN, RETURN_VOID -> copyInst(out, code, v);
                case STORE_VAR -> {
                    int t = peekType();
                    boolean e = peekExplicit();
                    int varIdx = v.payload() & 0xFFFF;
                    if (varIdx < varTypes.length) varTypes[varIdx] = t;
                    pop1(); pushType(t, e);
                    copyInst(out, code, v);
                }
                case STORE_GLOBAL -> {
                    int t = peekType(); boolean e = peekExplicit();
                    pop1(); pushType(t, e);
                    copyInst(out, code, v);
                }
                default -> copyInst(out, code, v);
            }
            v.advance();
        }
        return changed ? out : null;
    }

    /** Emit a strict (throw-on-fail) guard for the given type. */
    private static void emitGuard(IntList out, int typeId) {
        out.add(IRFormat.pack2h(GUARD_TYPE, K_GUARDED, typeId));
        out.add(Opcode.STRICT_GUARD);
    }

    /** Copy a variable-length instruction from source to output. */
    private static void copyInst(IntList out, int[] code, InstructionView v) {
        int w = v.totalWords();
        for (int i = 0; i < w; i++) out.add(code[v.offset() + i]);
    }

    private static int mapNegOp(int t) {
        return switch (t) {
            case T_INT -> INEG; case T_LONG -> LNEG;
            case T_DOUBLE -> DNEG; default -> -1;
        };
    }

    // ── Type stack helpers ──

    private void pushType(int t, boolean explicit) {
        if (sp >= typeStack.length) {
            typeStack = Arrays.copyOf(typeStack, typeStack.length * 2);
            explicitStack = Arrays.copyOf(explicitStack, explicitStack.length * 2);
        }
        typeStack[sp] = t;
        explicitStack[sp] = explicit;
        sp++;
    }
    private int popType() { return sp > 0 ? typeStack[--sp] : -1; }
    private int peekType() { return sp > 0 ? typeStack[sp - 1] : -1; }
    /** Get explicit flag at stack offset (0=top, 1=below top), without popping. */
    private boolean explicitAt(int offset) {
        int idx = sp - 1 - offset;
        return idx >= 0 && idx < explicitStack.length && explicitStack[idx];
    }
    private boolean peekExplicit() { return explicitAt(0); }
    private void pop1() { popType(); }
    private void pop2() { popType(); popType(); }

    private static int typeOf(Object val) {
        if (val instanceof Integer || val instanceof Short || val instanceof Byte) return T_INT;
        if (val instanceof Long) return T_LONG;
        if (val instanceof Double || val instanceof Float) return T_DOUBLE;
        if (val instanceof Boolean) return T_BOOL;
        if (val instanceof String) return T_STRING;
        return -1;
    }

    private Object getConst(int idx) {
        if (idx >= 0 && idx < pool.length) return pool[idx];
        return null;
    }

    // ── Specialization helpers ──

    /** Replace DYNADD/DYNSUB/etc. with typed variant if both operands have known types. */
    private int specializeBinary(int[] code, int offset, int dynOp, int t1, int t2) {
        if (t1 < 0 || t2 < 0) return -1;

        int wider = wider(t1, t2);
        int newOp = mapBinaryOp(dynOp, wider);
        if (newOp < 0) return -1;

        code[offset] = pack1(newOp, K_PRIM, wider);
        return wider;
    }

    private void replaceDynNeg(int[] code, int offset, int t) {
        int newOp = switch (t) {
            case T_INT -> INEG; case T_LONG -> LNEG;
            case T_DOUBLE -> DNEG; default -> -1;
        };
        if (newOp >= 0) code[offset] = pack1(newOp, K_PRIM, t);
    }

    private static int mapBinaryOp(int dynOp, int typeId) {
        return switch (dynOp) {
            case DYNADD -> switch (typeId) {
                case T_INT -> IADD; case T_LONG -> LADD; case T_DOUBLE -> DADD; default -> -1; };
            case DYNSUB -> switch (typeId) {
                case T_INT -> ISUB; case T_LONG -> LSUB; case T_DOUBLE -> DSUB; default -> -1; };
            case DYNMUL -> switch (typeId) {
                case T_INT -> IMUL; case T_LONG -> LMUL; case T_DOUBLE -> DMUL; default -> -1; };
            case DYNDIV -> switch (typeId) {
                case T_INT -> IDIV; case T_LONG -> LDIV; case T_DOUBLE -> DDIV; default -> -1; };
            case DYNREM -> switch (typeId) {
                case T_INT -> IREM; case T_LONG -> LREM; default -> -1; };
            default -> -1;
        };
    }

    /** Map DYN comparison to typed comparison based on operand types. */
    private static int specializeCmpOp(int dynOp, int t1, int t2) {
        if (t1 < 0 || t2 < 0) return -1;
        int wider = wider(t1, t2);
        return switch (dynOp) {
            case Opcode.DYNEQ -> switch (wider) {
                case T_INT -> Opcode.IEQ; case T_LONG -> Opcode.LEQ;
                case T_DOUBLE -> Opcode.DEQ; default -> -1; };
            case Opcode.DYNLT -> switch (wider) {
                case T_INT -> Opcode.ILT; case T_LONG -> Opcode.LLT;
                case T_DOUBLE -> Opcode.DLT; default -> -1; };
            case Opcode.DYNLE -> switch (wider) {
                case T_INT -> Opcode.ILE; case T_LONG -> Opcode.LLE;
                case T_DOUBLE -> Opcode.DLE; default -> -1; };
            default -> -1;
        };
    }

    private static int wider(int a, int b) {
        if (a == T_DOUBLE || b == T_DOUBLE) return T_DOUBLE;
        if (a == T_LONG || b == T_LONG) return T_LONG;
        return a >= 0 ? a : b;
    }
}
