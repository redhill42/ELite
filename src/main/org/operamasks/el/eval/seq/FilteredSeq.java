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

public class FilteredSeq extends DelaySeq
{
    private Seq seq;
    private Closure proc;

    private FilteredSeq(Seq seq, Closure proc) {
        this.seq = seq;
        this.proc = proc;
    }

    public static Seq make(Seq seq, Closure proc) {
        return new FilteredSeq(seq, proc);
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
                if (p.test(elctx, x)) {
                    head = x;
                    tail = new FilteredSeq(t, p);
                    break;
                }
            } catch (Control.Break b) {
                break;
            } catch (Control.Continue c) {
                continue;
            }
        }
    }
}
