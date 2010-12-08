/*
 * $Id: EvaluationException.java,v 1.3 2009/03/23 13:18:25 danielyuan Exp $
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

import javax.el.ELException;
import javax.el.ELContext;

public class EvaluationException extends ELException
{
    private Frame frame;

    public EvaluationException(ELContext elctx, String message) {
        super(message);
        frame = (elctx == null) ? null : StackTrace.getFrame(elctx);
    }

    public EvaluationException(ELContext elctx, Throwable cause) {
        super(cause);
        frame = (elctx == null) ? null : StackTrace.getFrame(elctx);
    }

    public EvaluationException(ELContext elctx, String message, Throwable cause) {
        super(message, cause);
        frame = (elctx == null) ? null : StackTrace.getFrame(elctx);
    }

    public String getRawMessage() {
        return super.getMessage();
    }
    
    public String getMessage() {
        if (frame == null) {
            return super.getMessage();
        } else {
            StringBuilder buf = new StringBuilder();
            buf.append(super.getMessage());
            buf.append("\n");
            for (Frame f = frame; f != null; f = f.getNext()) {
                buf.append("\tat ").append(f).append("\n");
            }
            buf.append("-------------------------");
            return buf.toString();
        }
    }

    public String getFileName() {
        return (frame == null) ? null : frame.getFileName();
    }

    public int getLineNumber() {
        return (frame == null) ? -1 : frame.getLineNumber();
    }

    public int getColumnNumber() {
        return (frame == null) ? -1 : frame.getColumnNumber();
    }
}
