/*
 * $Id: ConditionalExpression.java,v 1.3 2009/05/13 05:39:00 danielyuan Exp $
 *
 * Copyright (C) 2006 Operamasks Community.
 * Copyright (C) 2000-2006 Apusic Systems, Inc.
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
