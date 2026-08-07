package org.elite.ir;

import org.elite.eval.ELProgram;
import org.elite.parser.DefaultVisitor;
import org.elite.parser.ELNode;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.elite.resources.Resources.*;

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
    table.enterScope(null);
    if (program.isStandalone()) // set local scope for standalone program
      table.enterScope(null);
    for (ELNode def : program.getDefinitions())
      def.accept(visitor);
    for (ELNode exp : program.getExpressions())
      exp.accept(visitor);
    if (program.isStandalone())
      table.leaveScope();
    visitor.finish();
    return table;
  }

  /**
   * Build a symbol table for a single expression or subtree.
   */
  public static SymbolTable build(ELNode node) {
    SymbolTable table = new SymbolTable();
    BuilderVisitor visitor = new BuilderVisitor(table);
    table.enterScope(null);
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
    SymbolTable.Scope enclosingScope;

    record Undefined(ELNode.IDENT var, SymbolTable.Scope scope, boolean call) {}
    List<Undefined> undefined = new ArrayList<>();

    BuilderVisitor(SymbolTable table) {
      this.table = table;
      this.enclosingScope = null;
    }

    public void visit(ELNode.DEFINE e) {
      if ("_".equals(e.id))
        return;

      var sym = table.lookupLocal(e.id);
      if (sym != null) {
        table.addRedefinition(e.id, e.pos, sym.def.pos);
        return;
      }

      sym = table.define(e);
      e.symbol = sym;

      // The special xmlns need global scope.
      if (e.id.equals("xmlns"))
        sym.captured = true;

      if (e.expr instanceof ELNode.LAMBDA fn) {
        // Create a IRFunction skeleton.
        IRClass owner;
        int modifiers = Modifier.PUBLIC;
        if (enclosingScope != null && enclosingScope.isClassScope()) {
          owner = enclosingScope.frontier.symbol.clazz;
          modifiers = e.meta != null ? e.meta.modifiers : Modifier.PUBLIC;
          if (e.id.equals(owner.name)) {
            // Remove constructor from symbol table because it is conflict
            // with class definition, but keep symbol for IRFunction skeleton.
            table.undef(sym);
          }
        } else {
          owner = table.currentScope().enclosingClass();
          if (owner == null || table.currentScope().isStaticScope())
            modifiers |= Modifier.STATIC;
        }
        sym.func = new IRFunction(owner, e.id, fn.vars.length,
                                  fn.varargs, modifiers);
        fn.symbol = sym;
      } else if (e.expr instanceof ELNode.CLASSDEF cdef) {
        // Create a IRClass skeleton.
        sym.clazz = new IRClass(e.id, cdef);
        cdef.symbol = sym;
      }

      scan(e.expr);
    }

    public void visit(ELNode.LAMBDA e) {
      SymbolTable.Scope previous = enclosingScope;
      table.enterScope(e, e);
      enclosingScope = table.currentScope();

      for (ELNode.DEFINE param : e.vars) {
        if ("_".equals(param.id)) {
          table.skipSlot();
        } else {
          param.symbol = table.define(param);
        }
        scan(param.expr);
      }

      // Lambda has its own evaluation context, no need to create redundant
      // context for compound scope.
      if (e.body instanceof ELNode.COMPOUND stmts) {
        scan(stmts.exps);
      } else {
        scan(e.body);
      }

      table.leaveScope();
      enclosingScope = previous;
    }

    public void visit(ELNode.CLASSDEF e) {
      SymbolTable.Scope previous = enclosingScope;
      table.enterScope(e, e);
      enclosingScope = table.currentScope();

      IRClass clazz = e.symbol.clazz;

      // Create internal class name for nested class. This includes inner class
      // and static nested class.
      String internalName = e.id;
      IRClass outer = table.currentScope().parent.enclosingClass();
      if (outer != null)
        internalName = outer.name + "$" + internalName;
      clazz.internalName = internalName;

      // Run default visitor to populate member symbols.
      super.visit(e);

      // Add an implicit this variable.
      table.define(new ELNode.DEFINE(e.pos, "this"));

      // Create AST for init procedures.
      clazz.clinit_proc = createClassInitProc(e);
      clazz.init_proc = createInitProc(e);

      table.leaveScope();
      enclosingScope = previous;
    }

    private ELNode.LAMBDA createClassInitProc(ELNode.CLASSDEF e) {
      List<ELNode> initBody = new ArrayList<>();
      ELNode.DEFINE initProc = null;
      for (ELNode.DEFINE var : e.cvars) {
        if (!(var.expr instanceof ELNode.LAMBDA) &&
            !(var.expr instanceof ELNode.CLASSDEF) &&
            !(var.expr instanceof ELNode.NULL)) {
          ELNode.IDENT ident = new ELNode.IDENT(var.pos, var.id);
          initBody.add(new ELNode.ASSIGN(var.pos, ident, var.expr));
        } else if (var.id.equals("__clinit__") &&
                   var.expr instanceof ELNode.LAMBDA) {
          initProc = var;
        }
      }
      if (initProc != null) {
        initBody.add(new ELNode.APPLY(
          e.pos, new ELNode.IDENT(e.pos, initProc.id), new ELNode[0], null));
      }

      if (initBody.isEmpty())
        return null;

      ELNode.LAMBDA initFunc = new ELNode.LAMBDA(
        e.pos, e.file, "<clinit>", null, new ELNode.DEFINE[0], false,
        new ELNode.COMPOUND(e.pos, initBody.toArray(new ELNode[0])));
      ELNode.DEFINE initDef = new ELNode.DEFINE(
        e.pos, "<clinit>", null, new ELNode.METASET(e.pos, Modifier.STATIC),
        initFunc);
      scan(initDef);
      return initFunc;
    }

    private ELNode.LAMBDA createInitProc(ELNode.CLASSDEF e) {
      List<ELNode.DEFINE> initVars = new ArrayList<>();
      if (e.vars != null)
        Collections.addAll(initVars, e.vars);
      Collections.addAll(initVars, e.ivars);

      ELNode.LAMBDA initProc = null;
      for (int i = 0; i < e.ivars.length; i++) {
        ELNode.DEFINE ivar = e.ivars[i];
        if (ivar.id.equals(e.id) && ivar.expr instanceof ELNode.LAMBDA init) {
          initProc = init;

          // Remove the init proc.
          ELNode.DEFINE[] ivars = new ELNode.DEFINE[e.ivars.length - 1];
          System.arraycopy(e.ivars, 0, ivars, 0, i);
          System.arraycopy(e.ivars, i + 1, ivars, i, ivars.length - i);
          e.ivars = ivars;
          break;
        }
      }

      List<ELNode.DEFINE> initParams = new ArrayList<>();
      if (e.vars != null) {
        for (ELNode.DEFINE var : e.vars) {
          initParams.add(new ELNode.DEFINE(var.pos, "*"+initParams.size()+"*",
                                           null, null, var.expr));
        }
      } else if (initProc != null) {
        initParams.addAll(Arrays.asList(initProc.vars));
      }

      List<ELNode> initBody = new ArrayList<>();
      for (ELNode.DEFINE var : initVars) {
        if (!(var.expr instanceof ELNode.LAMBDA) &&
            !(var.expr instanceof ELNode.CLASSDEF) &&
            !(var.expr instanceof ELNode.NULL)) {
          ELNode.IDENT ident = new ELNode.IDENT(var.pos, var.id);
          ELNode value;
          if (e.vars != null && initBody.size() < e.vars.length)
            value = new ELNode.IDENT(var.pos, "*" + initBody.size() + "*");
          else
            value = var.expr;
          initBody.add(new ELNode.ASSIGN(var.pos, ident, value));
        }
      }

      if (initProc != null) {
        ELNode firstNode = initProc.body;
        if (firstNode instanceof ELNode.COMPOUND comp)
          firstNode = comp.exps[0];
        if (firstNode instanceof ELNode.APPLY app &&
            app.right instanceof ELNode.IDENT ident &&
            ident.id.equals("super")) {
          if (initProc.body instanceof ELNode.COMPOUND comp) {
            ELNode[] exps = new ELNode[comp.exps.length - 1];
            System.arraycopy(comp.exps, 1, exps, 0, exps.length);
            initBody.add(new ELNode.COMPOUND(comp.pos, exps));
          }
          e.symbol.clazz.super_args = app.args;
          e.symbol.clazz.super_keys = app.keys;
        } else {
          initBody.add(initProc.body);
        }
      }

      ELNode.LAMBDA initFunc = new ELNode.LAMBDA(
        e.pos, e.file, "<init>", null, initParams.toArray(new ELNode.DEFINE[0]),
        initProc != null && initProc.varargs,
        new ELNode.COMPOUND(e.pos, initBody.toArray(new ELNode[0])));
      ELNode.DEFINE initDef = new ELNode.DEFINE(e.pos, "<init>", null, null,
                                                initFunc);
      scan(initDef);
      return initFunc;
    }

    public void visit(ELNode.IDENT e) {
      var sym = table.lookup(e.id);
      if (sym != null) {
        if (!checkAccess(e.pos, sym, table.currentScope()))
          return;

        e.symbol = sym;

        // Mark this variable is captured by enclosing lambda.
        if (!inScope(sym)) {
          sym.captured = true;
        }
      } else {
        // Add to undefined table for later resolution.
        undefined.add(new Undefined(e, table.currentScope(), false));
      }
    }

    public void visit(ELNode.APPLY e) {
      if (e.right instanceof ELNode.IDENT var) {
        // Handling function call. Lambda no need to capture because we will
        // generate direct call.
        SymbolTable.Symbol sym = table.lookup(var.id);
        if (sym != null) {
          var.symbol = sym;

          // Mark this symbol captured if this is not a direct function call.
          if (!(sym.def.expr instanceof ELNode.LAMBDA) && !inScope(sym)) {
            sym.captured = true;
          }
        } else {
          // Add to undefined table for later resolution.
          undefined.add(new Undefined(var, table.currentScope(), true));
        }

        scan(e.args);
      } else {
        scan(e.right);
        scan(e.args);
      }
    }

    private boolean inScope(SymbolTable.Symbol sym) {
      return enclosingScope == null ||
             sym.scope.enclosingScope() == enclosingScope;
    }

    public void visit(ELNode.WHILE e) {
      scan(e.cond);
      table.enterScope(e.body);
      scan(e.body);
      table.leaveScope();
    }

    public void visit(ELNode.FOR e) {
      if (e.local) {
        table.enterScope(e);
        super.visit(e);
        table.leaveScope();
      } else {
        scan(e.init);
        scan(e.cond);
        table.enterScope(e.body);
        scan(e.body);
        table.leaveScope();
        scan(e.step);
      }
    }

    public void visit(ELNode.FOREACH e) {
      table.enterScope(e);
      super.visit(e);
      table.leaveScope();
    }

    public void visit(ELNode.COND e) {
      scan(e.cond);
      table.enterScope(e.left);
      scan(e.left);
      table.leaveScope();
      if (e.right != null) {
        table.enterScope(e.right);
        scan(e.right);
        table.leaveScope();
      }
    }

    public void visit(ELNode.COMPOUND e) {
      if (e.scope == null) {
        table.enterScope(e);
        super.visit(e);
        table.leaveScope();
      } else {
        super.visit(e);
      }
    }

    public void visit(ELNode.MATCH e) {
      // Each case body gets its own scope for pattern variables.
      scan(e.args);
      for (ELNode.CASE c : e.alts) {
        table.enterScope(c);
        collectCaseBindings(c);
        scan(c.guards);
        scan(c.bodies);
        table.leaveScope();
      }

      if (e.deflt != null) {
        table.enterScope(e.deflt);
        scan(e.deflt);
        table.leaveScope();
      }
    }

    /**
     * Register pattern variable names from CASE.
     */
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
            def.symbol = table.define(def);
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
      } else if (pat instanceof ELNode.NOT not) {
        collectPatternBindings(not.right, bind);
      } else if (pat instanceof ELNode.NEW data) {
        var sym = table.lookup(((ELNode.IDENT)data.base).id);
        if (sym != null) {
          data.base.symbol = sym;
          if (!inScope(sym))
            sym.captured = true;
        } else {
          undefined.add(new Undefined((ELNode.IDENT)data.base,
                                      table.currentScope(), false));
        }
        for (ELNode v : data.args)
          collectPatternBindings(v, bind);
      }
    }

    public void visit(ELNode.LET e) {
      collectPatternBindings(e.left, true);
      scan(e.right);
    }

    private void finish() {
      // Resolve forward referenced functions and class definitions.
      for (Undefined undef : undefined) {
        SymbolTable.Symbol sym = undef.scope.lookup(undef.var.id);
        if (sym != null) {
          if (!checkAccess(undef.var.pos, sym, undef.scope))
            continue;

          undef.var.symbol = sym;
          if (!(undef.call && sym.def.expr instanceof ELNode.LAMBDA) &&
              sym.scope.enclosingScope() != undef.scope.enclosingScope())
            sym.captured = true;
        }
      }
    }

    private boolean checkAccess(int pos, SymbolTable.Symbol sym,
                                SymbolTable.Scope scope) {
      // Check if the symbol is a class member variable.
      if (sym.scope.isClassScope() &&
          (scope.isStaticScope() ||
           (scope.enclosingClassScope() != sym.scope &&
            scope.enclosingClass().node.symbol.isStatic()))) {
        // The instance member can only be accessed by instance procedure.
        if (!sym.isStatic() && !sym.isConstructor()) {
          table.addError(pos, _T(EL_STATIC_CONTEXT_ACCESS_INSTANCE_MEMBER,
                                 sym.name));
          return false;
        }
      }
      return true;
    }
  }
}
