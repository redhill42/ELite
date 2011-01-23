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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.beans.FeatureDescriptor;
import java.beans.IntrospectionException;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.security.AccessController;
import java.security.PrivilegedAction;
import javax.el.ELResolver;
import javax.el.ELContext;
import javax.el.MethodInfo;
import javax.el.ELException;
import javax.el.PropertyNotWritableException;
import javax.el.PropertyNotFoundException;

import elite.lang.Closure;

import org.operamasks.util.BeanProperty;
import org.operamasks.util.BeanUtils;
import org.operamasks.util.SimpleCache;
import org.operamasks.el.eval.PropertyDelegate;
import org.operamasks.el.eval.PropertyResolvable;
import org.operamasks.el.eval.closure.MethodClosure;
import org.operamasks.el.eval.closure.LiteralClosure;
import org.operamasks.el.eval.closure.ClosureObject;
import org.operamasks.el.eval.closure.ClassDefinition;
import org.operamasks.el.eval.TypeCoercion;

public class BeanPropertyELResolver extends ELResolver
{
    public Class<?> getType(ELContext context, Object base, Object property) {
        if (context == null) {
            throw new NullPointerException();
        }

        if (base == null || property == null) {
            return null;
        }

        // Special properties
        if ("@@class".equals(property)) {
            context.setPropertyResolved(true);
            if (base instanceof ClosureObject) {
                return ClassDefinition.class;
            } else {
                return Class.class;
            }
        }
        
        // Access class field
        if (base instanceof Class) {
            Field field = getBeanField((Class)base, property);
            if (field != null && Modifier.isStatic(field.getModifiers())) {
                context.setPropertyResolved(true);
                return field.getType();
            }
        }

        // Don't expose static properties for a PropertyDelegate
        if (!(base instanceof PropertyDelegate)) {
            // If the property has read or write methods then get the property type
            BeanProperty bp = getBeanProperty(base.getClass(), property);
            if (bp != null) {
                if (bp.getReadMethod() == null) {
                    throw new PropertyNotFoundException();
                } else {
                    context.setPropertyResolved(true);
                    return bp.getType();
                }
            }

            // If field present and accessible then return the field type.
            Field field = getBeanField(base.getClass(), property);
            if (field != null) {
                context.setPropertyResolved(true);
                return field.getType();
            }
        }

        // Return the dynamic property type.
        if (base instanceof PropertyResolvable) {
            Class type = ((PropertyResolvable)base).getType(context, property);
            if (context.isPropertyResolved()) {
                return type;
            }
        }

        // Return the expando dynamic property type.
        if (!(base instanceof ClosureObject)) {
            MethodClosure expando = MethodResolver.getInstance(context)
                .resolveMethod(base.getClass(), "[]");
            if (expando != null) {
                MethodInfo info = expando.getMethodInfo(context);
                context.setPropertyResolved(true);
                return info == null ? null : info.getReturnType();
            }
        }

        throw new PropertyNotFoundException();
    }

    public Object getValue(ELContext context, Object base, Object property) {
        if (context == null) {
            throw new NullPointerException();
        }

        if (base == null || property == null) {
            return null;
        }

        // Special properties
        if ("@@class".equals(property)) {
            context.setPropertyResolved(true);
            if (base instanceof ClosureObject) {
                return ((ClosureObject)base).get_class();
            } else {
                return base.getClass();
            }
        }

        // Access class field
        if (base instanceof Class) {
            Field field = getBeanField((Class)base, property);
            if (field != null && Modifier.isStatic(field.getModifiers())) {
                Object value = getFieldValue(field, null);
                context.setPropertyResolved(true);
                return value;
            }
        }

        // Dont' expose properties for a PropertyDelegate
        if (!(base instanceof PropertyDelegate)) {
            // If the property has read or write methods then get the property value
            BeanProperty bp = getBeanProperty(base.getClass(), property);
            if (bp != null) {
                if (bp.getReadMethod() == null) {
                    throw new PropertyNotFoundException();
                } else {
                    Object value = getPropertyValue(bp.getReadMethod(), base);
                    context.setPropertyResolved(true);
                    return value;
                }
            }

            // If field present and accessible then return the field value.
            Field field = getBeanField(base.getClass(), property);
            if (field != null) {
                Object value = getFieldValue(field, base);
                context.setPropertyResolved(true);
                return value;
            }
        }

        // Return the dynamic property value.
        if (base instanceof PropertyResolvable) {
            Object value = ((PropertyResolvable)base).getValue(context, property);
            if (context.isPropertyResolved()) {
                return value;
            }
        }

        // Return the expando dynamic property value.
        if (!(base instanceof ClosureObject)) {
            MethodClosure expando = MethodResolver.getInstance(context)
                .resolveMethod(base.getClass(), "[]");
            if (expando != null) {
                Closure[] args = { new LiteralClosure(property) };
                Object value = expando.invoke(context, base, args);
                context.setPropertyResolved(true);
                return value;
            }
        }

        throw new PropertyNotFoundException();
    }

    public void setValue(ELContext context, Object base, Object property, Object value) {
        if (context == null) {
            throw new NullPointerException();
        }

        if (base == null || property == null) {
            return;
        }

        // Special property
        if ("@@class".equals(property)) {
            throw new PropertyNotWritableException();
        }

        // Access class field
        if (base instanceof Class) {
            Field field = getBeanField((Class)base, property);
            if (field != null && Modifier.isStatic(field.getModifiers())) {
                if (Modifier.isFinal(field.getModifiers())) {
                    throw new PropertyNotWritableException(((Class)base).getName() + "." + property);
                } else {
                    setFieldValue(field, null, value);
                    context.setPropertyResolved(true);
                    return;
                }
            }
        }

        // Don't expose static properties for a PropertyDelegate
        if (!(base instanceof PropertyDelegate)) {
            // If the property has read or write methods then set the property value
            BeanProperty bp = getBeanProperty(base.getClass(), property);
            if (bp != null) {
                if (bp.getWriteMethod() == null) {
                    throw new PropertyNotWritableException();
                } else {
                    value = TypeCoercion.coerce(context, value, bp.getType());
                    setPropertyValue(bp.getWriteMethod(), base, value);
                    context.setPropertyResolved(true);
                    return;
                }
            }

            // If field present and accessible then set the field value.
            Field field = getBeanField(base.getClass(), property);
            if (field != null) {
                setFieldValue(field, base, value);
                context.setPropertyResolved(true);
                return;
            }
        }

        // Set the dynamic property value.
        if (base instanceof PropertyResolvable) {
            ((PropertyResolvable)base).setValue(context, property, value);
            if (context.isPropertyResolved()) {
                return;
            }
        }

        // Set the expando dynamic property value.
        if (!(base instanceof ClosureObject)) {
            MethodClosure expando = MethodResolver.getInstance(context)
                .resolveMethod(base.getClass(), "[]=");
            if (expando != null) {
                Closure[] args = { new LiteralClosure(property), new LiteralClosure(value) };
                expando.invoke(context, base, args);
                context.setPropertyResolved(true);
                return;
            }
        }

        throw new PropertyNotFoundException();
    }

    public boolean isReadOnly(ELContext context, Object base, Object property) {
        if (context == null) {
            throw new NullPointerException();
        }

        if (base == null || property == null) {
            return false;
        }

        // Special properties
        if ("@@class".equals(property)) {
            context.setPropertyResolved(true);
            return true;
        }

        // Access class field
        if (base instanceof Class) {
            Field field = getBeanField((Class)base, property);
            if (field != null && Modifier.isStatic(field.getModifiers())) {
                context.setPropertyResolved(true);
                return Modifier.isFinal(field.getModifiers());
            }
        }

        // Don't expose static properties for a PropertyDelegate
        if (!(base instanceof PropertyDelegate)) {
            // If the property has read or write methods then check the write method
            BeanProperty bp = getBeanProperty(base.getClass(), property);
            if (bp != null) {
                context.setPropertyResolved(true);
                return bp.getWriteMethod() == null;
            }

            // If field present and accessible then return false.
            Field field = getBeanField(base.getClass(), property);
            if (field != null) {
                context.setPropertyResolved(true);
                return Modifier.isFinal(field.getModifiers());
            }
        }

        // Return the dynamic property value.
        if (base instanceof PropertyResolvable) {
            boolean value = ((PropertyResolvable)base).isReadOnly(context, property);
            if (context.isPropertyResolved()) {
                return value;
            }
        }

        // Return the expando dynamic property value.
        if (!(base instanceof ClosureObject)) {
            MethodResolver resolver = MethodResolver.getInstance(context);
            if (resolver.resolveMethod(base.getClass(), "[]") != null) {
                context.setPropertyResolved(true);
                return resolver.resolveMethod(base.getClass(), "[]=") == null;
            }
        }

        throw new PropertyNotFoundException();
    }

    public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext context, Object base) {
        if (base == null) {
            return null;
        }

        ArrayList<FeatureDescriptor> list = new ArrayList<FeatureDescriptor>();
        Class baseClass = base.getClass();

        for (Class c = baseClass; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (fieldAccessible(baseClass, f)) {
                    FeatureDescriptor feat = new FeatureDescriptor();
                    feat.setName(f.getName());
                    feat.setDisplayName(f.getName());
                    feat.setExpert(false);
                    feat.setHidden(false);
                    feat.setPreferred(true);
                    feat.setValue(RESOLVABLE_AT_DESIGN_TIME, Boolean.TRUE);
                    feat.setValue(TYPE, f.getType());
                    list.add(feat);
                }
            }
        }

        try {
            for (BeanProperty bp : BeanUtils.getProperties(baseClass)) {
                FeatureDescriptor feat = new FeatureDescriptor();
                feat.setName(bp.getName());
                feat.setDisplayName(bp.getName());
                feat.setExpert(false);
                feat.setHidden(false);
                feat.setPreferred(true);
                feat.setValue(RESOLVABLE_AT_DESIGN_TIME, Boolean.TRUE);
                feat.setValue(TYPE, bp.getType());
                list.add(feat);
            }
        } catch (IntrospectionException ex) {}

        return list.iterator();
    }

    public Class getCommonPropertyType(ELContext context, Object base) {
        if (base == null)
            return null;
        return Object.class;
    }

    // Implementation --------------

    protected BeanProperty getBeanProperty(Class baseClass, Object property) {
        try {
            return BeanUtils.getProperty(baseClass, property.toString());
        } catch (IntrospectionException ex) {
            return null;
        }
    }

    protected Object getPropertyValue(Method method, Object base) {
        try {
            return method.invoke(base);
        } catch (InvocationTargetException ex) {
            throw new ELException(ex.getTargetException());
        } catch (Exception ex) {
            throw new ELException(ex);
        }
    }

    protected void setPropertyValue(Method method, Object base, Object value) {
        try {
            method.invoke(base, value);
        } catch (InvocationTargetException ex) {
            throw new ELException(ex.getTargetException());
        } catch (Exception ex) {
            throw new ELException(ex);
        }
    }

    protected boolean fieldAccessible(Class baseClass, Field f) {
        return Modifier.isPublic(f.getModifiers());
    }

    protected Object getFieldValue(Field field, Object base) {
        try {
            return field.get(base);
        } catch (Exception ex) {
            throw new ELException(ex);
        }
    }

    protected void setFieldValue(Field field, Object base, Object value) {
        try {
            field.set(base, value);
        } catch (Exception ex) {
            throw new ELException(ex);
        }
    }

    /**
     * Defines the property fields for a bean.
     */
    protected final class BeanFields {
        private final Map<String,Field> fieldMap = new HashMap<String, Field>();

        public BeanFields(final Class baseClass) {
            AccessController.doPrivileged(new PrivilegedAction<Object>() {
                public Object run() {
                    for (Class c = baseClass; c != null; c = c.getSuperclass()) {
                        for (Field f : c.getDeclaredFields()) {
                            if (fieldAccessible(baseClass, f)) {
                                f.setAccessible(true);
                                fieldMap.put(f.getName(), f);
                            }
                        }
                    }
                    return null;
                }});
        }

        public Field getBeanField(String name) {
            return fieldMap.get(name);
        }
    }

    private SimpleCache<Class,BeanFields> cache = SimpleCache.make(200);

    protected Field getBeanField(Class baseClass, Object prop) {
        BeanFields fields = cache.get(baseClass);
        if (fields == null) {
            fields = new BeanFields(baseClass);
            cache.put(baseClass, fields);
        }
        return fields.getBeanField(prop.toString());
    }
}
