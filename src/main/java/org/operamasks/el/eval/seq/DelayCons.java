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
