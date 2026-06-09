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

    public Object head() {
        return value[offset];
    }

    public Object set_head(Object x) {
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
