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

package elite.ast;

import org.operamasks.el.parser.ELNode;
import elite.lang.annotation.Data;

/**
 * Represents an infix expression.
 */
@Data({"nodeType", "name", "precedence", "left", "right"})
public class InfixExpression extends BinaryExpression
{
    protected String name;
    protected int precedence;

    protected InfixExpression(String name, int precedence, Expression left, Expression right) {
        super(ExpressionType.INFIX, left, right);
        this.name = name;
        this.precedence = precedence;
    }

    /**
     * Returns the operator name.
     * @return the operator name.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the operator precedence.
     * @return the operator precedence.
     */
    public int getPrecedence() {
        return Math.abs(precedence);
    }

    /**
     * Convert this expression into an internal node representation.
     * @return the internal node representation.
     */
    protected ELNode toInternal(int pos) {
        return new ELNode.INFIX(pos, name, precedence, left.getNode(pos), right.getNode(pos));
    }

    /**
     * Returns the string representation of this expression.
     * @return the string representation
     */
    public String toString() {
        StringBuilder buf = new StringBuilder();
        if (left.getPrecedence() < getPrecedence()) { // FIXME
            buf.append('(').append(left).append(')');
        } else {
            buf.append(left);
        }

        buf.append(' ').append(name).append(' ');

        if (right.getPrecedence() < getPrecedence()) {
            buf.append('(').append(right).append(')');
        } else {
            buf.append(right);
        }

        return buf.toString();
    }
}
