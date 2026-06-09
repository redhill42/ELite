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
import elite.lang.Seq;
import static org.operamasks.el.eval.ELEngine.getCurrentELContext;

public abstract class DelaySeq extends AbstractSeq
{
    protected Object head;
    protected Seq tail;

    public Object head() {
        force(getCurrentELContext());
        return head;
    }

    public Object set_head(Object x) {
        Object old = head();
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
