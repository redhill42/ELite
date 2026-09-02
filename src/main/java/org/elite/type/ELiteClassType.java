package org.elite.type;

import org.elite.ir.SymbolTable;

/** A reference to an ELite class definition. */
public final class ELiteClassType extends Type {
  private final SymbolTable.Symbol symbol;

  public ELiteClassType(SymbolTable.Symbol symbol) {
    this.symbol = symbol;
  }

  public SymbolTable.Symbol symbol() { return symbol; }

  @Override
  public String toString() { return symbol.name; }

  @Override
  public boolean equals(Object o) {
    return o instanceof ELiteClassType that && symbol == that.symbol;
  }

  @Override
  public int hashCode() { return System.identityHashCode(symbol); }
}
