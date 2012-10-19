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

import elite.lang.annotation.Data;
import org.operamasks.el.parser.ELNode;

/**
 * Represents a prefix expression.
 */
@Data({"nodeType", "name", "precedence", "operand"})
public class PrefixExpression extends UnaryExpression
{
    protected String name;
    protected int precedence;

    protected PrefixExpression(String name, int precedence, Expression operand) {
        super(ExpressionType.PREFIX, operand);
        this.name = name;
        this.precedence = precedence;
    }

    public String getName() {
        return name;
    }

    public int getPrecedence() {
        return Math.abs(precedence);
    }

    protected ELNode toInternal(int pos) {
        return new ELNode.PREFIX(pos, name, precedence, operand.getNode(pos));
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(name).append(' ');
        if (operand.getPrecedence() < getPrecedence()) {
            buf.append('(').append(operand).append(')');
        } else {
            buf.append(operand);
        }
        return buf.toString();
    }
}
