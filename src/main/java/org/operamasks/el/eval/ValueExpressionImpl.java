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

package org.operamasks.el.eval;

import javax.el.ELContext;
import javax.el.ValueExpression;
import javax.el.FunctionMapper;
import javax.el.ValueReference;
import javax.el.VariableMapper;
import java.io.Serializable;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.IOException;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Parser;
import org.operamasks.util.Utils;

public class ValueExpressionImpl extends ValueExpression
    implements Serializable
{
    private static final long serialVersionUID = -9053167105172092880L;

    private String         expression;
    private ELNode         node;
    private Class<?>       expectedType;
    private FunctionMapper fnMapper;
    private VariableMapper varMapper;

    public ValueExpressionImpl(String         expression,
                               ELNode         node,
                               Class<?>       expectedType,
                               FunctionMapper fnMapper,
                               VariableMapper varMapper)
    {
        this.expression   = expression;
        this.node         = node;
        this.expectedType = expectedType;
        this.fnMapper     = fnMapper;
        this.varMapper    = varMapper;
    }

    public Object getValue(ELContext elctx) {
        try {
            StackTrace.addFrame(elctx, "__expression__", null, 0);
            EvaluationContext ctx = new EvaluationContext(elctx, fnMapper, varMapper);
            Object value = node.getValue(ctx);
            if (expectedType == null || expectedType == Object.class) {
                return value;
            } else {
                return TypeCoercion.coerce(elctx, value, expectedType);
            }
        } catch (EvaluationException ex) {
            throw wrap(elctx, ex);
        } catch (RuntimeException ex) {
            throw wrap(elctx, ex);
        } finally {
            StackTrace.removeFrame(elctx);
        }
    }

    public ValueReference getValueReference(ELContext elctx) {
        try {
            StackTrace.addFrame(elctx, "__expression__", null, 0);
            EvaluationContext ctx = new EvaluationContext(elctx, fnMapper, varMapper);
            return node.getValueReference(ctx);
        } catch (EvaluationException ex) {
            throw wrap(elctx, ex);
        } catch (RuntimeException ex) {
            throw wrap(elctx, ex);
        } finally {
            StackTrace.removeFrame(elctx);
        }
    }

    public void setValue(ELContext elctx, Object value) {
        try {
            StackTrace.addFrame(elctx, "__expression__", null, 0);
            EvaluationContext ctx = new EvaluationContext(elctx, fnMapper, varMapper);
            node.setValue(ctx, value);
        } catch (EvaluationException ex) {
            throw wrap(elctx, ex);
        } catch (RuntimeException ex) {
            throw wrap(elctx, ex);
        } finally {
            StackTrace.removeFrame(elctx);
        }
    }

    public boolean isReadOnly(ELContext elctx) {
        try {
            StackTrace.addFrame(elctx, "__expression__", null, 0);
            EvaluationContext ctx = new EvaluationContext(elctx, fnMapper, varMapper);
            return node.isReadOnly(ctx);
        } catch (EvaluationException ex) {
            throw wrap(elctx, ex);
        } catch (RuntimeException ex) {
            throw wrap(elctx, ex);
        } finally {
            StackTrace.removeFrame(elctx);
        }
    }

    public Class<?> getType(ELContext elctx) {
        try {
            StackTrace.addFrame(elctx, "__expression__", null, 0);
            EvaluationContext ctx = new EvaluationContext(elctx, fnMapper, varMapper);
            return node.getType(ctx);
        } catch (EvaluationException ex) {
            throw wrap(elctx, ex);
        } catch (RuntimeException ex) {
            throw wrap(elctx, ex);
        } finally {
            StackTrace.removeFrame(elctx);
        }
    }

    private EvaluationException wrap(ELContext elctx, EvaluationException ex) {
        String message = ex.getRawMessage() + "\n>>> " + expression;
        EvaluationException ex2 = new EvaluationException(elctx, message);
        ex2.initCause(ex.getCause());
        ex2.setStackTrace(ex.getStackTrace());
        return ex2;
    }

    private EvaluationException wrap(ELContext elctx, RuntimeException ex) {
        String message = ex.getMessage() + "\n>>>" + expression;
        return new EvaluationException(elctx, message, ex);
    }

    public Class<?> getExpectedType() {
        return expectedType;
    }

    public String getExpressionString() {
        return expression;
    }

    public boolean equals(Object obj) {
        if (obj instanceof ValueExpressionImpl) {
            return expression.equals(((ValueExpressionImpl)obj).getExpressionString());
        } else {
            return false;
        }
    }

    public int hashCode() {
        return expression.hashCode();
    }

    public boolean isLiteralText() {
        return node instanceof ELNode.LITERAL;
    }

    private void writeObject(ObjectOutputStream out)
        throws IOException
    {
        out.writeUTF(expression);
        out.writeUTF(expectedType == null ? "" : expectedType.getName());
        out.writeObject(fnMapper);
        out.writeObject(varMapper);
    }

    private void readObject(ObjectInputStream in)
        throws IOException, ClassNotFoundException
    {
        expression = in.readUTF();
        node = Parser.parse(expression);
        String type = in.readUTF();
        expectedType = (type.length() == 0) ? null : Utils.findClass(type);
        fnMapper = (FunctionMapper)in.readObject();
        varMapper = (VariableMapper)in.readObject();
    }
}
