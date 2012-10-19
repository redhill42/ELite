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

package org.operamasks.util;

import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.Collection;
import java.beans.IntrospectionException;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class BeanUtils
{
    private static final SimpleCache<Class,BeanProperties> cache = SimpleCache.make(1000);

    private static final class BeanProperties {
        private final Map<String,BeanProperty> properties;
        private final Class<?> type;

        BeanProperties(Class<?> type) throws IntrospectionException {
            this.type = type;
            this.properties = new HashMap<String,BeanProperty>();
            BeanInfo info = Introspector.getBeanInfo(type);
            for (PropertyDescriptor pd : info.getPropertyDescriptors()) {
                properties.put(pd.getName(), new BeanProperty(type, pd));
            }
        }

        public Class<?> getType() {
            return type;
        }

        public BeanProperty get(String name) {
            return properties.get(name); // MAY BE NULL
        }

        public Collection<BeanProperty> getAll() {
            return Collections.unmodifiableCollection(properties.values());
        }
    }

    public static final Collection<BeanProperty> getProperties(Class<?> type)
        throws IntrospectionException
    {
        BeanProperties properties = cache.get(type);
        if (properties == null) {
            properties = new BeanProperties(type);
            cache.put(type, properties);
        }
        return properties.getAll();
    }

    public static final BeanProperty getProperty(Class<?> type, String name)
        throws IntrospectionException
    {
        BeanProperties properties = cache.get(type);
        if (properties == null) {
            properties = new BeanProperties(type);
            cache.put(type, properties);
        }
        return properties.get(name);
    }

    public static final Method getReadMethod(Class<?> type, String name)
        throws IntrospectionException
    {
        BeanProperty property = getProperty(type, name);
        return (property == null) ? null : property.getReadMethod();
    }

    public static final Method getWriteMethod(Class<?> type, String name)
        throws IntrospectionException
    {
        BeanProperty property = getProperty(type, name);
        return (property == null) ? null : property.getWriteMethod();
    }

    public static final Class<?> getPropertyType(Class<?> type, String name)
        throws IntrospectionException
    {
        BeanProperty property = getProperty(type, name);
        return (property == null) ? null : property.getType();
    }

    static final Method getMethod(Class type, Method m) {
        if (m == null || Modifier.isPublic(type.getModifiers()))
            return m;

        Method mp = null;
        for (Class inf : type.getInterfaces()) {
            try {
                mp = inf.getMethod(m.getName(), (Class[])m.getParameterTypes());
                mp = getMethod(mp.getDeclaringClass(), mp);
                if (mp != null) return mp;
            } catch (NoSuchMethodException ex) {}
        }

        Class sup = type.getSuperclass();
        if (sup != null) {
            try {
                mp = sup.getMethod(m.getName(), (Class[])m.getParameterTypes());
                mp = getMethod(mp.getDeclaringClass(), mp);
                if (mp != null) return mp;
            } catch (NoSuchMethodException ex) {}
        }

        return null;
    }
}
