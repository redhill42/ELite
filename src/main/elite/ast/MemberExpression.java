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
