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
