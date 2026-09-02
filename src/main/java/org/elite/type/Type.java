package org.elite.type;

/**
 * Base class of the ELite static type model.
 * All subclasses are immutable with structural equality.
 */
public abstract class Type {
  Type() {}

  @Override
  public abstract String toString();

  @Override
  public abstract boolean equals(Object o);

  @Override
  public abstract int hashCode();
}
