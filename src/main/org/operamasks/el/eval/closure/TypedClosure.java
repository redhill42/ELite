/*
 * $Id: TypedClosure.java,v 1.6 2009/05/04 08:35:55 jackyzhang Exp $
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

package org.operamasks.el.eval.closure;

import java.lang.reflect.Array;
import javax.el.ELContext;
import elite.lang.Closure;
import org.operamasks.el.eval.EvaluationContext;
import org.operamasks.el.eval.TypeCoercion;
import org.operamasks.el.eval.EvaluationException;
import org.operamasks.el.eval.ELUtils;
import org.operamasks.el.resolver.MethodResolver;
import static org.operamasks.el.eval.ELEngine.resolveClass;
import static org.operamasks.el.eval.TypeCoercion.getBoxedType;
import static org.operamasks.el.resources.Resources.*;

public class TypedClosure extends DelegatingClosure
{
    TypedClosure(Closure delegate) {
        super(delegate);
    }

    static Object typecast(ELContext elctx, Class<?> type, Object value) {
        if (type == Void.TYPE) {
            return null;
        }

        if (value == null) {
            if (!type.isPrimitive()) {
                return null;
            }
        } else {
            if (type == Array.class && value.getClass().isArray()) {
                return value;
            }
        }

        try {
            return TypeCoercion.coerce(elctx, value, type);
        } catch (Exception ex) {
            throw new EvaluationException(elctx, ex);
        }
    }

    static Object typecast(ELContext elctx, ClassDefinition type, Object value) {
        if (type.isInstance(elctx, value)) {
            return value;
        }

        if (value == null) {
            return null;
        }

        if (value instanceof ClosureObject) {
            Object result = ((ClosureObject)value).invokeSpecial(
                elctx, "__coerce__", new Closure[] {type});
            if (result != ELUtils.NO_RESULT) {
                return result;
            }
        } else {
            MethodClosure method = MethodResolver.getInstance(elctx)
                .resolveMethod(value.getClass(), "__coerce__");
            if (method != null) {
                return method.invoke(elctx, value, new Closure[] {type});
            }
        }

        String name =
            (value instanceof ClosureObject)
                ? ((ClosureObject)value).get_class().getName()
                : value.getClass().getName();

        throw new EvaluationException(elctx, _T(JSPRT_COERCE_ERROR, name, type.getName()));
    }

    public static Object typecast(EvaluationContext ctx, String typename, Object value) {
        if (typename == null) {
            return value;
        }

        Object type = resolveClass(ctx, typename);
        if (type instanceof ClassDefinition) {
            return typecast(ctx.getELContext(), (ClassDefinition)type, value);
        } else {
            return typecast(ctx.getELContext(), (Class)type, value);
        }
    }

    public static boolean typecheck(EvaluationContext ctx, String typename, Object value) {
        if (typename == null) {
            return true;
        }

        if (value == null) {
            return false;
        }

        Object type = resolveClass(ctx, typename);
        if (type instanceof ClassDefinition) {
            ClassDefinition cls = (ClassDefinition)type;
            return cls.isInstance(ctx.getELContext(), value);
        } else if (type == Array.class) {
            return value.getClass().isArray();
        } else {
            return getBoxedType((Class)type).isInstance(value);
        }
    }

    public static Closure make(EvaluationContext ctx, String typename, Closure closure) {
        if (typename == null) {
            return closure;
        }

        if (closure instanceof TypedClosure) {
            closure = ((TypedClosure)closure).getDelegate();
        }

        Object type = resolveClass(ctx, typename);
        if (type instanceof ClassDefinition) {
            return new ClosureTypedClosure(closure, (ClassDefinition)type);
        } else {
            return new JavaTypedClosure(closure, (Class)type);
        }
    }

    public static Closure make(EvaluationContext ctx, String typename, Object value, boolean readonly) {
        if ((value instanceof Closure) && !(value instanceof ClosureObject)) {
            return make(ctx, typename, (Closure)value);
        }

        if (typename == null) {
            return new LiteralClosure(value, readonly);
        }

        Object type = resolveClass(ctx, typename);

        if (type instanceof ClassDefinition) {
            ClassDefinition cls = (ClassDefinition)type;
            Object newval = typecast(ctx.getELContext(), cls, value);
            Closure closure = new LiteralClosure(newval, readonly);
            return new ClosureTypedClosure(closure, cls);
        } else {
            Class cls = (Class)type;
            Object newval = typecast(ctx.getELContext(), cls, value);
            Closure closure = new LiteralClosure(newval, readonly);
            return new JavaTypedClosure(closure, cls);
        }
    }

    static class JavaTypedClosure extends TypedClosure {
        private final Class<?> type;

        JavaTypedClosure(Closure delegate, Class<?> type) {
            super(delegate);
            this.type = type;
        }

        public Object getValue(ELContext elctx) {
            return typecast(elctx, type, delegate.getValue(elctx));
        }

        public void setValue(ELContext elctx, Object value) {
            delegate.setValue(elctx, typecast(elctx, type, value));
        }

        public Class<?> getType(ELContext elctx) {
            return type;
        }

        public Class<?> getExpectedType() {
            return type;
        }

        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (obj instanceof JavaTypedClosure) {
                JavaTypedClosure other = (JavaTypedClosure)obj;
                return delegate.equals(other.delegate) && type.equals(other.type);
            }

            return false;
        }

        public int hashCode() {
            return delegate.hashCode() ^ type.hashCode();
        }
    }

    static class ClosureTypedClosure extends DelegatingClosure {
        private final ClassDefinition type;

        ClosureTypedClosure(Closure delegate, ClassDefinition type) {
            super(delegate);
            this.type = type;
        }

        public Object getValue(ELContext elctx) {
            return typecast(elctx, type, delegate.getValue(elctx));
        }

        public void setValue(ELContext elctx, Object value) {
            delegate.setValue(elctx, typecast(elctx, type, value));
        }

        public Class<?> getType(ELContext elctx) {
            return Object.class;
        }

        public Class<?> getExpectedType() {
            return Object.class;
        }

        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (obj instanceof ClosureTypedClosure) {
                ClosureTypedClosure other = (ClosureTypedClosure)obj;
                return delegate.equals(other.delegate) && type.equals(other.type);
            }

            return false;
        }

        public int hashCode() {
            return delegate.hashCode() ^ type.hashCode();
        }
    }
}