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

package org.operamasks.el.eval.closure;

import java.io.Serializable;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

import javax.el.ELContext;
import javax.el.MethodNotFoundException;
import javax.el.ValueExpression;
import javax.el.VariableMapper;

import elite.lang.Closure;
import org.operamasks.el.eval.TypeCoercion;
import org.operamasks.el.eval.EvaluationException;
import org.operamasks.el.eval.PropertyResolvable;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.DelegatingELContext;
import static org.operamasks.el.eval.ELUtils.*;
import static org.operamasks.el.resources.Resources.*;

class BasicThisObject extends ThisObject
{
    protected ClassDefinition cdef;     // 类定义
    protected Map<String,Closure> vmap; // 对象实例变量
    protected List<Class> interfaces;   // 闭包对象所实现的Java接口
    protected ClosureObject owner;      // 闭包对象外部接口
    protected Object proxy;             // 闭包对象的代理对象

    private static final long serialVersionUID = 5012955516372665795L;

    BasicThisObject(ELContext elctx, ClassDefinition cdef, Map<String,Closure> vmap) {
        this.cdef = cdef;
        this.vmap = vmap;

        // 为成员函数建立求值环境
        for (Map.Entry<String,Closure> e : vmap.entrySet()) {
            e.setValue(attach(elctx, e.getValue()));
        }
    }

    /**
     * 为闭包对象增加Java接口. 如果闭包对象实现了Java接口则必须生成代理.
     */
    protected void addInterface(Class iface) {
        assert iface.isInterface();

        if (interfaces == null) {
            interfaces = new ArrayList<Class>();
            interfaces.add(ClosureObject.class); // default implements
        }

        if (!interfaces.contains(iface)) {
            interfaces.add(iface);
        }
    }

    /**
     * 得到闭包对象所实现的所有接口.
     */
    protected Class[] getInterfaces() {
        if (interfaces == null) {
            return null;
        } else {
            Class[] ifs = interfaces.toArray(new Class[interfaces.size()]);
            interfaces = null; // no longer used, save memory
            return ifs;
        }
    }

    /**
     * 当对象继承树创建完毕, 在初始化函数调用之前设置外部对象接口
     */
    protected void setOwner(ClosureObject owner) {
        this.owner = owner;
    }

    /**
     * 调用闭包对象的初始化函数.
     */
    protected void init(ELContext elctx, Closure[] args) {
        Closure init = get_closure(elctx, ClassDefinition.INIT_PROC);
        if (init != null) {
            init.invoke(elctx, args);
        } else if (args.length != 0) {
            throw new EvaluationException(elctx, _T(EL_FN_BAD_ARG_COUNT,
                                                    cdef.getName(),
                                                    0, args.length));
        }
    }

    /**
     * 闭包对象初始化完成之后, 返回最终代理对象.
     */
    protected Object createProxy(ELContext elctx) {
        assert owner != null && proxy == null;

        if (interfaces == null) {
            proxy = owner;
        } else {
            ClosureProxyHandler handler = new ClosureProxyHandler(elctx, owner);
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) loader = getClass().getClassLoader();
            proxy = Proxy.newProxyInstance(loader, getInterfaces(), handler);
        }
        return proxy;
    }

    /**
     * 获得闭包对象的类定义
     */
    public ClassDefinition get_class() {
        return cdef;
    }

    /**
     * 获得闭包对象的外部接口
     */
    public ClosureObject get_owner() {
        return owner;
    }

    /**
     * 获得闭包对象的代理对象.
     */
    public Object get_proxy() {
        return proxy;
    }

    /**
     * 查找成员变量
     */
    public Closure get_closure(ELContext elctx, String name) {
        return get_my_closure(elctx, name);
    }

    /**
     * 获得所有成员变量
     */
    public Map<String,Closure> get_closures(ELContext elctx) {
        Map<String,Closure> map = new HashMap<String,Closure>();
        for (Map.Entry<String,Closure> e : vmap.entrySet()) {
            if (e.getValue().isPublic()) {
                map.put(e.getKey(), e.getValue());
            }
        }
        return map;
    }

    /**
     * 仅在当前类范围内查找类成员变量, 不查找基类.
     */
    protected Closure get_my_closure(ELContext elctx, String name) {
        Closure c = vmap.get(name);
        if (c == null) {
            c = cdef.getExpandoClosure(name);
            if (c != null) {
                c = new ExpandoClosure(c, get_owner());
                vmap.put(name, c);
            }
        }
        return c;
    }

    /**
     * 获得所有成员变量
     */
    protected Map<String,Closure> getClosureMap() {
        return vmap;
    }

    /**
     * 设置成员函数的求值环境, 使其可以访问闭包对象的内部成员
     */
    protected Closure attach(ELContext elctx, Closure closure) {
        closure._setenv(elctx, new Environment(elctx, this));
        if (closure.isProcedure() && closure.isSynchronized())
            closure = new SynchronizedClosure(this, closure);
        return closure;
    }

    /**
     * 在闭包对象的环境中查找变量
     */
    protected ValueExpression resolveVariable(ELContext elctx, String name) {
        if ("this".equals(name)) {
            return this;
        }

        if ("self".equals(name)) {
            Object proxy = get_proxy();
            if (proxy instanceof Closure) {
                return (Closure)proxy;
            } else {
                return new LiteralClosure(proxy, true);
            }
        }

        return get_closure(elctx, name);
    }

    /**
     * 定义类成员函数的环境, 使用不同的策略查找变量. 这些变量包括闭包原来
     * 所处的环境变量, 隐含的类成员变量, 以及"this"和"super"特殊变量.
     */
    static class Environment extends VariableMapper {
        protected ELContext elctx;
        protected BasicThisObject thisObj;

        Environment(ELContext elctx, BasicThisObject thisObject) {
            this.elctx = elctx;
            this.thisObj = thisObject;
        }

        public ValueExpression resolveVariable(String name) {
            // 查找闭包对象的成员变量
            ValueExpression value = thisObj.resolveVariable(elctx, name);
            if (value != null) {
                return value;
            }

            // 查找类静态成员
            value = thisObj.cdef.getPrivateClosure(elctx, name);
            if (value != null) {
                return value;
            }

            return null;
        }

        public ValueExpression setVariable(String name, ValueExpression var) {
            return null;
        }
    }

    /**
     * 动态添加的扩展成员函数
     */
    static class ExpandoClosure extends DelegatingClosure {
        final Closure scope;

        ExpandoClosure(Closure delegate, ClosureObject scope) {
            super(delegate);
            this.scope = (scope instanceof Closure)
                            ? (Closure) scope
                            : new LiteralClosure(scope);
        }

        public Object invoke(ELContext elctx, Closure[] args) {
            Closure[] expando = new Closure[args.length+1];
            expando[0] = scope;
            System.arraycopy(args, 0, expando, 1, args.length);
            return delegate.invoke(elctx, expando);
        }

        public Object getValue(ELContext elctx) {
            return this;
        }
    }

    static class SynchronizedClosure extends DelegatingClosure {
        final BasicThisObject thisObj;

        SynchronizedClosure(BasicThisObject thisObj, Closure delegate) {
            super(delegate);
            this.thisObj = thisObj;
        }

        public Object invoke(ELContext elctx, Closure[] args) {
            Object proxy = thisObj.get_proxy();
            if (proxy != null) {
                synchronized (proxy) {
                    return delegate.invoke(elctx, args);
                }
            } else {
                return delegate.invoke(elctx, args);
            }
        }
    }

    static class ClosureProxyHandler implements InvocationHandler, Serializable {
        private transient ELContext elctx;
        private ClosureObject target;
        private static final long serialVersionUID = -5877362109550947177L;

        ClosureProxyHandler(ELContext elctx, ClosureObject target) {
            this.elctx = elctx;
            this.target = target;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
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

            Object result = target.invoke(elctx, name, ELEngine.getCallArgs(args));
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

            if (method.getDeclaringClass() == Object.class) {
                return invokeObjectMethod(method, args);
            }

            throw new MethodNotFoundException(
                _T(EL_METHOD_NOT_FOUND, target.get_class().getName(), name));
        }

        private Object invokeObjectMethod(Method method, Object[] args) {
            String name = method.getName();

            if (name.equals("equals"))
                return target.equals(args[0]);
            if (name.equals("hashCode"))
                return target.hashCode();
            if (name.equals("toString"))
                return target.toString();

            throw new MethodNotFoundException(
                _T(EL_METHOD_NOT_FOUND, target.get_class().getName(), name));
        }
    }
}
