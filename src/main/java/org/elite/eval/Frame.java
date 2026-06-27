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

package org.elite.eval;

import java.io.Serializable;
import javax.el.ELContext;

import org.elite.parser.Position;

public class Frame implements Serializable
{
    private String    procName;
    private String    fileName;
    private int       pos;
    private Frame     next;
    private ELContext previousContext;

    public Frame(String procName, String fileName, int pos, Frame next) {
        this.procName = procName;
        this.fileName = fileName;
        this.pos      = pos;
        this.next     = next;
    }

    public String getProcName() {
        return procName;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineNumber() {
        return Position.line(pos);
    }

    public int getColumnNumber() {
        return Position.column(pos);
    }

    public int getPos() {
        return pos;
    }

    public void setPos(int pos) {
        this.pos = pos;
    }
    
    public Frame getNext() {
        return next;
    }

    void enter(ELContext elctx) {
        previousContext = ELEngine.setCurrentELContext(elctx);
    }

    Frame exit() {
        ELEngine.setCurrentELContext(previousContext);
        return next;
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();
        if (procName != null)
            buf.append(procName);
        buf.append("(");
        if (fileName != null)
            buf.append(fileName).append(":");
        buf.append(getLineNumber());
        buf.append(")");
        return buf.toString();
    }
}
