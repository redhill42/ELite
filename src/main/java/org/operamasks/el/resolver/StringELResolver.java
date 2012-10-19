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

package org.operamasks.el.resolver;

import java.beans.FeatureDescriptor;
import java.util.Iterator;
import java.util.List;
import javax.el.ELResolver;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;
import elite.lang.Range;

public class StringELResolver extends ELResolver
{
    public Class<?> getType(ELContext context, Object base, Object property) {
        if ((base instanceof CharSequence) && (property != null)) {
            if (property instanceof Range) {
                context.setPropertyResolved(true);
                return base.getClass();
            } else if ("length".equals(property) || "size".equals(property)) {
                context.setPropertyResolved(true);
                return Integer.class;
            } else if (property instanceof Number) {
                context.setPropertyResolved(true);
                return Character.TYPE;
            }
        }
        return null;
    }

    public Object getValue(ELContext context, Object base, Object property) {
        if ((base instanceof CharSequence) && (property != null)) {
            CharSequence cs = (CharSequence)base;
            if (property instanceof Range) {
                context.setPropertyResolved(true);
                return getSubSequence(cs, (Range)property);
            } else if (property instanceof String) {
                if ("length".equals(property) || "size".equals(property)) {
                    context.setPropertyResolved(true);
                    return cs.length();
                } else if ("first".equals(property)) {
                    context.setPropertyResolved(true);
                    return cs.charAt(0);
                } else if ("last".equals(property)) {
                    context.setPropertyResolved(true);
                    return cs.charAt(cs.length()-1);
                }
            } else if (property instanceof Number) {
                int length = cs.length();
                int index = ((Number)property).intValue();
                if (index >= 0 && index < length) {
                    context.setPropertyResolved(true);
                    return cs.charAt(index);
                }
            } else if ((property instanceof List) && ((List)property).isEmpty()) {
                // handle empty range
                context.setPropertyResolved(true);
                return "";
            }
        }
        return null;
    }

    public void setValue(ELContext context, Object base, Object property, Object value) {
        if ((base instanceof CharSequence) && (property != null)) {
            context.setPropertyResolved(true);
            throw new PropertyNotWritableException();
        }
    }

    public boolean isReadOnly(ELContext context, Object base, Object property) {
        if (base instanceof CharSequence) {
            context.setPropertyResolved(true);
            return true;
        } else {
            return false;
        }
    }

    public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext context, Object base) {
        return null;
    }

    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        if (base instanceof CharSequence) {
            return Integer.class;
        }
        return null;
    }

    // Implementation -----------------------

    private CharSequence getSubSequence(CharSequence cs, Range range) {
        long begin   = range.getBegin();
        long end     = range.getEnd();
        long step    = range.getStep();
        long length  = cs.length();

        if (range.isUnbound()) {
            end = step > 0 ? length : 0;
        }
        if ((step < 0) || (begin < 0 && end < 0) || (begin >= length && end >= length)) {
            return cs.subSequence(0, 0);
        }

        begin = begin < 0 ? 0 : begin;
        end = end >= length ? length : end+1;
        assert begin <= length && end <= length && end >= begin;
        return cs.subSequence((int)begin, (int)end);
    }
}
