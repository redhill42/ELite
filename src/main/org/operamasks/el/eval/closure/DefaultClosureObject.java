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
import java.util.List;
import java.util.Map;

import javax.el.ELContext;
import javax.el.MethodNotFoundException;
import javax.el.PropertyNotWritableException;

import elite.lang.Closure;
import org.operamasks.el.parser.ELNode;
import static org.operamasks.el.eval.ELUtils.*;

/**
 * 闭包对象的对外接口. 应用程序使用这个接口间接访问对象的成员变量, 而
 * 私有变量将受到保护.
 */
class DefaultClosureObject extends AbstractClosure
    implements ClosureObject, Serializable
{
    protected ThisObject thisObj; // 闭包对象的内部实现
    private static final long serialVersionUID = 2293668654108141430L;

    /**
     * 创建闭包对象.
     */
    DefaultClosureObject(ThisObject thisObj) {
        this.thisObj = thisObj;
    }

    /**
     * 获得闭包对象的类定义
     */
    public ClassDefinition get_class() {
        return thisObj.get_class();
    }

    /**
     * 获得闭包对象的内部接口
     */
    public ClosureObject get_this() {
        return thisObj;
    }

    /**
     * 获得闭包对象的外部接口
     */
    public ClosureObject get_owner() {
        return this;
    }

    /**
     * 获得闭包对象的代理对象
     */
    public Object get_proxy() {
        return thisObj.get_proxy();
    }
    
    /**
     * 获得成员变量
     */
    public Closure get_closure(ELContext elctx, String name) {
        return thisObj.getPublicClosure(elctx, name);
    }

    /**
     * 获得所有公共成员变量
     * @param elctx
     */
    public Map<String,Closure> get_closures(ELContext elctx) {
        return thisObj.get_closures(elctx);
    }

    /**
     * 返回闭包对象的属性值, 闭包对象属性通过以下几种方式定义:
     *
     * 1) get和set方法
     * 2) 数据成员变量
     * 3) 由[]运算符实现的动态属性.
     */
    public Object getValue(ELContext elctx, Object property) {
        if (property instanceof String) {
            String key = (String)property;
            Object result;

            // invoke getter procedure first
            result = thisObj.invokePublic(elctx, "get" + capitalize(key), NO_PARAMS);
            if (result != NO_RESULT) {
                elctx.setPropertyResolved(true);
                return checkResult(result);
            }

            // get the data member property
            Closure member = get_closure(elctx, key);
            if (member != null) {
                result = member.getValue(elctx);
                elctx.setPropertyResolved(true);
                return checkResult(result);
            }
        }

        // invoke [] procedure to get dynamic property
        Closure operator = get_closure(elctx, "[]");
        if (operator != null) {
            Object result = operator.call(elctx, property);
            elctx.setPropertyResolved(true);
            return checkResult(result);
        }

        return null;
    }

    /**
     * 设置闭包对象的属性值, 闭包对象属性通过以下几种方式定义:
     *
     * 1) get和set方法
     * 2) 数据成员变量
     * 3) 由[]=运算符实现的动态属性.
     */
    public void setValue(ELContext elctx, Object property, Object value) {
        if (property instanceof String) {
            String key = (String)property;
            Object result;

            // invoke setter procedure first
            result = thisObj.invokePublic(elctx, "set" + capitalize(key),
                                          new Closure[] { new LiteralClosure(value) });
            if (result != NO_RESULT) {
                elctx.setPropertyResolved(true);
                return;
            }

            // set the data member property
            Closure member = get_closure(elctx, key);
            if (member != null) {
                try {
                    member.setValue(elctx, value);
                    elctx.setPropertyResolved(true);
                    return;
                } catch (PropertyNotWritableException ex) {
                    // ignore
                }
            }
        }

        // invoke []= procedure for dynamic property
        Closure operator = get_closure(elctx, "[]=");
        if (operator != null) {
            operator.call(elctx, property, value);
            elctx.setPropertyResolved(true);
            return;
        }
    }

    /**
     * 调用闭包对象的成员函数, 包括由invoke函数定义的动态方法.
     */
    public Object invoke(ELContext elctx, String name, Closure[] args) {
        // invoke member closure procedure
        Object result = thisObj.invokePublic(elctx, name, args);
        if (result != NO_RESULT) {
            return checkResult(result);
        }

        // invoke dynamic closure procedure
        Closure proc = get_closure(elctx, "invoke");
        if (proc != null) {
            if (proc.arity(elctx) == 3) {
                // accept named arguments
                String[] keys = new String[args.length];
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof NamedClosure) {
                        NamedClosure c = (NamedClosure)args[i];
                        keys[i] = c.name();
                        args[i] = c.getDelegate();
                    }
                }
                List vlist = new ELNode.VarArgList(elctx, args, 0);
                return checkResult(proc.call(elctx, name, keys, vlist));
            } else {
                // accpet variable arguments only
                List vlist = new ELNode.VarArgList(elctx, args, 0);
                return checkResult(proc.call(elctx, name, vlist));
            }
        }

        return NO_RESULT;
    }

    /**
     * 调用闭包对象的特殊成员函数, 例如运算符重载函数, 不包括由invoke函数定义的动态方法.
     */
    public Object invokeSpecial(ELContext elctx, String name, Closure[] args) {
        return checkResult(thisObj.invokePublic(elctx, name, args));
    }

    /**
     * 如果闭包对象实现了__call__函数, 则可以将闭包对象当作函数调用.
     */
    public Object invoke(ELContext elctx, Closure[] args) {
        Object result = thisObj.invokePublic(elctx, "__call__", args);
        if (result != NO_RESULT) {
            return checkResult(result);
        } else {
            throw new MethodNotFoundException();
        }
    }

    /**
     * 返回闭包对象属性的类型.
     */
    public Class<?> getType(ELContext elctx, Object property) {
        return thisObj.getType(elctx, property);
    }

    /**
     * 确定闭包对象属性是否时只读的.
     */
    public boolean isReadOnly(ELContext elctx, Object property) {
        return thisObj.isReadOnly(elctx, property);
    }

    /**
     * 返回闭包对象的字符串表示.
     */
    public String toString() {
        return thisObj.toString();
    }

    /**
     * 比较两个闭包对象.
     */
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else {
            return thisObj.equals(obj);
        }
    }

    /**
     * 返回闭包对象的哈希值.
     */
    public int hashCode() {
        return thisObj.hashCode();
    }

    private Object checkResult(Object result) {
        return result == thisObj ? this : result;
    }
}
