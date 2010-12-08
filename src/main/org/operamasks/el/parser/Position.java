/*
 * $Id: Position.java,v 1.3 2009/04/14 19:32:36 danielyuan Exp $
 *
 * Copyright (C) 2006 Operamasks Community.
 * Copyright (C) 2000-2006 Apusic Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see http://www.gnu.org/licenses.
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
