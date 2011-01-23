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
import elite.lang.Seq;
import static org.operamasks.el.eval.ELEngine.getCurrentELContext;

public abstract class DelaySeq extends AbstractSeq
{
    protected Object head;
    protected Seq tail;

    public Object get() {
        force(getCurrentELContext());
        return head;
    }

    public Object set(Object x) {
        Object old = get();
        head = x;
        return old;
    }

    public Seq tail() {
        force(getCurrentELContext());
        return tail;
    }

    public void set_tail(Seq t) {
        force(getCurrentELContext());
        tail = t;
    }

    public boolean isEmpty() {
        return tail() == null;
    }

    protected abstract void force(ELContext elctx);
}
