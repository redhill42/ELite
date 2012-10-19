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

import javax.el.ELContext;
import elite.lang.Closure;

public class TargetMethodClosure extends DelegatingClosure
{
    private final Object target;

    public TargetMethodClosure(Object target, MethodClosure closure) {
        super(closure);
        this.target = target;
    }

    public Object invoke(ELContext elctx, Closure[] args) {
        MethodClosure closure = (MethodClosure)getDelegate();
        return closure.invoke(elctx, target, args);
    }

    public Object getValue(ELContext elctx) {
        return this;
    }
}
