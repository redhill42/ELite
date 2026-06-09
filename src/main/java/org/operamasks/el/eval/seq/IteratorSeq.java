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
