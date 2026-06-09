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

@Data({"left", "right"})
public class MemberExpression extends Expression
{
    protected Expression left;
    protected Expression right;

    protected MemberExpression(Expression left, Expression field) {
        super(ExpressionType.MEMBER);
        this.left = left;
        this.right = field;
    }

    public Expression getLeft() {
        return left;
    }

    public Expression getRight() {
        return right;
    }

    protected ELNode toInternal(int pos) {
        return new ELNode.ACCESS(pos, left.getNode(pos), right.getNode(pos));
    }

    public String toString() {
        if (right instanceof ConstantExpression) {
            Object key = ((ConstantExpression) right).getValue();
            if (key instanceof String) {
                return left.toString() + "." + key;
            }
        }
        return left.toString() + "[" + right.toString() + "]";
    }
}
