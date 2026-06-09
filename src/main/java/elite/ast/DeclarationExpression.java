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

@Data({"name", "expression"})
public class DeclarationExpression extends Expression
{
    protected String name;
    protected Expression expression;

    protected DeclarationExpression(String name, Expression expression) {
        super(ExpressionType.DECLARATION);
        this.name = name;
        this.expression = expression;
    }

    public String getName() {
        return name;
    }

    public Expression getExpression() {
        return expression;
    }

    protected ELNode toInternal(int pos) {
        return new ELNode.DEFINE(pos, name, null, null, expression.getNode(pos), true);
    }

    public String toString() {
        return "define " + name + "=" + expression;
    }
}
