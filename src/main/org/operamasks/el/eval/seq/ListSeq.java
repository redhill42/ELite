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

import java.util.List;
import java.util.ListIterator;
import java.util.Collection;
import java.util.RandomAccess;
import java.util.NoSuchElementException;
import elite.lang.Seq;

@SuppressWarnings("unchecked")
public class ListSeq extends AbstractSeq
{
    private final List list;
    private final int offset;
    private Seq tail;

    protected ListSeq(List list, int offset) {
        this.list = list;
        this.offset = offset;
    }

    public static Seq make(List list) {
        return make(list, 0);
    }

    public static Seq make(List list, int offset) {
        if (offset < list.size()) {
            if (list instanceof RandomAccess) {
                return new RandomAccessListSeq(list, offset);
            } else {
                return new ListSeq(list, offset);
            }
        } else {
            return Cons.nil();
        }
    }

    public Object get() {
        return list.get(offset);
    }

    public Object set(Object x) {
        return list.set(offset, x);
    }

    public Object remove() {
        return list.remove(offset);
    }

    public Seq tail() {
        if (tail == null)
            tail = make(list, offset+1);
        return tail;
    }

    public Seq last() {
        if (offset+1 >= list.size()) {
            return this;
        } else {
            return make(list, list.size()-1);
        }
    }

    public int size() {
        return list.size() - offset;
    }

    public boolean isEmpty() {
        return offset >= list.size();
    }

    //---------------------------------------

    public Object get(int index) {
        rangeCheck(index);
        return list.get(offset + index);
    }

    public Object set(int index, Object x) {
        rangeCheck(index);
        return list.set(offset + index, x);
    }

    public boolean add(Object x) {
        return list.add(x);
    }

    public void add(int index, Object x) {
        rangeCheck(index);
        list.add(offset + index, x);
    }

    public Object remove(int index) {
        rangeCheck(index);
        return list.remove(offset + index);
    }

    public boolean addAll(Collection c) {
        return list.addAll(c);
    }

    public boolean addAll(int index, Collection c) {
        rangeCheck(index);
        return list.addAll(offset+index, c);
    }

    public java.util.Iterator iterator() {
        return listIterator();
    }

    public ListIterator listIterator(int index) {
        rangeCheck(index);
        final ListIterator i = list.listIterator(offset+index);
        return new ListIterator() {
            public boolean hasNext() { return i.hasNext(); }
            public Object next() { return i.next(); }
            public boolean hasPrevious() { return previousIndex() >= 0; }
            public Object previous() {
                if (hasPrevious()) {
                    return i.previous();
                } else {
                    throw new NoSuchElementException();
                }
            }
            public int nextIndex() { return i.nextIndex() - offset; }
            public int previousIndex() { return i.previousIndex() - offset; }
            public void remove() { i.remove(); }
            public void set(Object o) { i.set(o); }
            public void add(Object o) { i.add(o); }
        };
    }

    private void rangeCheck(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("Index:"+index);
        }
    }
}

class RandomAccessListSeq extends ListSeq implements RandomAccess
{
    public RandomAccessListSeq(List list, int offset) {
        super(list, offset);
    }
}
