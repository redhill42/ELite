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
