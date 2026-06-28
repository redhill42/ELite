package org.elite.ir;

import java.util.*;

import org.elite.eval.ELProgram;
import org.elite.parser.*;

/**
 * Two-pass analysis: walks an ELite program's AST to build a
 * {@link SymbolTable} with scope nesting, capture relationships,
 * and function prototypes.
 *
 * <p>Pass 1 — scope analysis, variable renaming, function prototypes.
 * <p>Pass 2 — thunk-aware capture analysis: when a known function is
 * called with lazy-param arguments, those argument expressions are
 * treated as thunk bodies and their free-variable captures are marked.
 */
public final class SymbolTableBuilder {
    private SymbolTableBuilder() {}

    /**
     * Build a symbol table for the given program (two passes).
     */
    public static SymbolTable build(ELProgram program) {
        SymbolTable table = new SymbolTable();

        // ── Pass 1: scope analysis + rename + function prototypes ──
        Pass1Visitor pass1 = new Pass1Visitor(table);
        table.enterScope("program");
        for (ELNode def : program.getDefinitions())
            def.accept(pass1);
        for (ELNode exp : program.getExpressions())
            exp.accept(pass1);

        // ── Pass 2: thunk-aware capture analysis ──
        Pass2Visitor pass2 = new Pass2Visitor(table);
        for (ELNode def : program.getDefinitions())
            def.accept(pass2);
        for (ELNode exp : program.getExpressions())
            exp.accept(pass2);

        return table;
    }

    /** Build a symbol table for a single expression or subtree. */
    public static SymbolTable build(ELNode node) {
        SymbolTable table = new SymbolTable();
        Pass1Visitor pass1 = new Pass1Visitor(table);
        table.enterScope("expr");
        node.accept(pass1);
        // Pass 2 for single nodes
        Pass2Visitor pass2 = new Pass2Visitor(table);
        node.accept(pass2);
        return table;
    }

    /**
     * Pass 1: scope analysis, variable renaming, function prototype registration.
     * After this pass, all DEFINE/IDENT nodes carry symbol annotations,
     * and function symbols have {@code paramFlags} set.
     */
    static class Pass1Visitor extends DefaultVisitor {
        final SymbolTable table;

        Pass1Visitor(SymbolTable table) {
            this.table = table;
        }

        public void visit(ELNode.DEFINE e) {
            var info = table.define(e.id);
            e.symbol = info;
            e.id = info.mangledName;

            if (e.expr instanceof ELNode.LAMBDA) {
                var fnInfo = table.lookup(e.id);
                if (fnInfo != null)
                    fnInfo.funcPoolIdx = -2; // pending allocation
            }

            scan(e.expr);
        }

        public void visit(ELNode.LAMBDA e) {
            if (e instanceof ELNode.BLOCK) {
                // Don't enter nested blocks (they share the parent scope in ELite)
                return;
            }

            table.enterScope(e.name != null ? "fn:"+e.name : "lambda", true);
            for (ELNode.DEFINE param : e.vars) {
                if (!"_".equals(param.id)) {
                    var pi = table.define(param.id);
                    param.symbol = pi;
                    param.id = pi.mangledName;
                }
            }
            scan(e.body);

            // Compute param flags for this function's prototype.
            // Stored on the function's SymbolInfo (looked up from the
            // enclosing scope) so Pass 2 can identify lazy params.
            if (e.name != null) {
                var fnInfo = table.lookup(e.name);
                if (fnInfo != null) {
                    int[] pFlags = new int[e.vars.length];
                    for (int i = 0; i < e.vars.length; i++) {
                        if (e.vars[i].type != null)
                            pFlags[i] |= IRFunction.PARAM_EXPLICIT_TYPE;
                        if (!e.vars[i].immediate)
                            pFlags[i] |= IRFunction.PARAM_LAZY;
                    }
                    fnInfo.paramFlags = pFlags;
                }
            }

            // After walking the lambda body, determine which variables
            // from outer scopes are captured by this lambda.
            markCapturedIn(table, e.body, new HashSet<>());
            table.leaveScope();
        }

        public void visit(ELNode.CLASSDEF e) {
            // Class definition: fresh scope (separate compilation unit)
            table.enterScope("class:" + e.id, true);
            // TODO: walk class members when CLASSDEF compilation is ready
            table.leaveScope();
        }

        public void visit(ELNode.IDENT e) {
            var info = table.lookup(e.id);
            if (info != null) {
                e.symbol = info;
                e.id = info.mangledName;
            }
        }

        public void visit(ELNode.WHILE e) {
//            scan(e.cond);
            table.enterScope("while");
            scan(e.body);
            table.leaveScope();
        }

        public void visit(ELNode.REPEAT e) {
            table.enterScope("repeat");
            scan(e.body);
            table.leaveScope();
            scan(e.cond);
        }

        public void visit(ELNode.FOR e) {
            table.enterScope("for");
            super.visit(e);
            table.leaveScope();
        }

        public void visit(ELNode.FOREACH e) {
            table.enterScope("foreach");
            super.visit(e);
            table.leaveScope();
        }

        public void visit(ELNode.COND e) {
            scan(e.cond);
            table.enterScope("if-body");
            scan(e.left);
            table.leaveScope();
            if (e.right != null) {
                table.enterScope("if-else");
                scan(e.right);
                table.leaveScope();
            }
        }

        public void visit(ELNode.COMPOUND e) {
            table.enterScope("compound");
            super.visit(e);
            table.leaveScope();
        }

        public void visit(ELNode.MATCH e) {
            // Each case body gets its own scope for pattern variables.
            scan(e.args);
            for (ELNode.CASE c : e.alts) {
                table.enterScope("case");
                collectCaseBindings(c);
                scan(c.guards);
                scan(c.bodies);
                table.leaveScope();
            }
            scan(e.deflt);
        }

        /** Register pattern variable names from CASE. */
        private void collectCaseBindings(ELNode.CASE c) {
            if (c.patterns == null)
                return;
            for (ELNode.Pattern p : c.patterns)
                collectPatternBindings((ELNode)p);
        }

        private void collectPatternBindings(ELNode pat) {
            if (pat == null)
                return;

            if (pat instanceof ELNode.DEFINE def) {
                if (!"_".equals(def.id)) {
                    if (table.currentScope().get(def.id) == null) {
                        var si = table.define(def.id);
                        def.symbol = si;
                        def.id = si.mangledName;
                    } else {
                        // Already defined in this scope (OR duplicate) - link to existing
                        var si = table.currentScope().get(def.id);
                        def.symbol = si;
                        def.id = si.mangledName;
                    }
                }
            } else if (pat instanceof ELNode.TUPLE t) {
                for (ELNode e : t.elems)
                    collectPatternBindings(e);
            } else if (pat instanceof ELNode.CONS cons) {
                collectPatternBindings(cons.head);
                collectPatternBindings(cons.tail);
            } else if (pat instanceof ELNode.MAP m) {
                for (ELNode v : m.values)
                    collectPatternBindings(v);
            } else if (pat instanceof ELNode.OR or) {
                collectPatternBindings(or.left);
                collectPatternBindings(or.right);
            } else if (pat instanceof ELNode.NEW) {
                // FIXME: handle data constructor
            }
        }
    }

    // ── Shared capture-marking utility (used by Pass 1 and Pass 2) ──

    /**
     * Walk a subtree (lambda body or thunk expression) and mark
     * outer-scope variables that are captured as {@code captured=true}
     * on their defining scope's SymbolInfo.
     */
    private static void markCapturedIn(SymbolTable table, ELNode node,
                                       Set<String> excludeNames) {
        node.accept(new DefaultVisitor() {
            public void visit(ELNode.IDENT e) {
                if (excludeNames.contains(e.id))
                    return;
                var info = table.lookup(e.id);
                if (info != null && table.isOuter(e.id)) {
                    for (SymbolTable.Scope s : table.allScopes()) {
                        SymbolTable.SymbolInfo si = s.get(e.id);
                        if (si != null && si == info) {
                            si.captured = true;
                            break;
                        }
                    }
                }
            }

            public void visit(ELNode.LAMBDA e) {
                Set<String> nestedExcludes = new HashSet<>(excludeNames);
                for (ELNode.DEFINE v : e.vars)
                    nestedExcludes.add(v.id);
                markCapturedIn(table, e.body, nestedExcludes);
            }
        });
    }

    // ── Pass 2: thunk-aware capture analysis ──

    /**
     * Pass 2: walks the AST a second time, now with full knowledge of
     * function prototypes (paramFlags).  When a call to a known function
     * passes an argument to a lazy parameter, that argument expression
     * is treated as a thunk body — any outer-scope variables it
     * references are marked as captured.
     */
    static class Pass2Visitor extends DefaultVisitor {
        private final SymbolTable table;

        Pass2Visitor(SymbolTable table) {
            this.table = table;
        }

        public void visit(ELNode.APPLY e) {
            // If the target is a known function with lazy params,
            // mark variables captured by the lazy-arg expressions.
            if (e.right instanceof ELNode.IDENT id) {
                var fnInfo = table.lookup(id.id);
                if (fnInfo != null && fnInfo.paramFlags != null) {
                    int[] pFlags = fnInfo.paramFlags;
                    for (int i = 0; i < e.args.length && i < pFlags.length; i++) {
                        if ((pFlags[i] & IRFunction.PARAM_LAZY) != 0) {
                            // This arg becomes a thunk — its free vars
                            // must be marked as captured in the enclosing scope.
                            markCapturedIn(table, e.args[i], new HashSet<>());
                        }
                    }
                }
            }
            // Continue scanning children (the thunk expressions themselves
            // may contain further APPLY nodes with lazy args).
            super.visit(e);
        }
    }
}