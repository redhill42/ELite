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

package org.elite.eval.seq;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.AbstractList;
import java.util.RandomAccess;
import java.io.Serializable;

import javax.el.ELContext;
import elite.lang.Seq;
import elite.lang.Closure;

public abstract class AbstractSeq extends AbstractList
    implements Seq, Serializable
{
    public boolean isEmpty() {
        return false;
    }

    public int size() {
        return isEmpty() ? 0 : 1 + tail().size();
    }

    public Object set_head(Object x) {
        throw new UnsupportedOperationException();
    }

    public Object remove() {
        throw new UnsupportedOperationException();
    }

    public void set_tail(Seq t) {
        throw new UnsupportedOperationException();
    }

    public Seq last() {
        if (isEmpty()) {
            return this;
        }

        Seq l = this;
        while (true) {
            Seq t = l.tail();
            if (t.isEmpty()) {
                return l;
            }
            l = t;
        }
    }

    public Seq reverse() {
        Seq rev = new Cons();
        for (Seq l = this; !l.isEmpty(); l = l.tail()) {
            rev = new Cons(l.head(), rev);
        }
        return rev;
    }

    public Seq append(Seq xs) {
        return AppendSeq.make(this, xs);
    }

    static class AppendSeq extends DelaySeq {
        private Seq xs, ys;

        private AppendSeq(Seq xs, Seq ys) {
            this.xs = xs;
            this.ys = ys;
        }

        static Seq make(Seq xs, Seq ys) {
            if (xs.isEmpty())
                return ys;
            if (ys.isEmpty())
                return xs;
            return new AppendSeq(xs, ys);
        }

        public boolean isEmpty() {
            return false;
        }
        
        protected void force(ELContext elctx) {
            if (xs != null) {
                head = xs.head();
                tail = make(xs.tail(), ys);
                xs = ys = null;
            }
        }
    }

    public Seq map(Closure proc) {
        return MappedSeq.make(this, proc);
    }

    public Seq filter(Closure pred) {
        return FilteredSeq.make(this, pred);
    }

    public Seq mappend(Closure proc) {
        return MappendSeq.make(this, proc);
    }
    
    public Object get(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("Index: "+index);
        }

        Seq t = this;
        for (int i = index; !(t == null || t.isEmpty()); i--) {
            if (t != this && t instanceof RandomAccess) {
                return t.get(i);
            } else if (i == 0) {
                return t.head();
            } else {
                t = t.tail();
            }
        }

        throw new IndexOutOfBoundsException("Index: "+index);
    }

    @SuppressWarnings("unchecked")
    public Object set(int index, Object x) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("Index: "+index);
        }

        Seq t = this;
        for (int i = index; !(t == null || t.isEmpty()); i--) {
            if (t != this && t instanceof RandomAccess) {
                return t.set(i, x);
            } else if (i == 0) {
                return t.set_head(x);
            } else {
                t = t.tail();
            }
        }

        throw new IndexOutOfBoundsException("Index: "+index);
    }

    public Object remove(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("Index: "+index);
        }

        Seq t = this;
        for (int i = index; !(t == null || t.isEmpty()); i--) {
            if (t != this && t instanceof RandomAccess) {
                return t.remove(i);
            } else if (i == 0) {
                return t.remove();
            } else {
                t = t.tail();
            }
        }

        throw new IndexOutOfBoundsException("Index: "+index);
    }
    
    public Iterator iterator() {
        if (this instanceof RandomAccess) {
            return super.iterator();
        } else {
            return new Itr(this);
        }
    }

    private static class Itr implements Iterator {
        protected Seq next, lastRet;

        public Itr(Seq seq) {
            this.next = seq;
        }

        public boolean hasNext() {
            return !next.isEmpty();
        }

        public Object next() {
            if (next.isEmpty()) {
                throw new NoSuchElementException();
            }
            lastRet = next;
            next = next.tail();
            return lastRet.head();
        }

        public void remove() {
            if (lastRet == null) {
                throw new NoSuchElementException();
            }
            lastRet.remove();
            next = lastRet;
            lastRet = null;
        }
    }
}
