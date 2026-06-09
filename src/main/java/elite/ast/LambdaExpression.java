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

@Data({"name", "parameters", "body"})
public class LambdaExpression extends Expression
{
    protected String name;
    protected String[] parameters;
    protected Expression body;

    protected LambdaExpression(String name, String[] parameters, Expression body) {
        super(ExpressionType.LAMBDA);
        this.name = name;
        this.parameters = parameters;
        this.body = body;
    }

    public String getName() {
        return name;
    }

    public String[] getParameters() {
        return parameters.clone();
    }

    public Expression getBody() {
        return body;
    }

    protected ELNode toInternal(int pos) {
        ELNode.DEFINE[] vars = new ELNode.DEFINE[parameters.length];
        for (int i = 0; i < vars.length; i++)
            vars[i] = new ELNode.DEFINE(pos, parameters[i], null, null, null, true);
        return new ELNode.LAMBDA(pos, null, name, null, vars, false, body.getNode(pos));
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("{");
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) buf.append(",");
            buf.append(parameters[i]);
        }
        buf.append("=>");
        buf.append(body);
        buf.append("}");
        return buf.toString();
    }
}
