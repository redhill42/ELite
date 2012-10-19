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
