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
                e = t.get(); t = t.tail();
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
            head = v.get();
            v = v.tail();
            if (v.isEmpty())
                v = null;
            tail = new MappendSeq(t, p, v);
        } 
    }
}
