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

package org.elite.eval;

import javax.el.MethodExpression;
import javax.el.MethodInfo;
import javax.el.ELContext;
import javax.el.FunctionMapper;
import javax.el.VariableMapper;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.IOException;
import org.elite.parser.ELNode;
import org.elite.parser.Parser;
import org.elite.util.Utils;

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

    public boolean isParmetersProvided() {
        return node instanceof ELNode.APPLY;
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
