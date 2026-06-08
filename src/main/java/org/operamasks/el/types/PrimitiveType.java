package org.operamasks.el.types;

import java.util.Arrays;

/**
 * Primitive (atomic) type: Integer, String, Boolean, etc.
 */
public class PrimitiveType extends Type {
    public final String name;
    public final Class<?> javaClass;

    public PrimitiveType(String name, Class<?> javaClass) {
        this.name = name;
        this.javaClass = javaClass;
    }

    @Override
    public boolean isSubtypeOf(Type other) {
        if (other == DYNAMIC || other == TOP) return true;
        if (other instanceof PrimitiveType) {
            PrimitiveType pt = (PrimitiveType) other;
            if (this == pt) return true;
            // Number hierarchy: Integer <: Long <: Number
            if (pt == NUMBER && Number.class.isAssignableFrom(this.javaClass)) return true;
            if (pt == OBJECT) return true;
        }
        return false;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof PrimitiveType)
            return this.name.equals(((PrimitiveType) obj).name);
        return false;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toTypeString() {
        return name;
    }
}
