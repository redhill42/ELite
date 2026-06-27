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

import java.util.Collection;
import elite.lang.Seq;
import org.elite.eval.TypeCoercion;

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

    public Object head() {
        return head;
    }

    public Object set_head(Object x) {
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
            head = s.head();
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
            head = tail.head();
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
