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

package org.elite.script;

import javax.el.ELContext;
import javax.script.ScriptContext;
import javax.script.SimpleScriptContext;
import javax.script.Bindings;

import org.elite.eval.PropertyResolvable;

public class ScriptContextImpl extends SimpleScriptContext
    implements ScriptContext, PropertyResolvable
{
    public Bindings getEngineScope() {
        return getBindings(ENGINE_SCOPE);
    }

    public Bindings getGlobalScope() {
        return getBindings(GLOBAL_SCOPE);
    }
    
    public Object getValue(ELContext elctx, Object property) {
        String name = (String)property;
        int scope = getAttributesScope(name);
        if (scope != -1) {
            elctx.setPropertyResolved(true);
            return getAttribute(name);
        } else {
            return null;
        }
    }

    public Class<?> getType(ELContext elctx, Object property) {
        Object value = getValue(elctx, property);
        return (value == null) ? null : value.getClass();
    }

    public void setValue(ELContext elctx, Object property, Object value) {
        String name = (String)property;
        int scope = getAttributesScope(name);
        if (scope == -1)
            scope = ScriptContext.ENGINE_SCOPE;
        setAttribute(name, value, scope);
        elctx.setPropertyResolved(true);
    }

    public boolean isReadOnly(ELContext elctx, Object property) {
        String name = (String)property;
        int scope = getAttributesScope(name);
        if (scope != -1)
            elctx.setPropertyResolved(true);
        return false;
    }
}
