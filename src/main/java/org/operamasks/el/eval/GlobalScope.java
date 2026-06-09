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

package org.operamasks.el.eval;

import java.math.MathContext;
import java.io.Serializable;
import javax.el.ELContext;
import javax.el.ValueExpression;
import javax.el.PropertyNotFoundException;

import org.operamasks.el.eval.closure.LiteralClosure;
import org.operamasks.el.eval.closure.MethodClosure;
import org.operamasks.el.resolver.MethodResolver;
import elite.lang.Rational;

public class GlobalScope implements PropertyDelegate, Serializable
{
    public static final GlobalScope SINGLETON = new GlobalScope();
    private GlobalScope() {}

    // API integration

    public Object getContext(ELContext elctx, Class key) {
        return elctx.getContext(key);
    }

    public void putContext(ELContext elctx, Class key, Object value) {
        elctx.putContext(key, value);
    }

    public static MathContext getMathContext(ELContext elctx) {
        return (MathContext)elctx.getContext(MathContext.class);
    }

    public static void setMathContext(ELContext elctx, MathContext mc) {
        elctx.putContext(MathContext.class, mc);
    }

    public static boolean isRationalEnabled(ELContext elctx) {
        return Boolean.TRUE.equals(elctx.getContext(Rational.class));
    }

    public static void setRationalEnabled(ELContext elctx, boolean value) {
        elctx.putContext(Rational.class, value);
    }

    // Global variable resolver

    public Object getValue(ELContext elctx, Object property) {
        String name = (String)property;

        ValueExpression ve = elctx.getVariableMapper().resolveVariable(name);
        if (ve != null) {
            Object result = ve.getValue(elctx);
            elctx.setPropertyResolved(true);
            return result;
        }

        MethodResolver resolver = MethodResolver.getInstance(elctx);
        MethodClosure closure = resolver.resolveGlobalMethod(name);
        if (closure != null) {
            elctx.setPropertyResolved(true);
            return closure;
        }

        throw new PropertyNotFoundException();
    }

    public Class<?> getType(ELContext elctx, Object property) {
        String name = (String)property;

        ValueExpression ve = elctx.getVariableMapper().resolveVariable(name);
        if (ve != null) {
            Class<?> result = ve.getType(elctx);
            elctx.setPropertyResolved(true);
            return result;
        }

        MethodResolver resolver = MethodResolver.getInstance(elctx);
        if (resolver.resolveGlobalMethod(name) != null) {
            elctx.setPropertyResolved(true);
            return MethodClosure.class;
        }

        throw new PropertyNotFoundException();
    }

    public void setValue(ELContext elctx, Object property, Object value) {
        String name = (String)property;

        ValueExpression ve = elctx.getVariableMapper().resolveVariable(name);
        if (ve != null) {
            ve.setValue(elctx, value);
            elctx.setPropertyResolved(true);
            return;
        }

        elctx.getVariableMapper().setVariable(name, new LiteralClosure(value));
        elctx.setPropertyResolved(true);
    }

    public boolean isReadOnly(ELContext elctx, Object property) {
        elctx.setPropertyResolved(true);
        return false;
    }

    private Object readResolve() {
        return SINGLETON;
    }
}
