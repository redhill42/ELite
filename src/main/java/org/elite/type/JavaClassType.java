package org.elite.type;

import java.util.Objects;

/** A reference to a Java class. */
public final class JavaClassType extends Type {
  private final Class<?> javaClass;

  public JavaClassType(Class<?> javaClass) {
    this.javaClass = javaClass;
  }

  public Class<?> javaClass() { return javaClass; }

  @Override
  public String toString() { return javaClass.getSimpleName(); }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaClassType that && javaClass == that.javaClass;
  }

  @Override
  public int hashCode() { return Objects.hashCode(javaClass); }
}
