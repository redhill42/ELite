/*
 * $Id: DataClass.java,v 1.1 2009/05/11 03:08:10 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package org.operamasks.el.eval.closure;

import javax.el.ELContext;
import elite.lang.Closure;
import org.operamasks.el.eval.ELEngine;

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
