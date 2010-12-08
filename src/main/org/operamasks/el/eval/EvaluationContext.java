/*
 * $Id: EvaluationContext.java,v 1.6 2009/05/10 16:54:55 danielyuan Exp $
 *
 * Copyright (C) 2006 Operamasks Community.
 * Copyright (C) 2000-2006 Apusic Systems, Inc.
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

package org.operamasks.el.eval;

import javax.el.ELContext;
import javax.el.FunctionMapper;
import javax.el.VariableMapper;
import javax.el.ValueExpression;
import javax.el.MethodNotFoundException;
import elite.xml.Namespace;
import elite.lang.Closure;
import org.operamasks.el.eval.closure.ClassDefinition;
import org.operamasks.el.eval.closure.AbstractClosure;
import org.operamasks.el.eval.closure.DataClass;
import static javax.xml.XMLConstants.*;

/**
 * The evaluation context that associated with a expression.
 */
public class EvaluationContext extends AbstractClosure
    implements PropertyDelegate
{
    private ELContext      elctx;
    private FunctionMapper fnm;
    private StackTrace     trace;

    public EvaluationContext(ELContext elctx) {
        this(elctx, elctx.getFunctionMapper(), elctx.getVariableMapper());
    }

    public EvaluationContext(ELContext elctx, FunctionMapper fm, VariableMapper vm) {
        this.elctx = elctx;
        this.fnm = fm;

        // initialize global context
        if (vm != null) {
            tail = new VMResolver(vm, null);
        }

        // declare wellknown namespaces
        declarePrefix(XML_NS_PREFIX, XML_NS_URI);
        declarePrefix(XMLNS_ATTRIBUTE, XMLNS_ATTRIBUTE_NS_URI);
    }

    public ELContext getELContext() {
        return elctx;
    }

    public void setELContext(ELContext elctx) {
        if (elctx != this.elctx) {
            this.elctx = elctx;
            this.trace = null;
        }
    }

    public FunctionMapper getFunctionMapper() {
        return fnm != null ? fnm : elctx.getFunctionMapper();
    }

    public Frame getFrame() {
        if (trace == null)
            trace = StackTrace.getInstance(elctx);
        return trace.frame;
    }

    /**
     * The Resolver represents a variable mapper or a local variable.
     */
    static abstract class Resolver {
        Resolver next;  // the next element in the list

        Resolver(Resolver next) {
            this.next = next;
        }

        /**
         * Resolver the given variable name.
         */
        abstract ValueExpression resolve(String name);

        /**
         * Set the variable. Returns true if a previous variable found.
         */
        abstract boolean set(String name, ValueExpression value);
    }

    /**
     * The VariableMapper resolver.
     */
    static class VMResolver extends Resolver {
        VariableMapper vm;

        VMResolver(VariableMapper vm, Resolver next) {
            super(next);
            this.vm = vm;
        }

        ValueExpression resolve(String name) {
            return vm.resolveVariable(name);
        }

        boolean set(String name, ValueExpression value) {
            vm.setVariable(name, value);
            return true;
        }
    }

    /**
     * A variable binding tuple that contains the variable name
     * and variable value.
     */
    static class Variable extends Resolver {
        String name;
        ValueExpression value;

        Variable(String name, ValueExpression value, Resolver next) {
            super(next);
            this.name = name;
            this.value = value;
        }

        ValueExpression resolve(String name) {
            if (name.equals(this.name) && value != null)
                return value;
            return null;
        }

        boolean set(String name, ValueExpression value) {
            if (name.equals(this.name)) {
                this.value = value;
                return true;
            }
            return false;
        }
    }

    /*
     * Local variable binding list. Linked together from innermost context
     * to outermost context;
     */
    private Resolver head, tail;

    private EvaluationContext() {}

    public EvaluationContext pushContext() {
        EvaluationContext newctx = new EvaluationContext();
        newctx.elctx = this.elctx;
        newctx.fnm   = this.fnm;
        newctx.trace = this.trace;
        newctx.head  = newctx.tail = this.tail;
        return newctx;
    }

    public EvaluationContext pushContext(VariableMapper env) {
        EvaluationContext newctx = new EvaluationContext();
        newctx.elctx = this.elctx;
        newctx.fnm   = this.fnm;
        newctx.trace = this.trace;
        newctx.head  = this.tail;
        newctx.tail  = new VMResolver(env, this.tail);
        return newctx;
    }

    public void setVariable(String name, ValueExpression value) {
        if (name.equals("xmlns") || name.startsWith("xmlns:")) {
            // set namespace variable
            String prefix = name.equals("xmlns") ? "" : name.substring(6);
            Namespace namespace = new Namespace(prefix, null);
            namespace.setValue(elctx, value.getValue(elctx));
            internalSetVariable(name, namespace);
        } else {
            internalSetVariable(name, value);
        }
    }

    private void internalSetVariable(String name, ValueExpression value) {
        // see if variable already exists in current context
        for (Resolver r = tail; r != head; r = r.next) {
            if (r.set(name, value)) {
                return;
            }
        }

        // add new variable to the list
        if (value != null) {
            tail = new Variable(name, value, tail);
        }
    }

    public ValueExpression resolveVariable(String name) {
        ValueExpression value;

        // find variable in all contexts, from innermost to outermost
        for (Resolver r = tail; r != null; r = r.next) {
            if ((value = r.resolve(name)) != null) {
                return value;
            }
        }

        if (name.equals("environ")) {
            return this;
        }

        // variable not found
        return null;
    }

    public ValueExpression resolveLocalVariable(String name) {
        ValueExpression value;

        // find variable in current context
        for (Resolver r = tail; r != head; r = r.next) {
            if ((value = r.resolve(name)) != null) {
                return value;
            }
        }

        // variable not found
        return null;
    }

    public Object resolveClass(String name) {
        Object cls;

        // find class in all context
        for (Resolver r = tail; r != null; r = r.next) {
            cls = r.resolve(name);
            if (cls instanceof ClassDefinition || cls instanceof DataClass) {
                return cls;
            }
        }

        // class not found
        return null;
    }

    public void declarePrefix(String prefix, String uri) {
        String name = prefix.length() == 0 ? "xmlns" : ("xmlns:"+prefix);
        internalSetVariable(name, new Namespace(prefix, uri));
    }

    public String getURI(String prefix) {
        String name = prefix.length() == 0 ? "xmlns" : ("xmlns:"+prefix);
        ValueExpression ve = resolveVariable(name);
        return (ve instanceof Namespace) ? ((Namespace)ve).getUri() : null;
    }

    // PropertyDelegate implementation

    public Object getValue(ELContext elctx, Object property) {
        if (property instanceof String) {
            ValueExpression expr = resolveVariable((String)property);
            if (expr != null) {
                Object value = expr.getValue(elctx);
                elctx.setPropertyResolved(true);
                return value;
            }
        }
        return null;
    }

    public Class<?> getType(ELContext elctx, Object property) {
        if (property instanceof String) {
            ValueExpression expr = resolveVariable((String)property);
            if (expr != null) {
                Class<?> type = expr.getType(elctx);
                elctx.setPropertyResolved(true);
                return type;
            }
        }
        return null;
    }

    public void setValue(ELContext elctx, Object property, Object value) {
        if (property instanceof String) {
            ValueExpression expr = resolveVariable((String)property);
            if (expr != null) {
                expr.setValue(elctx, value);
                elctx.setPropertyResolved(true);
            }
        }
    }

    public boolean isReadOnly(ELContext elctx, Object property) {
        if (property instanceof String) {
            ValueExpression expr = resolveVariable((String)property);
            if (expr != null) {
                boolean readonly = expr.isReadOnly(elctx);
                elctx.setPropertyResolved(true);
                return readonly;
            }
        }
        return false;
    }

    // AbstractClosure implementation

    public Object getValue(ELContext elctx) {
        return this;
    }

    public Class<?> getType(ELContext context) {
        return EvaluationContext.class;
    }

    public Class<?> getExpectedType() {
        return EvaluationContext.class;
    }

    public Object invoke(ELContext elctx, Closure[] args) {
        throw new MethodNotFoundException();
    }
}
