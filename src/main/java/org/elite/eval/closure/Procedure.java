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
import javax.el.PropertyNotFoundException;
import javax.el.PropertyNotWritableException;
import javax.el.ValueExpression;

import elite.lang.Closure;
import org.elite.eval.ELEngine;
import org.elite.eval.ELUtils;
import org.elite.eval.EvaluationContext;
import org.elite.eval.EvaluationException;
import org.elite.eval.MethodDelegate;
import org.elite.eval.MethodResolvable;
import org.elite.eval.VariableMapperImpl;
import org.elite.parser.ELNode;
import org.elite.resolver.MethodResolver;

import static org.elite.resources.Resources.EL_UNDEFINED_IDENTIFIER;
import static org.elite.resources.Resources._T;

public class Procedure extends EvalClosure implements CallableClosure
{
    public Procedure(EvaluationContext context, ELNode node) {
        super(context, node);
    }

    public Object getValue(ELContext elctx) {
        return this;
    }

    public void setValue(ELContext elctx, Object value) {
        throw new PropertyNotWritableException();
    }

    public boolean isReadOnly(ELContext elctx) {
        return true;
    }

    public Class<?> getType(ELContext elctx) {
        return Procedure.class;
    }

    public boolean isProcedure() {
        return true;
    }

    /**
     * Invoke the procedure within the given scope. The variables in the
     * scope is visible to the procedure. The procedure is behaviors like
     * a member procedure of scoped object.
     *
     * @param elctx the evaluation context
     * @param scope the scoped object
     * @return result of procedure execution
     */
    @Override
    public Object call_with(ELContext elctx, Object scope, Object... args) {
        if (scope instanceof ClosureObject) {
            scope = ((ClosureObject)scope).get_owner();
        }

        EvaluationContext env = getContext(elctx);
        env = env.pushContext(new EnvExtent(env, scope));

        Closure[] xargs = new Closure[args.length + 1];
        xargs[0] = new LiteralClosure(scope);
        for (int i = 0; i < args.length; i++)
            xargs[i + 1] = new LiteralClosure(args[i]);
        return node.invoke(env, xargs);
    }

    private static class EnvExtent extends VariableMapperImpl {
        final EvaluationContext env;
        final Object scope;

        public EnvExtent(EvaluationContext env, Object scope) {
            this.env = env;
            this.scope = scope;
        }

        public ValueExpression resolveVariable(final String name) {
            ValueExpression value = super.resolveVariable(name);
            if (value != null) {
                return value;
            }

            // create a wrapper to call into scoped object
            Closure wrapper = new ScopedClosure(env, scope, name);
            super.setVariable(name, wrapper);
            return wrapper;
        }

        private static class ScopedClosure extends AbstractClosure {
            final EvaluationContext env;
            final Object scope;
            final String name;

            ScopedClosure(EvaluationContext env, Object scope, String name) {
                this.env = env;
                this.scope = scope;
                this.name = name;
            }

            public Object invoke(ELContext elctx, Closure[] args) {
                MethodResolver resolver = MethodResolver.getInstance(elctx);
                MethodClosure method;
                Object target, result;

                // resolve the scoped method
                if (scope instanceof ClosureObject) {
                    result = ((ClosureObject)scope).invokeSpecial(elctx, name, args);
                    if (result != ELUtils.NO_RESULT) {
                        return result;
                    }
                } else if (!(scope instanceof MethodDelegate)) {
                    method = resolver.resolveMethod(scope.getClass(), name);
                    if (method != null) {
                        return method.invoke(elctx, scope, args);
                    }
                }

                // resolve the enclosing variable
                ValueExpression expr = env.resolveVariable(name);
                if (expr != null) {
                    target = (expr instanceof Closure) ? expr : expr.getValue(elctx);
                    return ELEngine.invokeTarget(elctx, target, args);
                }

                // resolve the global function
                method = resolver.resolveGlobalMethod(name);
                if (method != null) {
                    return method.invoke(elctx, args);
                }

                // resolve the global variable
                elctx.setPropertyResolved(false);
                target = elctx.getELResolver().getValue(elctx, null, name);
                if (target != null && elctx.isPropertyResolved()) {
                    return ELEngine.invokeTarget(elctx, target, args);
                }

                // invoke dynamically
                if (scope instanceof ClosureObject) {
                    result = ((ClosureObject)scope).invoke(elctx, name, args);
                    if (result != ELUtils.NO_RESULT) {
                        return result;
                    }
                } else if (scope instanceof MethodResolvable) {
                    return ((MethodResolvable)scope).invoke(elctx, name, args);
                }

                throw new EvaluationException(elctx, _T(EL_UNDEFINED_IDENTIFIER, name));
            }

            public Object getValue(ELContext elctx) {
                MethodResolver resolver = MethodResolver.getInstance(elctx);
                Object result;

                // resolve the scoped property
                if (scope instanceof ClosureObject) {
                    elctx.setPropertyResolved(false);
                    result = ((ClosureObject)scope).getValue(elctx, name);
                    if (elctx.isPropertyResolved()) {
                        return result;
                    }
                } else {
                    try {
                        elctx.setPropertyResolved(false);
                        result = elctx.getELResolver().getValue(elctx, scope, name);
                        if (elctx.isPropertyResolved()) {
                            return result;
                        }
                    } catch (PropertyNotFoundException ex) {
                        // fall through
                    }
                }

                // resolve the enclosing variable
                ValueExpression expr = env.resolveVariable(name);
                if (expr != null) {
                    elctx.setPropertyResolved(true);
                    return expr.getValue(elctx);
                }

                // resolve the scoped method
                if (!(scope instanceof ClosureObject)) {
                    result = resolver.resolveMethod(scope.getClass(), name);
                    if (result != null) {
                        elctx.setPropertyResolved(true);
                        return result;
                    }
                }

                // resolve the global function
                result = resolver.resolveGlobalMethod(name);
                if (result != null) {
                    elctx.setPropertyResolved(true);
                    return result;
                }

                // resolve the global variable
                elctx.setPropertyResolved(false);
                result = elctx.getELResolver().getValue(elctx, null, name);
                if (elctx.isPropertyResolved()) {
                    return result;
                }

                throw new EvaluationException(elctx, _T(EL_UNDEFINED_IDENTIFIER, name));
            }

            public void setValue(ELContext elctx, Object value) {
                // set scoped property value
                if (scope instanceof ClosureObject) {
                    elctx.setPropertyResolved(false);
                    ((ClosureObject)scope).setValue(elctx, name, value);
                    if (elctx.isPropertyResolved()) {
                        return;
                    }
                } else {
                    elctx.setPropertyResolved(false);
                    elctx.getELResolver().setValue(elctx, scope, name, value);
                    if (elctx.isPropertyResolved()) {
                        return;
                    }
                }

                // set the enclosing variable value
                ValueExpression expr = env.resolveVariable(name);
                if (expr != null) {
                    elctx.setPropertyResolved(true);
                    expr.setValue(elctx, value);
                    return;
                }

                // set the global variable value
                elctx.setPropertyResolved(false);
                elctx.getELResolver().setValue(elctx, null, name, value);
                if (elctx.isPropertyResolved()) {
                    return;
                }

                throw new EvaluationException(elctx, _T(EL_UNDEFINED_IDENTIFIER, name));
            }
        }
    }
}
