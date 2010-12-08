/*
 * $Id: Map2Seq.java,v 1.4 2009/05/14 06:12:50 danielyuan Exp $
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
import elite.lang.Seq;
import elite.lang.Closure;
import org.operamasks.el.eval.Control;

public class Map2Seq extends DelaySeq
{
    protected Seq s1, s2;
    protected Closure proc;

    private Map2Seq(Seq s1, Seq s2, Closure proc) {
        this.s1 = s1;
        this.s2 = s2;
        this.proc = proc;
    }

    public static Seq make(Seq s1, Seq s2, Closure proc) {
        return new Map2Seq(s1, s2, proc);
    }

    protected void force(ELContext elctx) {
        if (s1 == null || s2 == null) {
            return;
        }

        Seq t1 = s1, t2 = s2;
        Closure p = proc;
        s1 = s2 = null;
        proc = null;

        while (!(t1.isEmpty() || t2.isEmpty())) {
            Object x = t1.get();
            Object y = t2.get();
            t1 = t1.tail();
            t2 = t2.tail();
            try {
                head = p.call(elctx, x, y);
                tail = new Map2Seq(t1, t2, p);
                break;
            } catch (Control.Break b) {
                break;
            } catch (Control.Continue c) {
                continue;
            }
        }
    }
}
