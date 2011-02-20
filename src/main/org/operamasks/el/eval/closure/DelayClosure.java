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
import javax.el.MethodInfo;
import javax.el.PropertyNotWritableException;

import elite.lang.Closure;
import org.operamasks.el.eval.EvaluationException;
import org.operamasks.el.eval.ELEngine;
import static org.operamasks.el.resources.Resources.*;

public abstract class DelayClosure extends AnnotatedClosure
{
    protected Object value = NO_VALUE;
    private transient ValueChangeListener listener;
    private static final Object NO_VALUE = new Object();

    protected abstract Object force(ELContext elctx);
    protected abstract void forget();

    public void setValueChangeListener(ValueChangeListener listener) {
        this.listener = listener;
    }

    public Object getValue(ELContext elctx) {
        if (value == NO_VALUE)
            value = force(elctx);
        return value;
    }

    public void setValue(ELContext context, Object newValue) {
        if (isFinal()) {
            throw new PropertyNotWritableException(_T(EL_PROPERTY_NOT_WRITABLE));
        }

        forget();
        if (listener != null) {
            Object oldValue = value == NO_VALUE ? null : value;
            value = newValue;
            listener.valueChanged(oldValue, newValue);
        } else {
            value = newValue;
        }
    }

    public boolean isReadOnly(ELContext elctx) {
        return false;
    }

    public Class<?> getType(ELContext elctx) {
        Object v = getValue(elctx);
        return v == null ? null : v.getClass();
    }

    public Class<?> getExpectedType() {
        return Object.class;
    }

    public int arity(ELContext elctx) {
        Object v = getValue(elctx);
        if (v instanceof Closure) {
            return ((Closure)v).arity(elctx);
        } else {
            return -1;
        }
    }

    public MethodInfo getMethodInfo(ELContext elctx) {
        Object v = getValue(elctx);
        if (v instanceof Closure) {
            return ((Closure)v).getMethodInfo(elctx);
        } else {
            throw new EvaluationException(elctx, _T(EL_INVALID_METHOD_EXPRESSION, ""));
        }
    }

    public Object invoke(ELContext elctx, Closure[] args) {
        Object v = getValue(elctx);
        if (v instanceof Closure) {
            return ((Closure)v).invoke(elctx, args);
        } else {
            return ELEngine.invokeTarget(elctx, v, args);
        }
    }

    public String getExpressionString() {
        return null;
    }

    public boolean isLiteralText() {
        return false;
    }

    public boolean equals(Object obj) {
        return this == obj;
    }

    public int hashCode() {
        return System.identityHashCode(this);
    }

    public String toString() {
        return value == NO_VALUE ? "#<delay>" : String.valueOf(value);
    }
}
