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

package org.operamasks.el.eval.closure;

import javax.el.ELContext;
import javax.el.VariableMapper;

import elite.lang.Closure;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.eval.EvaluationContext;

public class DelayEvalClosure extends DelayClosure
{
    protected Closure eval;

    public DelayEvalClosure(Closure eval) {
        this.eval = eval;
    }

    public DelayEvalClosure(EvaluationContext ctx, ELNode node) {
        eval = new EvalClosure(ctx, node);
    }

    public EvaluationContext getContext() {
        return eval != null ? eval.getContext() : null;
    }

    public EvaluationContext getContext(ELContext elctx) {
        return eval != null ? eval.getContext(elctx) : null;
    }

    public void _setenv(ELContext elctx, VariableMapper env) {
        if (eval != null) {
            eval._setenv(elctx, env);
        }
    }

    protected Object force(ELContext elctx) {
        if (eval != null) {
            Object result = eval.getValue(elctx);
            eval = null;
            return result;
        } else {
            return null;
        }
    }

    protected void forget() {
        eval = null;
    }
}
