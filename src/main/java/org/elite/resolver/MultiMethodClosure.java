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
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import javax.el.ELContext;
import javax.el.ELException;
import javax.el.MethodNotFoundException;
import javax.el.MethodInfo;

import elite.lang.Closure;
import org.elite.eval.ELEngine;
import org.elite.eval.closure.NamedClosure;
import org.elite.util.Utils;

import static org.elite.resources.Resources.*;

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
        Utils.setAccessible(method);
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
        Method method = checkMethod(elctx, args);
        return ELEngine.invokeMethod(elctx, base, method, args);
    }

    public Object invokeSuper(ELContext elctx, Object base, Closure[] args) {
        Method method = checkMethod(elctx, args);
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
            Utils.setAccessible(methods[i]);
        }
    }

    private Method checkMethod(ELContext elctx, Closure[] args) {
        Method method = ELEngine.resolveMethod(elctx, methods, name, args);
        if (method == null) {
            String clsname = methods[0].getDeclaringClass().getName();
            throw new MethodNotFoundException(_T(EL_FN_NO_SUCH_METHOD, name, name, clsname));
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

        return method;
    }
}
