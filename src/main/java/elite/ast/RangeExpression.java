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

import org.elite.parser.ELNode;
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
