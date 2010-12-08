/*
 * $Id: AbstractSeq.java,v 1.6 2009/05/17 18:02:15 danielyuan Exp $
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

    public Object set(Object x) {
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
            rev = new Cons(l.get(), rev);
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
                head = xs.get();
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
                return t.get();
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
                return t.set(x);
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
            return lastRet.get();
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
