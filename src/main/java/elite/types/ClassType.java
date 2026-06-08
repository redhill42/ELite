package elite.types;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A nominal class type, optionally parameterized (e.g., List&lt;Integer&gt;).
 */
public class ClassType extends Type {
    public final Class<?> javaClass;
    public final List<Type> typeArgs;

    public ClassType(Class<?> javaClass, Type... typeArgs) {
        this.javaClass = javaClass;
        this.typeArgs = Arrays.asList(typeArgs);
    }

    @Override
    public boolean isSubtypeOf(Type other) {
        if (other == DYNAMIC || other == TOP) return true;
        if (other instanceof ClassType) {
            ClassType ct = (ClassType) other;
            if (!ct.javaClass.isAssignableFrom(this.javaClass)) return false;
            if (ct.typeArgs.isEmpty()) return true;
            if (this.typeArgs.size() != ct.typeArgs.size()) return false;
            for (int i = 0; i < typeArgs.size(); i++) {
                if (!this.typeArgs.get(i).isSubtypeOf(ct.typeArgs.get(i))) return false;
            }
            return true;
        }
        if (other instanceof PrimitiveType && other == OBJECT) return true;
        return false;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ClassType) {
            ClassType ct = (ClassType) obj;
            return this.javaClass == ct.javaClass && this.typeArgs.equals(ct.typeArgs);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return javaClass.hashCode() * 31 + typeArgs.hashCode();
    }

    @Override
    public Type subst(Map<VarType, Type> subst) {
        if (typeArgs.isEmpty()) return this;
        List<Type> newArgs = typeArgs.stream()
            .map(t -> t.subst(subst))
            .collect(Collectors.toList());
        if (newArgs.equals(typeArgs)) return this;
        return new ClassType(javaClass, newArgs.toArray(new Type[0]));
    }

    @Override
    public String toTypeString() {
        if (typeArgs.isEmpty()) return javaClass.getSimpleName();
        return javaClass.getSimpleName() + "<" +
            typeArgs.stream().map(Type::toTypeString).collect(Collectors.joining(", ")) + ">";
    }
}
