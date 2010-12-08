/*
 * $Id: ArrayELResolver.java,v 1.6 2009/05/04 08:35:55 jackyzhang Exp $
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

package org.operamasks.el.resolver;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.lang.reflect.Array;
import java.beans.FeatureDescriptor;
import javax.el.ELResolver;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;
import elite.lang.Range;
import static org.operamasks.el.eval.TypeCoercion.*;

public class ArrayELResolver extends ELResolver
{
    private boolean isReadOnly;

    public ArrayELResolver() {
        this.isReadOnly = false;
    }

    public ArrayELResolver(boolean isReadOnly) {
        this.isReadOnly = isReadOnly;
    }

    public Class<?> getType(ELContext elctx, Object base, Object property) {
        if (base != null && base.getClass().isArray()) {
            if (property instanceof Range) {
                elctx.setPropertyResolved(true);
                return base.getClass();
            } else if ("length".equals(property) || "size".equals(property)) {
                elctx.setPropertyResolved(true);
                return Integer.class;
            } else if (property instanceof Number) {
                elctx.setPropertyResolved(true);
                return base.getClass().getComponentType();
            }
        }
        return null;
    }

    public Object getValue(ELContext elctx, Object base, Object property) {
        if (base == null || !base.getClass().isArray()) {
            return null;
        }

        if (property instanceof Range) {
            elctx.setPropertyResolved(true);
            return extractRange(base, (Range)property);
        } else if (property instanceof String) {
            if ("length".equals(property) || "size".equals(property)) {
                elctx.setPropertyResolved(true);
                return Array.getLength(base);
            } else if ("first".equals(property)) {
                elctx.setPropertyResolved(true);
                if (Array.getLength(base) > 0) {
                    return Array.get(base, 0);
                }
            } else if ("last".equals(property)) {
                elctx.setPropertyResolved(true);
                int length = Array.getLength(base);
                if (length > 0) {
                    return Array.get(base, length - 1);
                }
            }
        } else if (property instanceof Number) {
            elctx.setPropertyResolved(true);
            int length = Array.getLength(base);
            int index = ((Number)property).intValue();
            if (index >= 0 && index < length) {
                return Array.get(base, index);
            }
        } else if ((property instanceof List) && ((List)property).isEmpty()) {
            // handle empty range
            elctx.setPropertyResolved(true);
            return Array.newInstance(base.getClass().getComponentType(), 0);
        }
        return null;
    }

    public void setValue(ELContext elctx, Object base, Object property, Object value) {
        if (base == null || !base.getClass().isArray()) {
            return;
        }

        if (isReadOnly) {
            throw new PropertyNotWritableException();
        }

        if (property instanceof Range) {
            if (value != null && value.getClass().isArray()) {
                copyRangeWithArray(elctx, base, (Range)property, value);
            } else if (value instanceof Collection) {
                copyRangeWithCollection(elctx, base, (Range)property, (Collection)value);
            } else {
                copyRangeWithSingle(elctx, base, (Range)property, value);
            }
            elctx.setPropertyResolved(true);
        } else if (property instanceof String) {
            if ("length".equals(property) || "size".equals(property)) {
                throw new PropertyNotWritableException();
            }
        } else if (property instanceof Number) {
            Class<?> type = base.getClass().getComponentType();
            int length = Array.getLength(base);
            int index = ((Number)property).intValue();
            rangeCheck(index, length);
            Array.set(base, index, coerce(elctx, value, type));
            elctx.setPropertyResolved(true);
        }
    }

    public boolean isReadOnly(ELContext elctx, Object base, Object property) {
        if (base != null && base.getClass().isArray()) {
            elctx.setPropertyResolved(true);
        }
        return isReadOnly;
    }

    public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext elctx, Object base) {
        return null;
    }

    public Class<?> getCommonPropertyType(ELContext elctx, Object base) {
        if (base != null && base.getClass().isArray()) {
            return Integer.class;
        }
        return null;
    }

    // Implementation -----------------

    private Object extractRange(Object base, Range range) {
        Class<?> type = base.getClass().getComponentType();

        long begin  = range.getBegin();
        long end    = range.getEnd();
        long step   = range.getStep();
        long length = Array.getLength(base);

        if (range.isUnbound()) {
            end = step > 0 ? length : 0;
        }
        if ((begin < 0 && end < 0) || (begin >= length && end >= length)) {
            return Array.newInstance(type, 0); // out of range
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

        Object array = Array.newInstance(type, size);
        if (step == 1) {
            System.arraycopy(base, (int)begin, array, 0, size);
        } else {
            for (int i = 0; i < size; i++, begin += step) {
                Array.set(array, i, Array.get(base, (int)begin));
            }
        }
        return array;
    }

    private void copyRangeWithArray(ELContext elctx, Object base, Range range, Object value) {
        long begin  = range.getBegin();
        long end    = range.getEnd();
        long step   = range.getStep();
        long length = Array.getLength(base);

        if (range.isUnbound()) {
            end = step > 0 ? length : 0;
        }
        if ((begin < 0 && end < 0) || (begin >= length && end >= length)) {
            return;  // out of range
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

        int value_size = Array.getLength(value);
        if (size > value_size) {
            size = value_size;
        }

        if (step == 1 && value.getClass() == base.getClass()) {
            System.arraycopy(value, 0, base, (int)begin, size);
        } else {
            Class<?> type = base.getClass().getComponentType();
            for (int i = 0; i < size; i++, begin += step) {
                Array.set(base, (int)begin, coerce(elctx, Array.get(value, i), type));
            }
        }
    }

    private void copyRangeWithCollection(ELContext elctx, Object base, Range range, Collection value) {
        long begin  = range.getBegin();
        long end    = range.getEnd();
        long step   = range.getStep();
        long length = Array.getLength(base);

        if (range.isUnbound()) {
            end = step > 0 ? length : 0;
        }
        if ((begin < 0 && end < 0) || (begin >= length && end >= length)) {
            return;  // out of range
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

        Class<?> type = base.getClass().getComponentType();
        Iterator it = value.iterator();
        for (int i = 0; i < size && it.hasNext(); i++, begin += step) {
            Array.set(base, (int)begin, coerce(elctx, it.next(), type));
        }
    }

    private void copyRangeWithSingle(ELContext elctx, Object base, Range range, Object value) {
        long begin  = range.getBegin();
        long end    = range.getEnd();
        long step   = range.getStep();
        long length = Array.getLength(base);

        if (range.isUnbound()) {
            end = step > 0 ? length : 0;
        }
        if ((begin < 0 && end < 0) || (begin >= length && end >= length)) {
            return;  // out of range
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

        value = coerce(elctx, value, base.getClass().getComponentType());
        for (int i = 0; i < size; i++, begin += step) {
            Array.set(base, (int)begin, value);
        }
    }

    private void rangeCheck(int index, int length) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("Array index out of bounds: " + index);
        }
    }
}
