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

package org.operamasks.el.eval;

import java.util.Locale;
import javax.el.ELContext;
import javax.el.ELResolver;
import javax.el.FunctionMapper;
import javax.el.VariableMapper;

public class DelegatingELContext extends ELContext
{
    private ELContext delegate;
    private Thread thread;

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
