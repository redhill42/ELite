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

package org.operamasks.el.resolver;

import java.util.List;
import java.util.Collection;
import java.util.Iterator;
import java.util.Collections;
import java.util.ArrayList;
import java.lang.reflect.Array;
import java.beans.FeatureDescriptor;
import javax.el.ELResolver;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;
import elite.lang.Range;

@SuppressWarnings("unchecked")
public class ListELResolver extends ELResolver
{
    private boolean isReadOnly;

    public ListELResolver() {
        this.isReadOnly = false;
    }

    public ListELResolver(boolean isReadOnly) {
        this.isReadOnly = isReadOnly;
    }

    public Class<?> getType(ELContext context, Object base, Object property) {
        if (base instanceof List) {
            context.setPropertyResolved(true);
            if (property instanceof Range) {
                return List.class;
            } else if ("length".equals(property) || "size".equals(property)) {
                return Integer.class;
            } else {
                return Object.class;
            }
        }
        return null;
    }

    public Object getValue(ELContext context, Object base, Object property) {
        if (!(base instanceof List)) {
            return null;
        }

        List list = (List)base;
        Object result = null;

        if (property instanceof String) {
            if ("length".equals(property) || "size".equals(property)) {
                result = list.size();
                context.setPropertyResolved(true);
            } else if ("first".equals(property)) {
                result = list.isEmpty() ? null : list.get(0);
                context.setPropertyResolved(true);
            } else if ("last".equals(property)) {
                result = list.isEmpty() ? null : list.get(list.size()-1);
                context.setPropertyResolved(true);
            } else if ("rest".equals(property)) {
                result = list.isEmpty() ? list : list.subList(1, list.size());
                context.setPropertyResolved(true);
            }
        } else if (property instanceof Range) {
            result = extractRange(list, (Range)property);
            context.setPropertyResolved(true);
        } else if (property instanceof Number) {
            try {
                result = list.get(((Number)property).intValue());
            } catch (IndexOutOfBoundsException ex) {
                result = null;
            }
            context.setPropertyResolved(true);
        } else if ((property instanceof List) && ((List)property).isEmpty()) {
            // handle empty range
            result = Collections.EMPTY_LIST;
            context.setPropertyResolved(true);
        }

        return result;
    }

    private static Class<?> theUnmodifiableListClass =
        Collections.unmodifiableList(new ArrayList()).getClass();

    public void setValue(ELContext context, Object base, Object property, Object value) {
        if (!(base instanceof List)) {
            return;
        }

        if (isReadOnly || base.getClass() == theUnmodifiableListClass) {
            throw new PropertyNotWritableException();
        }

        List list = (List)base;

        if (property instanceof String) {
            if ("length".equals(property) || "size".equals(property) || "rest".equals(property)) {
                throw new PropertyNotWritableException(property.toString());
            } else if ("first".equals(property)) {
                set(list, 0, value);
                context.setPropertyResolved(true);
            } else if ("last".equals(property)) {
                set(list, list.size()-1, value);
                context.setPropertyResolved(true);
            }
        } else if (property instanceof Range) {
            if (value != null && value.getClass().isArray()) {
                copyRangeWithArray(list, (Range)property, value);
            } else if (value instanceof Collection) {
                copyRangeWithCollection(list, (Range)property, (Collection)value);
            } else {
                copyRangeWithSingle(list, (Range)property, value);
            }
            context.setPropertyResolved(true);
        } else if (property instanceof Number) {
            int index = ((Number)property).intValue();
            set(list, index, value);
            context.setPropertyResolved(true);
        }
    }

    public boolean isReadOnly(ELContext context, Object base, Object property) {
        if (base instanceof List) {
            context.setPropertyResolved(true);
            return isReadOnly || base.getClass() == theUnmodifiableListClass;
        }
        return false;
    }

    public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext context, Object base) {
        return null;
    }

    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        if (base instanceof List) {
            return Integer.class;
        }
        return null;
    }

    // Implementation -----------------

    private void set(List base, int index, Object value) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("Index:"+index);
        }

        if (index < base.size()) {
            base.set(index, value);
        } else if (value != null) {
            if (base instanceof ArrayList) {
                ((ArrayList)base).ensureCapacity(index+1);
            }
            while (base.size() < index) {
                base.add(null);
            }
            base.add(value);
        }
    }

    private List extractRange(List base, Range range) {
        long begin  = range.getBegin();
        long end    = range.getEnd();
        long step   = range.getStep();
        long length = base.size();

        if (range.isUnbound()) {
            end = step > 0 ? length : 0;
        }
        if ((begin < 0 && end < 0) || (begin >= length && end >= length)) {
            return new ArrayList();
        }

        int size;
        if (step > 0) {
            begin = begin < 0 ? 0 : begin;
            end = end >= length ? length-1 : end;
            assert begin < length && end < length && end >= begin;
            size = (int)((end - begin + step) / step);
        } else {
            end = end < 0 ? 0 : end;
            begin = begin >= length ? length-1 : begin;
            assert begin < length && end < length && begin >= end;
            size = (int)((begin - end - step) / -step);
        }

        List list = new ArrayList(size);
        for (int i = 0; i < size; i++, begin += step) {
            list.add(base.get((int)begin));
        }
        return list;
    }

    private void copyRangeWithArray(List base, Range range, Object value) {
        long begin  = range.getBegin();
        long end    = range.getEnd();
        long step   = range.getStep();
        long length = base.size();
        
        if (range.isUnbound()) {
            if (step > 0) {
                if (begin >= length)
                    return;
                end = length - 1;
            } else {
                if (begin < 0)
                    return;
                end = 0;
            }
        } else {
            if (begin < 0 && end < 0) {
                return;  // out of range
            }
        }

        int size;
        if (step > 0) {
            begin = begin < 0 ? 0 : begin;
            assert end >= begin;
            size = (int)((end - begin + step) / step);
        } else {
            end = end < 0 ? 0 : end;
            assert begin >= end;
            size = (int)((begin - end - step) / -step);
        }

        int value_size = Array.getLength(value);
        if (size > value_size) {
            size = value_size;
        }

        for (int i = 0; i < size; i++, begin += step) {
            set(base, (int)begin, Array.get(value, i));
        }
    }

    private void copyRangeWithCollection(List base, Range range, Collection value) {
        long begin  = range.getBegin();
        long end    = range.getEnd();
        long step   = range.getStep();
        long length = base.size();

        if (range.isUnbound()) {
            if (step > 0) {
                if (begin >= length)
                    return;
                end = length - 1;
            } else {
                if (begin < 0)
                    return;
                end = 0;
            }
        } else {
            if (begin < 0 && end < 0) {
                return;  // out of range
            }
        }

        int size;
        if (step > 0) {
            begin = begin < 0 ? 0 : begin;
            assert end >= begin;
            size = (int)((end - begin + step) / step);
        } else {
            end = end < 0 ? 0 : end;
            assert begin >= end;
            size = (int)((begin - end - step) / -step);
        }

        Iterator it = value.iterator();
        for (int i = 0; i < size && it.hasNext(); i++, begin += step) {
            set(base, (int)begin, it.next());
        }
    }

    private void copyRangeWithSingle(List base, Range range, Object value) {
        long begin  = range.getBegin();
        long end    = range.getEnd();
        long step   = range.getStep();
        long length = base.size();

        if (range.isUnbound()) {
            if (step > 0) {
                if (begin >= length)
                    return;
                end = length - 1;
            } else {
                if (begin < 0)
                    return;
                end = 0;
            }
        } else {
            if (begin < 0 && end < 0) {
                return;  // out of range
            }
        }

        int size;
        if (step > 0) {
            begin = begin < 0 ? 0 : begin;
            assert end >= begin;
            size = (int)((end - begin + step) / step);
        } else {
            end = end < 0 ? 0 : end;
            assert begin >= end;
            size = (int)((begin - end - step) / -step);
        }

        for (int i = 0; i < size; i++, begin += step) {
            set(base, (int)begin, value);
        }
    }
}
