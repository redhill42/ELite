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
    private int[] varSrcStack; // which variable each stack slot came from (-1 = not from var)
    private boolean[] varGuarded; // per-variable: has been type-guarded since last store
    private int sp;            // stack pointer
    private Object[] pool;
    private IRFunction fn;     // current function being specialized

    // Placeholder for block IDs that will be patched after block list is built
    private static final int DEOPT_PLACEHOLDER = 0xFFFE;

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
        this.varSrcStack = new int[64];
        this.varGuarded = new boolean[Math.max(argTypes.length, 32)];
        this.sp = 0;

        boolean changed = false;
        int[] oldCode = fn.code();
        int origBlockCount = fn.blockCount();

        // Build expanded block list (deopt split may create new blocks)
        List<IntList> allBlocks = new ArrayList<>();
        int[] oldToNew = new int[origBlockCount]; // old block ID → new first index
        int[] extraBlocks = new int[origBlockCount]; // 0=no split, 2=split into 3

        for (int b = 0; b < origBlockCount; b++) {
            int start = fn.blockStart(b);
            int end = (b + 1 < origBlockCount) ? fn.blockStart(b + 1) : oldCode.length;
            oldToNew[b] = allBlocks.size();

            List<IntList> deoptSplit = tryDeoptSplit(oldCode, start, end);
            if (deoptSplit != null) {
                allBlocks.addAll(deoptSplit);
                extraBlocks[b] = 2;
                changed = true;
            } else {
                IntList specialized = specializeBlockSimple(oldCode, start, end);
                if (specialized != null) {
                    allBlocks.add(specialized);
                    changed = true;
                } else {
                    IntList orig = new IntList();
                    for (int i = start; i < end; i++) orig.add(oldCode[i]);
                    allBlocks.add(orig);
                }
            }
        }

        if (!changed) return fn;

        // Patch placeholders and remap JUMP targets for expanded blocks
        for (int b = 0; b < origBlockCount; b++) {
            if (extraBlocks[b] > 0) {
                int prefixIdx = oldToNew[b];
                int deoptIdx = prefixIdx + 1;
                int suffixIdx = prefixIdx + 2;
                patchPlaceholders(allBlocks.get(prefixIdx), suffixIdx, deoptIdx);
                patchPlaceholders(allBlocks.get(deoptIdx), suffixIdx, deoptIdx);
            }
        }
        remapAllJumps(allBlocks, oldToNew, origBlockCount);

        // Rebuild function
        IntList merged = new IntList();
        int[] newOffsets = new int[allBlocks.size()];
        for (int i = 0; i < allBlocks.size(); i++) {
            newOffsets[i] = merged.size();
            merged.addAll(allBlocks.get(i).toArray());
        }
        return new IRFunction(fn.name(), fn.paramCount(), fn.captureCount(),
                merged.toArray(), newOffsets, fn.constantPool(),
                fn.varNames(), fn.sourcePositions(), fn.paramFlags());
    }

    /** Remap all JUMP-type instruction targets from old to new block IDs. */
    private static void remapAllJumps(List<IntList> blocks, int[] oldToNew, int oldCount) {
        for (IntList block : blocks) {
            for (int i = 0; i < block.size(); i++) {
                int word = block.get(i);
                int op = IRFormat.opcode(word);
                if (Opcode.isJump(op) || op == INVOKE_TAIL) {
                    int oc = IRFormat.opCount(word);
                    int oldTarget = oc == 0 ? IRFormat.payload(word) : block.get(i + 1);
                    if (oldTarget < oldCount) {
                        int newTarget = oldToNew[oldTarget];
                        if (oc == 0) {
                            block.set(i, IRFormat.pack1(op, K_NONE, newTarget & 0xFFFF));
                        } else {
                            block.set(i + 1, newTarget & 0xFFFF);
                        }
                    }
                }
            }
        }
    }

    /**
     * Try to split the block for deopt. Scans for the last dynamic op with
     * inferred-type operands. If found as the last non-terminator, builds
     * [prefix, deopt, suffix] blocks. Returns null if no deopt possible.
     */
    private List<IntList> tryDeoptSplit(int[] code, int start, int end) {
        // Check if any dynamic op in this block has inferred types
        boolean hasInferred = false;
        InstructionView firstDyn = null;

        sp = 0;
        InstructionView v = new InstructionView(code, start);
        while (v.inBounds() && v.offset() < end) {
            int op = v.opcode();
            // Check types BEFORE simulation (stack has operands before op executes)
            if (op == DYNADD || op == DYNSUB || op == DYNMUL
                || op == DYNDIV || op == DYNREM || op == DYNNEG
                || op == DYNEQ || op == DYNLT || op == DYNLE) {
                int argc = (op == DYNNEG) ? 1 : 2;
                boolean allInferred = true, anyTyped = false;
                for (int i = 0; i < argc; i++) {
                    if (sp <= i) { allInferred = false; break; }
                    if (explicitAt(i)) allInferred = false;
                    if (typeStack[sp - 1 - i] >= 0) anyTyped = true;
                }
                boolean allKnown = true;
                for (int i = 0; i < argc; i++) {
                    if (sp <= i || typeStack[sp - 1 - i] < 0) { allKnown = false; break; }
                }
                if (anyTyped && allInferred && allKnown) {
                    hasInferred = true;
                    if (firstDyn == null) firstDyn = v.dup();
                }
            }
            // Now simulate the op's effect on the stack for subsequent ops
            simulateStackForType(code, v, true);
            v.advance();
        }

        if (!hasInferred || firstDyn == null) return null;

        // Build prefix: copy all instructions, replacing inferred dynamic ops
        // with guards + typed op. Build deopt: copy all instructions as-is.
        sp = 0;
        IntList prefix = new IntList();
        IntList deopt = new IntList();
        v = new InstructionView(code, start);
        while (v.inBounds() && v.offset() < end) {
            int op = v.opcode();
            // Skip terminator — goes to suffix
            if (op == RETURN || op == RETURN_VOID || Opcode.isJump(op)) break;

            // Copy original to deopt block
            copyInst(deopt, code, v);

            // Check if this op should be replaced in the typed prefix
            boolean replaced = false;
            if (op == DYNADD || op == DYNSUB || op == DYNMUL
                || op == DYNDIV || op == DYNREM || op == DYNNEG) {
                int argc = (op == DYNNEG) ? 1 : 2;
                boolean allInferred = true;
                int resultType = -1, newOp = -1;
                for (int i = 0; i < argc; i++) {
                    if (sp <= i || explicitAt(i)) { allInferred = false; break; }
                }
                boolean allKnown = true;
                for (int i = 0; i < argc; i++) {
                    if (sp <= i || typeStack[sp - 1 - i] < 0) { allKnown = false; break; }
                }
                if (allInferred && allKnown && sp >= argc) {
                    if (argc == 2) {
                        int t2 = typeStack[sp-1], t1 = typeStack[sp-2];
                        int wider = wider(t1, t2);
                        newOp = mapBinaryOp(op, wider);
                        resultType = wider;
                    } else {
                        int t = typeStack[sp-1];
                        newOp = mapNegOp(t);
                        resultType = t;
                    }
                    if (newOp >= 0) {
                        emitGuardDeopt(prefix, resultType, DEOPT_PLACEHOLDER);
                        prefix.add(pack1(newOp, K_PRIM, resultType));
                        replaced = true;
                    }
                }
            }
            if (!replaced) copyInst(prefix, code, v);

            simulateStackForType(code, v, true);
            v.advance();
        }

        // Add JUMP to suffix in both prefix and deopt
        prefix.add(pack1(JUMP, K_NONE, DEOPT_PLACEHOLDER));
        deopt.add(pack1(JUMP, K_NONE, DEOPT_PLACEHOLDER));

        // Build suffix: just the terminator
        IntList suffix = new IntList();
        while (v.inBounds() && v.offset() < end) {
            copyInst(suffix, code, v);
            v.advance();
        }

        return List.of(prefix, deopt, suffix);
    }

    /** Simulate stack effects for type tracking without emitting code. */
    private void simulateStackForType(int[] code, InstructionView v, boolean trackVars) {
        int op = v.opcode();
        switch (op) {
            case PUSH_CONST -> {
                Object val = getConst(v.constPoolIndex());
                pushType(typeOf(val), false, -1);
            }
            case PUSH_VAR -> {
                int varIdx = v.varIndex();
                int t = varIdx < varTypes.length ? varTypes[varIdx] : -1;
                boolean expl = fn.isExplicitParamType(varIdx);
                pushType(t, expl, varIdx);
            }
            case PUSH_TRUE, PUSH_FALSE -> pushType(T_BOOL, false, -1);
            case PUSH_NULL -> pushType(-1, false, -1);
            case IADD, ISUB, IMUL, IDIV, IREM -> { pop2(); pushType(T_INT, false, -1); }
            case LADD, LSUB, LMUL, LDIV, LREM -> { pop2(); pushType(T_LONG, false, -1); }
            case DADD, DSUB, DMUL, DDIV -> { pop2(); pushType(T_DOUBLE, false, -1); }
            case INEG -> { pop1(); pushType(T_INT, false, -1); }
            case LNEG -> { pop1(); pushType(T_LONG, false, -1); }
            case DNEG -> { pop1(); pushType(T_DOUBLE, false, -1); }
            case DYNADD, DYNSUB, DYNMUL, DYNDIV, DYNREM -> {
                int t2 = popType(), t1 = popType();
                pushType(wider(t1, t2) >= 0 ? wider(t1, t2) : t1, false, -1);
            }
            case DYNNEG -> { int t = popType(); pushType(t >= 0 ? t : -1, false, -1); }
            case IEQ, INE, ILT, ILE, IGT, IGE -> { pop2(); pushType(T_BOOL, false, -1); }
            case LEQ, LNE, LLT, LLE, LGT, LGE -> { pop2(); pushType(T_BOOL, false, -1); }
            case DEQ, DNE, DLT, DLE, DGT, DGE -> { pop2(); pushType(T_BOOL, false, -1); }
            case DYNEQ, DYNLT, DYNLE -> { pop2(); pushType(T_BOOL, false, -1); }
            case DUP -> { int t = peekType(); boolean e = peekExplicit(); pushType(t, e, varSrcAt(0)); }
            case POP -> pop1();
            case POP_N -> { for (int i=0; i<v.payload(); i++) pop1(); }
            case STORE_VAR -> {
                int t = peekType(), vi = v.payload() & 0xFFFF;
                if (vi < varTypes.length) varTypes[vi] = t;
                pop1(); pushType(t, peekExplicit(), -1);
            }
            case NOT -> { pop1(); pushType(T_BOOL, false, -1); }
            case RETURN, RETURN_VOID, JUMP, JUMP_IF_TRUE, JUMP_IF_FALSE,
                 JUMP_IF_NULL, JUMP_IF_NONNULL, NOP -> {} // no stack effect
            default -> {} // conservative: no stack change
        }
    }

    private IntList specializeBlockSimple(int[] code, int start, int end) {
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
                    pushType(t, expl, varIdx);
                    copyInst(out, code, v);
                }
                case PUSH_TRUE, PUSH_FALSE -> { pushType(T_BOOL, false, -1); copyInst(out, code, v); }
                case PUSH_NULL -> { pushType(-1, false, -1); copyInst(out, code, v); }

                // Typed arithmetic: pop 2, push result type (never from a variable)
                case IADD, ISUB, IMUL, IDIV, IREM -> { pop2(); pushType(T_INT, false, -1); copyInst(out, code, v); }
                case LADD, LSUB, LMUL, LDIV, LREM -> { pop2(); pushType(T_LONG, false, -1); copyInst(out, code, v); }
                case DADD, DSUB, DMUL, DDIV ->     { pop2(); pushType(T_DOUBLE, false, -1); copyInst(out, code, v); }
                case INEG -> { pop1(); pushType(T_INT, false, -1); copyInst(out, code, v); }
                case LNEG -> { pop1(); pushType(T_LONG, false, -1); copyInst(out, code, v); }
                case DNEG -> { pop1(); pushType(T_DOUBLE, false, -1); copyInst(out, code, v); }

                // Dynamic ops: check if we can specialize
                case DYNADD, DYNSUB, DYNMUL, DYNDIV, DYNREM -> {
                    int s2 = varSrcAt(0), s1 = varSrcAt(1);
                    boolean e2 = explicitAt(0), e1 = explicitAt(1);
                    int t2 = popType(), t1 = popType();
                    int wider = wider(t1, t2);
                    if (t1 >= 0 && t2 >= 0) {
                        int newOp = mapBinaryOp(op, wider);
                        if (newOp >= 0) {
                            // Guard operands that came from variables with known types
                            if (e1) emitGuardIfNeeded(out, t1, s1);
                            else if (t1 >= 0 && s1 >= 0) emitGuard(out, t1);
                            if (e2) emitGuardIfNeeded(out, t2, s2);
                            else if (t2 >= 0 && s2 >= 0) emitGuard(out, t2);
                            out.add(pack1(newOp, K_PRIM, wider));
                            changed = true;
                        } else {
                            copyInst(out, code, v);
                        }
                    } else {
                        copyInst(out, code, v);
                    }
                    pushType(wider >= 0 ? wider : wider(t1, t2), false, -1);
                }
                case DYNNEG -> {
                    int s = varSrcAt(0);
                    boolean e = explicitAt(0);
                    int t = popType();
                    if (t >= 0 && s >= 0) { // only guard if from a variable
                        int newOp = mapNegOp(t);
                        if (newOp >= 0) {
                            if (e) emitGuardIfNeeded(out, t, s);
                            else emitGuard(out, t);
                            out.add(pack1(newOp, K_PRIM, t));
                            changed = true;
                        } else {
                            copyInst(out, code, v);
                        }
                    } else {
                        copyInst(out, code, v);
                    }
                    pushType(t, false, -1);
                }

                // Comparisons (results are booleans, not from variables)
                case IEQ, INE, ILT, ILE, IGT, IGE -> { pop2(); pushType(T_BOOL, false, -1); copyInst(out, code, v); }
                case LEQ, LNE, LLT, LLE, LGT, LGE -> { pop2(); pushType(T_BOOL, false, -1); copyInst(out, code, v); }
                case DEQ, DNE, DLT, DLE, DGT, DGE -> { pop2(); pushType(T_BOOL, false, -1); copyInst(out, code, v); }
                case DYNEQ, DYNLT, DYNLE -> {
                    int s2 = varSrcAt(0), s1 = varSrcAt(1);
                    boolean e2 = explicitAt(0), e1 = explicitAt(1);
                    int t2 = popType(), t1 = popType();
                    int newOp = specializeCmpOp(op, t1, t2);
                    if (newOp >= 0) {
                        if (e1) emitGuardIfNeeded(out, t1, s1);
                        else if (t1 >= 0 && s1 >= 0) emitGuard(out, t1);
                        if (e2) emitGuardIfNeeded(out, t2, s2);
                        else if (t2 >= 0 && s2 >= 0) emitGuard(out, t2);
                        out.add(pack1(newOp, K_BOOL, 0));
                        changed = true;
                    } else {
                        copyInst(out, code, v);
                    }
                    pushType(T_BOOL, false, -1); // boolean result is never explicit
                }

                // Function calls: clear all guarded state (calls can modify any variable)
                case INVOKE_DIRECT, INVOKE_DYN, INVOKE, INVOKE_TAIL -> {
                    Arrays.fill(varGuarded, false);
                    copyInst(out, code, v);
                }
                // Ops that don't need type-stack tracking: copy as-is
                case JUMP, JUMP_IF_TRUE, JUMP_IF_FALSE, JUMP_IF_NULL, JUMP_IF_NONNULL,
                     LOAD_PROPERTY, STORE_PROPERTY,
                     NEW_LIST, NEW_MAP, NEW_TUPLE, NEW_RANGE,
                     GET_ITER, ITER_NEXT, ITER_DONE,
                     IAND, IOR, IXOR, ISHL, ISHR, IUSHR, IBITNOT,
                     LAND, LOR, LXOR, LSHL, LSHR, LUSHR, LBITNOT,
                     DYNPOW, DYNCAT, DYNIN,
                     CAT, IPOW, LPOW, DPOW,
                     GUARD_TYPE, NOP -> copyInst(out, code, v);

                case NOT -> { pop1(); pushType(T_BOOL, false, -1); copyInst(out, code, v); }
                case DUP -> {
                    boolean e = peekExplicit(); int s = varSrcAt(0);
                    pushType(peekType(), e, s); copyInst(out, code, v);
                }
                case POP -> { pop1(); copyInst(out, code, v); }
                case POP_N -> { for (int i=0; i<v.payload(); i++) pop1(); copyInst(out, code, v); }

                case RETURN, RETURN_VOID -> copyInst(out, code, v);
                case STORE_VAR -> {
                    int t = peekType(); boolean e = peekExplicit(); int s = varSrcAt(0);
                    int varIdx = v.payload() & 0xFFFF;
                    if (varIdx < varTypes.length) varTypes[varIdx] = t;
                    if (varIdx < varGuarded.length) varGuarded[varIdx] = false; // invalidate guard
                    pop1(); pushType(t, e, s);
                    copyInst(out, code, v);
                }
                case STORE_GLOBAL -> {
                    int t = peekType(); boolean e = peekExplicit();
                    pop1(); pushType(t, e, -1);
                    copyInst(out, code, v);
                }
                default -> copyInst(out, code, v);
            }
            v.advance();
        }
        return changed ? out : null;
    }

    /** Get var source at stack offset (0=top, 1=below top), without popping. */
    private int varSrcAt(int offset) {
        int idx = sp - 1 - offset;
        return idx >= 0 && idx < varSrcStack.length ? varSrcStack[idx] : -1;
    }

    /** Emit a guard only if the value needs it (not already guarded). */
    private void emitGuardIfNeeded(IntList out, int typeId, int varSrc) {
        if (varSrc >= 0 && varSrc < varGuarded.length && varGuarded[varSrc]) {
            return; // already guarded, skip
        }
        emitGuard(out, typeId);
        if (varSrc >= 0) {
            if (varSrc >= varGuarded.length) {
                varGuarded = Arrays.copyOf(varGuarded, varSrc + 16);
            }
            varGuarded[varSrc] = true;
        }
    }

    /** Emit a strict (throw-on-fail) guard for the given type. */
    private static void emitGuard(IntList out, int typeId) {
        out.add(IRFormat.pack2h(GUARD_TYPE, K_GUARDED, typeId));
        out.add(Opcode.STRICT_GUARD);
    }

    /** Emit a deopt guard: check type, jump to deoptBlockId on mismatch. */
    private static void emitGuardDeopt(IntList out, int typeId, int deoptBlockId) {
        out.add(IRFormat.pack2h(GUARD_TYPE, K_GUARDED, typeId));
        out.add(deoptBlockId);
    }

    /** Patch all DEOPT_PLACEHOLDER values in a block to the real block ID. */
    private static void patchPlaceholders(IntList block, int suffixBlockId, int deoptBlockId) {
        for (int i = block.size() - 1; i >= 0; i--) {
            int word = block.get(i);
            int op = IRFormat.opcode(word);
            if (op == JUMP && IRFormat.payload(word) == DEOPT_PLACEHOLDER) {
                block.set(i, pack1(JUMP, K_NONE, suffixBlockId & 0xFFFF));
            } else if (op == GUARD_TYPE) {
                // GUARD_TYPE is 2 words: header + deopt target
                if (i + 1 < block.size() && block.get(i + 1) == DEOPT_PLACEHOLDER) {
                    block.set(i + 1, deoptBlockId);
                }
            }
        }
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

    private void pushType(int t, boolean explicit) { pushType(t, explicit, -1); }
    private void pushType(int t, boolean explicit, int varSrc) {
        if (sp >= typeStack.length) {
            typeStack = Arrays.copyOf(typeStack, typeStack.length * 2);
            explicitStack = Arrays.copyOf(explicitStack, explicitStack.length * 2);
            varSrcStack = Arrays.copyOf(varSrcStack, varSrcStack.length * 2);
        }
        typeStack[sp] = t;
        explicitStack[sp] = explicit;
        varSrcStack[sp] = varSrc;
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
