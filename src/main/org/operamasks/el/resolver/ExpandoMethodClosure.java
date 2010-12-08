/*
 * $Id: ExpandoMethodClosure.java,v 1.4 2009/03/22 08:37:26 danielyuan Exp $
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

import java.lang.reflect.Method;
import javax.el.ELContext;
import javax.el.MethodInfo;
import elite.lang.Closure;
import org.operamasks.el.eval.closure.MethodClosure;
import org.operamasks.el.eval.closure.LiteralClosure;

class ExpandoMethodClosure extends MethodClosure
{
    protected String name;
    protected Class<?> target;
    protected Closure delegate;

    public ExpandoMethodClosure(String name, Class<?> target, Closure delegate) {
        this.name = name;
        this.target = target;
        this.delegate = delegate;
    }

    public String getName() {
        return name;
    }

    public Class<?> getTarget() {
        return target;
    }

    public void addMethod(Method method) {
        if (delegate instanceof JavaMethodClosure) { // FIXME otherwise?
            delegate = ((JavaMethodClosure)delegate).addMethod(method);
        }
    }
    
    public int arity(ELContext elctx) {
        return delegate.arity(elctx);
    }

    public MethodInfo getMethodInfo(ELContext elctx) {
        return delegate.getMethodInfo(elctx);
    }

    public Object invoke(ELContext elctx, Closure[] args) {
        return delegate.invoke(elctx, args);
    }

    public Object invoke(ELContext elctx, Object base, Closure[] args) {
        Closure[] expando = new Closure[args.length+1];
        expando[0] = new LiteralClosure(base);
        System.arraycopy(args, 0, expando, 1, args.length);
        return delegate.invoke(elctx, expando);
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj instanceof ExpandoMethodClosure) {
            ExpandoMethodClosure other = (ExpandoMethodClosure)obj;
            return name.equals(other.name)
                && target.equals(other.target)
                && delegate.equals(other.delegate);
        }

        return false;
    }

    public int hashCode() {
        return name.hashCode() ^ target.hashCode() ^ delegate.hashCode();
    }

    public String toString() {
        return delegate.toString();
    }
}
