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

package org.operamasks.el.eval.seq;

import javax.el.ELContext;
import elite.lang.Closure;
import elite.lang.Seq;
import org.operamasks.el.eval.Control;

public class MappendSeq extends DelaySeq
{
    private Seq seq;
    private Closure proc;
    private Seq values;

    private MappendSeq(Seq seq, Closure proc, Seq values) {
        this.seq = seq;
        this.proc = proc;
        this.values = values;
    }

    public static Seq make(Seq seq, Closure proc) {
        return new MappendSeq(seq, proc, null);
    }

    protected void force(ELContext elctx) {
        if (seq == null && values == null) {
            return;
        }

        Seq t = seq;
        Closure p = proc;
        Seq v = values;
        seq = null;
        proc = null;
        values = null;

        if (v == null) {
            while (!t.isEmpty()) {
                Object e;

                // evaluate the mappend procedure
                e = t.head(); t = t.tail();
                try {
                    e = p.call(elctx, e);
                } catch (Control.Break b) {
                    return; // terminate the sequence
                } catch (Control.Continue c) {
                    continue;
                }

                // the procedure should generate a sequence
                if (e instanceof Seq && !((Seq)e).isEmpty()) {
                    v = (Seq)e;
                    break;
                }
            }
        }

        if (v != null) {
            // generate next element
            head = v.head();
            v = v.tail();
            if (v.isEmpty())
                v = null;
            tail = new MappendSeq(t, p, v);
        } 
    }
}
