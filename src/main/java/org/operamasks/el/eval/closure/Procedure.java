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
import javax.el.PropertyNotWritableException;
import javax.el.ValueExpression;
import javax.el.PropertyNotFoundException;

import elite.lang.Closure;
import org.operamasks.el.eval.EvaluationContext;
import org.operamasks.el.eval.ELUtils;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.VariableMapperImpl;
import org.operamasks.el.eval.EvaluationException;
import org.operamasks.el.eval.MethodDelegate;
import org.operamasks.el.eval.MethodResolvable;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.resolver.MethodResolver;
import static org.operamasks.el.resources.Resources.*;

public class Procedure extends EvalClosure
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
     * @param args the procedure arguments
     * @return result of procedure execution
     */
    public Object call_with(ELContext elctx, Object scope, Closure... args) {
        if (scope instanceof ClosureObject) {
            scope = ((ClosureObject)scope).get_owner();
        }

        EvaluationContext env = getContext(elctx);
        env = env.pushContext(new EnvExtent(env, scope));
        return node.invoke(env, args);
    }

    private static class EnvExtent extends VariableMapperImpl {
        final EvaluationContext env;
        final Object scope;

        EnvExtent(EvaluationContext env, Object scope) {
            this.env = env;
            this.scope = scope;
        }

        public ValueExpression resolveVariable(final String name) {
            ValueExpression value = super.resolveVariable(name);
            if (value != null) {
                return value;
            }

            // resolve the "this" special variable
            if ("this".equals(name)) {
                Closure thisObj = new LiteralClosure(scope);
                super.setVariable("this", thisObj);
                return thisObj;
            }

            // create a wrapper to call into scoped object
            Closure wrapper = new ScopedClosure(env, scope, name);
            super.setVariable(name, wrapper);
            return wrapper;
        }
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
