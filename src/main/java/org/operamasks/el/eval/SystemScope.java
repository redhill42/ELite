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

package org.operamasks.el.eval;

import java.io.Serializable;
import javax.el.ELContext;
import javax.el.PropertyNotFoundException;
import javax.el.PropertyNotWritableException;
import elite.lang.Closure;
import org.operamasks.el.resolver.MethodResolver;

public class SystemScope implements PropertyDelegate, Serializable
{
    public static final SystemScope SINGLETON = new SystemScope();
    private SystemScope() {}
    
    public Object getValue(ELContext elctx, Object property) {
        MethodResolver resolver = MethodResolver.getInstance(elctx);
        Object value = resolver.resolveSystemMethod((String)property);

        if (value != null) {
            elctx.setPropertyResolved(true);
            return value;
        } else {
            throw new PropertyNotFoundException();
        }
    }

    public Class<?> getType(ELContext elctx, Object property) {
        MethodResolver resolver = MethodResolver.getInstance(elctx);
        Object value = resolver.resolveSystemMethod((String)property);

        if (value != null) {
            elctx.setPropertyResolved(true);
            return Closure.class;
        } else {
            throw new PropertyNotFoundException();
        }
    }

    public void setValue(ELContext elctx, Object property, Object value) {
        throw new PropertyNotWritableException();
    }

    public boolean isReadOnly(ELContext elctx, Object property) {
        return true;
    }

    private Object readResolve() {
        return SINGLETON;
    }
}
