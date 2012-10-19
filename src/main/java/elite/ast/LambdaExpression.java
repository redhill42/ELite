/*
 * Copyright (c) 2006-2011 Daniel Yuan.
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
