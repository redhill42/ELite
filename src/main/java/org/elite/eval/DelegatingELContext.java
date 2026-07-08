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

import java.util.Locale;
import javax.el.ELContext;
import javax.el.ELResolver;
import javax.el.FunctionMapper;
import javax.el.VariableMapper;

public class DelegatingELContext extends ELContext
{
    private final ELContext delegate;
    private final Thread thread;

    DelegatingELContext(ELContext delegate) {
        this.delegate = delegate;
        this.thread = Thread.currentThread();
    }

    public ELContext getDelegate() {
        return delegate;
    }
    
    public Thread getThread() {
        return thread;
    }

    public static ELContext get(ELContext delegate) {
        if (delegate == null) {
            return ELEngine.getCurrentELContext();
        }

        Thread thread = Thread.currentThread();

        if (delegate instanceof ELContextImpl) {
            if (thread == ((ELContextImpl)delegate).getThread()) {
                return delegate;
            }
        }

        if (delegate instanceof DelegatingELContext) {
            if (thread == ((DelegatingELContext)delegate).getThread()) {
                return delegate;
            }
        }

        return new DelegatingELContext(delegate);
    }

    public void putContext(Class key, Object contextObject) {
        delegate.putContext(key, contextObject);
    }

    public Object getContext(Class key) {
        return delegate.getContext(key);
    }

    public ELResolver getELResolver() {
        return delegate.getELResolver();
    }

    public FunctionMapper getFunctionMapper() {
        return delegate.getFunctionMapper();
    }

    public Locale getLocale() {
        return delegate.getLocale();
    }

    public void setLocale(Locale locale) {
        delegate.setLocale(locale);
    }

    public VariableMapper getVariableMapper() {
        return delegate.getVariableMapper();
    }
}
