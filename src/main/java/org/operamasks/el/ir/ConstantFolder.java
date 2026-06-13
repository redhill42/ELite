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

import java.util.ArrayList;
import java.util.List;

import static org.operamasks.el.ir.Opcode.*;
import static org.operamasks.el.ir.IRFormat.*;

/**
 * Constant folding optimization pass.
 * <p>
 * Scans the instruction stream for sequences of constant loads followed by
 * pure typed operations and replaces them with the pre-computed result.
 *
 * <p>Examples:
 * <pre>
 *   PUSH_CONST(3), PUSH_CONST(4), IADD  →  PUSH_CONST(7), NOP, NOP
 *   PUSH_CONST(10), PUSH_CONST(0), IMUL →  PUSH_CONST(0), NOP, NOP
 * </pre>
 */
public class ConstantFolder implements IRPass {

    private Object[] pool;
    private List<Object> addedConstants;

    @Override
    public IRFunction transform(IRFunction input) {
        this.pool = input.constantPool();
        this.addedConstants = null;
        boolean changed = false;

        int[] oldCode = input.code();
        int[] blockOffsets = input.blockOffsets();
        int[][] newBlocks = new int[input.blockCount()][];

        for (int b = 0; b < input.blockCount(); b++) {
            int blockStart = blockOffsets[b];
            int blockEnd = (b + 1 < blockOffsets.length) ?
                    blockOffsets[b + 1] : oldCode.length;
            int blockLen = blockEnd - blockStart;

            if (blockLen == 0) {
                newBlocks[b] = new int[0];
                continue;
            }

            int[] blockCode = new int[blockLen];
            System.arraycopy(oldCode, blockStart, blockCode, 0, blockLen);

            int[] folded = foldBlock(blockCode);
            if (folded != blockCode)
                changed = true;
            newBlocks[b] = folded;
        }

        if (!changed)
            return input;

        // Build extended constant pool
        Object[] newPool = input.constantPool();
        if (addedConstants != null && !addedConstants.isEmpty()) {
            newPool = new Object[newPool.length + addedConstants.size()];
            System.arraycopy(input.constantPool(), 0, newPool, 0,
                    input.constantPool().length);
            for (int i = 0; i < addedConstants.size(); i++) {
                newPool[input.constantPool().length + i] =
                        addedConstants.get(i);
            }
        }

        // Rebuild merged code array
        IntList merged = new IntList();
        int[] newOffsets = new int[newBlocks.length];
        for (int i = 0; i < newBlocks.length; i++) {
            newOffsets[i] = merged.size();
            merged.addAll(newBlocks[i]);
        }

        return new IRFunction(input.name(), input.paramCount(),
                merged.toArray(), newOffsets, newPool, input.varNames(),
                input.sourcePositions(), input.paramFlags(),
                input.defaultValues());
    }

    private int[] foldBlock(int[] code) {
        // Iterate until no more folds (handles cascading: 3*3+5 → 9+5 → 14)
        boolean changed;
        do {
            changed = foldOnePass(code);
            if (changed)
                code = compactNops(code);
        } while (changed);
        return code;
    }

    /**
     * Single pass: scan and fold adjacent PUSH_CONST pairs. Returns true if
     * any fold occurred.
     */
    private boolean foldOnePass(int[] code) {
        boolean anyFolded = false;
        InstructionView v = new InstructionView(code, 0);
        while (v.inBounds()) {
            int startOffset = v.offset();
            if (v.opcode() == PUSH_CONST) {
                InstructionView c1 = v.dup();
                int aPoolIdx = c1.constPoolIndex();
                c1.advance();
                if (c1.inBounds() && c1.opcode() == PUSH_CONST) {
                    InstructionView c2 = c1.dup();
                    int bPoolIdx = c2.constPoolIndex();
                    c2.advance();
                    if (c2.inBounds()) {
                        int op = c2.opcode();
                        Object folded = tryFold(op, pool, aPoolIdx, bPoolIdx);
                        if (folded != null) {
                            int newPoolIdx = ensureInPool(folded);
                            int kind = poolKind(folded);
                            code[startOffset] = pack1(PUSH_CONST, kind,
                                    newPoolIdx & 0xFFFF);
                            int c1W = c1.totalWords(), c2W = c2.totalWords();
                            code[c1.offset()] = pack1(NOP, K_NONE, 0);
                            code[c2.offset()] = pack1(NOP, K_NONE, 0);
                            for (int i = 1; i < c1W; i++)
                                code[c1.offset() + i] = 0;
                            for (int i = 1; i < c2W; i++)
                                code[c2.offset() + i] = 0;
                            anyFolded = true;
                        }
                    }
                }
            }
            v.advance();
        }
        return anyFolded;
    }

    /** Remove NOP instructions from code. */
    private static int[] compactNops(int[] code) {
        IntList compacted = new IntList();
        InstructionView v = new InstructionView(code, 0);
        while (v.inBounds()) {
            if (v.opcode() != NOP) {
                int words = v.totalWords();
                for (int i = 0; i < words; i++) compacted.add(code[v.offset() + i]);
            }
            v.advance();
        }
        return compacted.toArray();
    }

    private Object tryFold(int op, Object[] pool, int aIdx, int bIdx) {
        Object a = getFromPool(aIdx), b = getFromPool(bIdx);
        if (a == null || b == null)
            return null;
        if (!(a instanceof Number) || !(b instanceof Number))
            return null;

        Number na = (Number) a, nb = (Number) b;
        boolean isFloat =
                (na instanceof Double || na instanceof Float || nb instanceof Double || nb instanceof Float);
        return switch (op) {
            case IADD, LADD, DYNADD ->
                    isFloat ? na.doubleValue() + nb.doubleValue() :
                            wrap(na.longValue() + nb.longValue());
            case ISUB, LSUB, DYNSUB ->
                    isFloat ? na.doubleValue() - nb.doubleValue() :
                            wrap(na.longValue() - nb.longValue());
            case IMUL, LMUL, DYNMUL ->
                    isFloat ? na.doubleValue() * nb.doubleValue() :
                            wrap(na.longValue() * nb.longValue());
            case IDIV, LDIV -> {
                if (nb.longValue() == 0)
                    yield null;
                yield wrap(na.longValue() / nb.longValue());
            }
            case DYNDIV -> {
                long xl = na.longValue(), yl = nb.longValue();
                if (yl == 0)
                    yield null;
                yield isFloat ? na.doubleValue() / nb.doubleValue() :
                        (xl % yl == 0) ? wrap(xl / yl) :
                        na.doubleValue() / nb.doubleValue();
            }
            case IREM, LREM, DYNREM -> {
                if (nb.longValue() == 0)
                    yield null;
                yield isFloat ? na.doubleValue() % nb.doubleValue() :
                        wrap(na.longValue() % nb.longValue());
            }
            // Typed double ops
            case DADD -> na.doubleValue() + nb.doubleValue();
            case DSUB -> na.doubleValue() - nb.doubleValue();
            case DMUL -> na.doubleValue() * nb.doubleValue();
            case DDIV -> na.doubleValue() / nb.doubleValue();
            // Power ops
            case IPOW -> wrap((long) Math.pow(na.intValue(), nb.intValue()));
            case LPOW -> wrap((long) Math.pow(na.longValue(), nb.longValue()));
            case DPOW, DYNPOW -> Math.pow(na.doubleValue(), nb.doubleValue());
            // Comparison ops — all types
            case IEQ, LEQ -> na.longValue() == nb.longValue();
            case INE, LNE -> na.longValue() != nb.longValue();
            case ILT, LLT -> na.longValue() < nb.longValue();
            case ILE, LLE -> na.longValue() <= nb.longValue();
            case IGT, LGT -> na.longValue() > nb.longValue();
            case IGE, LGE -> na.longValue() >= nb.longValue();
            case DEQ -> na.doubleValue() == nb.doubleValue();
            case DNE -> na.doubleValue() != nb.doubleValue();
            case DLT -> na.doubleValue() < nb.doubleValue();
            case DLE -> na.doubleValue() <= nb.doubleValue();
            case DGT -> na.doubleValue() > nb.doubleValue();
            case DGE -> na.doubleValue() >= nb.doubleValue();
            // Bitwise ops (int and long only)
            case IAND, LAND -> wrap(na.longValue() & nb.longValue());
            case IOR, LOR -> wrap(na.longValue() | nb.longValue());
            case IXOR, LXOR -> wrap(na.longValue() ^ nb.longValue());
            case ISHL, LSHL -> wrap(na.longValue() << nb.longValue());
            case ISHR, LSHR -> wrap(na.longValue() >> nb.longValue());
            case IUSHR, LUSHR -> wrap(na.longValue() >>> nb.longValue());
            default -> null;
        };
    }

    private static Object wrap(long v) {
        if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) return (int) v;
        return v;
    }

    private static int poolKind(Object v) {
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long
            || v instanceof Double || v instanceof String) return K_PRIM;
        return K_NONE;
    }

    private Object getFromPool(int idx) {
        if (idx < pool.length)
            return pool[idx];
        if (addedConstants != null) {
            int extIdx = idx - pool.length;
            if (extIdx < addedConstants.size())
                return addedConstants.get(extIdx);
        }
        return null;
    }

    private int ensureInPool(Object value) {
        // Search existing pool
        for (int i = 0; i < pool.length; i++) {
            if (java.util.Objects.equals(pool[i], value))
                return i;
        }
        // Search added constants
        if (addedConstants != null) {
            for (int i = 0; i < addedConstants.size(); i++) {
                if (java.util.Objects.equals(addedConstants.get(i), value))
                    return pool.length + i;
            }
        }
        // Add to extended pool
        if (addedConstants == null)
            addedConstants = new ArrayList<>();
        addedConstants.add(value);
        return pool.length + addedConstants.size() - 1;
    }
}
