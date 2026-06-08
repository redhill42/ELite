package org.operamasks.el.types;

/**
 * The top type — supertype of all types.
 */
public class TopType extends Type {
    TopType() {}

    @Override
    public boolean isSubtypeOf(Type other) {
        return this == other || other == DYNAMIC;
    }

    @Override
    public String toTypeString() {
        return "?";
    }
}
