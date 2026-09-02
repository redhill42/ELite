package org.elite.type;

/**
 * The error type: annotated when inference detected a type error.
 * Absorbs stronger than Any — computations based on an ErrorType
 * expression are also ErrorType.
 */
public final class ErrorType extends Type {
  public static final ErrorType ERROR = new ErrorType();

  private ErrorType() {}

  @Override
  public String toString() { return "Error"; }

  @Override
  public boolean equals(Object o) { return o instanceof ErrorType; }

  @Override
  public int hashCode() { return 1; }
}
