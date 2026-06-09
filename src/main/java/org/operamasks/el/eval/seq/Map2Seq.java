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
            Object x = t1.head();
            Object y = t2.head();
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
