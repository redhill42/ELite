package org.elite.ir;

import org.elite.parser.ELNode;
import org.elite.parser.ParseError;
import org.elite.parser.Position;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.elite.resources.Resources.*;

public class SymbolTable {

  /**
   * Information about a single symbol.
   */
  public static class Symbol {
    public final Scope scope;       // the scope of this symbol defined
    public final String name;       // symbol name from source
    public final ELNode.DEFINE def; // the node that provide symbol definition info

    public int slot = -1;           // IR locals[] index
    public boolean captured;        // captured by an inner closure?
    public IRFunction func;         // known function: the compiled IRFunction
    public IRClass clazz;           // the symbol is defined as an IRClass

    public boolean immutable = true; // the symbol is immutable by default

    Symbol(Scope scope, ELNode.DEFINE def) {
      this.scope = scope;
      this.name = def.id;
      this.def = def;
    }

    public boolean isFunction() {
      return func != null;
    }

    public boolean isPublic() {
      return def.meta == null || Modifier.isPublic(def.meta.modifiers) ||
             (!Modifier.isPrivate(def.meta.modifiers) &&
              !Modifier.isProtected(def.meta.modifiers));
    }

    public boolean isPrivate() {
      return def.meta != null && Modifier.isPrivate(def.meta.modifiers);
    }

    public boolean isProtected() {
      return def.meta != null && Modifier.isProtected(def.meta.modifiers);
    }

    public boolean isStatic() {
      return def.meta != null && Modifier.isStatic(def.meta.modifiers);
    }

    public boolean isFinal() {
      return def.meta != null && Modifier.isFinal(def.meta.modifiers);
    }

    public boolean isAbstract() {
      return def.meta != null && Modifier.isAbstract(def.meta.modifiers);
    }

    public boolean isConstructor() {
      if (def.expr instanceof ELNode.CLASSDEF)
        return true;
      if (def.expr instanceof ELNode.LAMBDA) {
        IRClass irc = scope.enclosingClass();
        return irc != null && def.id.equals(irc.name);
      }
      return false;
    }
  }

  /**
   * A single scope layer holding name -> symbol mappings.
   */
  public static class Scope {
    final Scope parent;    // chained to direct enclosing scope
    final int depth;       // nesting depth (0 = root)
    final ELNode frontier; // the frontier of the scope, lambda or class
    final Map<String, Symbol> symbols = new LinkedHashMap<>();
    int nextSlot;          // currently allocated slots
    int maxSlots;          // max slot index used across this scope and all
                           // nested sub-scopes

    // A flag to indicate a program scope. Symbols defined in this scope
    // implemented as a static filed in program class.
    boolean programScope;

    Scope(Scope parent, int depth, ELNode frontier, int startSlot) {
      this.parent   = parent;
      this.depth    = depth;
      this.frontier = frontier;
      this.nextSlot = startSlot;
      this.maxSlots = startSlot;
    }

    public int depth() {
      return depth;
    }

    public boolean isTopLevel() {
      return parent == null;
    }

    public boolean isProgramScope() {
      return programScope;
    }

    public boolean hasCaptures() {
      for (Symbol sym : symbols.values()) {
        if (sym.captured)
          return true;
      }
      return false;
    }

    Symbol get(String name) {
      return symbols.get(name);
    }

    void put(String name, Symbol sym) {
      symbols.put(name, sym);
    }

    void remove(String name) {
      symbols.remove(name);
    }

    Symbol lookup(String name) {
      for (Scope s = this; s != null; s = s.parent) {
        Symbol sym = s.get(name);
        if (sym != null)
          return sym;
      }
      return null;
    }

    Symbol lookupOuter(String name) {
      for (Scope s = parent; s != null; s = s.parent) {
        Symbol sym = s.get(name);
        if (sym != null)
          return sym;
      }
      return null;
    }

    Scope enclosingScope() {
      for (Scope s = this; s != null; s = s.parent) {
        if (s.frontier != null)
          return s;
      }
      return null;
    }

    Scope enclosingClassScope() {
      for (Scope s = this; s != null; s = s.parent) {
        if (s.frontier instanceof ELNode.CLASSDEF)
          return s;
      }
      return null;
    }

    IRClass enclosingClass() {
      for (Scope s = this; s != null; s = s.parent) {
        if (s.frontier instanceof ELNode.CLASSDEF)
          return s.frontier.symbol.clazz;
      }
      return null;
    }

    boolean isClassScope() {
      return frontier instanceof ELNode.CLASSDEF;
    }

    boolean isLambdaScope() {
      return frontier instanceof ELNode.LAMBDA;
    }

    boolean isMemberProcedureScope() {
      Scope s = enclosingScope();
      return s != null && s.isLambdaScope() && s.parent.isClassScope();
    }

    boolean isStaticMemberProcedureScope() {
      Scope s = enclosingScope();
      return s != null && s.isLambdaScope() && s.parent.isClassScope() &&
             s.frontier.symbol.isStatic();
    }

    boolean isInstanceMemberProcedureScope() {
      Scope s = enclosingScope();
      return s != null && s.isLambdaScope() && s.parent.isClassScope() &&
             !s.frontier.symbol.isStatic();
    }

    boolean isStaticScope() {
      Scope s = enclosingScope();
      while (s != null) {
        if (s.isLambdaScope() && s.parent.isClassScope() &&
            s.frontier.symbol.isStatic())
          return true;
        s = s.parent;
      }
      return false;
    }
  }

  /*--------------------------------------------------------------------------*/

  private Scope current = null;
  private final List<ParseError> errors = new ArrayList<>();

  void enterScope(ELNode node) {
    enterScope(node, null);
  }

  void enterProgramScope() {
    enterScope(null);
    current.programScope = true;
  }

  void enterScope(ELNode node, ELNode fresh) {
    int depth = current == null ? 0 : current.depth + 1;
    int startSlot = fresh != null || current == null ? 0 : current.nextSlot;
    current = new Scope(current, depth, fresh, startSlot);
    if (node != null)
      node.scope = current;
  }

  void leaveScope() {
    assert current != null;
    Scope parent = current.parent;
    if (parent != null && current.frontier == null)
      parent.maxSlots = Math.max(parent.maxSlots, current.maxSlots);
    current = parent;
  }

  public Scope currentScope() {
    return current;
  }

  Symbol define(ELNode.DEFINE def) {
    assert current != null;
    Symbol sym = new Symbol(current, def);

    if (current.isTopLevel() || current.isClassScope()) {
      // Always put top level defined variable and class member variables
      // in global context.
      sym.captured = true;
    } else {
      // Allocate local slot for nested scope.
      sym.slot = current.nextSlot++;
      current.maxSlots++;
    }

    current.put(def.id, sym);
    return sym;
  }

  void undef(Symbol sym) {
    current.remove(sym.name);
  }

  void skipSlot() {
    // Skip slot for wildcard function parameter
    current.nextSlot++;
    current.maxSlots++;
  }

  Symbol lookup(String name) {
    return current.lookup(name);
  }

  Symbol lookupLocal(String name) {
    return current.get(name);
  }

  void addError(int pos, String message) {
    errors.add(new ParseError(pos, message, null));
  }

  void addRedefinition(String id, int pos, int previousPos) {
    addError(pos, _T(EL_REDEFINED_IDENTIFIER, id, Position.line(previousPos)));
  }

  public List<ParseError> getErrors() {
    return errors;
  }
}
