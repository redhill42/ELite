package org.elite.ir;

import java.util.*;

/**
 * Stack-based symbol table for IR compilation scope management.
 *
 * <p>Each scope corresponds to a variable-defining context
 * (function body, control-flow block, match case, etc.).
 * Scopes nest: lookup searches from innermost outward.
 *
 * <p>When a variable shadows an outer one, the inner variable
 * is renamed (e.g. {@code n} → {@code n$1}) at the AST level.
 * This prevents STORE_GLOBAL/DEFINE_GLOBAL name conflicts.
 */
public final class SymbolTable {

    /** Information about a single symbol. */
    public static class SymbolInfo {
        public final String originalName;  // as written in source
        public String mangledName;         // renamed if shadowed
        public int slot = -1;              // IR locals[] index
        public int flags;                  // param flags (PARAM_LAZY, etc.)
        public boolean captured;           // captured by an inner closure?
        public IRFunction func;            // known function: the compiled IRFunction
        public int funcPoolIdx = -1;       // constant pool index of the IRFunction

        SymbolInfo(String name) {
            this.originalName = name;
            this.mangledName = name;
        }

        public boolean isFunction()      { return func != null || funcPoolIdx >= 0; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(mangledName);
            if (!mangledName.equals(originalName))
                sb.append("(=").append(originalName).append(")");
            sb.append(":slot").append(slot);
            if (captured) sb.append(" captured");
            if (isFunction()) sb.append(" fn");
            if ((flags & 4) != 0) sb.append(" lazy");
            return sb.toString();
        }
    }

    /** A single scope layer holding name → symbol mappings. */
    public static class Scope {
        final String label;  // for debugging
        final int depth;     // nesting depth (0 = root)
        final Map<String, SymbolInfo> symbols = new LinkedHashMap<>();
        int nextSlot;        // next slot to allocate within this scope
        int slotBase;        // offset: actual slot = slotBase + localSlot

        Scope(String label, int depth, int slotBase) {
            this.label = label;
            this.depth = depth;
            this.slotBase = slotBase;
            this.nextSlot = slotBase;
        }

        SymbolInfo get(String name) { return symbols.get(name); }

        SymbolInfo put(String name, SymbolInfo info) {
            symbols.put(name, info);
            return info;
        }

        public Collection<SymbolInfo> entries() { return symbols.values(); }
        public boolean isEmpty() { return symbols.isEmpty(); }

        @Override
        public String toString() { return "Scope[" + label + "]"; }
    }

    private final Deque<Scope> stack = new ArrayDeque<>();
    private final List<Scope> allScopes = new ArrayList<>(); // retained for debugging
    private int renameCounter;

    /** Current scope depth (0 = top-level/outermost). */
    public int depth() { return stack.size(); }

    /**
     * Push a new scope.
     * @param label debug label
     * @param fresh if true, start slot counter from 0 (for new IRFunctions
     *              like lambdas); if false, inherit from parent (for control
     *              flow scopes within the same function)
     */
    public Scope enterScope(String label, boolean fresh) {
        int base = fresh ? 0 : (stack.isEmpty() ? 0 : stack.peek().nextSlot);
        Scope s = new Scope(label, stack.size(), base);
        stack.push(s);
        allScopes.add(s);
        return s;
    }

    /** Push a new scope inheriting the parent's slot counter. */
    public Scope enterScope(String label) {
        return enterScope(label, false);
    }

    /** Pop and discard the top scope. */
    public Scope leaveScope() {
        return stack.pop();
    }

    /** Current (innermost) scope. */
    public Scope currentScope() {
        return stack.peek();
    }

    /** All scopes (including left ones) for iteration. */
    List<Scope> allScopes() {
        return allScopes;
    }

    /**
     * Define a symbol in the current scope, allocating a slot index.
     * If the name already exists in an outer scope, rename it to avoid
     * shadowing conflicts in the evalContext.
     */
    public SymbolInfo define(String name) {
        Scope current = stack.peek();
        SymbolInfo info = new SymbolInfo(name);

        // Allocate slot: increments this scope's counter.
        // On leaveScope, the parent's counter is UNCHANGED, so sibling
        // scopes reuse the same slot range.
        info.slot = current.nextSlot++;

        // Check if this name exists in any outer scope
        boolean shadowed = false;
        Iterator<Scope> it = stack.iterator();
        it.next(); // skip current scope
        while (it.hasNext()) {
            if (it.next().get(name) != null) {
                shadowed = true;
                break;
            }
        }

        if (shadowed) {
            info.mangledName = name + "$" + (++renameCounter);
        }

        current.put(name, info);
        return info;
    }

    /**
     * Look up a symbol from innermost to outermost scope.
     * @return the SymbolInfo, or null if not found.
     */
    public SymbolInfo lookup(String name) {
        for (Scope s : stack) {
            SymbolInfo info = s.get(name);
            if (info != null) return info;
        }
        return null;
    }

    /** Check if any outer scope contains this name. */
    public boolean isOuter(String name) {
        Iterator<Scope> it = stack.iterator();
        if (it.hasNext()) it.next(); // skip current
        while (it.hasNext()) {
            if (it.next().get(name) != null) return true;
        }
        return false;
    }

    // ── Debug dump ──

    /** Dump the full symbol table for debugging. */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Symbol Table (").append(allScopes.size()).append(" scopes) ===\n");
        boolean[] active = new boolean[allScopes.size()];
        int idx = 0;
        for (Scope s : stack) {
            active[allScopes.indexOf(s)] = true;
        }
        for (Scope s : allScopes) {
            for (int i = 0; i < s.depth; i++) sb.append("  ");
            sb.append("[").append(s.depth).append("] ").append(s.label);
            if (!stack.contains(s)) sb.append(" (left)");
            sb.append("\n");
            for (SymbolInfo si : s.entries()) {
                for (int i = 0; i <= s.depth; i++) sb.append("  ");
                sb.append(si).append("\n");
            }
        }
        return sb.toString();
    }
}
