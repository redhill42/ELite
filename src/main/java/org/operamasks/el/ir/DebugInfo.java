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

/**
 * Source-level debug information attached to an {@link IRFunction}.
 *
 * <p>Contains a PC→source-line mapping for reconstructing stack traces
 * when errors occur during IR interpretation. The mapping is stored as
 * an array of alternating (pc, lineNumber) entries, sorted by PC.
 *
 * <p>When {@code elite.debug} is false, this record is empty and has
 * zero runtime overhead.
 */
public record DebugInfo(
    String  fileName,         // source file name (null if unknown)
    String  functionName,     // function name for frame construction
    int[]   blockPositions,   // per-block start position (Position encoding), parallel to blockOffsets
    int[]   pcLineTable,      // [pc0, line0, pc1, line1, ...] sorted by PC
    int     entryCount        // number of entries in pcLineTable (= pcLineTable.length / 2)
) {
    /** Empty debug info — no source mapping available. */
    public static final DebugInfo EMPTY = new DebugInfo(null, null, null, null, 0);

    /**
     * Look up the source line number for a given instruction pointer (PC).
     * Returns the line of the closest recorded PC ≤ {@code ip}, or 0 if
     * no mapping exists before this IP.
     */
    public int lineForPC(int ip) {
        if (pcLineTable == null) return 0;
        int bestLine = 0;
        int limit = entryCount * 2;
        for (int i = 0; i < limit; i += 2) {
            if (pcLineTable[i] <= ip) {
                bestLine = pcLineTable[i + 1];
            } else {
                break; // pcLineTable is sorted by PC
            }
        }
        return bestLine;
    }

    /** Look up the source position (line+col) for a given block ID. */
    public int positionForBlock(int blockId) {
        if (blockPositions == null || blockId < 0 || blockId >= blockPositions.length)
            return 0;
        return blockPositions[blockId];
    }

    @Override
    public String toString() {
        if (entryCount == 0) return "DebugInfo{empty}";
        StringBuilder sb = new StringBuilder("DebugInfo{file=").append(fileName)
            .append(", fn=").append(functionName)
            .append(", entries=").append(entryCount);
        if (entryCount <= 5 && pcLineTable != null) {
            sb.append(", pcLines=[");
            for (int i = 0; i < entryCount * 2; i += 2) {
                if (i > 0) sb.append(", ");
                sb.append(pcLineTable[i]).append("→L").append(pcLineTable[i + 1]);
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }
}
