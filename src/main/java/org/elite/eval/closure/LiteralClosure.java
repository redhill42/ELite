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

import java.lang.reflect.Modifier;
import javax.el.MethodInfo;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;

import elite.lang.Closure;
import org.elite.eval.EvaluationException;
import org.elite.eval.ELEngine;
import static org.elite.resources.Resources.*;

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
