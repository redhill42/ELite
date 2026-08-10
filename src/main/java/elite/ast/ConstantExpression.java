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

import org.elite.eval.ELUtils;
import org.elite.parser.ELNode;
import org.elite.eval.TypeCoercion;
import elite.lang.annotation.Data;

/**
 * Represents an expression that has a constant value.
 */
@Data({"value"})
public class ConstantExpression extends Expression
{
    protected ConstantExpression(ELNode node) {
        super(ExpressionType.CONSTANT, node);
    }

    /**
     * Returns the constant value.
     * @return the constant value.
     */
    public Object getValue() {
        return node.getValue(null);
    }

    /**
     * Convert this expression into an internal node representation.
     * @return the internal node representation.
     */
    protected ELNode toInternal(int pos) {
        return node;
    }

    /**
     * Returns the string representation of this expression.
     * @return the string representation
     */
    public String toString() {
        Object value = getValue();
        if (value == null) {
            return "null";
        } else if (value instanceof String) {
            return ELUtils.escape((String)value);
        } else {
            return TypeCoercion.coerceToString(value);
        }
    }
}
