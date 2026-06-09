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

    public Object head() {
        return list.get(offset);
    }

    public Object set_head(Object x) {
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
