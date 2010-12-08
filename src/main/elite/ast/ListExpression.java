/*
 * $Id: ListExpression.java,v 1.4 2009/05/13 05:39:00 danielyuan Exp $
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

@Data({"head", "tail"})
public class ListExpression extends Expression
{
    protected Expression head;
    protected Expression tail;

    public ListExpression(Expression head, Expression tail) {
        super(ExpressionType.LIST);
        assert !(head == null ^ tail == null);
        this.head = head;
        this.tail = tail;
    }

    public Expression getHead() {
        return head;
    }

    public Expression getTail() {
        return tail;
    }

    protected ELNode toInternal(int pos) {
        if (head == null) {
            return new ELNode.NIL(pos);
        } else {
            return new ELNode.CONS(pos, head.getNode(pos), tail.getNode(pos));
        }
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[");

        ListExpression list = this;
        if (list.head != null) {
            buf.append(list.head);
        }
        while (list.tail != null) {
            if (list.tail instanceof ListExpression) {
                list = (ListExpression)list.tail;
                if (list.head != null) {
                    buf.append(",");
                    buf.append(list.head);
                }
            } else {
                buf.append(":");
                buf.append(list.tail);
                break;
            }
        }

        buf.append("]");
        return buf.toString();
    }
}
