package org.elite.type;

import java.util.Arrays;

/** An ELite function type, e.g. Function[Int, [Int, Int]]. */
public final class FunctionType extends Type {
  private final Type returnType;
  private final Type[] params;

  public FunctionType(Type returnType, Type[] params) {
    this.returnType = returnType;
    this.params = params;
  }

  public Type returnType() { return returnType; }

  public Type[] params() { return params; }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("Function[")
      .append(returnType).append(", [");
    for (int i = 0; i < params.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(params[i]);
    }
    return sb.append("]]").toString();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof FunctionType that &&
           returnType.equals(that.returnType) &&
           Arrays.equals(params, that.params);
  }

  @Override
  public int hashCode() { return returnType.hashCode() * 31 + Arrays.hashCode(params); }
}
