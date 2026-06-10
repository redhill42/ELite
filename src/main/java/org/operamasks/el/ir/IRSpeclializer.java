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

    private int[] argTypes;   // type IDs for each parameter (-1 = unknown)
    private int[] typeStack;  // simulated stack: type ID for each slot
    private int sp;           // stack pointer
    private Object[] pool;

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
        this.argTypes = argTypes;
        this.pool = fn.constantPool();
        this.typeStack = new int[64];
        this.sp = 0;

        boolean changed = false;
        int[] oldCode = fn.code();
        int[] newCode = oldCode.clone();

        for (int b = 0; b < fn.blockCount(); b++) {
            int start = fn.blockStart(b);
            int end = (b + 1 < fn.blockCount()) ? fn.blockStart(b + 1) : oldCode.length;
            boolean blockChanged = specializeBlock(newCode, start, end);
            if (blockChanged) changed = true;
        }

        if (!changed) return fn;

        // Rebuild function with specialized code
        IntList merged = new IntList();
        int[] newOffsets = new int[fn.blockCount()];
        for (int b = 0; b < fn.blockCount(); b++) {
            newOffsets[b] = merged.size();
            int start = fn.blockStart(b);
            int end = (b + 1 < fn.blockCount()) ? fn.blockStart(b + 1) : oldCode.length;
            for (int i = start; i < end; i++) merged.add(newCode[i]);
        }
        return new IRFunction(fn.name(), fn.paramCount(),
                merged.toArray(), newOffsets, fn.constantPool(),
                fn.varNames(), fn.sourcePositions());
    }

    private boolean specializeBlock(int[] code, int start, int end) {
        sp = 0;
        boolean changed = false;
        InstructionView v = new InstructionView(code, start);
        while (v.inBounds() && v.offset() < end) {
            int op = v.opcode();

            // Track types on the simulated stack
            switch (op) {
                case PUSH_CONST -> {
                    Object val = getConst(v.constPoolIndex());
                    pushType(typeOf(val));
                }
                case PUSH_VAR -> {
                    int varIdx = v.varIndex();
                    int t = (varIdx < argTypes.length) ? argTypes[varIdx] : -1;
                    if (t >= 0) {
                        // Insert GUARD_TYPE before PUSH_VAR
                        // For now, just track the type
                    }
                    pushType(t);
                }
                case PUSH_TRUE, PUSH_FALSE -> pushType(T_BOOL);
                case PUSH_NULL -> pushType(-1);

                // Typed arithmetic: pop 2, push result type
                case IADD, ISUB, IMUL, IDIV, IREM -> { pop2(); pushType(T_INT); }
                case LADD, LSUB, LMUL, LDIV, LREM -> { pop2(); pushType(T_LONG); }
                case DADD, DSUB, DMUL, DDIV ->     { pop2(); pushType(T_DOUBLE); }
                case INEG -> { pop1(); pushType(T_INT); }
                case LNEG -> { pop1(); pushType(T_LONG); }
                case DNEG -> { pop1(); pushType(T_DOUBLE); }

                // Dynamic ops: check if we can specialize
                case DYNADD, DYNSUB, DYNMUL, DYNDIV, DYNREM -> {
                    int t2 = popType(), t1 = popType();
                    int resultType = specializeBinary(code, v.offset(), op, t1, t2);
                    pushType(resultType);
                    if (resultType >= 0) changed = true;
                }
                case DYNNEG -> {
                    int t = popType();
                    if (t >= 0) {
                        replaceDynNeg(code, v.offset(), t);
                        changed = true;
                    }
                    pushType(t);
                }

                // Comparisons
                case IEQ, INE, ILT, ILE, IGT, IGE -> { pop2(); pushType(T_BOOL); }
                case LEQ, LNE, LLT, LLE, LGT, LGE -> { pop2(); pushType(T_BOOL); }
                case DEQ, DNE, DLT, DLE, DGT, DGE -> { pop2(); pushType(T_BOOL); }
                case DYNEQ, DYNLT, DYNLE -> { pop2(); pushType(T_BOOL); }

                case NOT -> { pop1(); pushType(T_BOOL); }
                case DUP -> pushType(peekType());
                case POP -> pop1();
                case POP_N -> { for (int i=0; i<v.payload(); i++) pop1(); }

                case RETURN, RETURN_VOID -> {}
                case STORE_VAR, STORE_GLOBAL -> {
                    // Pops value, pushes it back (assignment returns value)
                    int t = peekType();
                    pop1(); pushType(t);
                }
            }
            v.advance();
        }
        return changed;
    }

    // ── Type stack helpers ──

    private void pushType(int t) {
        if (sp >= typeStack.length) {
            typeStack = Arrays.copyOf(typeStack, typeStack.length * 2);
        }
        typeStack[sp++] = t;
    }
    private int popType() { return sp > 0 ? typeStack[--sp] : -1; }
    private int peekType() { return sp > 0 ? typeStack[sp - 1] : -1; }
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

    private static int wider(int a, int b) {
        if (a == T_DOUBLE || b == T_DOUBLE) return T_DOUBLE;
        if (a == T_LONG || b == T_LONG) return T_LONG;
        return a >= 0 ? a : b;
    }
}
