/*
 * $Id: ScriptContextImpl.java,v 1.1 2009/03/22 08:37:27 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
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
