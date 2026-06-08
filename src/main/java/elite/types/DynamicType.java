package elite.types;

/**
 * The dynamic type — compatible with everything.
 * Used as the escape hatch for unanalyzable code.
 */
class DynamicType extends Type {
    DynamicType() {}

    @Override
    public boolean isSubtypeOf(Type other) {
        return true;
    }

    @Override
    public Type unify(Type other) {
        return other;
    }

    @Override
    public String toTypeString() {
        return "dynamic";
    }
}
