/*
 * $Id: ConstantExpression.java,v 1.4 2009/05/13 05:39:00 danielyuan Exp $
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

package elite.ast;

import org.operamasks.el.parser.ELNode;
import org.operamasks.el.eval.TypeCoercion;
import elite.lang.annotation.Data;

/**
 * Represents an expression that has a constant value.
 */
@Data({"value"})
public class ConstantExpression extends Expression
{
    protected ConstantExpression(ELNode node) {
        super(ExpressionType.CONSTANT, node);
    }

    /**
     * Returns the constant value.
     * @return the constant value.
     */
    public Object getValue() {
        return node.getValue(null);
    }

    /**
     * Convert this expression into an internal node representation.
     * @return the internal node representation.
     */
    protected ELNode toInternal(int pos) {
        return node;
    }

    /**
     * Returns the string representation of this expression.
     * @return the string representation
     */
    public String toString() {
        Object value = getValue();
        if (value == null) {
            return "null";
        } else if (value instanceof String) {
            return TypeCoercion.escape((String)value);
        } else {
            return TypeCoercion.coerceToString(value);
        }
    }
}
