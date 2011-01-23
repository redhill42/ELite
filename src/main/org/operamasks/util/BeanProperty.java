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

