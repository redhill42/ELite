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

package org.elite.eval;

import java.util.Map;
import java.util.LinkedHashMap;
import javax.el.VariableMapper;
import javax.el.ValueExpression;
import org.elite.eval.closure.LiteralClosure;

public class VariableMapperImpl extends VariableMapper
    implements java.io.Serializable
{
    private Map<String,ValueExpression> map;
    private static final long serialVersionUID = -2203438169608773760L;

    public VariableMapperImpl() {
        map = new LinkedHashMap<String,ValueExpression>();
    }

    public VariableMapperImpl(Map<String,Object> m) {
        map = new LinkedHashMap<String,ValueExpression>();
        for (Map.Entry<String,Object> e : m.entrySet()) {
            Object v = e.getValue();
            ValueExpression ve = (v instanceof ValueExpression)
                                    ? (ValueExpression)v
                                    : new LiteralClosure(v);
            map.put(e.getKey(), ve);
        }
    }

    public ValueExpression resolveVariable(String name) {
        return map.get(name);
    }

    public ValueExpression setVariable(String name, ValueExpression expression) {
        if (expression == null) {
            return map.remove(name);
        } else {
            return map.put(name, expression);
        }
    }

    public Map<String,ValueExpression> getVariableMap() {
        return map;
    }
}
