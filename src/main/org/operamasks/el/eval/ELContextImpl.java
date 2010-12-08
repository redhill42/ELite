/*
 * $Id: ELContextImpl.java,v 1.3 2009/03/22 08:37:56 danielyuan Exp $
 *
 * Copyright (C) 2006 Operamasks Community.
 * Copyright (C) 2000-2006 Apusic Systems, Inc.
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

import javax.el.ELContext;
import javax.el.ELResolver;
import javax.el.FunctionMapper;
import javax.el.VariableMapper;

import org.operamasks.el.eval.closure.LiteralClosure;

public class ELContextImpl extends ELContext
{
    private ELResolver resolver;
    private FunctionMapper fnMapper;
    private VariableMapper varMapper;
    private Thread thread;

    public ELContextImpl(ELResolver resolver, VariableMapper vm) {
        this.resolver = resolver;
        this.varMapper = vm;
        this.thread = Thread.currentThread();

        vm.setVariable("sys", new LiteralClosure(SystemScope.SINGLETON, true));
        vm.setVariable("global", new LiteralClosure(GlobalScope.SINGLETON, true));
    }

    public ELContextImpl(ELResolver resolver) {
        this(resolver, new VariableMapperImpl());
    }

    public Thread getThread() {
        return thread;
    }
    
    public ELResolver getELResolver() {
        return resolver;
    }

    public void setELResolver(ELResolver resolver) {
        this.resolver = resolver;
    }

    public FunctionMapper getFunctionMapper() {
        return fnMapper;
    }

    public void setFunctionMapper(FunctionMapper mapper) {
        this.fnMapper = mapper;
    }

    public VariableMapper getVariableMapper() {
        return varMapper;
    }

    public void setVariableMapper(VariableMapper mapper) {
        this.varMapper = mapper;
    }
}
