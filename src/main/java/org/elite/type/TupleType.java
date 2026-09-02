package org.elite.type;

import java.util.Arrays;

/** An ELite tuple type, e.g. Tuple[String, Int]. */
public final class TupleType extends Type {
  private final Type[] elems;

  public TupleType(Type[] elems) {
    this.elems = elems;
  }

  public Type[] elems() { return elems; }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("Tuple[");
    for (int i = 0; i < elems.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(elems[i]);
    }
    return sb.append(']').toString();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TupleType that && Arrays.equals(elems, that.elems);
  }

  @Override
  public int hashCode() { return Arrays.hashCode(elems); }
}
