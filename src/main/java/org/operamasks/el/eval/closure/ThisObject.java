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

import javax.el.ELContext;
import javax.el.MethodNotFoundException;

import elite.lang.Closure;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.EvaluationException;
import static org.operamasks.el.eval.TypeCoercion.*;
import static org.operamasks.el.eval.ELUtils.*;
import static org.operamasks.el.resources.Resources.*;

/**
 * 闭包对象的内部接口. 闭包对象通过此接口访问内部成员变量.
 */
public abstract class ThisObject extends AbstractClosure
    implements ClosureObject, Serializable
{
    protected ThisObject() {}

    /**
     * 为闭包对象增加Java接口. 如果闭包对象实现了Java接口则必须生成代理.
     */
    protected abstract void addInterface(Class iface);

    /**
     * 得到闭包对象所实现的所有接口.
     */
    protected abstract Class[] getInterfaces();

    /**
     * 当对象继承树创建完毕, 在初始化函数调用之前设置外部对象接口
     */
    protected abstract void setOwner(ClosureObject owner);

    /**
     * 调用闭包对象的初始化函数.
     */
    protected abstract void init(ELContext elctx, Closure[] args);

    /**
     * 闭包对象初始化完成之后, 返回最终代理对象.
     */
    protected abstract Object createProxy(ELContext elctx);

    /**
     * 获得闭包对象的类定义
     */
    public abstract ClassDefinition get_class();

    /**
     * 获得闭包对象的内部接口
     */
    public final ClosureObject get_this() {
        return this;
    }

    /**
     * 获得闭包对象的外部接口
     */
    public abstract ClosureObject get_owner();

    /**
     * 查找成员变量
     */
    public abstract Closure get_closure(ELContext elctx, String name);

    /**
     * 获得所有(含基类)成员变量
     * @param elctx
     */
    public abstract Map<String,Closure> get_closures(ELContext elctx);

    /**
     * 仅在当前类范围内查找类成员变量, 不查找基类.
     */
    protected abstract Closure get_my_closure(ELContext elctx, String name);

    /**
     * 获得所有成员变量
     */
    protected abstract Map<String,Closure> getClosureMap();

    /**
     * 返回闭包对象的属性值
     */
    public Object getValue(ELContext elctx, Object property) {
        if (property instanceof String) {
            Closure c = get_closure(elctx, (String)property);
            if (c != null) {
                Object result = c.getValue(elctx);
                elctx.setPropertyResolved(true);
                return result;
            }
        }
        return null;
    }

    /**
     * 设置闭包对象的属性值
     */
    public void setValue(ELContext elctx, Object property, Object value) {
        if (property instanceof String) {
            Closure c = get_closure(elctx, (String)property);
            if (c != null) {
                c.setValue(elctx, value);
                elctx.setPropertyResolved(true);
            }
        }
    }

    /**
     * 返回闭包对象属性的类型.
     */
    public Class<?> getType(ELContext elctx, Object property) {
        if (property instanceof String) {
            Closure c = get_closure(elctx, (String)property);
            if (c != null) {
                Class<?> result = c.getType(elctx);
                elctx.setPropertyResolved(true);
                return result;
            }
        }
        return null;
    }

    /**
     * 确定闭包对象属性是否是只读的.
     */
    public boolean isReadOnly(ELContext elctx, Object property) {
        elctx.setPropertyResolved(true);
        return false;
    }

    /**
     * 调用闭包对象的成员函数. 在闭包对象的作用域之内, 任何成员函数都可以
     * 使用this关键字显式调用其他成员函数, 包括所有公共, 保护和私有成员.
     */
    public Object invoke(ELContext elctx, String name, Closure[] args) {
        Closure c = get_my_closure(elctx, name);
        if (c != null) {
            return c.invoke(elctx, args);
        } else {
            return NO_RESULT;
        }
    }

    /**
     * 调用闭包对象的特殊成员函数. 在闭包对象的作用域之内, 特殊成员函数与普通
     * 成员函数没有区别.
     */
    public Object invokeSpecial(ELContext elctx, String name, Closure[] args) {
        return invoke(elctx, name, args);
    }

    /**
     * 调用闭包对象的公共成员函数. 这是一种优化调用方法, 只能通过闭包对象的
     * 外部引用来调用.
     */
    protected Object invokePublic(ELContext elctx, String name, Closure[] args) {
        Closure proc = get_my_closure(elctx, name);
        if (proc != null) {
            if (proc.isPublic() || get_class().inScope(elctx)) {
                return get_class().invokeInScope(elctx, proc, args);
            } else {
                throw new EvaluationException(elctx, _T(EL_UNDEFINED_IDENTIFIER, name));
            }
        } else {
            return NO_RESULT;
        }
    }

    /**
     * 调用闭包对象的公共或保护成员函数. 该方法将由派生类调用.
     */
    protected Object invokeProtected(ELContext elctx, String name, Closure[] args) {
        Closure proc = get_my_closure(elctx, name);
        if (proc != null) {
            if (!proc.isPrivate()) {
                return get_class().invokeInScope(elctx, proc, args);
            } else {
                throw new EvaluationException(elctx, _T(EL_UNDEFINED_IDENTIFIER, name));
            }
        } else {
            return NO_RESULT;
        }
    }
    
    /**
     * 将闭包对象当作函数使用, 但必须通过外部接口调用.
     */
    public Object invoke(ELContext elctx, Closure[] args) {
        throw new MethodNotFoundException();
    }

    /**
     * 查找公共成员变量.
     */
    protected Closure getPublicClosure(ELContext elctx, String name) {
        Closure c = get_closure(elctx, name);
        if (c != null && (c.isPublic() || get_class().inScope(elctx))) {
            return c;
        } else {
            return null;
        }
    }

    /**
     * 返回闭包对象的字符串表示.
     */
    public String toString() {
        ELContext elctx = ELEngine.getCurrentELContext();
        Object result = invokePublic(elctx, "toString", NO_PARAMS);
        if (result != NO_RESULT) {
            return coerceToString(result);
        } else {
            return "#" + get_class().getName() + "@" + Integer.toHexString(hashCode());
        }
    }

    /**
     * 比较两个闭包对象.
     */
    public boolean equals(Object obj) {
        if (!(obj instanceof ClosureObject)) {
            return false;
        }

        ClosureObject other = (ClosureObject)obj;
        if (this == other.get_this()) {
            return true;
        }

        ELContext elctx = ELEngine.getCurrentELContext();
        if (get_class().isInstance(elctx, other)) {
            Closure[] args = {(Closure)other};
            Object result = invokePublic(elctx, "equals", args);
            if (result != NO_RESULT) {
                return coerceToBoolean(result);
            }
        }
        return false;
    }

    /**
     * 返回闭包对象的哈希值.
     */
    public int hashCode() {
        ELContext elctx = ELEngine.getCurrentELContext();
        Object result = invokePublic(elctx, "hashCode", NO_PARAMS);
        if (result != NO_RESULT) {
            return coerceToInt(result);
        } else {
            return System.identityHashCode(this);
        }
    }
}
