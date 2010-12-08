/*
 * $Id: MethodClosure.java,v 1.4 2009/03/23 13:18:25 danielyuan Exp $
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

import javax.el.ELContext;
import javax.el.MethodNotFoundException;
import elite.lang.Closure;

/**
 * Java方法的包装对象, 使Java对象方法融入ELite的对象体系.
 */
public abstract class MethodClosure extends AbstractClosure
{
    /**
     * 返回方法名.
     */
    public abstract String getName();

    /**
     * 调用Java方法, 如果方法对象包含多个Java方法则需要按参数类型进行匹配.
     */
    public abstract Object invoke(ELContext elctx, Object base, Closure[] args);

    /**
     * 调用Java对象的基类方法, 只有在非常特殊的情况下才需要调用此方法, 在一般情况
     * 下调用此方法通常都会失败.
     */
    public Object invokeSuper(ELContext elctx, Object base, Closure[] args) {
        throw new MethodNotFoundException();
    }

    /**
     * 以静态方式调用Java方法, 如果此方法对象表示的不是一个静态方法将会发生
     * 运行时错误.
     */
    public Object invoke(ELContext elctx, Closure[] args) {
        return invoke(elctx, null, args);
    }

    public boolean isProcedure() {
        return true;
    }
}
