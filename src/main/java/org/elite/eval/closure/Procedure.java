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
import javax.el.PropertyNotWritableException;

import elite.lang.Closure;
import org.elite.eval.EvaluationContext;
import org.elite.parser.ELNode;

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
     * @param args the procedure arguments
     * @return result of procedure execution
     */
    @Override
    public Object call_with(ELContext elctx, Object scope, Closure... args) {
        if (scope instanceof ClosureObject) {
            scope = ((ClosureObject)scope).get_owner();
        }

        EvaluationContext env = getContext(elctx);
        env = env.pushContext(new EnvExtent(env, scope));
        return node.invoke(env, args);
    }
}
