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
