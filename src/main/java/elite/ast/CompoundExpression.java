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

@Data({"elements"})
public class CompoundExpression extends Expression
{
    protected Expression[] elements;

    protected CompoundExpression(Expression[] expressions) {
        super(ExpressionType.COMPOUND);
        this.elements = expressions;
    }

    public Expression[] getElements() {
        return elements.clone();
    }

    protected ELNode toInternal(int pos) {
        ELNode[] exps = new ELNode[elements.length];
        for (int i = 0; i < exps.length; i++)
            exps[i] = elements[i].getNode(pos);
        return new ELNode.COMPOUND(pos, exps);
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();
        for (Expression e : elements) {
            buf.append(e).append(";\n");
        }
        return buf.toString();
    }
}
