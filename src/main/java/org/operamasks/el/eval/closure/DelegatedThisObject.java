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

package org.operamasks.el.eval.closure;

import java.util.Map;
import javax.el.ELContext;

import elite.lang.Closure;
import org.operamasks.el.resolver.MethodResolver;
import static org.operamasks.el.eval.ELUtils.*;

/**
 * 将成员函数的调用委托给其他的对象.
 */
class DelegatedThisObject extends ThisObject
{
    protected ThisObject thisObj;
    protected Closure[] delegates;

    DelegatedThisObject(ThisObject thisObj, Closure[] delegates) {
        this.thisObj = thisObj;
        this.delegates = delegates;
    }

    protected void addInterface(Class iface) {
        thisObj.addInterface(iface);
    }

    protected Class[] getInterfaces() {
        return thisObj.getInterfaces();
    }

    protected void setOwner(ClosureObject owner) {
        thisObj.setOwner(owner);
    }

    protected void init(ELContext elctx, Closure[] args) {
        thisObj.init(elctx, args);
    }

    protected Object createProxy(ELContext elctx) {
        return thisObj.createProxy(elctx);
    }

    public ClassDefinition get_class() {
        return thisObj.get_class();
    }

    public ClosureObject get_owner() {
        return thisObj.get_owner();
    }

    public Object get_proxy() {
        return thisObj.get_proxy();
    }

    protected Map<String, Closure> getClosureMap() {
        return thisObj.getClosureMap();
    }

    protected Closure get_my_closure(ELContext elctx, String name) {
        return thisObj.get_my_closure(elctx, name);
    }

    public Closure get_closure(ELContext elctx, String name) {
        // find closure from most derived object
        Closure c = thisObj.get_my_closure(elctx, name);
        if (c != null) {
            return c;
        }

        // find closure from delegates
        for (Closure del : delegates) {
            Object obj = del.isProcedure() ? del.invoke(elctx, NO_PARAMS)
                                           : del.getValue(elctx);

            if (obj != null) {
                if (obj instanceof ClosureObject) {
                    c = ((ClosureObject)obj).get_closure(elctx, name);
                    if (c != null) {
                        return c;
                    }
                } else if (elctx != null) {
                    MethodClosure method = MethodResolver.getInstance(elctx)
                        .resolveMethod(obj.getClass(), name);
                    if (method != null) {
                        return new TargetMethodClosure(obj, method);
                    }
                }
            }
        }

        // find closure from base objects
        return thisObj.get_closure(elctx, name);
    }

    public Map<String,Closure> get_closures(ELContext elctx) {
        return thisObj.get_closures(elctx);
    }

    protected Object invokePublic(ELContext elctx, String name, Closure[] args) {
        // invoke most derived procedure
        Object result = super.invokePublic(elctx, name, args);
        if (result != NO_RESULT) {
            return result;
        }

        // invoke delegated procedure
        for (Closure del : delegates) {
            Object obj = del.isProcedure() ? del.invoke(elctx, NO_PARAMS)
                                           : del.getValue(elctx);

            if (obj != null) {
                if (obj instanceof ClosureObject) {
                    result = ((ClosureObject)obj).invoke(elctx, name, args);
                    if (result != NO_RESULT) {
                        return result;
                    }
                } else if (elctx != null) {
                    MethodClosure method = MethodResolver.getInstance(elctx)
                        .resolveMethod(obj.getClass(), name);
                    if (method != null) {
                        return method.invoke(elctx, obj, args);
                    }
                }
            }
        }

        // invoke procedure from base objects
        return thisObj.invokePublic(elctx, name, args);
    }
}
