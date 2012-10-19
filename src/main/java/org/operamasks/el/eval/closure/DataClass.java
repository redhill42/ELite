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
import org.operamasks.el.eval.ELEngine;

public class DataClass extends AbstractClosure
{
    private Class jclass;
    private String[] slots;

    public DataClass(Class jclass, String[] slots) {
        this.jclass = jclass;
        this.slots = slots;
    }

    public Class getJavaClass() {
        return jclass;
    }

    public String[] getSlots() {
        return slots;
    }
    
    public Object getValue(ELContext elctx) {
        return jclass;
    }

    public Class getType(ELContext elctx) {
        return Class.class;
    }

    public Class getExpectedType(ELContext elctx) {
        return Class.class;
    }

    public Object invoke(ELContext elctx, Closure[] args) {
        return ELEngine.invokeTarget(elctx, jclass, args);
    }
}
