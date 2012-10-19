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
import org.operamasks.el.eval.TypeCoercion;

public class DelayCons extends DelaySeq
{
    private Closure head_promise;
    private Closure tail_promise;

    public DelayCons(Closure head_promise, Closure tail_promise) {
        this.head_promise = head_promise;
        this.tail_promise = tail_promise;
    }

    protected void force(ELContext elctx) {
        if (head_promise != null) {
            head = head_promise.getValue(elctx);
            head_promise = null;
        }

        if (tail_promise != null) {
            tail = TypeCoercion.coerceToSeq(tail_promise.getValue(elctx));
            tail_promise = null;
        }
    }
}
