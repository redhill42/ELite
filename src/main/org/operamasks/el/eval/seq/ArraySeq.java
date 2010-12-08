/*
 * $Id: ArraySeq.java,v 1.5 2009/05/04 08:35:55 jackyzhang Exp $
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

import java.util.RandomAccess;
import elite.lang.Seq;
import org.operamasks.el.eval.TypeCoercion;

public class ArraySeq extends AbstractSeq implements RandomAccess
{
    private final Object[] value;
    private final Class type;
    private final int offset;
    private final int count;

    private ArraySeq(Object[] value, int offset, int count) {
        this.value = value;
        this.type = value.getClass().getComponentType();
        this.offset = offset;
        this.count = count;
    }

    public static Seq make(Object[] value) {
        return make(value, 0, value.length);
    }

    public static Seq make(Object[] value, int offset, int count) {
        if (count > 0) {
            return new ArraySeq(value, offset, count);
        } else {
            return Cons.nil();
        }
    }

    public Object get() {
        return value[offset];
    }

    public Object set(Object x) {
        Object old = value[offset];
        value[offset] = TypeCoercion.coerce(x, type);
        return old;
    }

    public Object get(int index) {
        if (index < 0 || index >= count)
            throw new IndexOutOfBoundsException("Index:"+index);
        return value[offset + index];
    }

    public Object set(int index, Object x) {
        if (index < 0 || index >= count)
            throw new IndexOutOfBoundsException("Index:"+index);
        Object old = value[offset + index];
        value[offset + index] = TypeCoercion.coerce(x, type);
        return old;
    }

    public Seq tail() {
        return make(value, offset+1, count-1);
    }

    public Seq last() {
        if (count == 1) {
            return this;
        } else {
            return make(value, offset+count-1, 1);
        }
    }

    public int size() {
        return count;
    }
}
