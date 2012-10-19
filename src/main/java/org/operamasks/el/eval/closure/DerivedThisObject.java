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

import java.util.Map;
import java.util.LinkedHashMap;
import javax.el.ELContext;
import javax.el.ValueExpression;

import elite.lang.Closure;
import org.operamasks.el.eval.EvaluationException;
import static org.operamasks.el.eval.ELUtils.*;
import static org.operamasks.el.resources.Resources.*;

/**
 * 继承类的闭包对象. 子类对象可以继承或覆盖基类的成员函数.
 */
class DerivedThisObject extends BasicThisObject
{
    // 表示基类对象的接口, 在子类成员函数中可以通过"super"访问
    protected SuperObject zuper;

    DerivedThisObject(ELContext           elctx,
                      ClassDefinition     cdef,
                      Map<String,Closure> vmap,
                      ThisObject          base)
    {
        super(elctx, cdef, vmap);

        this.zuper = new SuperObject(this, base);
        override(base);
    }

    /**
     * 增加Java接口
     */
    protected void addInterface(Class iface) {
        zuper.base.addInterface(iface);
    }

    /**
     * 当对象继承树创建完毕时设置外部对象接口
     */
    protected void setOwner(ClosureObject owner) {
        super.setOwner(owner);
        zuper.base.setOwner(owner);
    }

    /**
     * 返回代理对象.
     */
    protected Object createProxy(ELContext elctx) {
        if (!zuper.initialized) {
            // invoke default initialization procedure
            zuper.invoke(elctx, NO_PARAMS);
        }
        return proxy = zuper.base.createProxy(elctx);
    }
    
    /**
     * 查找成员变量, 当子类中找不到时需要到基类查找, 但基类的private
     * 成员变量是受保护的.
     */
    public Closure get_closure(ELContext elctx, String name) {
        Closure c = super.get_closure(elctx, name);
        if (c == null) {
            c = zuper.get_closure(elctx, name);
        }
        return c;
    }

    /**
     * 获得所有(含基类)成员变量
     * @param elctx
     */
    public Map<String,Closure> get_closures(ELContext elctx) {
        Map<String,Closure> map = super.get_closures(elctx);
        Map<String,Closure> smap = zuper.base.get_closures(elctx);
        for (Map.Entry<String,Closure> e : smap.entrySet()) {
            if (!map.containsKey(e.getKey()) && e.getValue().isPublic()) {
                map.put(e.getKey(), e.getValue());
            }
        }
        return map;
    }

    /**
     * 调用类成员函数, 如果找不到对应的函数则调用基类函数.
     */
    public Object invoke(ELContext elctx, String name, Closure[] args) {
        Object result = super.invoke(elctx, name, args);
        if (result == NO_RESULT) {
            result = zuper.invoke(elctx, name, args);
        }
        return result;
    }

    /**
     * 调用公共成员函数, 如果找不到对应的函数则调用基类公共成员函数.
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
     * 在闭包对象的环境中查找变量, 如果找不到则在基类中查找
     */
    protected ValueExpression resolveVariable(ELContext elctx, String name) {
        if ("super".equals(name)) {
            return zuper;
        } else {
            return super.resolveVariable(elctx, name);
        }
    }

    /**
     * 将子类中的成员函数覆盖基类中的同名成员函数.
     */
    protected void override(ThisObject base) {
        Map<String,Closure> basemap = base.getClosureMap();
        for (Map.Entry<String,Closure> e : vmap.entrySet()) {
            String key = e.getKey();
            Closure c = e.getValue();
            Closure bc = basemap.get(key);

            if (canOverride(key, c, bc)) {
                basemap.put(key, c);
                if (bc != null) {
                    // 被覆盖的基类成员函数被放在另一个表中, 这样子类
                    // 就可以通过"super"特殊变量访问这些函数.
                    zuper.add_closure(key, bc);
                }
            }
        }

        // 递归覆盖基-基类成员函数
        if (base instanceof DerivedThisObject) {
            override(((DerivedThisObject)base).zuper.base);
        }
    }

    protected boolean canOverride(String name, Closure c, Closure bc) {
        // 不能覆盖初始化函数
        if (name.equals(ClassDefinition.INIT_PROC)) {
            return false;
        }
        
        // 不能覆盖未知函数
        if (bc == null) {
            return false;
        }

        // 不能覆盖私有函数
        if (c.isPrivate() || bc.isPrivate()) {
            return false;
        }

        // 不能覆盖数据成员变量
        return c.isProcedure() && bc.isProcedure();
    }

    /**
     * 对基类对象的引用
     */
    static class SuperObject extends DefaultClosureObject {
        protected ThisObject base;
        protected Map<String,Closure> smap;
        protected boolean initialized = false;

        SuperObject(ThisObject thisObj, ThisObject base) {
            super(thisObj);
            this.base = base;
            this.smap = new LinkedHashMap<String,Closure>();
        }

        /**
         * 增加一个被覆盖的基类成员函数
         */
        void add_closure(String name, Closure closure) {
            if (!smap.containsKey(name)) {
                smap.put(name, closure);
            }
        }

        /**
         * 查找成员变量, 当子类中找不到时需要到基类查找, 但基类的private
         * 成员变量是受保护的.
         */
        public Closure get_closure(ELContext elctx, String name) {
            if (name.equals(ClassDefinition.INIT_PROC)) {
                return null;
            }
            
            Closure c = smap.get(name);
            if (c == null) {
                c = base.get_closure(elctx, name);
                if (c == null || (c.isPrivate() && !base.get_class().inScope(elctx))) {
                    return null;
                }
            }
            return c;
        }

        /**
         * 调用基类成员方法.
         */
        public Object invoke(ELContext elctx, String name, Closure[] args) {
            if (name.equals(ClassDefinition.INIT_PROC)) {
                return NO_RESULT;
            }

            Closure c = smap.get(name);
            if (c != null) {
                return base.get_class().invokeInScope(elctx, c, args);
            } else {
                return base.invokeProtected(elctx, name, args);
            }
        }

        /**
         * 调用基类公共成员方法.
         */
        public Object invokePublic(ELContext elctx, String name, Closure[] args) {
            if (name.equals(ClassDefinition.INIT_PROC)) {
                return NO_RESULT;
            }

            return base.invokePublic(elctx, name, args);
        }
        
        /**
         * 调用基类初始化函数. 注意基类对象只能被初始化一次.
         */
        public Object invoke(ELContext elctx, Closure[] args) {
            if (initialized) {
                throw new EvaluationException(elctx, _T(EL_BASE_CLASS_INITIALIZED));
            }

            Closure init = base.get_closure(elctx, ClassDefinition.INIT_PROC);
            if (init != null) {
                init.invoke(elctx, args);
            } else if (args.length != 0) {
                throw new EvaluationException(elctx, _T(EL_FN_BAD_ARG_COUNT,
                                                        base.get_class().getName(),
                                                        0, args.length));
            }

            initialized = true;
            return null;
        }
    }
}
