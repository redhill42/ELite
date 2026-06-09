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

package org.operamasks.el.eval;

import java.lang.reflect.Method;
import javax.el.FunctionMapper;

class FunctionMapperBuilder extends FunctionMapper
{
    private FunctionMapper source;
    private FunctionMapperImpl target;

    FunctionMapperBuilder(FunctionMapper source) {
        this.source = source;
    }

    public Method resolveFunction(String prefix, String localName) {
        Method m = source.resolveFunction(prefix, localName);
        if (m != null) {
            if (target == null)
                target = new FunctionMapperImpl();
            target.addFunction(prefix, localName, m);
        }
        return m;
    }

    public FunctionMapper build() {
        return target;
    }
}
