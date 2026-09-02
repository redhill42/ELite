package org.elite.type;

import java.lang.reflect.Field;

/** A Java field resolved by reflection; the payload is the Field. */
public final class JavaFieldType extends Type {
  private final Field field;

  public JavaFieldType(Field field) {
    this.field = field;
  }

  public Field field() { return field; }

  private static String jvmTypeName(Class<?> c) {
    return c.isPrimitive() ? c.getName() : PrimitiveType.of(c).toString();
  }

  @Override
  public String toString() {
    return "JavaField[" + jvmTypeName(field.getType()) + "]";
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaFieldType that && field.equals(that.field);
  }

  @Override
  public int hashCode() { return field.hashCode(); }
}
