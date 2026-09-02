package org.elite.type;

/** An ELite list type, e.g. List[Int]. */
public final class ListType extends Type {
  private final Type element;

  public ListType(Type element) {
    this.element = element;
  }

  public Type element() { return element; }

  @Override
  public String toString() { return "List[" + element + "]"; }

  @Override
  public boolean equals(Object o) {
    return o instanceof ListType that && element.equals(that.element);
  }

  @Override
  public int hashCode() { return element.hashCode() * 31 + 1; }
}
