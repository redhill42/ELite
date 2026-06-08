package elite.types;

import java.util.Map;

/**
 * A type variable used for type inference (e.g., 'a, 'b).
 */
public class VarType extends Type {
    public final String name;
    private Type binding = null;

    public VarType(String name) {
        this.name = name;
    }

    /** Bind this type variable to a concrete type. Returns false if occurs check fails. */
    public boolean bind(Type t) {
        if (this == t) return true;
        if (t instanceof VarType && this.name.equals(((VarType) t).name)) return true;
        if (t.occurs(this)) return false; // occurs check
        this.binding = t;
        return true;
    }

    /** Returns the resolved type, following the binding chain. */
    public Type resolve() {
        if (binding == null) return this;
        if (binding instanceof VarType) return ((VarType) binding).resolve();
        return binding;
    }

    public boolean isBound() {
        return binding != null;
    }

    @Override
    public Type unify(Type other) {
        Type resolved = resolve();
        if (resolved != this) return resolved.unify(other);
        if (other instanceof VarType) {
            VarType ov = (VarType) other;
            Type oresolved = ov.resolve();
            if (oresolved != ov) return this.unify(oresolved);
            if (this.name.equals(ov.name)) return this;
        }
        // Bind if possible
        if (bind(other)) return other;
        return null;
    }

    @Override
    public boolean isSubtypeOf(Type other) {
        Type resolved = resolve();
        if (resolved != this) return resolved.isSubtypeOf(other);
        if (other == DYNAMIC || other == TOP) return true;
        return this == other;
    }

    @Override
    public boolean occurs(VarType var) {
        if (this == var) return true;
        Type resolved = resolve();
        if (resolved != this) return resolved.occurs(var);
        return false;
    }

    @Override
    public Type subst(Map<VarType, Type> subst) {
        Type s = subst.get(this);
        if (s != null) return s;
        Type resolved = resolve();
        if (resolved != this) return resolved.subst(subst);
        return this;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof VarType) {
            return this.name.equals(((VarType) obj).name);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toTypeString() {
        Type resolved = resolve();
        if (resolved != this) return resolved.toTypeString();
        return "'" + name;
    }
}
