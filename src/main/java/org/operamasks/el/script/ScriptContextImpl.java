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

package org.operamasks.el.script;

import javax.el.ELContext;
import javax.script.ScriptContext;
import javax.script.SimpleScriptContext;
import javax.script.Bindings;

import org.operamasks.el.eval.PropertyResolvable;

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
