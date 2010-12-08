/*
 * $Id: MappedSeq.java,v 1.5 2009/05/14 06:12:50 danielyuan Exp $
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

package org.operamasks.el.eval.seq;

import javax.el.ELContext;
import elite.lang.Closure;
import elite.lang.Seq;
import org.operamasks.el.eval.Control;

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
            x = t.get(); 
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
