/*
 * $Id: MultiMethodClosure.java,v 1.6 2009/05/07 16:57:04 danielyuan Exp $
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
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import javax.el.ELContext;
import javax.el.MethodNotFoundException;
import javax.el.MethodInfo;

import elite.lang.Closure;
import org.operamasks.el.eval.ELEngine;
import static org.operamasks.el.resources.Resources.*;

/**
 * 重载方法的包装对象.
 */
class MultiMethodClosure extends JavaMethodClosure
{
    private transient String name;
    private transient Method[] methods;

    private static final long serialVersionUID = -3244166360202624828L;

    public MultiMethodClosure(String name) {
        this.name = name;
        this.methods = new Method[0];
    }

    public String getName() {
        return name;
    }

    protected JavaMethodClosure addMethod(Method method) {
        for (Method m : this.methods) {
            if (m.equals(method)) {
                return this;
            }
        }

        Method[] newlist = new Method[methods.length+1];
        System.arraycopy(methods, 0, newlist, 0, methods.length);
        newlist[methods.length] = method;
        methods = newlist;
        method.setAccessible(true);
        return this;
    }

    public int arity(ELContext elctx) {
        int arity = 0;
        for (Method m : methods) {
            int n = m.getParameterTypes().length;
            if (n > arity)
                arity = n;
        }
        return arity;
    }

    public MethodInfo getMethodInfo(ELContext elctx) {
        return new MethodInfo(methods[0].getName(),
                              methods[0].getReturnType(),
                              methods[0].getParameterTypes());
    }

    public int getModifiers() {
        return methods[0].getModifiers();
    }

    public Object invoke(ELContext elctx, Object base, Closure[] args) {
        Method method = ELEngine.resolveMethod(elctx, methods, name, args);
        if (method == null) {
            String clsname = methods[0].getDeclaringClass().getName();
            throw new MethodNotFoundException(_T(EL_FN_NO_SUCH_METHOD, name, name, clsname));
        }

        return ELEngine.invokeMethod(elctx, base, method, args);
    }

    public Object invokeSuper(ELContext elctx, Object base, Closure[] args) {
        Method method = ELEngine.resolveMethod(elctx, methods, name, args);
        if (method == null) {
            String clsname = methods[0].getDeclaringClass().getName();
            throw new MethodNotFoundException(_T(EL_FN_NO_SUCH_METHOD, name, name, clsname));
        }

        return invokeSuper(elctx, method, base, args);
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o instanceof MultiMethodClosure) {
            MultiMethodClosure that = (MultiMethodClosure)o;
            return this.name.equals(that.name)
                && Arrays.equals(this.methods, that.methods);
        }

        return false;
    }

    public int hashCode() {
        int result = name.hashCode();
        for (Method method : methods) {
            result = 31 * result + method.hashCode();
        }
        return result;
    }

    public String toString() {
        StringBuilder buf = new StringBuilder("#<primitive:");
        buf.append(name);
        buf.append("[");
        for (int i = 0; i < methods.length; i++) {
            if (i != 0) buf.append(",");
            buf.append(methods[i].toString());
        }
        buf.append("]>");
        return buf.toString();
    }

    private void writeObject(ObjectOutputStream out)
        throws IOException, ClassNotFoundException
    {
        out.writeUTF(name);
        out.writeInt(methods.length);
        for (Method method : methods) {
            writeMethod(out, method);
        }
    }

    private void readObject(ObjectInputStream in)
        throws IOException, ClassNotFoundException
    {
        name = in.readUTF();

        int count = in.readInt();
        methods = new Method[count];
        for (int i = 0; i < count; i++) {
            methods[i] = readMethod(in);
            methods[i].setAccessible(true);
        }
    }
}
