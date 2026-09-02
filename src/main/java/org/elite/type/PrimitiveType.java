package org.elite.type;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import org.elite.eval.TypeCoercion;

/**
 * A Java primitive/boxed type or an ELite exact numeric type, or one of
 * the two promotion-chain markers ANY_INT / ANY_FLOAT.
 */
public final class PrimitiveType extends Type {

  /** Int -> Long -> BigInt promotion chain marker. */
  public static final PrimitiveType ANY_INT = new PrimitiveType("AnyInt", null);

  /** Float -> Double -> BigDecimal promotion chain marker. */
  public static final PrimitiveType ANY_FLOAT = new PrimitiveType("AnyFloat", null);

  private static final Map<Class<?>, String> ALIASES = Map.of(
    Integer.class, "Int",
    Character.class, "Char",
    BigInteger.class, "BigInt",
    Void.class, "void");

  private final String name;
  private final Class<?> javaClass;   // null for ANY_INT / ANY_FLOAT

  private PrimitiveType(String name, Class<?> javaClass) {
    this.name = name;
    this.javaClass = javaClass;
  }

  public static PrimitiveType of(Class<?> c) {
    Class<?> boxed = TypeCoercion.getBoxedType(c);
    String display = ALIASES.getOrDefault(boxed, boxed.getSimpleName());
    return new PrimitiveType(display, boxed);
  }

  public Class<?> javaClass() { return javaClass; }

  public boolean isAnyInt() { return this == ANY_INT; }

  public boolean isAnyFloat() { return this == ANY_FLOAT; }

  @Override
  public String toString() { return name; }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof PrimitiveType that)) return false;
    return Objects.equals(javaClass, that.javaClass);
  }

  @Override
  public int hashCode() { return Objects.hashCode(javaClass); }
}
