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

package org.elite.eval.closure;

import javax.el.ELContext;
import javax.el.MethodNotFoundException;
import elite.lang.Closure;

import java.lang.reflect.Method;

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
     * 返回Java方法，如果方法不存在或存在多个重载方法，则返回null.
     */
    public Method getJavaMethod() {
        return null;
    }

    /**
     * 使用运行时参数解析Java方法，如果方法不存在则返回null.
     */
    public Method getJavaMethod(ELContext elctx, Object obj, Object... args) {
        return null;
    }

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
