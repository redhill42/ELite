package org.elite.ir;

import org.elite.parser.ELNode;

import java.util.*;

public class SymbolTable {

    /** Information about a single symbol. */
    public static class Symbol {
        public final Scope scope;       // the scope of this symbol defined
        public final String name;       // symbol name from source
        public final ELNode.DEFINE def; // the node that provide symbol definition info

        public int slot = -1;           // IR locals[] index
        public boolean captured;        // captured by an inner closure?
        public IRFunction func;         // known function: the compiled IRFunction

        Symbol(Scope scope, ELNode.DEFINE def) {
            this.scope = scope;
            this.name = def.id;
            this.def = def;
        }

        public boolean isFunction() {
            return func != null;
        }
    }

    /** A single scope layer holding name -> symbol mappings. */
    public static class Scope {
        final Scope parent; // chained to direct enclosing scope
        final String label; // for debugging
        final int depth;    // nesting depth (0 = root)
        final Map<String, Symbol> symbols = new LinkedHashMap<>();
        boolean fresh;      // a fresh closure scope
        int nextSlot;       // currently allocated slots
        int maxSlots;       // max slot index used across this scope and all nested sub-scopes

        Scope(Scope parent, String label, int depth, boolean fresh, int startSlot) {
            this.parent = parent;
            this.label = label;
            this.depth = depth;
            this.fresh = fresh;
            this.nextSlot = startSlot;
            this.maxSlots = startSlot;
        }

        public int depth() {
            return depth;
        }

        public boolean isTopLevel() {
            return parent == null;
        }

        public boolean hasCaptures() {
            for (Symbol sym : symbols.values()) {
                if (sym.captured)
                    return true;
            }
            return false;
        }

        void put(String name, Symbol sym) {
            symbols.put(name, sym);
        }

        Symbol get(String name) {
            return symbols.get(name);
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
                if (s.fresh)
                    return s;
            }
            return null;
        }
    }

    public record Redefinition(String id, int pos, int previousPos) {}

    private Scope current = null;
    private final List<Redefinition> redefinitions = new ArrayList<>();

    void enterScope(String label, ELNode node) {
        boolean fresh = node instanceof ELNode.LAMBDA;
        int depth = current == null ? 0 : current.depth + 1;
        int startSlot = fresh || current == null ? 0 : current.nextSlot;
        current = new Scope(current, label, depth, fresh, startSlot);
        if (node != null)
            node.scope = current;
    }

    void leaveScope() {
        assert current != null;
        Scope parent = current.parent;
        if (parent != null && !current.fresh)
            parent.maxSlots = Math.max(parent.maxSlots, current.maxSlots);
        current = parent;
    }

    public Scope currentScope() {
        return current;
    }

    Symbol define(ELNode.DEFINE def) {
        assert current != null;
        Symbol sym = new Symbol(current, def);

        if (current.isTopLevel()) {
            // Always put top level defined variable in global context.
            sym.captured = true;
        } else {
            // Allocate local slot for nested scope.
            sym.slot = current.nextSlot++;
            current.maxSlots++;
        }

        current.put(def.id, sym);
        return sym;
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

    void addRedefinition(String id, int pos, int previousPos) {
        redefinitions.add(new Redefinition(id, pos, previousPos));
    }

    public List<Redefinition> getRedefinitions() {
        return redefinitions;
    }
}
