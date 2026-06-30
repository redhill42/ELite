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

package org.elite.ir;

import javax.el.*;

import elite.lang.Closure;
import org.elite.eval.ELEngine;
import org.elite.eval.EvaluationContext;
import org.elite.eval.closure.ClosureObject;
import org.elite.eval.closure.EnvExtent;
import org.elite.resolver.MethodResolver;

import java.util.Arrays;

/**
 * A closure: an IRFunction bundled with captured variable values.
 * Extends {@link elite.lang.Closure} so that {@code .curry()},
 * {@code .call()}, and other Closure methods are discoverable by
 * {@link MethodResolver} when a closure
 * is accessed from AST-evaluated (trampolined) code.
 */
public class IRClosure extends Closure {
    final IRFunction function;

    /**
     * The evalContext chain active when this closure was created.
     * Used as the basis for PUSH_GLOBAL/STORE_GLOBAL inside the closure body,
     * so captured variable reads and writes resolve against the original
     * enclosing scope rather than the caller's scope.
     */
    transient EvaluationContext evalContext;

    public IRClosure(EvaluationContext context, IRFunction function) {
        this.evalContext = context;
        this.function = function;
    }

    @Override
    public EvaluationContext getContext() {
        return this.evalContext;
    }

    @Override
    public EvaluationContext getContext(ELContext elctx) {
        if (this.evalContext == null) {
            if (elctx == null)
                elctx = ELEngine.getCurrentELContext();
            this.evalContext = new EvaluationContext(elctx);
        } else {
            if (elctx != null)
                this.evalContext.setELContext(elctx);
        }
        return evalContext;
    }

    @Override
    public void _setenv(ELContext elctx, VariableMapper env) {
        this.evalContext = getContext(elctx).pushContext(env);
    }

    @Override
    public Object getValue(ELContext elctx) {
        return this;
    }

    @Override
    public void setValue(ELContext elctx, Object value) {
        throw new PropertyNotWritableException();
    }

    public boolean isReadOnly(ELContext elctx) {
        return true;
    }

    @Override
    public Class<?> getType(ELContext elctx) {
        return IRClosure.class;
    }

    @Override
    public boolean isProcedure() {
        return true;
    }

    @Override
    public int arity(ELContext elctx) {
        return function.paramCount();
    }

    @Override
    public MethodInfo getMethodInfo(ELContext elctx) {
        Class<?>[] paramTypes = new Class<?>[function.paramCount()];
        Arrays.fill(paramTypes, Object.class);
        return new MethodInfo(function.name(), Object.class, paramTypes);
    }

    @Override
    public Object invoke(ELContext elctx, Closure[] args) {
        Object[] callArgs = ELEngine.getArgValues(elctx, args);
        return new IRInterpreter(getContext(elctx), function).execute(callArgs);
    }

    /**
     * Invoke the procedure within the given scope. The variables in the
     * scope is visible to the procedure. The procedure is behaviors like
     * a member procedure of scoped object.
     *
     * @param elctx the evaluation context
     * @param scope the scoped object
     * @param args the procedure arguments
     * @return result of procedure execution
     */
    @SuppressWarnings("unused")
    public Object call_with(ELContext elctx, Object scope, Closure... args) {
        if (scope instanceof ClosureObject) {
            scope = ((ClosureObject)scope).get_owner();
        }

        EvaluationContext env = getContext(elctx);
        env = env.pushContext(new EnvExtent(env, scope));
        Object[] callArgs = ELEngine.getArgValues(elctx, args);
        return new IRInterpreter(env, function).execute(callArgs);
    }

    @Override
    public Class<?> getExpectedType() {
        return Object.class;
    }

    @Override
    public String getExpressionString() {
        return null;
    }

    @Override
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
        return "#<ir-closure:" + function.name() + ">";
    }
}
