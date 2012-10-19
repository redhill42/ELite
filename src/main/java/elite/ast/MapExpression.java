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
