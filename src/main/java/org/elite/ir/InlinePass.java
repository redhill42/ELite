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

import java.util.*;

import static org.elite.ir.Opcode.*;
import static org.elite.ir.IRFormat.*;

/**
 * Inline expansion pass: replaces INVOKE_DIRECT with the callee's body.
 *
 * Two-pass approach:
 * 1. Scan for inline targets, collect all constant pools
 * 2. Build merged pool, remap indices, emit inlined code
 */
public class InlinePass implements IRPass {

    private static final int MAX_INLINE_SIZE = 20;

    private Map<IRFunction, Integer> poolBaseMap; // fn → base index in merged pool

    private int baseSlot;
    private int tempSlots;

    @Override
    public IRFunction transform(IRFunction input) {
        int[] oldCode = input.code();
        int[] offsets = input.blockOffsets();
        int blockCount = input.blockCount();
        Object[] pool = input.constantPool();

        this.baseSlot = input.maxLocalCount();
        this.tempSlots = 0;

        // Pass 1: collect all inline targets and their constant pools
        this.poolBaseMap = new HashMap<>();
        List<Object> extraConstants = new ArrayList<>();
        Set<IRFunction> inlineTargets = new HashSet<>();

        for (int b = 0; b < blockCount; b++) {
            int start = offsets[b];
            int end = (b + 1 < blockCount) ? offsets[b + 1] : oldCode.length;
            collectTargets(oldCode, start, end, pool, inlineTargets, extraConstants);
        }

        if (inlineTargets.isEmpty())
            return input; // nothing to inline

        // Build merged pool
        Object[] mergedPool = new Object[pool.length + extraConstants.size()];
        System.arraycopy(pool, 0, mergedPool, 0, pool.length);
        for (int i = 0; i < extraConstants.size(); i++) {
            mergedPool[pool.length + i] = extraConstants.get(i);
        }

        // Pass 2: build blocks with inlined code and remapped indices
        int[][] newBlocks = new int[blockCount][];
        boolean changed = false;

        for (int b = 0; b < blockCount; b++) {
            int start = offsets[b];
            int end = (b + 1 < blockCount) ? offsets[b + 1] : oldCode.length;
            int[] newBlock = buildBlock(oldCode, start, end, pool);
            if (newBlock == null) {
                newBlocks[b] = Arrays.copyOfRange(oldCode, start, end);
            } else {
                newBlocks[b] = newBlock;
                changed = true;
            }
        }

        if (!changed)
            return input;

        // Rebuild function
        IntList merged = new IntList();
        int[] newOffsets = new int[blockCount];
        for (int b = 0; b < blockCount; b++) {
            newOffsets[b] = merged.size();
            merged.addAll(newBlocks[b]);
        }

        input.populate(merged.toArray(), input.maxLocals() + tempSlots, newOffsets, mergedPool,
               input.debugInfo(), input.defaultValues());
        return input;
    }

    /** Get the full function pool index from an INVOKE_DIRECT instruction. */
    private static int directFuncIdx(InstructionView v) {
        // emit2: opCount=1, full idx in payload (fits 16 bits)
        // emit3: opCount=2, idx = (payload << 16) | operand(0)
        if (v.opCount() <= 1) return v.payload();
        return (v.payload() << 16) | (v.operand(0) & 0xFFFF);
    }

    /** Pass 1: scan a block for inline targets and collect their constants. */
    private void collectTargets(int[] code, int start, int end, Object[] pool,
            Set<IRFunction> targets, List<Object> extra) {
        InstructionView v = new InstructionView(code, start);
        while (v.inBounds() && v.offset() < end) {
            if (v.opcode() == INVOKE_DIRECT && canInlineTarget(v, pool)) {
                IRFunction callee = (IRFunction) pool[directFuncIdx(v)];
                if (targets.add(callee)) {
                    // First time seeing this callee: add its constants to extra pool
                    int base = pool.length + extra.size();
                    poolBaseMap.put(callee, base);
                    Collections.addAll(extra, callee.constantPool());
                }
            }
            v.advance();
        }
    }

    /** Pass 2: build a new block with inlined code and remapped pool indices. */
    private int[] buildBlock(int[] code, int start, int end, Object[] callerPool) {
        // Check if there's anything to inline
        boolean hasInline = false;
        InstructionView scan = new InstructionView(code, start);
        while (scan.inBounds() && scan.offset() < end) {
            if (scan.opcode() == INVOKE_DIRECT
                    && poolBaseMap.containsKey(callerPool[directFuncIdx(scan)])) {
                hasInline = true;
                break;
            }
            scan.advance();
        }
        if (!hasInline)
            return null;

        IntList out = new IntList();
        InstructionView v = new InstructionView(code, start);
        while (v.inBounds() && v.offset() < end) {
            if (v.opcode() == INVOKE_DIRECT) {
                IRFunction callee = (IRFunction) callerPool[directFuncIdx(v)];
                Integer base = poolBaseMap.get(callee);
                if (base != null) {
                    int argc = v.opCount() > 0 ? code[v.offset() + 1] : 0;
                    int[] argTypes = inferArgTypes(code, start, v.offset(), argc);
                    emitInlinedBody(out, callee, argc, argTypes, base);
                    v.advance();
                    continue;
                }
            }
            // Copy as-is
            int w = v.totalWords();
            for (int i = 0; i < w; i++)
                out.add(code[v.offset() + i]);
            v.advance();
        }
        return out.toArray();
    }

    private boolean canInlineTarget(InstructionView v, Object[] pool) {
        int funcIdx = directFuncIdx(v);
        if (funcIdx >= pool.length)
            return false;
        return pool[funcIdx] instanceof IRFunction fn && canInline(fn);
    }

    private static boolean canInline(IRFunction callee) {
        if (callee.blockCount() != 1)
            return false;
        int[] body = callee.code();
        int count = 0;
        InstructionView v = new InstructionView(body, 0);
        while (v.inBounds()) {
            count++;
            if (count > MAX_INLINE_SIZE)
                return false;
            int op = v.opcode();
            if (op == INVOKE_DIRECT || op == INVOKE_DYN
                    || Opcode.isJump(op) || op == INVOKE_TAIL)
                return false;
            v.advance();
        }
        return true;
    }

    private int[] inferArgTypes(int[] code, int blockStart, int callOffset, int argc) {
        int[] types = new int[argc];
        Arrays.fill(types, -1);
        int off = callOffset;
        for (int i = argc - 1; i >= 0; i--) {
            off = prevInst(code, blockStart, off);
            if (off < 0)
                break;
            int op = IRFormat.opcode(code[off]);
            types[i] = switch (op) {
                case PUSH_TRUE, PUSH_FALSE -> T_BOOL;
                case IADD, ISUB, IMUL, IDIV, IREM, INEG -> T_INT;
                case DADD, DSUB, DMUL, DDIV, DNEG -> T_DOUBLE;
                default -> -1;
            };
        }
        return types;
    }

    private static int prevInst(int[] code, int blockStart, int beforeOffset) {
        int off = blockStart, prev = -1;
        while (off < beforeOffset) {
            prev = off;
            off += IRFormat.totalWords(code[off]);
        }
        return prev;
    }

    /** Emit inlined body with remapped pool indices. */
    private void emitInlinedBody(IntList out, IRFunction callee, int argc,
            int[] argTypes, int poolBase) {
        IRFunction body = IRSpecializer.specialize(callee, argTypes);
        tempSlots = Math.max(tempSlots, argc);

        // Pop args into temp locals (argN-1 first)
        for (int i = argc - 1; i >= 0; i--) {
            out.add(pack1(STORE_VAR, K_NONE, (baseSlot + i) & 0xFFFF));
            out.add(pack1(POP, K_NONE, 0));
        }

        // Emit body with remapped pool indices and PUSH_VAR remapping
        int[] bodyCode = body.code();
        InstructionView bv = new InstructionView(bodyCode, 0);
        while (bv.inBounds()) {
            int op = bv.opcode();
            if (op == PUSH_VAR) {
                int varIdx = bv.varIndex();
                int remapped = (varIdx < argc) ? (baseSlot + varIdx) : varIdx;
                out.add(pack1(PUSH_VAR, K_PRIM, remapped & 0xFFFF));
            } else if (op == PUSH_CONST) {
                int calleeIdx = bv.constPoolIndex();
                int callerIdx = poolBase + calleeIdx;
                int kind = bv.kind();
                out.add(pack1(PUSH_CONST, kind, callerIdx & 0xFFFF));
            } else if (op == PUSH_GLOBAL || op == DEFINE_GLOBAL) {
                int calleeIdx = bv.constPoolIndex();
                int callerIdx = poolBase + calleeIdx;
                out.add(pack1(op, K_NONE, callerIdx & 0xFFFF));
            } else if (op != RETURN && op != RETURN_VOID) {
                int w = bv.totalWords();
                for (int i = 0; i < w; i++)
                    out.add(bodyCode[bv.offset() + i]);
            }
            bv.advance();
        }
    }
}
