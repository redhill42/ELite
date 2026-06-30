package org.elite.ir;

import org.elite.parser.ELNode;

import java.util.*;

public class SymbolTable {

    /** Information about a single symbol. */
    public static class Symbol {
        public final Scope scope;
        public final String name;       // symbol name from source
        public String mangledName;      // renamed if shadowed
        public int slot = -1;           // IR locals[] index
        public int flags;               // param flags (PARAM_EXPLICIT_TYPE, etc.)
        public boolean captured;        // captured by an inner closure?
        public IRFunction func;         // known function: the compiled IRFunction
        public ELNode node = null;      // the node that provide symbol definition info

        Symbol(Scope scope, String name) {
            this.scope = scope;
            this.name = name;
            this.mangledName = name;
        }

        public boolean isFunction() {
            return func != null;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(mangledName);
            if (!mangledName.equals(name))
                sb.append("(=").append(name).append(")");
            sb.append(": slot ").append(slot);
            if (captured)
                sb.append(" captured");
            if (isFunction())
                sb.append(" fn");
            return sb.toString();
        }
    }

    /** A single scope layer holding name -> symbol mappings. */
    public static class Scope {
        final Scope parent;   // chained to direct enclosing scope
        final String label; // for debugging
        final int depth;    // nesting depth (0 = root)
        final Map<String, Symbol> symbols = new LinkedHashMap<>();
        boolean fresh;      // a fresh closure scope
        int nextSlot;       // currently allocated slots
        int maxSlots;       // max slot index used across this scope and all nested sub-scopes
        int renameCounter;  // the counter used to generate mangled name

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

        public Collection<Symbol> entries() {
            return symbols.values();
        }

        public boolean isEmpty() {
            return symbols.isEmpty();
        }

        @Override
        public String toString() {
            return "Scope[" + label + "]";
        }
    }

    private Scope current = null;
    private final List<Scope> allScopes = new ArrayList<>(); // for debugging

    public Scope enterScope(String label, ELNode node) {
        boolean fresh = node instanceof ELNode.LAMBDA;
        int depth = current == null ? 0 : current.depth + 1;
        int startSlot = fresh || current == null ? 0 : current.nextSlot;
        current = new Scope(current, label, depth, fresh, startSlot);
        allScopes.add(current);
        if (node != null)
            node.scope = current;
        return current;
    }

    public Scope leaveScope() {
        assert current != null;
        Scope parent = current.parent;
        if (parent != null && !current.fresh)
            parent.maxSlots = Math.max(parent.maxSlots, current.maxSlots);
        current = parent;
        return parent;
    }

    public Scope currentScope() {
        return current;
    }

    public Symbol define(String name) {
        assert current != null;
        Symbol sym = new Symbol(current, name);

        // Allocate slot: increments this scope's counter.
        // On leaveScope, the parent's counter is UNCHANGED, so sibling
        // scopes reuse the same slot range.
        sym.slot = current.nextSlot++;
        current.maxSlots++;

        // Check if this name exists in any outer scope.
        if (current.lookupOuter(name) != null) {
            sym.mangledName = "*" + name + "$" + (++current.renameCounter) + "*";
        }

        current.put(name, sym);
        return sym;
    }

    public Symbol lookup(String name) {
        return current.lookup(name);
    }

    public Symbol lookupLocal(String name) {
        return current.get(name);
    }

    public Symbol lookupOuter(String name) {
        return current.lookupOuter(name);
    }

    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Symbol Table (").append(allScopes.size()).append(" scopes) ===\n");
        for (Scope s : allScopes) {
            for (int i = 0; i < s.depth; i++) sb.append("  ");
            sb.append("[").append(s.depth).append("] ").append(s.label);
            sb.append("\n");
            for (Symbol si : s.entries()) {
                for (int i = 0; i <= s.depth; i++) sb.append("  ");
                sb.append(si).append("\n");
            }
        }
        return sb.toString();
    }
}
