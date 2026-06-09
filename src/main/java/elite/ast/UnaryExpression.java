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
        if (nodeType == ExpressionType.PARENTHESIS || operand.getPrecedence() < getPrecedence()) {
            buf.append('(').append(operand).append(')');
        } else {
            buf.append(operand);
        }

        switch (nodeType) {
        case NOT:            buf.insert(0, "!"); break;
        case BITWISE_NOT:    buf.insert(0, ":!:"); break;
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
