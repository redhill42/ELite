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

@Data({"test", "left", "right"})
public class ConditionalExpression extends Expression
{
    protected Expression test;
    protected Expression left;
    protected Expression right;

    protected ConditionalExpression(Expression test, Expression left, Expression right) {
        super(ExpressionType.CONDITIONAL);
        this.test = test;
        this.left = left;
        this.right = right;
    }

    public Expression getTest() {
        return test;
    }

    public Expression getLeft() {
        return left;
    }

    public Expression getRight() {
        return right;
    }

    protected ELNode toInternal(int pos) {
        return new ELNode.COND(pos, test.getNode(pos), left.getNode(pos), right.getNode(pos));
    }

    public String toString() {
        return test.toString() + " ? " + left.toString() + " : " + right.toString();
    }
}
