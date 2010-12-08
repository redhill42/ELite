/*
 * $Id: Frame.java,v 1.4 2009/05/13 05:39:05 danielyuan Exp $
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

package org.operamasks.el.eval;

import java.io.Serializable;
import javax.el.ELContext;

import org.operamasks.el.parser.Position;

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
