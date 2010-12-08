/*
 * $Id: IteratorSeq.java,v 1.5 2009/05/14 06:12:50 danielyuan Exp $
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

import java.util.Iterator;
import javax.el.ELContext;
import elite.lang.Seq;

public class IteratorSeq extends DelaySeq
{
    private Iterator iter;

    private IteratorSeq(Iterator iter) {
        this.iter = iter;
    }

    public static Seq make(Iterator iter) {
        if (iter.hasNext()) {
            return new IteratorSeq(iter);
        } else {
            return Cons.nil();
        }
    }

    protected void force(ELContext elctx) {
        if (iter != null) {
            head = iter.next();
            tail = make(iter);
            iter = null;
        }
    }
}
