package org.elite.type;

import java.lang.reflect.Constructor;

/**
 * A Java constructor resolved by reflection; the payload is the
 * Constructor.
 */
public final class ConstructorType extends MemberType {
  private final Constructor<?> ctor;

  public ConstructorType(Constructor<?> ctor) {
    this.ctor = ctor;
  }

  public Constructor<?> ctor() {
    return ctor;
  }

  @Override
  public Type getResultType() {
    return Type.of(ctor.getDeclaringClass());
  }

  @Override
  public Class<?> javaClass() {
    return ctor.getDeclaringClass();
  }

  private static String jvmTypeName(Class<?> c) {
    return c.isPrimitive() ? c.getName() : Type.of(c).toString();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("Constructor[")
      .append(ctor.getDeclaringClass().getSimpleName()).append('(');
    Class<?>[] params = ctor.getParameterTypes();
    for (int i = 0; i < params.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(jvmTypeName(params[i]));
    }
    return sb.append(")]").toString();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ConstructorType that && ctor.equals(that.ctor);
  }

  @Override
  public int hashCode() { return ctor.hashCode(); }
}
