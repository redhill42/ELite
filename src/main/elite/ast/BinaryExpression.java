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
 * Represents an expression that has a binary operator.
 */
@Data({"nodeType", "left", "right"})
public class BinaryExpression extends Expression
{
    protected Expression left;
    protected Expression right;

    protected BinaryExpression(ExpressionType nodeType, Expression left, Expression right) {
        super(nodeType);
        this.left = left;
        this.right = right;
    }

    /**
     * Returns the left operand of the binary expression.
     * @return the left operand of the binary expression.
     */
    public Expression getLeft() {
        return left;
    }

    /**
     * Returns the right operand of the binary expression.
     * @return the right operand of the binary expression.
     */
    public Expression getRight() {
        return right;
    }

    /**
     * Convert this expression into an internal node representation.
     * @return the internal node representation.
     */
    protected ELNode toInternal(int pos) {
        ELNode lhs = left.getNode(pos);
        ELNode rhs = right.getNode(pos);

        switch (nodeType) {
        case ADD:
            return new ELNode.ADD(pos, lhs, rhs);
        case AND:
            return new ELNode.AND(pos, lhs, rhs);
        case ASSIGN:
            return new ELNode.ASSIGN(pos, lhs, rhs);
        case BITWISE_AND:
            return new ELNode.BITAND(pos, lhs, rhs);
        case BITWISE_OR:
            return new ELNode.BITOR(pos, lhs, rhs);
        case CAT:
            return new ELNode.CAT(pos, lhs, rhs);
        case COALESCE:
            return new ELNode.COALESCE(pos, lhs, rhs);
        case DIVIDE:
            return new ELNode.DIV(pos, lhs, rhs);
        case EQUAL:
            return new ELNode.EQ(pos, lhs, rhs);
        case GREATER_THAN:
            return new ELNode.GT(pos, lhs, rhs);
        case GREATER_THAN_OR_EQUAL:
            return new ELNode.GE(pos, lhs, rhs);
        case IN:
            return new ELNode.IN(pos, lhs, rhs, false);
        case INSTANCEOF:
            return new ELNode.INSTANCEOF(pos, lhs, ((ELNode.STRINGVAL)rhs).value, false);
        case LEFT_SHIFT:
            return new ELNode.SHL(pos, lhs, rhs);
        case LESS_THAN:
            return new ELNode.LT(pos, lhs, rhs);
        case LESS_THAN_OR_EQUAL:
            return new ELNode.LE(pos, lhs, rhs);
        case MULTIPLY:
            return new ELNode.MUL(pos, lhs, rhs);
        case NOT_EQUAL:
            return new ELNode.NE(pos, lhs, rhs);
        case OR:
            return new ELNode.OR(pos, lhs, rhs);
        case POWER:
            return new ELNode.POW(pos, lhs, rhs);
        case REMAINDER:
            return new ELNode.REM(pos, lhs, rhs);
        case RIGHT_SHIFT:
            return new ELNode.SHR(pos, lhs, rhs);
        case SAFEREF:
            return new ELNode.SAFEREF(pos, lhs, rhs);
        case SUBTRACT:
            return new ELNode.SUB(pos, lhs, rhs);
        case THEN:
            return new ELNode.THEN(pos, lhs, rhs);
        case UNSIGNED_RIGHT_SHIFT:
            return new ELNode.USHR(pos, lhs, rhs);
        case XOR:
            return new ELNode.XOR(pos, lhs, rhs);
        default:
            throw new IllegalArgumentException();
        }
    }

    /**
     * Returns the string representation of this expression.
     * @return the string representation
     */
    public String toString() {
        StringBuilder buf = new StringBuilder();
        if (left.getPrecedence() < getPrecedence()) {
            buf.append('(').append(left).append(')');
        } else {
            buf.append(left);
        }

        if (Character.isLetter(nodeType.op().charAt(0))) {
            buf.append(' ').append(nodeType.op()).append(' ');
        } else {
            buf.append(nodeType.op());
        }

        if (right.getPrecedence() < getPrecedence()) {
            buf.append('(').append(right).append(')');
        } else {
            buf.append(right);
        }

        return buf.toString();
    }
}
