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

package org.operamasks.el.eval;

import java.util.Map;
import java.util.LinkedHashMap;
import javax.el.VariableMapper;
import javax.el.ValueExpression;
import org.operamasks.el.eval.closure.LiteralClosure;

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
