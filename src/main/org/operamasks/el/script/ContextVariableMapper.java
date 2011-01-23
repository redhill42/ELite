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

package org.operamasks.el.script;

import java.io.PrintWriter;
import java.io.Writer;
import java.io.OutputStream;
import java.io.Reader;
import java.io.InputStream;
import java.io.InputStreamReader;
import javax.el.ValueExpression;
import javax.el.ELContext;
import javax.script.ScriptContext;

import org.operamasks.el.eval.VariableMapperImpl;
import org.operamasks.el.eval.EvaluationException;
import org.operamasks.el.eval.closure.LiteralClosure;
import org.operamasks.el.eval.closure.ValueChangeListener;
import elite.lang.Closure;

class ContextVariableMapper extends VariableMapperImpl
{
    final ScriptContext context;

    ContextVariableMapper(ScriptContext context) {
        this.context = context;

        super.setVariable("stdin", new StdIn());
        super.setVariable("stdout", new StdOut());
        super.setVariable("stderr", new StdErr());
    }

    public ValueExpression resolveVariable(String name) {
        ValueExpression expression = super.resolveVariable(name);
        if (expression != null) {
            return expression;
        }

        synchronized (context) {
            int scope = context.getAttributesScope(name);
            if (scope != -1) {
                Object value = context.getAttribute(name, scope);
                if (value instanceof ValueExpression) {
                    return (ValueExpression)value;
                } else {
                    expression = new ContextValueExpression(name, value);
                    super.setVariable(name, expression);
                    return expression;
                }
            } else {
                return null;
            }
        }
    }

    public ValueExpression setVariable(final String name, ValueExpression expression) {
        ValueExpression retval = super.setVariable(name, expression);

        if ((expression instanceof Closure) && !(expression instanceof ContextValueExpression)) {
            Closure closure = (Closure)expression;

            // don't expose non-public top level variables to script context
            if (!closure.isPublic()) {
                return retval;
            }

            // register ValueChangeListener
            closure.setValueChangeListener(
                new ValueChangeListener() {
                    public void valueChanged(Object oldValue, Object newValue) {
                        setContextValue(name, newValue);
                    }
                });
        }

        // Set variable value to the script context
        synchronized (context) {
            int scope = context.getAttributesScope(name);
            if (scope == -1)
                scope = ScriptContext.ENGINE_SCOPE;
            if (expression == null) {
                context.removeAttribute(name, scope);
            } else if (expression instanceof LiteralClosure) {
                context.setAttribute(name, expression.getValue(null), scope);
            } else {
                context.setAttribute(name, expression, scope);
            }
        }

        return retval;
    }

    protected Object getContextValue(String name) {
        synchronized (context) {
            int scope = context.getAttributesScope(name);
            if (scope != -1) {
                return context.getAttribute(name, scope);
            }
        }
        return null;
    }

    protected void setContextValue(String name, Object value) {
        synchronized (context) {
            int scope = context.getAttributesScope(name);
            if (scope == -1)
                scope = ScriptContext.ENGINE_SCOPE;
            context.setAttribute(name, value, scope);
        }
    }

    private class ContextValueExpression extends LiteralClosure {
        final String name;

        ContextValueExpression(String name, Object value) {
            super(value);
            this.name = name;
        }

        @Override
        public Object getValue(ELContext elctx) {
            Object value = getContextValue(name);
            super.setValue(elctx, value);
            return value;
        }

        @Override
        public void setValue(ELContext elctx, Object value) {
            super.setValue(elctx, value);
            setContextValue(name, value);
        }
    }

    private class StdIn extends LiteralClosure {
        StdIn() { super(initReader(null, context.getReader())); }
        public void setValue(ELContext elctx, Object value) {
            Reader reader = initReader(elctx, value);
            super.setValue(elctx, reader);
            context.setReader(reader);
        }
    }

    private class StdOut extends LiteralClosure {
        StdOut() { super(initWriter(null, context.getWriter())); }
        public void setValue(ELContext elctx, Object value) {
            PrintWriter writer = initWriter(elctx, value);
            super.setValue(elctx, writer);
            context.setWriter(writer);
        }
    }

    private class StdErr extends LiteralClosure {
        StdErr() { super(initWriter(null, context.getErrorWriter())); }
        public void setValue(ELContext elctx, Object value) {
            PrintWriter writer = initWriter(elctx, value);
            super.setValue(elctx, writer);
            context.setErrorWriter(writer);
        }
    }

    static Reader initReader(ELContext elctx, Object value) {
        if (value instanceof Reader) {
            return (Reader)value;
        } else if (value instanceof InputStream) {
            return new InputStreamReader((InputStream)value);
        } else {
            throw new EvaluationException(elctx, "Reader expected");
        }
    }

    static PrintWriter initWriter(ELContext elctx, Object value) {
        if (value instanceof PrintWriter) {
            return (PrintWriter)value;
        } else if (value instanceof Writer) {
            return new PrintWriter((Writer)value, true);
        } else if (value instanceof OutputStream) {
            return new PrintWriter((OutputStream)value, true);
        } else {
            throw new EvaluationException(elctx, "Writer expected");
        }
    }
}
