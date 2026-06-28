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
        List<ELNode> defs = program.getDefinitions();
        List<ELNode> exps = program.getExpressions();

        table.enterScope("program");
        if (defs != null) {
            for (ELNode def : defs)
                walkDefinition(def, table);
        }
        if (exps != null) {
            for (ELNode exp : exps)
                walkExpression(exp, table);
        }
        // Don't leave program scope — it's the root
        return table;
    }

    // ── Definition walking ──

    private static void walkDefinition(ELNode node, SymbolTable table) {
        if (node instanceof ELNode.DEFINE def) {
            SymbolTable.SymbolInfo info = table.define(def.id);

            if (def.expr instanceof ELNode.LAMBDA lam) {
                // Function definition: enter scope for params + body
                table.enterScope("fn:" + def.id);
                for (ELNode.DEFINE param : lam.vars)
                    table.define(param.id);
                walkExpression(lam.body, table);
                table.leaveScope();
            } else if (def.expr instanceof ELNode.CLASSDEF) {
                // Class definition: placeholder for future compilation
                table.enterScope("class:" + def.id);
                // TODO: walk class members when CLASSDEF compilation is ready
                table.leaveScope();
            } else if (def.expr != null) {
                // Value definition: walk the expression for nested scopes
                walkExpression(def.expr, table);
            }
        } else if (node != null) {
            walkExpression(node, table);
        }
    }

    // ── Expression walking (scope-creating constructs) ──

    private static void walkExpression(ELNode node, SymbolTable table) {
        if (node == null) return;

        if (node instanceof ELNode.LAMBDA lam) {
            table.enterScope("lambda");
            for (ELNode.DEFINE param : lam.vars)
                table.define(param.id);
            walkExpression(lam.body, table);
            table.leaveScope();

        } else if (node instanceof ELNode.WHILE wh) {
            table.enterScope("while");
            walkExpression(wh.body, table);
            table.leaveScope();
            walkExpression(wh.cond, table);

        } else if (node instanceof ELNode.REPEAT rp) {
            table.enterScope("repeat");
            walkExpression(rp.body, table);
            table.leaveScope();
            walkExpression(rp.cond, table);

        } else if (node instanceof ELNode.FOR fr) {
            table.enterScope("for");
            if (fr.init != null) for (ELNode i : fr.init) walkExpression(i, table);
            walkExpression(fr.body, table);
            table.leaveScope();
            if (fr.cond != null) walkExpression(fr.cond, table);
            if (fr.step != null) for (ELNode s : fr.step) walkExpression(s, table);

        } else if (node instanceof ELNode.FOREACH fe) {
            table.enterScope("foreach");
            if (fe.var != null) table.define(fe.var.id);
            walkExpression(fe.body, table);
            table.leaveScope();

        } else if (node instanceof ELNode.COND cond) {
            walkExpression(cond.left, table);
            walkExpression(cond.right, table);

        } else if (node instanceof ELNode.COMPOUND cmp) {
            for (ELNode e : cmp.exps) walkExpression(e, table);

        } else if (node instanceof ELNode.MATCH match) {
            // Each case body gets its own scope for pattern variables
            for (ELNode.CASE c : match.alts) {
                table.enterScope("case");
                collectCaseBindings(c, table);
                if (c.bodies != null) {
                    for (ELNode body : c.bodies)
                        walkExpression(body, table);
                }
                table.leaveScope();
            }
            if (match.deflt != null) walkExpression(match.deflt, table);

        } else if (node instanceof ELNode.DEFINE def) {
            // Nested define inside a block
            walkDefinition(node, table);

        } else if (node instanceof ELNode.BLOCK) {
            // Don't enter nested blocks (they share the parent scope in ELite)
        }
        // Other expression types don't create scopes
    }

    /** Register pattern variable names from a CASE. */
    private static void collectCaseBindings(ELNode.CASE c, SymbolTable table) {
        if (c.patterns == null) return;
        for (ELNode.Pattern p : c.patterns) {
            collectPatternBindings((ELNode) p, table);
        }
    }

    private static void collectPatternBindings(ELNode pat, SymbolTable table) {
        if (pat instanceof ELNode.DEFINE def) {
            if (!"_".equals(def.id))
                table.define(def.id);
        } else if (pat instanceof ELNode.TUPLE t) {
            for (ELNode e : t.elems)
                collectPatternBindings(e, table);
        } else if (pat instanceof ELNode.CONS cons) {
            collectPatternBindings(cons.head, table);
            collectPatternBindings(cons.tail, table);
        } else if (pat instanceof ELNode.OR or) {
            collectPatternBindings(or.left, table);
            collectPatternBindings(or.right, table);
        }
        // NOT, NIL, NEW, NUMBER, etc. — no bindings
    }
}
