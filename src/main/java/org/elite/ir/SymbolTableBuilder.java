package org.elite.ir;

import java.util.*;

import org.elite.eval.ELProgram;
import org.elite.parser.*;

/**
 * First-pass analysis: walks an ELite program's AST and builds a
 * {@link SymbolTable} recording all variable/function/class definitions
 * with their scope nesting and capture relationships.
 *
 * <p>This is a pure analysis pass — it does not emit IR and does not
 * modify the existing compilation pipeline.
 */
public final class SymbolTableBuilder {
    private SymbolTableBuilder() {}

    /**
     * Build a symbol table for the given program.
     */
    public static SymbolTable build(ELProgram program) {
        SymbolTable table = new SymbolTable();
        BuilderVisitor visitor = new BuilderVisitor(table);

        table.enterScope("program");
        for (ELNode def : program.getDefinitions())
            def.accept(visitor);
        for (ELNode exp : program.getExpressions())
            exp.accept(visitor);
        // Don't leave program scope - it's the root
        return table;
    }

    static class BuilderVisitor extends DefaultVisitor {
        private final SymbolTable table;

        BuilderVisitor(SymbolTable table) {
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
            // After walking the lambda body, determine which variables
            // from outer scopes are captured by this lambda.
            markCaptured(e.body);
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

        /**
         * Walk the lambda body and mark outer-scope variables that are
         * referenced (captured) by this closure.  Recurses into nested
         * lambdas, excluding their params, so that transitive captures
         * (e.g. list comprehensions referencing enclosing function params)
         * are properly recorded.
         */
        private void markCaptured(ELNode node) {
            markCapturedIn(node, new HashSet<>());
        }

        private void markCapturedIn(ELNode node, Set<String> excludeNames) {
            node.accept(new DefaultVisitor() {
                public void visit(ELNode.IDENT e) {
                    if (excludeNames.contains(e.id))
                        return;
                    var info = table.lookup(e.id);
                    if (info != null && table.isOuter(e.id)) {
                        // Find the actual defining scope's entry and mark it
                        for (SymbolTable.Scope s : table.allScopes()) {
                            SymbolTable.SymbolInfo si = s.get(e.id);
                            if (si != null && si == info) {
                                si.captured = true;
                                break;
                            }
                        }
                    }
                }

                // Recurse into nested lambdas, excluding their own params
                public void visit(ELNode.LAMBDA e) {
                    Set<String> nestedExcludes = new HashSet<>(excludeNames);
                    for (ELNode.DEFINE v : e.vars)
                        nestedExcludes.add(v.id);
                    markCapturedIn(e.body, nestedExcludes);
                }
            });
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
}