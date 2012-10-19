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

import javax.el.VariableMapper;
import javax.el.ValueExpression;

class VariableMapperBuilder extends VariableMapper
{
    private VariableMapper source;
    private VariableMapper target;

    VariableMapperBuilder(VariableMapper source) {
        this.source = source;
    }

    VariableMapperBuilder(VariableMapper source, VariableMapper target) {
        this.source = source;
        this.target = target;
    }

    public ValueExpression resolveVariable(String name) {
        ValueExpression value = source.resolveVariable(name);
        if (value != null) {
            if (target == null)
                target = new VariableMapperImpl();
            target.setVariable(name, value);
        }
        return value;
    }

    public ValueExpression setVariable(String name, ValueExpression value) {
        throw new IllegalStateException();
    }

    public VariableMapper build() {
        return target;
    }
}
