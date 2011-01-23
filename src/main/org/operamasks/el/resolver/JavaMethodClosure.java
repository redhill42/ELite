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
import java.lang.reflect.Modifier;
import java.io.ObjectOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import javax.el.ELContext;
import javax.el.MethodNotFoundException;
import javax.el.ELException;

import org.operamasks.net.sf.cglib.proxy.MethodProxy;
import org.operamasks.net.sf.cglib.core.Signature;
import elite.lang.Closure;
import org.operamasks.el.eval.closure.MethodClosure;
import org.operamasks.el.eval.TypeCoercion;
import org.operamasks.el.eval.EvaluationException;
import org.operamasks.el.eval.ELUtils;
import org.operamasks.el.eval.ELEngine;
import static org.operamasks.el.resources.Resources.*;

abstract class JavaMethodClosure extends MethodClosure
{
    public Object invoke(ELContext elctx, Closure[] args) {
        // invoke static method
        if (Modifier.isStatic(getModifiers())) {
            return invoke(elctx, null, args);
        }

        // invoke non-static method, the target object is the
        // first argument in the argument list.
        if (args.length == 0) {
            throw new ELException(_T(EL_FN_BAD_ARG_COUNT, getName(), arity(elctx)+1, args.length));
        }

        Object target = args[0].getValue(elctx);
        Closure[] xargs = new Closure[args.length-1];
        System.arraycopy(args, 1, xargs, 0, args.length-1);
        return invoke(elctx, target, xargs);
    }

    /**
     * Add a Java method to this method closure.
     */
    protected abstract JavaMethodClosure addMethod(Method method);

    /**
     * Helper method to invoke super method.
     */
    protected static Object invokeSuper(ELContext elctx, Method method, Object base, Closure[] args) {
        if (Modifier.isFinal(method.getModifiers())) {
            return ELEngine.invokeMethod(elctx, base, method, args);
        }

        MethodProxy methodProxy = getMethodProxy(base.getClass(), method);
        if (methodProxy == null) {
            throw new MethodNotFoundException();
        }

        Class[] types = method.getParameterTypes();
        Object[] values = new Object[args.length];
        for (int i = 0; i < types.length; i++) {
            values[i] = TypeCoercion.coerce(elctx, args[i].getValue(elctx), types[i]);
        }

        try {
            return methodProxy.invokeSuper(base, values);
        } catch (EvaluationException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new EvaluationException(elctx, ex);
        }
    }

    // Utility methods

    protected static MethodProxy getMethodProxy(Class type, Method method) {
        String name = method.getName();
        String desc = ELUtils.getMethodDescriptor(method);
        return MethodProxy.find(type, new Signature(name, desc));
    }

    protected static void writeMethod(ObjectOutputStream out, Method method)
        throws IOException
    {
        out.writeObject(method.getDeclaringClass());
        out.writeObject(method.getName());
        out.writeObject(method.getParameterTypes());
    }

    protected static Method readMethod(ObjectInputStream in)
        throws IOException, ClassNotFoundException
    {
        Class cls = (Class)in.readObject();
        String name = (String)in.readObject();
        Class[] types = (Class[])in.readObject();

        try {
            return cls.getDeclaredMethod(name, types);
        } catch (NoSuchMethodException ex) {
            throw new NoSuchMethodError(ex.getMessage());
        }
    }
}
