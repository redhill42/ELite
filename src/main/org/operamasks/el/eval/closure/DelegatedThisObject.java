/*
 * $Id: DelegatedThisObject.java,v 1.7 2009/03/23 13:18:25 danielyuan Exp $
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
