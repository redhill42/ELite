/*
 * $Id: LiteralValueExpression.java,v 1.3 2009/05/04 08:35:55 jackyzhang Exp $
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

import javax.el.ValueExpression;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;
import java.io.Serializable;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.IOException;
import org.operamasks.util.Utils;

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
