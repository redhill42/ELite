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

package org.elite.parser;

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
