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
