/*
 * $Id: TupleExpression.java,v 1.3 2009/05/13 05:39:00 danielyuan Exp $
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

import java.util.Iterator;
import java.util.Arrays;
import org.operamasks.el.parser.ELNode;
import elite.lang.annotation.Data;

@Data({"elements"})
public class TupleExpression extends Expression implements Iterable
{
    protected Expression[] elements;

    public TupleExpression(Expression[] elements) {
        super(ExpressionType.TUPLE);
        this.elements = elements;
    }

    public Expression[] getElements() {
        return elements.clone();
    }

    public Iterator iterator() {
        return Arrays.asList(elements).iterator();
    }

    protected ELNode toInternal(int pos) {
        ELNode[] exps = new ELNode[elements.length];
        for (int i = 0; i < exps.length; i++)
            exps[i] = elements[i].getNode(pos);
        return new ELNode.TUPLE(pos, exps);
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("(");
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) buf.append(",");
            buf.append(elements[i]);
        }
        buf.append(")");
        return buf.toString();
    }
}
