/*
 * $Id: MapELResolver.java,v 1.3 2009/04/26 07:10:02 danielyuan Exp $
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

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Collections;
import java.util.HashMap;
import java.beans.FeatureDescriptor;
import javax.el.ELResolver;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;

@SuppressWarnings("unchecked")
public class MapELResolver extends ELResolver
{
    private boolean isReadOnly;

    public MapELResolver() {
        this.isReadOnly = false;
    }

    public MapELResolver(boolean isReadOnly) {
        this.isReadOnly = isReadOnly;
    }

    public Class<?> getType(ELContext context, Object base, Object property) {
        if (base instanceof Map) {
            context.setPropertyResolved(true);
            return Object.class;
        }
        return null;
    }

    public Object getValue(ELContext context, Object base, Object property) {
        if (base instanceof Map) {
            context.setPropertyResolved(true);
            return ((Map)base).get(property);
        }
        return null;
    }

    private static Class<?> theUnmodifiableMapClass =
        Collections.unmodifiableMap(new HashMap()).getClass();

    public void setValue(ELContext context, Object base, Object property, Object value) {
        if (base instanceof Map) {
            if (isReadOnly || base.getClass() == theUnmodifiableMapClass) {
                throw new PropertyNotWritableException();
            }

            context.setPropertyResolved(true);
            ((Map)base).put(property, value);
        }
    }

    public boolean isReadOnly(ELContext context, Object base, Object property) {
        if (base instanceof Map) {
            context.setPropertyResolved(true);
            return isReadOnly || base.getClass() == theUnmodifiableMapClass;
        }
        return false;
    }

    public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext context, Object base) {
        if (base != null && base instanceof Map) {
            Map map = (Map) base;
            Iterator iter = map.keySet().iterator();
            List<FeatureDescriptor> list = new ArrayList<FeatureDescriptor>();
            while (iter.hasNext()) {
                Object key = iter.next();
                FeatureDescriptor descriptor = new FeatureDescriptor();
                String name = (key==null) ? null : key.toString();
                descriptor.setName(name);
                descriptor.setDisplayName(name);
                descriptor.setShortDescription("");
                descriptor.setExpert(false);
                descriptor.setHidden(false);
                descriptor.setPreferred(true);
                descriptor.setValue("type", key==null ? null : key.getClass());
                descriptor.setValue("resolvableAtDesignTime", Boolean.TRUE);
                list.add(descriptor);
            }
            return list.iterator();
        }
        return null;
    }

    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        if (base instanceof Map) {
            return Object.class;
        }
        return null;
    }
}
