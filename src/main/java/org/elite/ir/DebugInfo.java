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
    String  file,         // source file name (null if unknown)
    int[]   pcLineTable   // [pc0, line0, pc1, line1, ...] sorted by PC
) {
    /**
     * Look up the source line number for a given instruction pointer (PC).
     * Returns the line of the closest recorded PC >= {@code ip}, or 0 if
     * no mapping exists before this IP.
     */
    public int lineForPC(int pc) {
        int len = pcLineTable.length / 2;
        int lo = 0, hi = len - 1;
        int i = len;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (pcLineTable[mid * 2] >= pc) {
                i = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        if (i < len)
            return pcLineTable[i * 2 + 1];
        return 0;
    }
}
