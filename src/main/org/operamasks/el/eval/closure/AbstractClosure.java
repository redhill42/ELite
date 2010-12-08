/*
 * $Id: AbstractClosure.java,v 1.3 2009/03/22 08:37:27 danielyuan Exp $
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

package org.operamasks.el.eval.closure;

import java.util.Arrays;
import javax.el.MethodInfo;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;
import elite.lang.Closure;

public abstract class AbstractClosure extends Closure
{
    public int arity(ELContext context) {
        return -1;
    }

    public MethodInfo getMethodInfo(ELContext context) {
        Class[] types = null;
        int arity = arity(context);
        if (arity >= 0) {
            types = new Class[arity];
            Arrays.fill(types, Object.class);
        }
        return new MethodInfo("#closure", Object.class, types);
    }

    public abstract Object invoke(ELContext context, Closure[] args);

    public Object getValue(ELContext context) {
        return this;
    }

    public void setValue(ELContext context, Object value) {
        throw new PropertyNotWritableException();
    }

    public boolean isReadOnly(ELContext context) {
        return true;
    }

    public Class<?> getType(ELContext context) {
        return Closure.class;
    }

    public Class<?> getExpectedType() {
        return Closure.class;
    }

    public String getExpressionString() {
        return null;
    }

    public boolean equals(Object obj) {
        return this == obj;
    }

    public int hashCode() {
        return System.identityHashCode(this);
    }

    public boolean isLiteralText() {
        return false;
    }
}
