/*
 * $Id: UnaryExpression.java,v 1.4 2009/05/13 05:39:00 danielyuan Exp $
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
import elite.lang.annotation.Data;

@Data({"nodeType", "operand"})
public class UnaryExpression extends Expression
{
    protected Expression operand;

    protected UnaryExpression(ExpressionType nodeType, Expression operand) {
        super(nodeType);
        this.operand = operand;
    }

    public Expression getOperand() {
        return operand;
    }

    protected ELNode toInternal(int pos) {
        ELNode rhs = this.operand.getNode(pos);

        switch (nodeType) {
        case NOT:            return new ELNode.NOT(pos, rhs);
        case BITWISE_NOT:    return new ELNode.BITNOT(pos, rhs);
        case NEGATE:         return new ELNode.NEG(pos, rhs);
        case UNARY_PLUS:     return new ELNode.POS(pos, rhs);
        case PARENTHESIS:    return new ELNode.EXPR(pos, rhs);
        case POST_INCREMENT: return new ELNode.INC(pos, rhs, false);
        case POST_DECREMENT: return new ELNode.DEC(pos, rhs, false);
        case PRE_INCREMENT:  return new ELNode.INC(pos, rhs, true);
        case PRE_DECREMENT:  return new ELNode.DEC(pos, rhs, true);
        case EMPTY:          return new ELNode.EMPTY(pos, rhs);

        default:
            throw new IllegalArgumentException();
        }
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();
        if (nodeType == ExpressionType.PARENTHESIS || operand.nodeType.prec() < nodeType.prec()) {
            buf.append("(").append(operand).append(")");
        } else {
            buf.append(operand);
        }

        switch (nodeType) {
        case NOT:            buf.insert(0, "!"); break;
        case BITWISE_NOT:    buf.insert(0, "^!"); break;
        case NEGATE:         buf.insert(0, "-"); break;
        case UNARY_PLUS:     buf.insert(0, "+"); break;
        case PARENTHESIS:    break;
        case POST_INCREMENT: buf.append("++"); break;
        case POST_DECREMENT: buf.append("--"); break;
        case PRE_INCREMENT:  buf.insert(0, "++"); break;
        case PRE_DECREMENT:  buf.insert(0, "--"); break;
        case EMPTY:          buf.insert(0, "empty "); break;

        default:
            throw new IllegalArgumentException();
        }

        return buf.toString();
    }
}
