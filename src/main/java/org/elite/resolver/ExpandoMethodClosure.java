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
import javax.el.ELContext;
import javax.el.MethodInfo;
import elite.lang.Closure;
import org.elite.eval.closure.MethodClosure;
import org.elite.eval.closure.LiteralClosure;

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

    public Method getJavaMethod() {
        if (delegate instanceof MethodClosure)
            return ((MethodClosure)delegate).getJavaMethod();
        return null;
    }

    public Method getJavaMethod(ELContext elctx, Object... args) {
        if (delegate instanceof MethodClosure)
            return ((MethodClosure)delegate).getJavaMethod(elctx, args);
        return null;
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
