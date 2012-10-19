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

import javax.el.MethodExpression;
import javax.el.MethodInfo;
import javax.el.ELContext;
import javax.el.FunctionMapper;
import javax.el.VariableMapper;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.IOException;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Parser;
import org.operamasks.util.Utils;

public class MethodExpressionImpl extends MethodExpression
{
    private String         expression;
    private ELNode         node;
    private Class<?>       expectedType;
    private Class<?>[]     paramTypes;
    private FunctionMapper fnMapper;
    private VariableMapper varMapper;
    
    public MethodExpressionImpl(String         expression,
                                ELNode         node,
                                Class<?>       expectedType,
                                Class<?>[]     paramTypes,
                                FunctionMapper fnMapper,
                                VariableMapper varMapper)
    {
        this.expression   = expression;
        this.node         = node;
        this.expectedType = expectedType;
        this.paramTypes   = paramTypes;
        this.fnMapper     = fnMapper;
        this.varMapper    = varMapper;
    }

    public MethodInfo getMethodInfo(ELContext elctx) {
        try {
            StackTrace.addFrame(elctx, "__expression__", null, 0);
            EvaluationContext ctx = new EvaluationContext(elctx, fnMapper, varMapper);
            MethodInfo info = node.getMethodInfo(ctx);
            return new MethodInfo(info.getName(), info.getReturnType(), this.paramTypes);
        } catch (EvaluationException ex) {
            throw wrap(elctx, ex);
        } catch (RuntimeException ex) {
            throw wrap(elctx, ex);
        } finally {
            StackTrace.removeFrame(elctx);
        }
    }

    public Object invoke(ELContext elctx, Object[] args) {
        if (args == null) {
            args = new Object[0];
        }

        try {
            StackTrace.addFrame(elctx, "__expression__", null, 0);

            EvaluationContext ctx = new EvaluationContext(elctx, fnMapper, varMapper);
            Object result = node.invokeMethod(ctx, args);

            if (result == null || expectedType == Void.TYPE) {
                return null;
            } else if (expectedType == null || expectedType == Object.class) {
                return result;
            } else {
                return TypeCoercion.coerce(elctx, result, expectedType);
            }
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

    public String getExpressionString() {
        return expression;
    }

    public boolean equals(Object obj) {
        if (obj instanceof MethodExpressionImpl) {
            return expression.equals(((MethodExpressionImpl)obj).getExpressionString());
        } else {
            return false;
        }
    }

    public int hashCode() {
        return expression.hashCode();
    }

    public String toString() {
        return expression;
    }
    
    public boolean isLiteralText() {
        return false;
    }

    private void writeObject(ObjectOutputStream out)
        throws IOException
    {
        out.writeUTF(expression);
        out.writeUTF(expectedType == null ? "" : expectedType.getName());
        if (paramTypes == null) {
            out.writeInt(-1);
        } else {
            out.writeInt(paramTypes.length);
            for (Class type : paramTypes) {
                out.writeUTF(type.getName());
            }
        }
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
        int len = in.readInt();
        if (len >= 0) {
            paramTypes = new Class[len];
            for (int i = 0; i < len; i++) {
                type = in.readUTF();
                paramTypes[i] = Utils.findClass(type);
            }
        }
        fnMapper = (FunctionMapper)in.readObject();
        varMapper = (VariableMapper)in.readObject();
    }
}
