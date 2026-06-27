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

import javax.el.ELContext;
import elite.lang.Closure;
import org.elite.eval.ELEngine;

public class DataClass extends AbstractClosure
{
    private Class jclass;
    private String[] slots;

    public DataClass(Class jclass, String[] slots) {
        this.jclass = jclass;
        this.slots = slots;
    }

    public Class getJavaClass() {
        return jclass;
    }

    public String[] getSlots() {
        return slots;
    }
    
    public Object getValue(ELContext elctx) {
        return jclass;
    }

    public Class getType(ELContext elctx) {
        return Class.class;
    }

    public Class getExpectedType(ELContext elctx) {
        return Class.class;
    }

    public Object invoke(ELContext elctx, Closure[] args) {
        return ELEngine.invokeTarget(elctx, jclass, args);
    }
}
