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

import org.elite.parser.ELNode;
import elite.lang.annotation.Data;

/**
 * Represents an expression that applies a function expression to a list of
 * argument expressions.
 */
@Data({"left", "args"})
public class ApplyExpression extends Expression
{
    protected Expression left;
    protected Expression[] args;

    protected ApplyExpression(Expression left, Expression[] args) {
        super(ExpressionType.APPLY);
        this.left = left;
        this.args = args;
    }

    public Expression getLeft() {
        return left;
    }

    public Expression[] getArgs() {
        return args.clone();
    }

    protected ELNode toInternal(int pos) {
        ELNode[] args = new ELNode[this.args.length];
        for (int i = 0; i < args.length; i++)
            args[i] = this.args[i].getNode(pos);
        return new ELNode.APPLY(pos, left.getNode(pos), args, null);
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(left);
        buf.append("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) buf.append(",");
            buf.append(args[i]);
        }
        buf.append(")");
        return buf.toString();
    }
}
