/*
 * Copyright (c) 2006-2011 Daniel Yuan.
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

import javax.el.ELException;

public class ParseException extends ELException
{
    private String file;
    private int line;
    private int column;

    public ParseException(String file, int line, int column, String message) {
        super(message);
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public String getFileName() {
        return file;
    }

    public int getLineNumber() {
        return line;
    }

    public int getColumnNumber() {
        return column;
    }

    public String getMessage() {
        if (file != null) {
            return file + ":" + line + ": " + super.getMessage();
        } else {
            return "line " + line + ": " + super.getMessage();
        }
    }
}
