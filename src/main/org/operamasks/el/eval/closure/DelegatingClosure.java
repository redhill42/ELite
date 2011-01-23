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

package org.operamasks.el.eval.closure;

import javax.el.ELContext;
import javax.el.MethodInfo;
import javax.el.VariableMapper;
import elite.lang.Closure;
import elite.lang.Annotation;
import org.operamasks.el.eval.EvaluationContext;

public abstract class DelegatingClosure extends Closure
{
    protected Closure delegate;

    protected DelegatingClosure(Closure delegate) {
        this.delegate = delegate;
    }

    public Closure getDelegate() {
        return delegate;
    }
    
    public EvaluationContext getContext() {
        return delegate.getContext();
    }

    public EvaluationContext getContext(ELContext elctx) {
        return delegate.getContext(elctx);
    }

    public void _setenv(ELContext elctx, VariableMapper env) {
        delegate._setenv(elctx, env);
    }

    public void setValueChangeListener(ValueChangeListener listener) {
        delegate.setValueChangeListener(listener);
    }
    
    public int getModifiers() {
        return delegate.getModifiers();
    }

    public void setModifiers(int modifiers) {
        delegate.setModifiers(modifiers);
    }

    public boolean isPrivate() {
        return delegate.isPrivate();
    }

    public boolean isProtected() {
        return delegate.isProtected();
    }

    public boolean isPublic() {
        return delegate.isPublic();
    }

    public boolean isStatic() {
        return delegate.isStatic();
    }

    public boolean isAbstract() {
        return delegate.isAbstract();
    }

    public boolean isFinal() {
        return delegate.isFinal();
    }

    public boolean isSynchronized() {
        return delegate.isSynchronized();
    }

    public boolean isProcedure() {
        return delegate.isProcedure();
    }

    public int arity(ELContext elctx) {
        return delegate.arity(elctx);
    }

    public MethodInfo getMethodInfo(ELContext elctx) {
        return delegate.getMethodInfo(elctx);
    }

    public boolean isAnnotationPresent(String type) {
        return delegate.isAnnotationPresent(type);
    }

    public Annotation getAnnotation(String type) {
        return delegate.getAnnotation(type);
    }

    public Annotation[] getAnnotations() {
        return delegate.getAnnotations();
    }

    public void addAnnotation(Annotation annotation) {
        delegate.addAnnotation(annotation);
    }

    public void removeAnnotation(String type) {
        delegate.removeAnnotation(type);
    }

    public void setMetaData(MetaData metadata) {
        delegate.setMetaData(metadata);
    }
    
    public Object invoke(ELContext elctx, Closure[] args) {
        return delegate.invoke(elctx, args);
    }

    public Object getValue(ELContext elctx) {
        return delegate.getValue(elctx);
    }

    public void setValue(ELContext elctx, Object value) {
        delegate.setValue(elctx, value);
    }

    public boolean isReadOnly(ELContext elctx) {
        return delegate.isReadOnly(elctx);
    }

    public Class<?> getType(ELContext elctx) {
        return delegate.getType(elctx);
    }

    public Class<?> getExpectedType() {
        return delegate.getExpectedType();
    }

    public String getExpressionString() {
        return delegate.getExpressionString();
    }

    public boolean isLiteralText() {
        return delegate.isLiteralText();
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if (obj instanceof DelegatingClosure) {
            DelegatingClosure other = (DelegatingClosure)obj;
            return delegate.equals(other.delegate);
        } else {
            return delegate.equals(obj);
        }
    }

    public int hashCode() {
        return delegate.hashCode();
    }

    public String toString() {
        return delegate.toString();
    }
}
