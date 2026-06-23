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

import java.util.Arrays;
import java.util.List;

import static org.operamasks.el.ir.Opcode.TRAMPOLINE;

/**
 * A compiled function in IR form.
 *
 * Holds the linear instruction stream (one contiguous int[] for all basic blocks),
 * a list of basic block start offsets, a constant pool for literals, and
 * variable/source metadata.
 *
 * <p>This is the unit of execution for the {@link IRInterpreter} and the unit
 * of compilation for the JVM bytecode backend.
 */
public class IRFunction {

    private final String name;
    private final int paramCount;
    /** Number of captured (closure) variables. 0 = no captures. */
    private final int captureCount;
    /** Single contiguous code array for all blocks. */
    private final int[] code;
    /** Start offset of each basic block in the code array. */
    private final int[] blockOffsets;
    /** Constant pool: literals indexed by PUSH_CONST payload. */
    private final Object[] constantPool;
    /** Variable names indexed by PUSH_VAR/PUSH_GLOBAL payload. */
    private final String[] varNames;

    /** Source-level debug info (PC→line mapping, file/function metadata). */
    private final DebugInfo debugInfo;

    /**
     * Per-parameter flags. Bit 0 = type was explicitly annotated (vs. inferred).
     * Parallel to varNames; only the first paramCount entries are meaningful.
     * May be null for functions compiled without type annotation info.
     */
    private final int[] paramFlags;

    /**
     * Default parameter values (parallel to params; null entries = no default).
     * Simple literals are evaluated at compile time; null for complex expressions.
     * Applied in execute() when caller provides fewer args than paramCount.
     */
    private final Object[] defaultValues;

    IRFunction(String name, int paramCount,
               int[] code, int[] blockOffsets,
               Object[] constantPool, String[] varNames) {
        this(name, paramCount, 0, code, blockOffsets, constantPool, varNames,
             DebugInfo.EMPTY, null, null);
    }

    // Backward-compatible: accepts int[] sourcePositions (ignored)
    IRFunction(String name, int paramCount,
               int[] code, int[] blockOffsets,
               Object[] constantPool, String[] varNames,
               int[] sourcePositions) {
        this(name, paramCount, 0, code, blockOffsets, constantPool, varNames,
             DebugInfo.EMPTY, null, null);
    }

    // Backward-compatible: sourcePositions + paramFlags
    IRFunction(String name, int paramCount,
               int[] code, int[] blockOffsets,
               Object[] constantPool, String[] varNames,
               int[] sourcePositions, int[] paramFlags) {
        this(name, paramCount, 0, code, blockOffsets, constantPool, varNames,
             DebugInfo.EMPTY, paramFlags, null);
    }

    IRFunction(String name, int paramCount,
               int[] code, int[] blockOffsets,
               Object[] constantPool, String[] varNames,
               DebugInfo debugInfo, int[] paramFlags) {
        this(name, paramCount, 0, code, blockOffsets, constantPool, varNames,
             debugInfo, paramFlags, null);
    }

    // For ConstantFolder which passes defaultValues as last arg
    IRFunction(String name, int paramCount,
               int[] code, int[] blockOffsets,
               Object[] constantPool, String[] varNames,
               DebugInfo debugInfo, int[] paramFlags,
               Object[] defaultValues) {
        this(name, paramCount, 0, code, blockOffsets, constantPool, varNames,
             debugInfo, paramFlags, defaultValues);
    }

    IRFunction(String name, int paramCount, int captureCount,
               int[] code, int[] blockOffsets,
               Object[] constantPool, String[] varNames,
               DebugInfo debugInfo, int[] paramFlags) {
        this(name, paramCount, captureCount, code, blockOffsets, constantPool, varNames,
             debugInfo, paramFlags, null);
    }

    IRFunction(String name, int paramCount, int captureCount,
               int[] code, int[] blockOffsets,
               Object[] constantPool, String[] varNames,
               DebugInfo debugInfo, int[] paramFlags,
               Object[] defaultValues) {
        this.name = name;
        this.paramCount = paramCount;
        this.captureCount = captureCount;
        this.code = code;
        this.blockOffsets = blockOffsets;
        this.constantPool = constantPool;
        this.varNames = varNames;
        this.debugInfo = debugInfo != null ? debugInfo : DebugInfo.EMPTY;
        this.paramFlags = paramFlags;
        this.defaultValues = defaultValues;
    }

    public String name()       { return name; }
    public int paramCount()    { return paramCount; }
    public int captureCount()  { return captureCount; }
    public int[] code()        { return code; }
    public int[] blockOffsets() { return blockOffsets; }
    public Object[] constantPool() { return constantPool; }
    public String[] varNames() { return varNames; }
    public DebugInfo debugInfo() { return debugInfo; }

    /**
     * Per-parameter flags. Bit 0 (EXPLICIT_TYPE) = type was explicitly annotated.
     * Returns null if no annotation info is available.
     */
    public static final int PARAM_EXPLICIT_TYPE = 1;
    /** Parameter is captured by an inner closure — must be stored in evalContext. */
    public static final int PARAM_CAPTURED = 2;
    /** Parameter is lazy (&param) — stores a DelayEvalClosure thunk in locals. */
    public static final int PARAM_LAZY = 4;

    public int[] paramFlags() { return paramFlags; }

    /** Check if parameter at index {@code paramIdx} has an explicit type annotation. */
    public boolean isExplicitParamType(int paramIdx) {
        return paramFlags != null && paramIdx < paramFlags.length
            && (paramFlags[paramIdx] & PARAM_EXPLICIT_TYPE) != 0;
    }

    /** Default parameter values (null = no default). */
    public Object[] defaultValues() { return defaultValues; }

    /** Return a copy of this function with the given default parameter values. */
    public IRFunction withDefaults(Object[] defs) {
        if (defs == null) return this;
        for (Object d : defs) if (d != null) {
            return new IRFunction(name, paramCount, captureCount, code, blockOffsets,
                    constantPool, varNames, debugInfo, paramFlags, defs);
        }
        return this; // all null — no defaults to apply
    }

    /** Maximum local variable index (params + define'd vars). */
    public int maxLocalCount() {
        return Math.max(paramCount, varNames != null ? varNames.length : 0);
    }

    /** Check if this function contains ops that the IR interpreter cannot handle. */
    public boolean hasUnsupportedOps() {
        for (int b = 0; b < blockCount(); b++) {
            int start = blockStart(b);
            int end = (b + 1 < blockCount()) ? blockStart(b + 1) : code.length;
            InstructionView v = new InstructionView(code, start);
            while (v.inBounds() && v.offset() < end) {
                int op = v.opcode();
                // Trampoline ops (TRAMPOLINE) require AST evaluation — IR can't handle
                if (op == TRAMPOLINE) return true;
                v.advance();
            }
        }
        return false;
    }

    /** Get the code offset for a given block ID. */
    public int blockStart(int blockId) {
        return blockOffsets[blockId];
    }

    /** Number of basic blocks. */
    public int blockCount() {
        return blockOffsets.length;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("IRFunction[").append(name).append("] params=").append(paramCount)
          .append(" blocks=").append(blockOffsets.length)
          .append(" codeWords=").append(code.length)
          .append("\n");

        for (int b = 0; b < blockOffsets.length; b++) {
            sb.append("  B").append(b).append(" (offset=").append(blockOffsets[b]).append("):\n");
            int start = blockOffsets[b];
            int end = (b + 1 < blockOffsets.length) ? blockOffsets[b + 1] : code.length;
            InstructionView v = new InstructionView(code, start, constantPool);
            while (v.inBounds() && v.offset() < end) {
                sb.append("    ").append(v).append("\n");
                v.advance();
            }
        }
        return sb.toString();
    }
}
