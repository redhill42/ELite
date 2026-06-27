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

package org.elite.resolver;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.io.ObjectOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import javax.el.ELContext;
import javax.el.MethodNotFoundException;
import javax.el.ELException;

import net.sf.cglib.proxy.MethodProxy;
import net.sf.cglib.core.Signature;
import elite.lang.Closure;
import org.elite.eval.closure.MethodClosure;
import org.elite.eval.TypeCoercion;
import org.elite.eval.EvaluationException;
import org.elite.eval.ELUtils;
import org.elite.eval.ELEngine;
import static org.elite.resources.Resources.*;

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
