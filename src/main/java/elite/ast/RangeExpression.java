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

@Data({"begin", "next", "end", "exclude"})
public class RangeExpression extends Expression
{
    protected Expression begin, next, end;
    protected boolean exclude;

    protected RangeExpression(Expression begin, Expression next, Expression end) {
        this(begin, next, end, false);
    }

    protected RangeExpression(Expression begin, Expression next, Expression end, boolean exclude) {
        super(ExpressionType.RANGE);
        this.begin = begin;
        this.next = next;
        this.end = end;
        this.exclude = exclude;
    }

    public Expression getBegin() {
        return begin;
    }

    public Expression getNext() {
        return next;
    }

    public Expression getEnd() {
        return end;
    }

    protected ELNode toInternal(int pos) {
        return new ELNode.RANGE(pos,
            (begin == null) ? null : begin.getNode(pos),
            (next  == null) ? null : next.getNode(pos),
            (end   == null) ? null : end.getNode(pos),
            exclude);
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[");
        buf.append(begin);
        if (next != null)
            buf.append(",").append(next);
        buf.append("..");
        if (exclude)
            buf.append("^");
        buf.append(end == null ? "*" : end);
        buf.append("]");
        return buf.toString();
    }
}
