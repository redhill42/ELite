/*
 * $Id: Cons.java,v 1.7 2009/05/17 18:02:15 danielyuan Exp $
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

import java.util.Collection;
import elite.lang.Seq;
import org.operamasks.el.eval.TypeCoercion;

public class Cons extends AbstractSeq implements Cloneable
{
    protected Object head;
    protected Seq tail;

    public Cons() {
        this(null, null);
    }

    public Cons(Object head, Seq tail) {
        this.head = head;
        this.tail = tail;
    }

    public static Cons nil() {
        return new Cons();
    }

    public static Cons make(Object x) {
        return new Cons(x, nil());
    }

    public static Cons make(Object x1, Object x2) {
        return new Cons(x1, new Cons(x2, nil()));
    }

    public static Cons make(Object x1, Object x2, Object x3) {
        return new Cons(x1, new Cons(x2, new Cons(x3, nil())));
    }

    public static Cons make(Object... args) {
        Cons ret = nil();
        for (int i = args.length; --i >= 0; ) {
            ret = new Cons(args[i], ret);
        }
        return ret;
    }

    public Object get() {
        return head;
    }

    public Object set(Object x) {
        Object old = head;
        head = x;
        return old;
    }

    public Seq tail() {
        return tail;
    }

    public void set_tail(Seq t) {
        tail = t;
    }
    
    public boolean isEmpty() {
        return tail == null;
    }

    public int size() {
        Cons l = this;
        int len = 0;
        while (l.tail != null) {
            len++;
            if (l.tail instanceof Cons) {
                l = (Cons)l.tail;
            } else {
                len += l.tail.size();
                break;
            }
        }
        return len;
    }

    @SuppressWarnings("unchecked")
    public boolean add(Object x) {
        Cons l = this;
        while (l.tail != null) {
            if (l.tail instanceof Cons) {
                l = (Cons)l.tail;
            } else {
                break;
            }
        }

        if (l.tail == null) {
            l.head = x;
            l.tail = nil();
            return true;
        } else {
            return l.tail.add(x);
        }
    }

    @SuppressWarnings("unchecked")
    public void add(int index, Object x) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("Index:"+index);
        } else if (index == 0) {
            if (tail == null) {
                head = x;
                tail = nil();
            } else {
                tail = new Cons(head, tail);
                head = x;
            }
        } else {
            tail.add(index-1, x);
        }
    }

    @SuppressWarnings("unchecked")
    public boolean addAll(Collection c) {
        if (c.isEmpty()) {
            return false;
        }
        
        if (tail == null) {
            Seq s = TypeCoercion.coerceToSeq(c);
            head = s.get();
            tail = s.tail();
            return true;
        }

        Cons l = this;
        while (l.tail instanceof Cons) {
            Cons t = (Cons)l.tail;
            if (t.tail == null) {
                l.tail = TypeCoercion.coerceToSeq(c);
                return true;
            }
            l = t;
        }

        return l.tail.addAll(c);
    }

    public Object remove() {
        Object old = head;
        if (tail != null) {
            head = tail.get();
            tail = tail.tail();
        }
        return old;
    }

    public void clear() {
        head = null;
        tail = null;
    }

    public Cons clone() {
        try {
            Cons s = (Cons)super.clone();
            if (tail instanceof Cons) {
                tail = ((Cons)tail).clone();
            }
            return s;
        } catch (CloneNotSupportedException ex) {
            throw new InternalError();
        }
    }
}
