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

import javax.el.ValueExpression;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;
import java.io.Serializable;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.IOException;
import org.elite.util.Utils;

public class LiteralValueExpression extends ValueExpression
    implements Serializable
{
    private static final long serialVersionUID = 5835790517485442464L;

    private Object value;
    private Class<?> expectedType;

    public LiteralValueExpression(Object value, Class<?> expectedType) {
        this.value = value;
        this.expectedType = expectedType;
    }

    public Object getValue(ELContext context) {
        if (expectedType == null || expectedType == Object.class) {
            return value;
        } else {
            return TypeCoercion.coerce(context, value, expectedType);
        }
    }

    public void setValue(ELContext context, Object value) {
        throw new PropertyNotWritableException();
    }

    public boolean isReadOnly(ELContext context) {
        return true;
    }

    public Class<?> getType(ELContext context) {
        return (value == null) ? null : value.getClass();
    }

    public Class<?> getExpectedType() {
        return expectedType;
    }

    public String getExpressionString() {
        return (value == null) ? null : value.toString();
    }

    public boolean equals(Object obj) {
        if (obj instanceof LiteralValueExpression) {
            LiteralValueExpression other = (LiteralValueExpression)obj;
            if (value == null) {
                return other.value == null;
            } else {
                return value.equals(other.value);
            }
        }
        return false;
    }

    public int hashCode() {
        return (value == null) ? 0 : value.hashCode();
    }

    public boolean isLiteralText() {
        return true;
    }

    private void writeObject(ObjectOutputStream out)
        throws IOException
    {
        out.writeObject(value);
        out.writeUTF(expectedType == null ? "" : expectedType.getName());
    }

    private void readObject(ObjectInputStream in)
        throws IOException, ClassNotFoundException
    {
        value = in.readObject();
        String type = in.readUTF();
        if (type.length() == 0) {
            expectedType = null;
        } else {
            expectedType = Utils.findClass(type);
        }
    }
}
