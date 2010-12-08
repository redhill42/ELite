/*
 * $Id: ApplyExpression.java,v 1.3 2009/05/13 05:39:00 danielyuan Exp $
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
