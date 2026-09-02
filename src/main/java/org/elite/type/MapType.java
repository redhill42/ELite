package org.elite.type;

import java.util.Objects;

/** An ELite map type, e.g. Map[String, Int]. */
public final class MapType extends Type {
  private final Type key;
  private final Type value;

  public MapType(Type key, Type value) {
    this.key = key;
    this.value = value;
  }

  public Type key() { return key; }

  public Type value() { return value; }

  @Override
  public String toString() { return "Map[" + key + ", " + value + "]"; }

  @Override
  public boolean equals(Object o) {
    return o instanceof MapType that &&
           key.equals(that.key) && value.equals(that.value);
  }

  @Override
  public int hashCode() { return Objects.hash(key, value); }
}
