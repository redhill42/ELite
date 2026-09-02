package org.elite.type;

import java.lang.reflect.Constructor;

/**
 * A Java constructor resolved by reflection; the payload is the
 * Constructor. Value-consumption sites dereference it to the declaring
 * class's JavaClassType via InferenceContext.valueType.
 */
public final class JavaConstructorType extends Type {
  private final Constructor<?> ctor;

  public JavaConstructorType(Constructor<?> ctor) {
    this.ctor = ctor;
  }

  public Constructor<?> ctor() { return ctor; }

  public Class<?> declaringClass() { return ctor.getDeclaringClass(); }

  private static String jvmTypeName(Class<?> c) {
    return c.isPrimitive() ? c.getName() : PrimitiveType.of(c).toString();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("JavaConstructor[")
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
    return o instanceof JavaConstructorType that && ctor.equals(that.ctor);
  }

  @Override
  public int hashCode() { return ctor.hashCode(); }
}
