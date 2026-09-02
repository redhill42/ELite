package org.elite.type;

import java.lang.reflect.Method;

/** A Java method resolved by reflection; the payload is the Method. */
public final class JavaMethodType extends Type {
  private final Method method;

  public JavaMethodType(Method method) {
    this.method = method;
  }

  public Method method() { return method; }

  private static String jvmTypeName(Class<?> c) {
    return c.isPrimitive() ? c.getName() : PrimitiveType.of(c).toString();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("JavaMethod[")
      .append(jvmTypeName(method.getReturnType())).append(' ')
      .append(method.getDeclaringClass().getSimpleName())
      .append('.').append(method.getName()).append('(');
    Class<?>[] params = method.getParameterTypes();
    for (int i = 0; i < params.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(jvmTypeName(params[i]));
    }
    return sb.append(")]").toString();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaMethodType that && method.equals(that.method);
  }

  @Override
  public int hashCode() { return method.hashCode(); }
}
