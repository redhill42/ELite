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

package org.elite.eval.closure;

import java.util.Arrays;
import javax.el.MethodInfo;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;
import elite.lang.Closure;

public abstract class AbstractClosure extends Closure
{
    public int arity(ELContext context) {
        return -1;
    }

    public MethodInfo getMethodInfo(ELContext context) {
        Class[] types = null;
        int arity = arity(context);
        if (arity >= 0) {
            types = new Class[arity];
            Arrays.fill(types, Object.class);
        }
        return new MethodInfo("#closure", Object.class, types);
    }

    public abstract Object invoke(ELContext context, Closure[] args);

    public Object getValue(ELContext context) {
        return this;
    }

    public void setValue(ELContext context, Object value) {
        throw new PropertyNotWritableException();
    }

    public boolean isReadOnly(ELContext context) {
        return true;
    }

    public Class<?> getType(ELContext context) {
        return Closure.class;
    }

    public Class<?> getExpectedType() {
        return Closure.class;
    }

    public String getExpressionString() {
        return null;
    }

    public boolean equals(Object obj) {
        return this == obj;
    }

    public int hashCode() {
        return System.identityHashCode(this);
    }

    public boolean isLiteralText() {
        return false;
    }
}
