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

import elite.lang.annotation.Data;
import org.elite.parser.ELNode;

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
