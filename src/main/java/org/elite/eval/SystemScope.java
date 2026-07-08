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

import java.io.Serial;
import java.io.Serializable;
import javax.el.ELContext;
import javax.el.PropertyNotFoundException;
import javax.el.PropertyNotWritableException;
import elite.lang.Closure;
import org.elite.resolver.MethodResolver;

public class SystemScope implements PropertyDelegate, Serializable
{
    public static final SystemScope SINGLETON = new SystemScope();
    private SystemScope() {}
    
    public Object getValue(ELContext elctx, Object property) {
        MethodResolver resolver = MethodResolver.getInstance(elctx);
        Object value = resolver.resolveSystemMethod((String)property);

        if (value != null) {
            elctx.setPropertyResolved(true);
            return value;
        } else {
            throw new PropertyNotFoundException();
        }
    }

    public Class<?> getType(ELContext elctx, Object property) {
        MethodResolver resolver = MethodResolver.getInstance(elctx);
        Object value = resolver.resolveSystemMethod((String)property);

        if (value != null) {
            elctx.setPropertyResolved(true);
            return Closure.class;
        } else {
            throw new PropertyNotFoundException();
        }
    }

    public void setValue(ELContext elctx, Object property, Object value) {
        throw new PropertyNotWritableException();
    }

    public boolean isReadOnly(ELContext elctx, Object property) {
        return true;
    }

    @Serial
    private Object readResolve() {
        return SINGLETON;
    }
}
