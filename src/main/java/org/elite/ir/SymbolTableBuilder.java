package org.elite.ir;

import java.util.*;

import org.elite.eval.ELProgram;
import org.elite.parser.*;

/**
 * Walks an ELite program's AST and builds a {@link SymbolTable}
 * recording all variable/function/class definitions with their
 * scope nesting and capture relationships.
 */
public final class SymbolTableBuilder {
    private SymbolTableBuilder() {}

    /**
     * Build a symbol table for the given program.
     */
    public static SymbolTable build(ELProgram program) {
        SymbolTable table = new SymbolTable();
        BuilderVisitor visitor = new BuilderVisitor(table);
        table.enterScope("program", null);
        for (ELNode def : program.getDefinitions())
            def.accept(visitor);
        for (ELNode exp : program.getExpressions())
            exp.accept(visitor);
        visitor.finish();
        return table;
    }

    /** Build a symbol table for a single expression or subtree. */
    public static SymbolTable build(ELNode node) {
        SymbolTable table = new SymbolTable();
        BuilderVisitor visitor = new BuilderVisitor(table);
        table.enterScope("expr", null);
        node.accept(visitor);
        visitor.finish();
        return table;
    }

    /**
     * Scope analysis, variable renaming, function prototype registration.
     * After this pass, all DEFINE/IDENT nodes carry symbol annotations,
     * and function symbols have {@code paramFlags} set.
     */
    static class BuilderVisitor extends DefaultVisitor {
        final SymbolTable table;
        ELNode.LAMBDA currentFn;

        record Undefined(ELNode.IDENT var, SymbolTable.Scope scope) {}
        List<Undefined> undefined = new ArrayList<>();

        // FIXME: mark all variables captured in trampolined.
        ELNode.Visitor trampolineFixup = new DefaultVisitor() {
            public void visit(ELNode.IDENT var) {
                if (var.symbol != null && !var.symbol.captured) {
                    for (SymbolTable.Scope s = table.currentScope().parent;
                         s != null; s = s.parent) {
                        if (s == var.symbol.scope) {
                            var.symbol.captured = true;
                            return;
                        }
                    }
                }
            }
        };

        BuilderVisitor(SymbolTable table) {
            this.table = table;
            this.currentFn = null;
        }

        public void visit(ELNode.DEFINE e) {
            if ("_".equals(e.id))
                return;

            var sym = table.define(e.id);
            e.symbol = sym;

            if (e.expr instanceof ELNode.LAMBDA lam) {
                // Create a IRFunction skeleton.
                sym.func = new IRFunction(e.id, lam.vars.length);
                sym.node = e.expr;
                lam.symbol = e.symbol;
            } else if (e.expr instanceof ELNode.CLASSDEF cdef) {
                sym.node = e.expr;
                cdef.symbol = sym;
            } else {
                sym.node = e;
            }

            scan(e.expr);
        }

        public void visit(ELNode.LAMBDA e) {
            // For named lambda, a.k.a named let, add a definition.
            if (e.name != null && e.symbol == null) {
                var sym = table.define(e.name);
                e.symbol = sym;
                sym.func = new IRFunction(e.name, e.vars.length);
                sym.node = e;
            }

            ELNode.LAMBDA previousFn = currentFn;
            currentFn = e;
            table.enterScope(e.name != null ? "fn:" + e.name : "lambda", e);
            for (ELNode.DEFINE param : e.vars) {
                if ("_".equals(param.id)) {
                    table.skipSlot();
                } else {
                    param.symbol = table.define(param.id);
                }
            }
            // Lambda has its own evaluation context, no need to create redundant
            // context for compound scope.
            if (e.body instanceof ELNode.COMPOUND stmts) {
                scan(stmts.exps);
            } else {
                scan(e.body);
            }
            table.leaveScope();
            currentFn = previousFn;
        }

        public void visit(ELNode.CLASSDEF e) {
            // Class definition is not supported yet.
            table.enterScope("class", e);
            e.accept(trampolineFixup);
            table.leaveScope();
        }

        public void visit(ELNode.IDENT e) {
            var sym = table.lookup(e.id);
            if (sym != null) {
                e.symbol = sym;

                // Mark this variable is captured by enclosing lambda.
                if (currentFn != null && sym.node != currentFn &&
                    sym.scope.enclosingScope() != currentFn.scope) {
                    sym.captured = true;
                }
            } else {
                // Add to undefined table for later resolution.
                undefined.add(new Undefined(e, table.currentScope()));
            }
        }

        public void visit(ELNode.WHILE e) {
            scan(e.cond);
            table.enterScope("while", e.body);
            scan(e.body);
            table.leaveScope();
        }

        public void visit(ELNode.REPEAT e) {
            table.enterScope("repeat", e.body);
            scan(e.body);
            table.leaveScope();
            scan(e.cond);
        }

        public void visit(ELNode.FOR e) {
            if (e.local) {
                scan(e.init);
                scan(e.cond);
                table.enterScope("for", e.body);
                scan(e.body);
                table.leaveScope();
                scan(e.step);
            } else {
                table.enterScope("for", e);
                super.visit(e);
                table.leaveScope();
            }
        }

        public void visit(ELNode.FOREACH e) {
            table.enterScope("foreach", e);
            super.visit(e);
            table.leaveScope();
        }

        public void visit(ELNode.COND e) {
            scan(e.cond);
            table.enterScope("if", e.left);
            scan(e.left);
            table.leaveScope();
            if (e.right != null) {
                table.enterScope("if", e.right);
                scan(e.right);
                table.leaveScope();
            }
        }

        public void visit(ELNode.CATCH e) {
            table.enterScope("catch", e);
            e.symbol = table.define(e.var);
            scan(e.body);
            table.leaveScope();
        }

        public void visit(ELNode.COMPOUND e) {
            if (e.scope == null) {
                table.enterScope("compound", e);
                super.visit(e);
                table.leaveScope();
            } else {
                super.visit(e);
            }
        }

        public void visit(ELNode.MATCH e) {
            // Prescan patterns to find unsupported patterns.
            boolean unsupported = hasUnsupportedPattern(e);

            // Each case body gets its own scope for pattern variables.
            scan(e.args);
            for (ELNode.CASE c : e.alts) {
                table.enterScope("case", c);
                collectCaseBindings(c);
                scan(c.guards);
                scan(c.bodies);
                if (unsupported) {
                    if (c.guards != null) {
                        for (ELNode g : c.guards)
                            g.accept(trampolineFixup);
                    }
                    if (c.bodies != null) {
                        for (ELNode b : c.bodies) {
                            b.accept(trampolineFixup);
                        }
                    }
                }
                table.leaveScope();
            }

            if (e.deflt != null) {
                table.enterScope("case", e.deflt);
                scan(e.deflt);
                if (unsupported)
                    e.deflt.accept(trampolineFixup);
                table.leaveScope();
            }
        }

        private boolean hasUnsupportedPattern(ELNode.MATCH e) {
            boolean[] unsupported = new boolean[1];
            ELNode.Visitor v = new DefaultVisitor() {
                public void visit(ELNode.NEW e) {
                    unsupported[0] = true;
                }
            };

            for (ELNode.CASE c : e.alts) {
                for (ELNode.Pattern pat : c.patterns) {
                    ((ELNode)pat).accept(v);
                    if (unsupported[0])
                        return true;
                }
            }

            return false;
        }

        /** Register pattern variable names from CASE. */
        private void collectCaseBindings(ELNode.CASE c) {
            if (c.patterns == null)
                return;
            for (ELNode.Pattern p : c.patterns)
                collectPatternBindings((ELNode)p, true);
        }

        private void collectPatternBindings(ELNode pat, boolean bind) {
            if (pat == null)
                return;

            if (pat instanceof ELNode.DEFINE def) {
                if (!"_".equals(def.id)) {
                    if (bind) {
                        def.symbol = table.define(def.id);
                    } else {
                        var sym = table.lookupLocal(def.id);
                        assert sym != null;
                        def.symbol = sym;
                    }
                }
            } else if (pat instanceof ELNode.IDENT ident) {
                var sym = table.lookupLocal(ident.id);
                if (sym != null) {
                    ident.symbol = sym;
                }
            } else if (pat instanceof ELNode.TUPLE t) {
                for (ELNode e : t.elems)
                    collectPatternBindings(e, bind);
            } else if (pat instanceof ELNode.CONS cons) {
                collectPatternBindings(cons.head, bind);
                collectPatternBindings(cons.tail, bind);
            } else if (pat instanceof ELNode.MAP m) {
                for (ELNode v : m.values)
                    collectPatternBindings(v, bind);
            } else if (pat instanceof ELNode.OR or) {
                // Only left pattern needs binding, right pattern
                // should have same bindings as left, this is guaranteed
                // by Parser.
                collectPatternBindings(or.left, bind);
                collectPatternBindings(or.right, false);
            } else if (pat instanceof ELNode.NEW) {
                // FIXME: handle data constructor
            }
        }

        private void finish() {
            // Resolve forward referenced functions.
            for (Undefined undef : undefined) {
                SymbolTable.Symbol sym = undef.scope.lookup(undef.var.id);
                if (sym != null && (sym.node instanceof ELNode.LAMBDA ||
                                    sym.node instanceof ELNode.CLASSDEF)) {
                    undef.var.symbol = sym;
                }
            }
        }
    }
}