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

package org.elite.resolver;

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
