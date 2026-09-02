package org.elite.type;

import elite.lang.Decimal;
import elite.lang.Rational;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

import static java.util.Map.entry;

public class Types {
  public static final Type UNKNOWN = new UnknownType();
  public static final Type ERROR = new ErrorType();
  public static final Type ANY = new AnyType();

  public static final Type VOID = new PrimitiveType("void", Void.TYPE);
  public static final Type BOOLEAN = new PrimitiveType("Boolean", Boolean.class);
  public static final Type BYTE = new PrimitiveType("Byte", Byte.class);
  public static final Type SHORT = new PrimitiveType("Short", Short.class);
  public static final Type INT = new PrimitiveType("Int", Integer.class);
  public static final Type LONG = new PrimitiveType("Long", Long.class);
  public static final Type FLOAT = new PrimitiveType("Float", Float.class);
  public static final Type DOUBLE = new PrimitiveType("Double", Double.class);
  public static final Type CHAR = new PrimitiveType("Char", Character.class);
  public static final Type STRING = new PrimitiveType("String", String.class);
  public static final Type BIGINT = new PrimitiveType("BigInt", BigInteger.class);
  public static final Type BIGDECIMAL = new PrimitiveType("BigDecimal", BigDecimal.class);
  public static final Type DECIMAL = new PrimitiveType("Decimal", Decimal.class);
  public static final Type RATIONAL = new PrimitiveType("Rational", Rational.class);

  public static final Type ANY_INT = new PrimitiveType("AnyInt", Number.class);
  public static final Type ANY_FLOAT = new PrimitiveType("AnyFloat", Number.class);

  static final Map<Class<?>, Type> PRIMITIVES = Map.ofEntries(
    entry(Void.TYPE,        VOID),
    entry(Void.class,       VOID),
    entry(Boolean.class,    BOOLEAN),
    entry(Byte.class,       BYTE),
    entry(Short.class,      SHORT),
    entry(Character.class,  CHAR),
    entry(Integer.class,    INT),
    entry(Long.class,       LONG),
    entry(Float.class,      FLOAT),
    entry(Double.class,     DOUBLE),
    entry(String.class,     STRING),
    entry(BigInteger.class, BIGINT),
    entry(BigDecimal.class, BIGDECIMAL),
    entry(Decimal.class,    DECIMAL),
    entry(Rational.class,   RATIONAL));
}
