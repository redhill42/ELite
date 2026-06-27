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

package org.elite.util;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import static org.elite.util.BeanUtils.getMethod;

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

