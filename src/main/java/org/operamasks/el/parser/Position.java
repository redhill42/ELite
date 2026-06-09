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

package org.operamasks.el.parser;

/**
 * Encodes and decodes source code positions. Source code positions
 * are internally represented as integers that contain both column
 * and line number information.
 */
public class Position
{
    public static final int LINESHIFT   = 10;
    public static final int LINEINC     = (1 << LINESHIFT);
    public static final int COLUMNMASK  = (1 << LINESHIFT) - 1;
    public static final int NOPOS       = 0;
    public static final int FIRSTPOS    = (1 << LINESHIFT) + 1;
    public static final int MAXPOS      = Integer.MAX_VALUE;

    /**
     * The line number of the given position.
     */
    public static int line(int pos) {
        return pos >>> LINESHIFT;
    }

    /**
     * The column number of the given position.
     */
    public static int column(int pos) {
        return pos & COLUMNMASK;
    }

    /**
     * Form a position from a line number and a column number.
     */
    public static int make(int line, int col) {
        return (line << LINESHIFT) + col;
    }

    /**
     * Get the position in the begining of next line.
     */
    public static int nextline(int pos) {
        return (pos & ~COLUMNMASK) + LINEINC + 1;
    }
}
