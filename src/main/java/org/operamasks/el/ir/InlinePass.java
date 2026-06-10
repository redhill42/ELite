package org.operamasks.el.ir;

import java.util.*;

import static org.operamasks.el.ir.Opcode.*;
import static org.operamasks.el.ir.IRFormat.*;

/**
 * Inline expansion pass: replaces INVOKE_DIRECT calls with the callee's body.
 * Combines with IRSpeclializer for type specialization at call sites.
 */
public class InlinePass implements IRPass {

    private static final int MAX_INLINE_SIZE = 20;

    @Override
    public IRFunction transform(IRFunction input) {
        int[] oldCode = input.code();
        int[] offsets = input.blockOffsets();
        int blockCount = input.blockCount();
        Object[] pool = input.constantPool();

        int[][] newBlocks = new int[blockCount][];
        boolean changed = false;

        for (int b = 0; b < blockCount; b++) {
            int start = offsets[b];
            int end = (b + 1 < blockCount) ? offsets[b + 1] : oldCode.length;
            newBlocks[b] = tryInlineInBlock(oldCode, start, end, pool, input.paramCount());
            if (newBlocks[b] == null) {
                // No changes — copy original block
                newBlocks[b] = Arrays.copyOfRange(oldCode, start, end);
            } else {
                changed = true;
            }
        }

        if (!changed) return input;

        // Merge extra constants from inlined functions into the pool
        Object[] newPool = pool;
        if (!extraPool.isEmpty()) {
            newPool = new Object[pool.length + extraPool.size()];
            System.arraycopy(pool, 0, newPool, 0, pool.length);
            for (int i = 0; i < extraPool.size(); i++) {
                newPool[pool.length + i] = extraPool.get(i);
            }
            // Fix up pool indices in block code: pool.length maps to extra pool start
            for (int b = 0; b < blockCount; b++) {
                if (newBlocks[b] != null) {
                    fixPoolIndices(newBlocks[b], pool.length);
                }
            }
        }

        // Rebuild with new block sizes
        IntList merged = new IntList();
        int[] newOffsets = new int[blockCount];
        for (int b = 0; b < blockCount; b++) {
            newOffsets[b] = merged.size();
            merged.addAll(newBlocks[b]);
        }

        return new IRFunction(input.name(), input.paramCount(),
                merged.toArray(), newOffsets, newPool,
                input.varNames(), input.sourcePositions());
    }

    /** Fix pool indices: values >= MARK_BASE are remapped to (value - MARK_BASE + baseOffset). */
    private static final int MARK_BASE = 0xFF00; // high marker to distinguish from normal indices

    private static void fixPoolIndices(int[] code, int baseOffset) {
        InstructionView v = new InstructionView(code, 0);
        while (v.inBounds()) {
            int op = v.opcode();
            if (op == PUSH_CONST || op == PUSH_GLOBAL || op == PUSH_GLOBAL_N || op == STORE_GLOBAL) {
                int idx = v.constPoolIndex();
                if (idx >= MARK_BASE) {
                    int absIdx = baseOffset + (idx - MARK_BASE);
                    int kind = v.kind();
                    if (absIdx < 0x10000) {
                        code[v.offset()] = pack1(op, kind, absIdx & 0xFFFF);
                    }
                }
            }
            v.advance();
        }
    }

    /** Try to inline calls in a block. Returns new code or null if no changes. */
    private int[] tryInlineInBlock(int[] code, int start, int end,
                                    Object[] pool, int callerParamCount) {
        // First pass: check if there's anything to inline
        boolean hasInlineTarget = false;
        InstructionView scan = new InstructionView(code, start);
        while (scan.inBounds() && scan.offset() < end) {
            if (scan.opcode() == INVOKE_DIRECT && canInlineTarget(code, scan, pool)) {
                hasInlineTarget = true;
                break;
            }
            scan.advance();
        }
        if (!hasInlineTarget) return null;

        // Second pass: build new block with inlined calls
        IntList out = new IntList();
        InstructionView v = new InstructionView(code, start);
        while (v.inBounds() && v.offset() < end) {
            if (v.opcode() == INVOKE_DIRECT && canInlineTarget(code, v, pool)) {
                int funcIdx = v.payload();
                int argc = v.opCount() > 0 ? code[v.offset() + 1] : 0;
                IRFunction callee = (IRFunction) pool[funcIdx];
                int[] argTypes = inferArgTypes(code, start, v.offset(), argc);
                emitInlinedBody(out, callee, argc, argTypes);
                v.advance(); // skip INVOKE_DIRECT
                continue;
            }
            // Copy instruction as-is
            int w = v.totalWords();
            for (int i = 0; i < w; i++) out.add(code[v.offset() + i]);
            v.advance();
        }
        return out.toArray();
    }

    private static boolean canInlineTarget(int[] code, InstructionView v, Object[] pool) {
        int funcIdx = v.payload();
        if (funcIdx >= pool.length) return false;
        Object fn = pool[funcIdx];
        if (!(fn instanceof IRFunction callee)) return false;
        return canInline(callee);
    }

    private static boolean canInline(IRFunction callee) {
        if (callee.blockCount() != 1) return false;
        // No constant pool dependencies for now (pool merging is complex)
        if (callee.constantPool().length > 0) return false;
        int[] body = callee.code();
        int count = 0;
        InstructionView v = new InstructionView(body, 0);
        while (v.inBounds()) {
            count++;
            if (count > MAX_INLINE_SIZE) return false;
            int op = v.opcode();
            if (op == INVOKE_DIRECT || op == INVOKE_DYN || op == INVOKE
                || Opcode.isJump(op) || op == INVOKE_TAIL
                || op == PUSH_CONST) return false; // can't handle constants yet
            v.advance();
        }
        return count <= MAX_INLINE_SIZE;
    }

    /** Infer argument types from instructions before the call site. */
    private static int[] inferArgTypes(int[] code, int blockStart, int callOffset, int argc) {
        int[] types = new int[argc];
        Arrays.fill(types, -1);
        int off = callOffset;
        for (int i = argc - 1; i >= 0; i--) {
            off = prevInst(code, blockStart, off);
            if (off < 0) break;
            int op = IRFormat.opcode(code[off]);
            int kind = IRFormat.kind(code[off]);
            types[i] = switch (op) {
                case PUSH_CONST -> kind == K_PRIM ? IRFormat.payload(code[off]) : -1;
                case PUSH_TRUE, PUSH_FALSE -> T_BOOL;
                case PUSH_NULL -> -1;
                case IADD, ISUB, IMUL, IDIV, IREM, INEG -> T_INT;
                case DADD, DSUB, DMUL, DDIV, DNEG -> T_DOUBLE;
                case IEQ, INE, ILT, ILE, IGT, IGE -> T_BOOL;
                default -> -1;
            };
        }
        return types;
    }

    private static int prevInst(int[] code, int blockStart, int beforeOffset) {
        int off = blockStart, prev = -1;
        while (off < beforeOffset) { prev = off; off += IRFormat.totalWords(code[off]); }
        return prev;
    }

    /** Emit the inlined body, popping args and remapping PUSH_VAR and pool indices. */
    private void emitInlinedBody(IntList out, IRFunction callee, int argc, int[] argTypes) {
        IRFunction body = argc > 0 ? IRSpeclializer.specialize(callee, argTypes) : callee;
        int baseSlot = 8;

        // Build a pool index remapping table: calleePoolIdx → callerPoolIdx
        int[] poolRemap = buildPoolRemap(body.constantPool());

        // Pop args from stack into temp locals (argN-1 first)
        for (int i = argc - 1; i >= 0; i--) {
            out.add(pack1(STORE_VAR, K_NONE, (baseSlot + i) & 0xFFFF));
            out.add(pack1(POP, K_NONE, 0));
        }

        // Emit callee body, remapping PUSH_VAR and pool indices
        int[] bodyCode = body.code();
        InstructionView bv = new InstructionView(bodyCode, 0);
        while (bv.inBounds()) {
            int op = bv.opcode();
            if (op == PUSH_VAR) {
                int varIdx = bv.varIndex();
                int remapped = (varIdx < argc) ? (baseSlot + varIdx) : varIdx;
                out.add(pack1(PUSH_VAR, K_PRIM, remapped & 0xFFFF));
            } else if (op == PUSH_CONST || op == PUSH_GLOBAL || op == PUSH_GLOBAL_N
                       || op == STORE_GLOBAL) {
                int calleeIdx = bv.constPoolIndex();
                int callerIdx = poolRemap[calleeIdx];
                int kind = bv.kind();
                int outOp = (op == STORE_GLOBAL) ? op : (op == PUSH_GLOBAL_N ? PUSH_GLOBAL : op);
                // Write with marker index (will be fixed in fixPoolIndices)
                out.add(pack1(outOp, kind, callerIdx & 0xFFFF));
                if (IRFormat.opCount(out.toArray()[out.size()-1]) > 0) {
                    out.add(callerIdx >>> 16); // not needed for oc=0 but safe
                }
            } else if (op != RETURN && op != RETURN_VOID) {
                int w = bv.totalWords();
                for (int i = 0; i < w; i++) out.add(bodyCode[bv.offset() + i]);
            }
            bv.advance();
        }
    }

    private List<Object> extraPool = new ArrayList<>();

    /** Build a remapping table from callee pool indices to caller pool indices. */
    private int[] buildPoolRemap(Object[] calleePool) {
        int[] remap = new int[calleePool.length];
        for (int i = 0; i < calleePool.length; i++) {
            extraPool.add(calleePool[i]);
            remap[i] = MARK_BASE + extraPool.size() - 1; // Marker index
        }
        return remap;
    }
}
