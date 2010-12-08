package org.operamasks.util;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import static org.operamasks.util.BeanUtils.getMethod;

public final class BeanProperty {
    private final Class<?> type;
    private final Class<?> owner;
    private PropertyDescriptor descriptor;
    private Method read;
    private Method write;
	private String name;

    public BeanProperty(Class<?> owner, String propName, Method read, Method write) {
        this.owner = owner;
        this.name = propName;
        this.read = read;
        this.write = write;
        this.type = read.getReturnType();
    }

    public BeanProperty(Class<?> owner, PropertyDescriptor descriptor) {
        this.owner = owner;
        this.descriptor = descriptor;
        this.type = descriptor.getPropertyType();
        this.name = descriptor.getName();
    }

    public Class<?> getType() {
        return type;
    }

    public String getName() {
        return this.name;
    }

    public PropertyDescriptor getDescriptor() {
        return descriptor;
    }

    public boolean isReadOnly() {
        return this.write == null &&
               getMethod(type, descriptor.getWriteMethod()) == null;
    }

    public Method getWriteMethod() {
        if (write == null)
            write = getMethod(owner, descriptor.getWriteMethod());
        return write; // MAY BE NULL
    }

    public Method getReadMethod() {
        if (read == null)
            read = getMethod(owner, descriptor.getReadMethod());
        return read; // MAY BE NULL
    }
}

