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

import java.io.ObjectOutputStream;
import java.io.IOException;
import javax.el.ELContext;
import javax.el.MethodInfo;
import javax.el.FunctionMapper;
import javax.el.VariableMapper;
import javax.el.ValueExpression;

import elite.lang.Closure;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.EvaluationContext;
import org.operamasks.el.eval.VariableMapperImpl;
import org.operamasks.el.parser.ELNode;

public class EvalClosure extends AnnotatedClosure
{
    protected final ELNode node;
    private FunctionMapper fm;
    private VariableMapper vm;

    protected transient EvaluationContext context;

    private static final long serialVersionUID = -7013242346088674319L;

    public EvalClosure(EvaluationContext context, ELNode node) {
        this.context = context;
        this.node = node;
        this.fm = context.getFunctionMapper();
        this.vm = null; // build it on demand
    }

    public EvaluationContext getContext() {
        return this.context;
    }
    
    public EvaluationContext getContext(ELContext elctx) {
        if (this.context == null) {
            if (elctx == null)
                elctx = ELEngine.getCurrentELContext();
            this.context = new EvaluationContext(elctx, fm, vm);
        } else {
            if (elctx != null) {
                this.context.setELContext(elctx);
            }
        }
        return this.context;
    }

    public void _setenv(ELContext elctx, VariableMapper env) {
        this.context = getContext(elctx).pushContext(env);
    }

    public Object getValue(ELContext elctx) {
        return node.getValue(getContext(elctx));
    }

    public void setValue(ELContext elctx, Object value) {
        node.setValue(getContext(elctx), value);
    }

    public boolean isReadOnly(ELContext elctx) {
        return node.isReadOnly(getContext(elctx));
    }

    public Class<?> getType(ELContext elctx) {
        return node.getType(getContext(elctx));
    }

    public int arity(ELContext elctx) {
        if (node instanceof ELNode.LAMBDA) {
            return ((ELNode.LAMBDA)node).vars.length;
        }

        MethodInfo info = node.getMethodInfo(getContext(elctx));
        if (info != null && info.getParamTypes() != null) {
            return info.getParamTypes().length;
        } else {
            return -1;
        }
    }

    public MethodInfo getMethodInfo(ELContext elctx) {
        return node.getMethodInfo(getContext(elctx));
    }

    public Object invoke(ELContext elctx, Closure[] args) {
        return node.invoke(getContext(elctx), args);
    }

    public Class<?> getExpectedType() {
        return Object.class;
    }

    public String getExpressionString() {
        return null;
    }

    public boolean isLiteralText() {
        return node instanceof ELNode.LITERAL;
    }

    public boolean equals(Object obj) {
        return this == obj;
    }

    public int hashCode() {
        return System.identityHashCode(this);
    }

    public String toString() {
        if (node instanceof ELNode.LAMBDA) {
            String name = ((ELNode.LAMBDA)node).name;
            return name == null ? "#<procedure>" : "#<procedure:" + name + ">";
        } else if (node instanceof ELNode.IDENT) {
            return "#<closure:" + ((ELNode.IDENT)node).id + ">";
        } else {
            return "#<closure>";
        }
    }

    private void writeObject(ObjectOutputStream out)
        throws IOException
    {
        // rebuild variable mapper to be saved
        if (context != null) {
            VariableMapperBuilder vmb = new VariableMapperBuilder(context);
            node.applyVariableMapper(vmb);
            this.vm = vmb.getVariableMapper();
        }

        out.defaultWriteObject();
    }

    static class VariableMapperBuilder extends VariableMapper {
        private EvaluationContext source;
        private VariableMapper target;

        VariableMapperBuilder(EvaluationContext source) {
            this.source = source;
        }

        public ValueExpression resolveVariable(String name) {
            ValueExpression value = source.resolveVariable(name);
            if (value != null) {
                if (target == null)
                    target = new VariableMapperImpl();
                target.setVariable(name, value);
            }
            return value;
        }

        public ValueExpression setVariable(String name, ValueExpression value) {
            throw new IllegalStateException();
        }

        VariableMapper getVariableMapper() {
            return target;
        }
    }
}
