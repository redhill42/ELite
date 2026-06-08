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
    public String toTypeString() {
        return "Nothing";
    }
}
