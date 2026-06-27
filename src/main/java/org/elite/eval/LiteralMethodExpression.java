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
import javax.el.ELContext;
import javax.el.MethodInfo;
import javax.el.ELException;
import java.io.Serializable;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.IOException;
import org.elite.util.Utils;

import static org.elite.resources.Resources.*;

public class LiteralMethodExpression extends MethodExpression
    implements Serializable
{
    private static final long serialVersionUID = 4394283970441392163L;

    private String expr;
    private Class<?> expectedType;
    private Class<?>[] paramTypes;
    private transient Object result;

    public LiteralMethodExpression(String expr, Class<?> expectedType, Class<?>[] paramTypes) {
        this.expr = expr;
        this.expectedType = expectedType;
        this.paramTypes = paramTypes;
        this.result = getResult(expr, expectedType);
    }

    public MethodInfo getMethodInfo(ELContext context) {
        return new MethodInfo(expr, expectedType, paramTypes);
    }

    public Object invoke(ELContext context, Object[] args) {
        if (result == null)
            result = getResult(expr, expectedType);
        return result;
    }

    private static Object getResult(String expr, Class<?> expectedType) {
        if (expectedType == Void.TYPE) {
            throw new ELException(_T(JSPRT_COERCE_ERROR, "java.lang.String", "void"));
        } else if (expectedType == null || expectedType == Object.class) {
            return expr;
        } else if (expectedType == String.class) {
            return expr;
        } else {
            return TypeCoercion.coerce(expr, expectedType);
        }
    }

    public String getExpressionString() {
        return expr;
    }

    public boolean equals(Object obj) {
        if (obj instanceof LiteralMethodExpression) {
            return expr.equals(((LiteralMethodExpression)obj).getExpressionString());
        } else {
            return false;
        }
    }

    public int hashCode() {
        return expr.hashCode();
    }

    public boolean isLiteralText() {
        return true;
    }

    private void writeObject(ObjectOutputStream out)
        throws IOException
    {
        out.writeUTF(expr);
        out.writeUTF(expectedType == null ? "" : expectedType.getName());
        if (paramTypes == null) {
            out.writeInt(-1);
        } else {
            out.writeInt(paramTypes.length);
            for (Class type : paramTypes) {
                out.writeUTF(type.getName());
            }
        }
    }

    private void readObject(ObjectInputStream in)
        throws IOException, ClassNotFoundException
    {
        expr = in.readUTF();
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
    }
}
