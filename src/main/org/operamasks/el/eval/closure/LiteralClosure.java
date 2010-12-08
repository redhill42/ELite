/*
 * $Id: LiteralClosure.java,v 1.4 2009/05/09 18:51:21 danielyuan Exp $
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

import java.lang.reflect.Modifier;
import javax.el.MethodInfo;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;

import elite.lang.Closure;
import org.operamasks.el.eval.EvaluationException;
import org.operamasks.el.eval.ELEngine;
import static org.operamasks.el.resources.Resources.*;

public class LiteralClosure extends AnnotatedClosure
{
    private Object value;
    private transient ValueChangeListener listener;

    public LiteralClosure(Object value) {
        this.value = value;
    }

    public LiteralClosure(Object value, boolean readonly) {
        this.value = value;
        if (readonly) {
            setModifiers(getModifiers() | Modifier.FINAL);
        }
    }

    public void setValueChangeListener(ValueChangeListener listener) {
        this.listener = listener;
    }

    public Object getValue(ELContext elctx) {
        return value;
    }

    public void setValue(ELContext elctx, Object value) {
        if (isFinal()) {
            throw new PropertyNotWritableException();
        }

        if (listener != null) {
            Object oldValue = this.value; this.value = value;
            listener.valueChanged(oldValue, value);
        } else {
            this.value = value;
        }
    }

    public boolean isReadOnly(ELContext elctx) {
        return isFinal();
    }

    public Class<?> getType(ELContext elctx) {
        return (value == null) ? null : value.getClass();
    }

    public Class<?> getExpectedType() {
        return Object.class;
    }

    public int arity(ELContext elctx) {
        return (value instanceof Closure) ? ((Closure)value).arity(elctx) : -1;
    }

    public MethodInfo getMethodInfo(ELContext elctx) {
        if (value instanceof Closure) {
            return ((Closure)value).getMethodInfo(elctx);
        } else {
            throw new EvaluationException(elctx, _T(EL_INVALID_METHOD_EXPRESSION, ""));
        }
    }

    public Object invoke(ELContext elctx, Closure[] args) {
        return ELEngine.invokeTarget(elctx, value, args);
    }

    public String getExpressionString() {
        return (value == null) ? null : value.toString();
    }

    public boolean isLiteralText() {
        return false;
    }
    
    public boolean equals(Object obj) {
        if (obj instanceof LiteralClosure) {
            LiteralClosure other = (LiteralClosure)obj;
            if (value == null) {
                return other.value == null;
            } else {
                return value.equals(other.value);
            }
        }
        return false;
    }

    public int hashCode() {
        return (value == null) ? 0 : value.hashCode();
    }

    public String toString() {
        return (value == null) ? null : value.toString();
    }
}
