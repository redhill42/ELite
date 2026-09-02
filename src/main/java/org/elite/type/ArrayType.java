package org.elite.type;

/** An array type, e.g. Array[Int, 2]. */
public final class ArrayType extends Type {
  private final Type element;
  private final int dims;

  public ArrayType(Type element, int dims) {
    this.element = element;
    this.dims = dims;
  }

  public Type element() { return element; }

  public int dims() { return dims; }

  @Override
  public String toString() { return "Array[" + element + ", " + dims + "]"; }

  @Override
  public boolean equals(Object o) {
    return o instanceof ArrayType that &&
           dims == that.dims && element.equals(that.element);
  }

  @Override
  public int hashCode() { return element.hashCode() * 31 + dims; }
}
