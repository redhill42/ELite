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
        table.enterScope("program");
        for (ELNode def : program.getDefinitions())
            def.accept(visitor);
        for (ELNode exp : program.getExpressions())
            exp.accept(visitor);
        return table;
    }

    /** Build a symbol table for a single expression or subtree. */
    public static SymbolTable build(ELNode node) {
        SymbolTable table = new SymbolTable();
        BuilderVisitor visitor = new BuilderVisitor(table);
        table.enterScope("expr");
        node.accept(visitor);
        return table;
    }

    /**
     * Pass 1: scope analysis, variable renaming, function prototype registration.
     * After this pass, all DEFINE/IDENT nodes carry symbol annotations,
     * and function symbols have {@code paramFlags} set.
     */
    static class BuilderVisitor extends DefaultVisitor {
        final SymbolTable table;

        BuilderVisitor(SymbolTable table) {
            this.table = table;
        }

        public void visit(ELNode.DEFINE e) {
            var info = table.define(e.id);
            if (e.expr instanceof ELNode.LAMBDA)
                info.funcPoolIdx = -2; // pending allocation
            e.symbol = info;
            e.id = info.mangledName;
            scan(e.expr);

            // Create a IRFunction skeleton.
            if (e.expr instanceof ELNode.LAMBDA lam) {
                e.symbol.func = new IRFunction(e.id, lam.vars.length);
                lam.symbol = e.symbol;
            }
        }

        public void visit(ELNode.LAMBDA e) {
            e.scope = table.enterScope(e.name != null ? "fn:"+e.name : "lambda", true);
            for (ELNode.DEFINE param : e.vars) {
                if (!"_".equals(param.id)) {
                    var pi = table.define(param.id);
                    param.symbol = pi;
                    param.id = pi.mangledName;
                }
            }
            scan(e.body);
            table.leaveScope();

            // After walking the lambda body, determine which variables
            // from outer scopes are captured by this lambda.
            markCaptured(e.scope, e.body);
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
            scan(e.cond);
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
            if (e.local) {
                scan(e.init);
                scan(e.cond);
                table.enterScope("for");
                scan(e.body);
                table.leaveScope();
                scan(e.step);
            } else {
                table.enterScope("for");
                super.visit(e);
                table.leaveScope();
            }
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

        public void visit(ELNode.TRY e) {
            table.enterScope("try");
            scan(e.body);
            table.leaveScope();
            if (e.handlers != null) {
                for (ELNode h : e.handlers) {
                    table.enterScope("catch");
                    scan(h);
                    table.leaveScope();
                }
            }
            if (e.finalizer != null) {
                table.enterScope("finally");
                scan(e.finalizer);
                table.leaveScope();
            }
        }

        public void visit(ELNode.CATCH e) {
            table.enterScope("catch");
            var sym = table.define(e.var);
            e.symbol = sym;
            e.var = sym.mangledName;
            scan(e.body);
            table.leaveScope();
        }

        public void visit(ELNode.SYNCHRONIZED e) {
            scan(e.exp);
            table.enterScope("synchronized");
            scan(e.body);
            table.leaveScope();
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
         * Walk a subtree (lambda body) and mark outer-scope variables that
         * are captured as {@code captured=true} on their defining scope's
         * SymbolInfo.
         */
        private void markCaptured(SymbolTable.Scope scope, ELNode node) {
            node.accept(new DefaultVisitor() {
                public void visit(ELNode.IDENT e) {
                    var sym = scope.lookupOuter(e.id);
                    if (sym != null)
                        sym.captured = true;
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
                    var sym = table.define(def.id);
                    def.symbol = sym;
                    def.id = sym.mangledName;
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