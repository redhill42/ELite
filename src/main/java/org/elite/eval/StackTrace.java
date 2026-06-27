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
