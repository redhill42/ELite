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

package org.elite.types;

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
            if (pt == OBJECT) return true;
            if (pt == NUMBER && Number.class.isAssignableFrom(this.javaClass)) return true;
            // Numeric widening hierarchy: byte → short → int → long → float → double
            if (isNumericWidening(this.javaClass, pt.javaClass)) return true;
        }
        return false;
    }

    /**
     * Check if {@code from} can be implicitly widened to {@code to}
     * per Java numeric promotion rules.
     */
    private static boolean isNumericWidening(Class<?> from, Class<?> to) {
        if (!Number.class.isAssignableFrom(from) || !Number.class.isAssignableFrom(to))
            return false;
        // Order: Byte < Short < Integer < Long < Float < Double
        int rankFrom = numericRank(from);
        int rankTo = numericRank(to);
        return rankFrom >= 0 && rankTo >= 0 && rankFrom <= rankTo;
    }

    private static int numericRank(Class<?> cls) {
        if (cls == Byte.class || cls == Byte.TYPE)       return 0;
        if (cls == Short.class || cls == Short.TYPE)     return 1;
        if (cls == Integer.class || cls == Integer.TYPE) return 2;
        if (cls == Long.class || cls == Long.TYPE)       return 3;
        if (cls == Float.class || cls == Float.TYPE)     return 4;
        if (cls == Double.class || cls == Double.TYPE)   return 5;
        return -1;
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
    public Type unify(Type other) {
        if (this.equals(other)) return this;
        if (other instanceof VarType) return other.unify(this);
        if (other == DYNAMIC) return this;
        if (other instanceof PrimitiveType) {
            PrimitiveType pt = (PrimitiveType) other;
            // Unify numbers using the wider type
            if (Number.class.isAssignableFrom(this.javaClass)
                && Number.class.isAssignableFrom(pt.javaClass)) {
                if (isNumericWidening(this.javaClass, pt.javaClass)) return pt;
                if (isNumericWidening(pt.javaClass, this.javaClass)) return this;
            }
        }
        return null; // cannot unify
    }

    @Override
    public String toTypeString() {
        return name;
    }
}
