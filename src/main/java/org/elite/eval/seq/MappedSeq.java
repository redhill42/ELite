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

package org.elite.eval.seq;

import javax.el.ELContext;
import elite.lang.Closure;
import elite.lang.Seq;
import org.elite.eval.Control;

public class MappedSeq extends DelaySeq
{
    protected Seq seq;
    protected Closure proc;

    private MappedSeq(Seq seq, Closure proc) {
        this.seq = seq;
        this.proc = proc;
    }

    public static Seq make(Seq seq, Closure proc) {
        return new MappedSeq(seq, proc);
    }

    protected void force(ELContext elctx) {
        if (seq == null) {
            return;
        }

        Seq t = seq;
        Closure p = proc;
        seq = null;
        proc = null;

        while (!t.isEmpty()) {
            Object x;
            x = t.head();
            t = t.tail();
            try {
                head = p.call(elctx, x);
                tail = new MappedSeq(t, p);
                break;
            } catch (Control.Break b) {
                break;
            } catch (Control.Continue c) {
                continue;
            }
        }
    }
}
