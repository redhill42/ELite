/*
 * Copyright (c) 2006-2012 Daniel Yuan.
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

package org.operamasks.el.types;

import java.util.List;
import java.util.Map;

/**
 * Abstract base class for the ELite type system. Supports gradual typing:
 * fully inferred types when possible, falling back to DynamicType when
 * static analysis cannot determine the type.
 */
public abstract class Type {

    /**
     * Returns true if this type is a subtype of the given type.
     * DynamicType is compatible with everything in gradual typing mode.
     */
    public abstract boolean isSubtypeOf(Type other);

    /**
     * Unify this type with another, returning the most general unifier.
     * Returns null if unification fails.
     */
    public Type unify(Type other) {
        if (this.equals(other)) return this;
        if (other instanceof VarType) return other.unify(this);
        if (this instanceof VarType) return ((VarType) this).bind(other) ? other : null;
        return null;
    }

    /**
     * Returns true if this type contains the given type variable (occurs check).
     */
    public boolean occurs(VarType var) {
        return false;
    }

    /**
     * Substitute type variables with concrete types.
     */
    public Type subst(Map<VarType, Type> subst) {
        return this;
    }

    /**
     * Returns a human-readable representation of this type.
     */
    public abstract String toTypeString();

    @Override
    public String toString() {
        return toTypeString();
    }

    // ---- Singleton common types ----

    public static final PrimitiveType INTEGER  = new PrimitiveType("Integer", Integer.class);
    public static final PrimitiveType LONG     = new PrimitiveType("Long", Long.class);
    public static final PrimitiveType DOUBLE   = new PrimitiveType("Double", Double.class);
    public static final PrimitiveType FLOAT    = new PrimitiveType("Float", Float.class);
    public static final PrimitiveType BOOLEAN  = new PrimitiveType("Boolean", Boolean.class);
    public static final PrimitiveType STRING   = new PrimitiveType("String", String.class);
    public static final PrimitiveType CHAR     = new PrimitiveType("Char", Character.class);
    public static final PrimitiveType NUMBER   = new PrimitiveType("Number", Number.class);
    public static final PrimitiveType OBJECT   = new PrimitiveType("Object", Object.class);

    /** The bottom type — nothing inhabits this. */
    public static final BottomType BOTTOM = new BottomType();

    /** The top type — everything is a subtype. Used as the initial unknown. */
    public static final TopType TOP = new TopType();

    /** Fallback for unanalyzable code — compatible with everything. */
    public static final DynamicType DYNAMIC = new DynamicType();

    // ---- Helpers ----

    /**
     * Resolve a Java Class to the corresponding ELite type.
     */
    public static Type fromClass(Class<?> cls) {
        if (cls == null) return DYNAMIC;
        if (cls == Integer.class || cls == Integer.TYPE) return INTEGER;
        if (cls == Long.class || cls == Long.TYPE) return LONG;
        if (cls == Double.class || cls == Double.TYPE) return DOUBLE;
        if (cls == Float.class || cls == Float.TYPE) return FLOAT;
        if (cls == Boolean.class || cls == Boolean.TYPE) return BOOLEAN;
        if (cls == String.class) return STRING;
        if (cls == Character.class || cls == Character.TYPE) return CHAR;
        if (Number.class.isAssignableFrom(cls)) return NUMBER;
        if (cls == Object.class) return OBJECT;
        if (cls == Void.TYPE || cls == Void.class) return new PrimitiveType("Void", Void.TYPE);
        return new ClassType(cls);
    }

    /**
     * Returns a fresh type variable.
     */
    public static VarType fresh(String prefix) {
        return new VarType(prefix + "_" + freshCounter.getAndIncrement());
    }

    public static VarType fresh() {
        return fresh("t");
    }

    private static final java.util.concurrent.atomic.AtomicLong freshCounter
        = new java.util.concurrent.atomic.AtomicLong();
}
