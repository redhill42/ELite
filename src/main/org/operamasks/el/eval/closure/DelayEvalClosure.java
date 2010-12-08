/*
 * $Id: DelayEvalClosure.java,v 1.1 2009/05/12 10:24:41 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
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
