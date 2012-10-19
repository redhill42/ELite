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

package org.operamasks.el.eval;

import javax.el.ELContext;

public class StackTrace
{
    private StackTrace() {}

    @SuppressWarnings("unchecked")
    static StackTrace getInstance(ELContext elctx) {
        ThreadLocal<StackTrace> tls = (ThreadLocal<StackTrace>)
            elctx.getContext(StackTrace.class);

        if (tls == null) {
            tls = new ThreadLocal<StackTrace>() {
                protected StackTrace initialValue() {
                    return new StackTrace();
                }
            };
            elctx.putContext(StackTrace.class, tls);
        }

        return tls.get();
    }

    Frame frame;

    public static Frame addFrame(ELContext elctx, String procName, String fileName, int pos) {
        StackTrace trace = getInstance(elctx);
        trace.frame = new Frame(procName, fileName, pos, trace.frame);
        trace.frame.enter(elctx);
        return trace.frame;
    }

    public static void removeFrame(ELContext elctx) {
        StackTrace trace = getInstance(elctx);
        trace.frame = trace.frame.exit();
    }

    public static Frame getFrame(ELContext elctx) {
        StackTrace trace = getInstance(elctx);
        return trace.frame;
    }
}
