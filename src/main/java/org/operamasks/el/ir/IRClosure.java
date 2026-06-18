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

package org.operamasks.el.ir;

import javax.el.ELContext;
import javax.el.MethodInfo;
import javax.el.PropertyNotWritableException;
import javax.el.ValueExpression;

import elite.lang.Closure;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.EvaluationContext;

/**
 * A closure: an IRFunction bundled with captured variable values.
 * Extends {@link elite.lang.Closure} so that {@code .curry()},
 * {@code .call()}, and other Closure methods are discoverable by
 * {@link org.operamasks.el.resolver.MethodResolver} when a closure
 * is accessed from AST-evaluated (trampolined) code.
 */
public class IRClosure extends Closure {
    public final IRFunction function;
    public final Object[] captured;
    /**
     * The evalContext chain active when this closure was created.
     * Used as the basis for PUSH_GLOBAL/STORE_DEEP inside the closure body,
     * so captured variable reads and writes resolve against the original
     * enclosing scope rather than the caller's scope.
     */
    public final EvaluationContext evalContext;

    public IRClosure(IRFunction function, Object[] captured) {
        this(function, captured, null);
    }

    public IRClosure(IRFunction function, Object[] captured, EvaluationContext evalContext) {
        this.function = function;
        this.captured = captured;
        this.evalContext = evalContext;
    }

    public IRFunction getFunction() { return function; }
    public Object[] getCaptured() { return captured; }

    // ── Closure abstract methods ──

    @Override
    public Object invoke(ELContext elctx, Closure[] args) {
        // IRClosures can be invoked from lazy sequence forcing where
        // elctx may be null (getCurrentELContext returns null after
        // the eval context has been torn down). Fall back to ThreadLocal.
        if (elctx == null)
            elctx = ELEngine.getCurrentELContext();
        int paramCount = function.paramCount();
        int captureCount = function.captureCount();
        int provided = Math.min(args != null ? args.length : 0, paramCount);
        Object[] expandedArgs = new Object[provided + captureCount];
        Object[] callArgs = ELEngine.getArgValues(elctx, args);
        System.arraycopy(callArgs, 0, expandedArgs, 0, provided);
        System.arraycopy(captured, 0, expandedArgs, provided, captureCount);
        EvaluationContext evalctx = null;
        try {
            evalctx = (EvaluationContext) elctx.getContext(
                EvaluationContext.class);
        } catch (Exception ignored) {}
        // Prefer the closure's own evalContext so that captured variable
        // reads and writes resolve in the original enclosing scope.
        if (evalContext != null)
            evalctx = evalContext;
        return new IRInterpreter(elctx, function, evalctx)
            .execute(expandedArgs);
    }

    @Override
    public int arity(ELContext elctx) {
        return function.paramCount();
    }

    @Override
    public MethodInfo getMethodInfo(ELContext elctx) {
        Class<?>[] paramTypes = new Class<?>[function.paramCount()];
        for (int i = 0; i < paramTypes.length; i++)
            paramTypes[i] = Object.class;
        return new MethodInfo(function.name(), Object.class, paramTypes);
    }

    // ── ValueExpression methods ──

    @Override
    public Object getValue(ELContext elctx) {
        return this;
    }

    @Override
    public void setValue(ELContext elctx, Object value) {
        throw new PropertyNotWritableException();
    }

    @Override
    public boolean isReadOnly(ELContext elctx) {
        return true;
    }

    @Override
    public Class<?> getType(ELContext elctx) {
        return IRClosure.class;
    }

    @Override
    public Class<?> getExpectedType() {
        return Closure.class;
    }

    @Override
    public String getExpressionString() {
        return function.name() != null ? function.name() : "<closure>";
    }

    @Override
    public boolean isLiteralText() {
        return false;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof IRClosure other)) return false;
        return function.equals(other.function)
            && java.util.Arrays.equals(captured, other.captured);
    }

    @Override
    public int hashCode() {
        return function.hashCode() * 31
            + java.util.Arrays.hashCode(captured);
    }

    @Override
    public String toString() {
        return "IRClosure[" + function.name() + "]";
    }
}
