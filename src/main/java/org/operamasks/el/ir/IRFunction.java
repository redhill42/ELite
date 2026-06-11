package org.operamasks.el.ir;

import java.util.Arrays;
import java.util.List;

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
    /** Single contiguous code array for all blocks. */
    private final int[] code;
    /** Start offset of each basic block in the code array. */
    private final int[] blockOffsets;
    /** Constant pool: literals indexed by PUSH_CONST payload. */
    private final Object[] constantPool;
    /** Variable names indexed by PUSH_VAR/PUSH_GLOBAL payload. */
    private final String[] varNames;

    /** Optional: positions for debugging. Parallel to blocks. */
    private final int[] sourcePositions;

    /**
     * Per-parameter flags. Bit 0 = type was explicitly annotated (vs. inferred).
     * Parallel to varNames; only the first paramCount entries are meaningful.
     * May be null for functions compiled without type annotation info.
     */
    private final int[] paramFlags;

    IRFunction(String name, int paramCount,
               int[] code, int[] blockOffsets,
               Object[] constantPool, String[] varNames,
               int[] sourcePositions) {
        this(name, paramCount, code, blockOffsets, constantPool, varNames,
             sourcePositions, null);
    }

    IRFunction(String name, int paramCount,
               int[] code, int[] blockOffsets,
               Object[] constantPool, String[] varNames,
               int[] sourcePositions, int[] paramFlags) {
        this.name = name;
        this.paramCount = paramCount;
        this.code = code;
        this.blockOffsets = blockOffsets;
        this.constantPool = constantPool;
        this.varNames = varNames;
        this.sourcePositions = sourcePositions;
        this.paramFlags = paramFlags;
    }

    public String name()       { return name; }
    public int paramCount()    { return paramCount; }
    public int[] code()        { return code; }
    public int[] blockOffsets() { return blockOffsets; }
    public Object[] constantPool() { return constantPool; }
    public String[] varNames() { return varNames; }
    public int[] sourcePositions() { return sourcePositions; }

    /**
     * Per-parameter flags. Bit 0 (EXPLICIT_TYPE) = type was explicitly annotated.
     * Returns null if no annotation info is available.
     */
    public static final int PARAM_EXPLICIT_TYPE = 1;

    public int[] paramFlags() { return paramFlags; }

    /** Check if parameter at index {@code paramIdx} has an explicit type annotation. */
    public boolean isExplicitParamType(int paramIdx) {
        return paramFlags != null && paramIdx < paramFlags.length
            && (paramFlags[paramIdx] & PARAM_EXPLICIT_TYPE) != 0;
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
            InstructionView v = new InstructionView(code, start);
            while (v.inBounds() && v.offset() < end) {
                sb.append("    ").append(v).append("\n");
                v.advance();
            }
        }
        return sb.toString();
    }
}
