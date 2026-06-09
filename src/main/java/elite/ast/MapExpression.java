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

@Data({"keys", "values"})
public class MapExpression extends Expression
{
    protected Expression[] keys;
    protected Expression[] values;

    protected MapExpression(Expression[] keys, Expression[] values) {
        super(ExpressionType.MAP);
        this.keys = keys;
        this.values = values;
    }

    public Expression[] getKeys() {
        return keys.clone();
    }

    public Expression[] getValues() {
        return values.clone();
    }

    protected ELNode toInternal(int pos) {
        ELNode[] keys_node = new ELNode[keys.length];
        ELNode[] values_node = new ELNode[values.length];
        for (int i = 0; i < keys_node.length; i++)
            keys_node[i] = keys[i].getNode(pos);
        for (int i = 0; i < values_node.length; i++)
            values_node[i] = values[i].getNode(pos);
        return new ELNode.MAP(0, keys_node, values_node);
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("{");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) buf.append(",");
            buf.append(keys[i]);
            buf.append(":");
            buf.append(values[i]);
        }
        buf.append("}");
        return buf.toString();
    }
}
