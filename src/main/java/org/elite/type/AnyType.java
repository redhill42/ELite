package org.elite.type;

/** The unknown type; the absorbing element of all merges. */
public final class AnyType extends Type {
  public static final AnyType ANY = new AnyType();

  private AnyType() {}

  @Override
  public String toString() { return "Any"; }

  @Override
  public boolean equals(Object o) { return o instanceof AnyType; }

  @Override
  public int hashCode() { return 0; }
}
