/*
 * $Id: ProxiedThisObject.java,v 1.10 2009/05/04 08:35:55 jackyzhang Exp $
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
import java.util.HashMap;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import javax.el.ELContext;
import javax.el.ValueExpression;

import org.operamasks.net.sf.cglib.proxy.MethodInterceptor;
import org.operamasks.net.sf.cglib.proxy.MethodProxy;
import org.operamasks.net.sf.cglib.proxy.Enhancer;

import elite.lang.Closure;
import org.operamasks.el.resolver.MethodResolver;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.PropertyResolvable;
import org.operamasks.el.eval.TypeCoercion;
import org.operamasks.el.eval.EvaluationException;
import org.operamasks.el.eval.DelegatingELContext;
import static org.operamasks.el.eval.ELUtils.*;
import static org.operamasks.el.resources.Resources.*;

/**
 * 继承自Java类的闭包对象, 通过代理实现.
 */
class ProxiedThisObject extends BasicThisObject
{
    protected Class superclass;       // Java基类
    protected SuperObject zuper;      // 对基类对象的引用
    protected boolean creatingProxy;  // 正在创建代理标志, 防止循环回调

    ProxiedThisObject(ELContext           elctx,
                      ClassDefinition     cdef,
                      Map<String,Closure> vmap,
                      Class<?>            superclass)
    {
        super(elctx, cdef, vmap);
        this.superclass = superclass;
        this.zuper = new SuperObject(this);
    }

    /**
     * 返回代理对象.
     */
    protected Object createProxy(ELContext elctx) {
        if (proxy == null) {
            proxy = createProxy(elctx, null);
        }
        return proxy;
    }

    /**
     * 创建代理对象
     */
    protected Object createProxy(ELContext elctx, Closure[] args) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = getClass().getClassLoader();

        Enhancer enhancer = new Enhancer();
        enhancer.setClassLoader(loader);
        enhancer.setSuperclass(superclass);
        enhancer.setInterfaces(getInterfaces());
        enhancer.setCallback(new ProxyInterceptor(elctx, get_owner()));

        // 在基类构造函数中有可能调用子类继承方法, 因此需要设置一个标志以防止循环回调
        this.creatingProxy = true;

        try {
            if (args == null || args.length == 0) {
                return enhancer.create();
            }

            Constructor cons = ELEngine.resolveConstructor(elctx, superclass, args);
            if (cons == null) {
                throw new EvaluationException(
                    elctx, _T(EL_METHOD_NOT_FOUND, superclass.getSimpleName()));
            }

            Class[] types = cons.getParameterTypes();
            Object[] values = new Object[args.length];
            for (int i = 0; i < types.length; i++) {
                values[i] = TypeCoercion.coerce(elctx, args[i].getValue(elctx), types[i]);
            }

            return enhancer.create(types, values);

        } catch (EvaluationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new EvaluationException(elctx, ex);
        } finally {
            this.creatingProxy = false;
        }
    }

    protected Class[] getInterfaces() {
        if (interfaces == null) {
            return new Class[] { ClosureObject.class };
        } else {
            return super.getInterfaces();
        }
    }

    /**
     * 查找成员变量, 当子类中找不到时则到Java基类查找.
     */
    public Closure get_closure(ELContext elctx, String name) {
        Closure c = super.get_closure(elctx, name);
        if (c == null) {
            c = zuper.get_closure(elctx, name);
        }
        return c;
    }

    public Map<String,Closure> get_closures(ELContext elctx) {
        Map<String,Closure> map = super.get_closures(elctx);
        Map<String,Closure> smap = zuper.get_closures(elctx);
        for (Map.Entry<String,Closure> e : smap.entrySet()) {
            if (!map.containsKey(e.getKey())) {
                map.put(e.getKey(), e.getValue());
            }
        }
        return map;
    }
    
    /**
     * 调用类成员函数, 如果找不到对应的函数则调用Java基类函数.
     */
    public Object invoke(ELContext elctx, String name, Closure[] args) {
        Object result = super.invoke(elctx, name, args);
        if (result == NO_RESULT) {
            result = zuper.invoke(elctx, name, args);
        }
        return result;
    }

    /**
     * 调用公共类成员函数, 如果找不到对应的函数则调用Java基类公共方法.
     */
    protected Object invokePublic(ELContext elctx, String name, Closure[] args) {
        Object result = super.invokePublic(elctx, name, args);
        if (result == NO_RESULT) {
            result = zuper.invokePublic(elctx, name, args);
        }
        return result;
    }

    /**
     * 调用保护成员函数, 如果找不到对应的函数则调用基类保护成员函数.
     */
    protected Object invokeProtected(ELContext elctx, String name, Closure[] args) {
        Object result = super.invokeProtected(elctx, name, args);
        if (result == NO_RESULT) {
            result = zuper.invoke(elctx, name, args);
        }
        return result;
    }

    /**
     * 在闭包对象的环境中查找变量, 如果找不到则在Java基类中查找
     */
    protected ValueExpression resolveVariable(ELContext elctx, String name) {
        if ("super".equals(name)) {
            return zuper;
        } else {
            return super.resolveVariable(elctx, name);
        }
    }

    /**
     * 对Java基类对象的引用
     */
    class SuperObject extends DefaultClosureObject {
        SuperObject(ThisObject thisObj) {
            super(thisObj);
        }

        public Closure get_closure(ELContext elctx, String name) {
            MethodResolver resolver = MethodResolver.getInstance(elctx);
            MethodClosure method = resolver.resolveProtectedMethod(superclass, name);
            if (method != null) {
                return wrap(name, method);
            } else {
                return null;
            }
        }

        public Map<String,Closure> get_closures(ELContext elctx) {
            MethodResolver resolver = MethodResolver.getInstance(elctx);
            Map<String,Closure> map = new HashMap<String, Closure>();
            for (Method method : superclass.getMethods()) {
                String name = method.getName();
                if (!map.containsKey(name)) {
                    MethodClosure closure = resolver.resolveMethod(superclass, name);
                    if (closure != null) {
                        map.put(name, wrap(name, closure));
                    }
                }
            }
            return map;
        }

        private Closure wrap(final String name, final MethodClosure method) {
            return new DelegatingClosure(method) {
                public Object invoke(ELContext elctx, Closure[] args) {
                    return SuperObject.this.invoke(elctx, name, args);
                }

                public Object getValue(ELContext elctx) {
                    return this;
                }
            };
        }

        /**
         * 调用Java基类对象的构造方法.
         */
        public Object invoke(ELContext elctx, Closure[] args) {
            if (proxy != null) {
                throw new EvaluationException(elctx, _T(EL_BASE_CLASS_INITIALIZED));
            }

            proxy = createProxy(elctx, args);
            return null;
        }

        /**
         * 调用Java基类成员方法.
         */
        public Object invoke(ELContext elctx, String name, Closure[] args) {
            if (proxy == null) {
                if (creatingProxy) {
                    return NO_RESULT;
                } else {
                    proxy = createProxy(elctx, null);
                }
            }

            MethodClosure method = MethodResolver.getInstance(elctx)
                .resolveProtectedMethod(superclass, name);
            if (method != null) {
                try {
                    return method.invokeSuper(elctx, proxy, args);
                } catch (EvaluationException ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    throw new EvaluationException(elctx, ex);
                }
            }

            return NO_RESULT;
        }

        /**
         * 调用Java公共基类方法.
         */
        public Object invokePublic(ELContext elctx, String name, Closure[] args) {
            if (proxy == null) {
                if (creatingProxy) {
                    return NO_RESULT;
                } else {
                    proxy = createProxy(elctx, null);
                }
            }

            MethodClosure method = MethodResolver.getInstance(elctx)
                .resolveMethod(superclass, name);
            if (method != null) {
                return method.invoke(elctx, proxy, args);
            }

            return NO_RESULT;
        }
    }

    static class ProxyInterceptor implements MethodInterceptor {
        private ELContext elctx;
        private ClosureObject target;

        public ProxyInterceptor(ELContext elctx, ClosureObject target) {
            this.elctx = elctx;
            this.target = target;
        }

        public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy)
            throws Throwable
        {
            if (args == null) {
                args = NO_ARGS;
            }

            if (PropertyResolvable.class.isAssignableFrom(method.getDeclaringClass())) {
                try {
                    Object result = method.invoke(target, args);
                    if (result == target)
                        result = proxy;
                    return result;
                } catch (InvocationTargetException ex) {
                    throw ex.getTargetException();
                }
            }

            ELContext elctx = DelegatingELContext.get(this.elctx);

            String name = method.getName();
            Class type = method.getReturnType();

            Object result = target.get_this().invoke(elctx, name, ELEngine.getCallArgs(args));
            if (result != NO_RESULT) {
                if (type == Void.TYPE) {
                    return null;
                } else if (result == target) {
                    return proxy;
                } else {
                    return TypeCoercion.coerce(elctx, result, type);
                }
            }

            if (name.startsWith("get") && args.length == 0) {
                name = decapitalize(name.substring(3));
                elctx.setPropertyResolved(false);
                Object value = target.getValue(elctx, name);
                if (elctx.isPropertyResolved()) {
                    return TypeCoercion.coerce(elctx, value, type);
                }
            } else if (name.startsWith("is") && args.length == 0 && type == Boolean.TYPE) {
                name = decapitalize(name.substring(2));
                elctx.setPropertyResolved(false);
                Object value = target.getValue(elctx, name);
                if (elctx.isPropertyResolved()) {
                    return TypeCoercion.coerce(elctx, value, type);
                }
            } else if (name.startsWith("set") && args.length == 1) {
                name = decapitalize(name.substring(3));
                elctx.setPropertyResolved(false);
                target.setValue(elctx, name, args[0]);
                if (elctx.isPropertyResolved()) {
                    return null;
                }
            }

            return methodProxy.invokeSuper(proxy, args);
        }
    }
}
