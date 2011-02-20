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

import java.lang.reflect.Method;
import java.io.ObjectOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import javax.el.ELContext;
import javax.el.MethodInfo;
import javax.el.ELException;

import elite.lang.Closure;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.closure.NamedClosure;

import static org.operamasks.el.resources.Resources.*;

/**
 * 非重载方法的包装对象, 由于不需要匹配参数类型, 因此调用非重载方法
 * 比调用重载方法具有更好的性能.
 */
class SingleMethodClosure extends JavaMethodClosure
{
    private transient Method method;
    private transient int nargs;
    private transient boolean vargs;

    public SingleMethodClosure(Method method) {
        this.method = method;
        init(method);
    }

    public String getName() {
        return method.getName();
    }

    protected JavaMethodClosure addMethod(Method method) {
        if (method.equals(this.method)) {
            return this;
        } else {
            JavaMethodClosure closure = new MultiMethodClosure(getName());
            closure.addMethod(this.method);
            closure.addMethod(method);
            return closure;
        }
    }

    public int arity(ELContext elctx) {
        return nargs;
    }

    public MethodInfo getMethodInfo(ELContext elctx) {
        return new MethodInfo(method.getName(),
                              method.getReturnType(),
                              method.getParameterTypes());
    }

    public int getModifiers() {
        return method.getModifiers();
    }

    public Object invoke(ELContext elctx, Object base, Closure[] args) {
        checkArgs(args);
        return ELEngine.invokeMethod(elctx, base, method, args);
    }

    public Object invokeSuper(ELContext elctx, Object base, Closure[] args) {
        checkArgs(args);
        return invokeSuper(elctx, method, base, args);
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o instanceof SingleMethodClosure) {
            SingleMethodClosure that = (SingleMethodClosure)o;
            return this.method.equals(that.method);
        }

        return false;
    }

    public int hashCode() {
        return method.hashCode();
    }

    public String toString() {
        return "#<primitive:" + method.getName() + "[" + method + "]>";
    }

    private void writeObject(ObjectOutputStream out)
        throws IOException
    {
        writeMethod(out, method);
    }

    private void readObject(ObjectInputStream in)
        throws IOException, ClassNotFoundException
    {
        method = readMethod(in);
        init(method);
    }

    private void init(Method method) {
        method.setAccessible(true);

        // cache argument count for quick test
        Class[] types = method.getParameterTypes();
        nargs = types.length;
        if (nargs > 0 && types[0] == ELContext.class)
            --nargs;
        vargs = method.isVarArgs();
    }

    private void checkArgs(Closure[] args) {
        if (nargs != args.length && (!vargs || args.length < nargs-1)) {
            throw new ELException(_T(EL_FN_BAD_ARG_COUNT, getName(), nargs, args.length));
        }

        StringBuilder named_args = null;
        for (Closure a : args) {
            if (a instanceof NamedClosure) {
                if (named_args == null) {
                    named_args = new StringBuilder();
                } else {
                    named_args.append(",");
                }
                named_args.append(((NamedClosure)a).name());
            }
        }

        if (named_args != null) {
            throw new ELException(_T(EL_UNKNOWN_ARG_NAME, named_args.toString()));
        }
    }
}
