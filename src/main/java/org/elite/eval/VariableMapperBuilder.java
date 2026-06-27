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
