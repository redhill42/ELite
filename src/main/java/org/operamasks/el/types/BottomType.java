package org.operamasks.el.types;

/**
 * The bottom type — subtype of all types. No value inhabits this type.
 */
class BottomType extends Type {
    BottomType() {}

    @Override
    public boolean isSubtypeOf(Type other) {
        return true;
    }

    @Override
    public Type unify(Type other) {
        // bottom unified with anything yields the other type
        return other;
    }

    @Override
    public String toTypeString() {
        return "Nothing";
    }
}
