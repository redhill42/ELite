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

import java.util.Iterator;
import java.util.Arrays;
import org.elite.parser.ELNode;
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
