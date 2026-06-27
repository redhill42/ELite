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

import javax.el.ELContext;
import javax.el.ELResolver;
import javax.el.FunctionMapper;
import javax.el.VariableMapper;

import org.elite.eval.closure.LiteralClosure;

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
