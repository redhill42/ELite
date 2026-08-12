/*
 * Copyright 2006-2026 Daniel Yuan.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.elite.ir;

import elite.lang.Builtin;
import elite.lang.MathLib;
import elite.lang.Seq;
import elite.lang.annotation.Data;
import elite.lang.annotation.Expando;
import elite.lang.annotation.ExpandoScope;
import org.elite.eval.ELEngine;
import org.elite.eval.ELProgram;
import org.elite.eval.EvaluationContext;
import org.elite.eval.EvaluationException;
import org.elite.eval.Runtime;
import org.elite.eval.TypeCoercion;
import org.elite.eval.closure.MethodClosure;
import org.elite.eval.seq.Cons;
import org.elite.parser.ELNode;
import org.elite.parser.ParseException;
import org.elite.parser.Position;
import org.elite.parser.Token;
import org.elite.resolver.ClassResolver;
import org.elite.resolver.MethodResolver;
import org.elite.resources.Resources;

import javax.el.ELContext;
import javax.el.ELResolver;
import javax.xml.XMLConstants;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Stream;

import static org.elite.ir.SymbolTable.Symbol;
import static org.elite.ir.SymbolTable.Scope;

import static org.elite.ir.IRFormat.*;
import static org.elite.ir.Opcode.*;
import static org.elite.resources.Resources.*;

/**
 * Converts an ELNode expression tree into IR form with explicit jump-based
 * control flow.
 *
 * <p>Design rule: every basic block MUST end with a terminator (JUMP, RETURN,
 * or conditional JUMP followed by unconditional JUMP). No block falls through
 * to the next block in memory. This ensures correctness regardless of block ID
 * order.
 */
public class IRBuilder extends ELNode.Visitor {

  // Context used to resolve global method and Java class.
  private final ELContext elctx;

  // The compiled IRProgram.
  private final IRProgram program;

  // The IRFunction to build.
  private final IRFunction func;

  // Tracking current scope.
  private Scope currentScope;

  // Tracking temporary slot allocation.
  private int maxLocals;
  private int nextTempSlot;
  private final Deque<Integer> freeSlots = new ArrayDeque<>();

  private static class Block {
    final int id;
    final IntList code;
    final Map<Integer, Integer> lineMap = new HashMap<>();
    int pc;

    BitSet predecessors = new BitSet();
    BitSet successors = new BitSet();
    int mappedId;

    Block(int id, int[] code, Map<Integer, Integer> lineMap) {
      this.id = id;
      this.code = new IntList(code);
      this.lineMap.putAll(lineMap);
    }

    boolean isDead() {
      return id != 0 && predecessors.isEmpty();
    }
  }

  // ── Block management
  private final List<Block> blocks = new ArrayList<>();
  private final IREmitter current;
  private int currentBlockId = 0;
  private int nextBlockId = 1;  // 0 is the initial block
  private int exitBlock = -1;

  // Peephole optimizer.
  private final PeepholeOpt peephole;

  // ── Constant pool (maybe shared with parent builder) ──
  private final List<Object> constants;
  private final Map<Object, Integer> constIndex;

  // ── Loop stack ──
  private record LoopTargets(int continueBlock, int breakBlock) {}
  private final Deque<LoopTargets> loopStack = new ArrayDeque<>();

  // ── Tail-call optimization ──
  private boolean inTailPosition = true;

  // ── Debug info ──
  private String currentFile;
  private final Map<Integer, Integer> linePcMapping = new HashMap<>();

  private static final Method getValueBootstrap;
  private static final Method setValueBootstrap;
  private static final Method coerceBootstrap;

  static {
    try {
      getValueBootstrap = DynamicBootstrap.class.getMethod(
        "getValueBootstrap", MethodHandles.Lookup.class, String.class,
        MethodType.class);
      setValueBootstrap = DynamicBootstrap.class.getMethod(
        "setValueBootstrap", MethodHandles.Lookup.class, String.class,
        MethodType.class);
      coerceBootstrap = CoercionBootstrap.class.getMethod(
        "coerceBootstrap", MethodHandles.Lookup.class, String.class,
        MethodType.class);
    } catch (NoSuchMethodException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * Create a top-level builder.  The symbol table must already be built
   * so that AST nodes carry slot/captured annotations.
   */
  IRBuilder(ELContext elctx, IRProgram program, IRFunction func, Scope scope) {
    this.elctx = elctx;
    this.program = program;
    this.func = func;
    this.peephole = new PeepholeOpt(elctx, this);
    this.current = new IREmitter(this);
    this.currentScope = scope;
    this.constants = new ArrayList<>();
    this.constIndex = new HashMap<>();
  }

  /**
   * Create a nested builder sharing the parent's constant pool, import
   * context, and symbol table.
   */
  private IRBuilder(IRBuilder parent, IRFunction func, Scope scope) {
    assert (parent != null);
    this.elctx = parent.elctx;
    this.program = parent.program;
    this.func = func;
    this.peephole = parent.peephole;
    this.current = new IREmitter(this);
    this.currentScope = scope;
    this.currentFile = parent.currentFile;

    // Share constants with parent so pool indices are consistent
    this.constants = parent.constants;
    this.constIndex = parent.constIndex;
  }

  /**
   * Set the source file name for debug info (called before compilation).
   */
  void setFile(String file) {
    this.currentFile = file;
  }

  /**
   * Resolve a class name at compile time using the builder's import context.
   * Returns null if resolution fails.
   */
  Class<?> resolveClassAtCompileTime(String name) {
    try {
      return ClassResolver.getInstance(elctx).resolveClass(name);
    } catch (ClassNotFoundException ex) {
      return null;
    }
  }

  Class<?> loadClassAtCompileTime(int pos, String name) {
    try {
      return ClassResolver.getInstance(elctx).resolveClass(name);
    } catch (ClassNotFoundException ex) {
      throw reportError(pos, _T(EL_CLASS_NOT_FOUND, name));
    }
  }

  private ParseException reportError(int pos, String message) {
    return new ParseException(currentFile, Position.line(pos),
                              Position.column(pos), message);
  }

  // ============ MAIN DISPATCH ============

  private void buildNode(ELNode node) {
    if (node == null) {
      current.emitPushNull();
      return;
    }

    if (node.scope != null) {
      Scope prevScope = currentScope;
      currentScope = node.scope;
      if (!(node instanceof ELNode.LAMBDA) &&
          !(node instanceof ELNode.CLASSDEF) &&
          node.scope.hasCaptures()) {
        // Set up new evaluation context if any variables captured in this scope.
        current.emitEnterScope();
        node.accept(this);
        current.emitLeaveScope();
      } else {
        node.accept(this);
      }
      currentScope = prevScope;
    } else {
      node.accept(this);
    }

    if (node.pos != Position.NOPOS) {
      int line = Position.line(node.pos);
      int pc = current.size();
      linePcMapping.compute(line, (k, v) -> v == null ? pc : Math.max(pc, v));
    }
  }

  void build(ELNode node) {
    boolean prev = inTailPosition;
    inTailPosition = false;
    buildNode(node);
    inTailPosition = prev;
  }

  /**
   * Build a node in tail position (preserves current tail status).
   */
  private void buildTail(ELNode node) {
    buildNode(node);
  }

  private void build(ELNode[] nodes) {
    for (ELNode node : nodes)
      build(node);
  }

  // ── Literals ──

  public void visit(ELNode.NUMBER node) {
    buildConst(node.value);
  }

  public void visit(ELNode.STRINGVAL node) {
    buildConst(node.value);
  }

  public void visit(ELNode.LITERAL node) {
    buildConst(node.value);
  }

  public void visit(ELNode.CHARVAL node) {
    buildConst(node.value);
  }

  public void visit(ELNode.BOOLEANVAL node) {
    buildConst(node.value);
  }

  public void visit(ELNode.NULL node) {
    current.emitPushNull();
  }

  public void visit(ELNode.REGEXP node) {
    buildConst(node.value);
  }

  public void visit(ELNode.SYMBOL node) {
    buildConst(node.value);
  }

  public void visit(ELNode.CONST node) {
    buildConst(node.value);
  }

  public void visit(ELNode.DEFINE node) {
    // All DEFINE nodes should carry a symbol annotation. Missing expr or symbol
    // can happen only on pattern or lambda parameters which are handled by
    // pattern or lambda compilation.  For normal definition they should always
    // present.
    assert node.expr != null && node.symbol != null;

    // CLASS nodes (from import): push the raw Class constant
    if (node.expr instanceof ELNode.CLASS c) {
      buildConst(loadClassAtCompileTime(node.pos, c.name));
    } else {
      build(node.expr);
    }

    // Define global or local variable according to it's captured flag.
    if (node.symbol.captured) {
      current.emitDefineGlobal(node.id, node.symbol.isFinal() ||
                                        node.symbol.clazz != null);
      current.emitPushNull();
    } else {
      current.emitStoreVar(node.symbol.slot);
    }
  }

  public void visit(ELNode.IDENT node) {
    if (node.symbol != null &&
        node.symbol.def.expr instanceof ELNode.CLASSDEF cdef) {
      buildConst(cdef.symbol.clazz);
    } else if (node.symbol != null && node.symbol.scope.isClassScope()) {
      IRClass currentClass = currentScope.enclosingClass();
      IRClass ownerClass = node.symbol.scope.frontier.symbol.clazz;
      if (ownerClass == currentClass) {
        // If the variable defined in a class scope, and current enclosing scope
        // (it must be a lambda scope) is directly enclosed in this class scope,
        // then the variable is referencing the class' instance variable.
        if (node.id.equals("this"))
          current.emitPushThis();
        else if (node.symbol.def.expr instanceof ELNode.LAMBDA) {
          IRFunction fn = node.symbol.func;
          fn.owner().closures.add(fn);
          current.emitClosure(fn);
        } else if (node.symbol.isStatic())
          current.emitGetStatic(currentClass, node.id);
        else {
          current.emitGetField(node.id);
        }
      } else {
        if (node.symbol.def.expr instanceof ELNode.LAMBDA) {
          IRFunction fn = node.symbol.func;
          fn.owner().closures.add(fn);
          current.emitClosure(fn);
        } else if (node.symbol.isStatic()) {
          current.emitGetStatic(ownerClass, node.id);
        } else {
          IRClass outer = currentClass.outer;
          current.emitPushThis();
          current.emitGetField(currentClass, "$outer");
          while (outer != ownerClass) {
            current.emitGetField(outer, "$outer");
            outer = outer.outer;
          }
          current.emitGetField(ownerClass, node.id);
        }
      }
    } else if (buildLoadClassMember(node.pos, node.id)) {
      // already done.
    } else if (node.symbol == null || node.symbol.captured) {
      current.emitPushGlobal(node.id);
    } else {
      current.emitPushVar(node.symbol.slot);
    }
  }

  private void buildStoreVariable(ELNode.IDENT node) {
    if (node.symbol != null && node.symbol.clazz != null)
      throw reportError(node.pos, _T(EL_VARIABLE_NOT_WRITABLE, node.id));

    if (node.symbol != null && node.symbol.scope.isClassScope()) {
      IRClass currentClass = currentScope.enclosingClass();
      IRClass ownerClass = node.symbol.scope.frontier.symbol.clazz;
      if (ownerClass == currentClass) {
        if (node.id.equals("this") || node.symbol.func != null)
          throw reportError(node.pos, _T(EL_PROPERTY_NOT_WRITABLE,
                                         currentClass.name, node.id));
        if (node.symbol.isFinal() &&
            !func.name().equals("<init>") && !func.name().equals("<clinit>"))
          throw reportError(node.pos, _T(EL_PROPERTY_NOT_WRITABLE,
                                         currentClass.name, node.id));

        if (node.symbol.isStatic())
          current.emitPutStatic(currentClass, node.id);
        else
          current.emitPutField(node.id);
      } else {
        if (node.symbol.isFinal() || node.symbol.func != null)
          throw reportError(node.pos, _T(EL_PROPERTY_NOT_WRITABLE,
                                         currentClass.name, node.id));

        if (node.symbol.isStatic())
          current.emitPutStatic(ownerClass, node.id);
        else {
          IRClass outer = currentClass.outer;
          current.emitPushThis();
          current.emitGetField(currentClass, "$outer");
          while (outer != ownerClass) {
            current.emitGetField(outer, "$outer");
            outer = outer.outer;
          }
          current.emitPutField(ownerClass, node.id);
        }
      }
    } else if (buildStoreClassMember(node.pos, node.id)) {
      // already done.
    } else {
      if (node.symbol != null && node.symbol.isFinal())
        throw reportError(node.pos, _T(EL_VARIABLE_NOT_WRITABLE, node.id));
      if (node.symbol == null || node.symbol.captured) {
        current.emitStoreGlobal(node.id);
      } else {
        current.emitStoreVar(node.symbol.slot);
      }
    }
  }

  private boolean buildLoadClassMember(int pos, String id) {
    IRClass clazz = currentScope.enclosingClass();
    if (clazz == null)
      return false;
    for (; clazz.base instanceof IRClass base; clazz = base) {
      Optional<ELNode.DEFINE> var =
        Stream.concat(
            Stream.concat(Arrays.stream(base.node.cvars),
                          Arrays.stream(base.node.ivars)),
            base.node.vars != null ? Arrays.stream(base.node.vars)
                                   : Stream.empty())
          .filter(x -> x.id.equals(id))
          .findFirst();

      if (var.isPresent()) {
        ELNode.DEFINE def = var.get();
        if (def.symbol.isPrivate())
          throw reportError(pos, _T(EL_ILLEGAL_ACCESS, base.name, id));
        if (currentScope.isStaticScope() && !def.symbol.isStatic())
          throw reportError(pos,
                            _T(EL_STATIC_CONTEXT_ACCESS_INSTANCE_MEMBER, id));
        if (def.expr instanceof ELNode.CLASSDEF cdef) {
          buildConst(cdef.symbol.clazz);
        } else if (def.expr instanceof ELNode.LAMBDA fn) {
          current.emitClosure(fn.symbol.func);
        } else if (def.symbol.isStatic()) {
          current.emitGetStatic(base, id);
        } else {
          current.emitPushThis();
          current.emitGetField(base, id);
        }
        return true;
      }
    }
    return false;
  }

  private boolean buildStoreClassMember(int pos, String id) {
    IRClass clazz = currentScope.enclosingClass();
    if (clazz == null)
      return false;
    for (; clazz.base instanceof IRClass base; clazz = base) {
      Optional<ELNode.DEFINE> var =
        Stream.concat(
            Stream.concat(Arrays.stream(base.node.cvars),
                          Arrays.stream(base.node.ivars)),
            base.node.vars != null ? Arrays.stream(base.node.vars)
                                   : Stream.empty())
          .filter(x -> x.id.equals(id))
          .findFirst();

      if (var.isPresent()) {
        ELNode.DEFINE def = var.get();
        if (def.symbol.isPrivate())
          throw reportError(pos, _T(EL_ILLEGAL_ACCESS, base.name, id));
        if (currentScope.isStaticScope() && !def.symbol.isStatic())
          throw reportError(pos,
                            _T(EL_STATIC_CONTEXT_ACCESS_INSTANCE_MEMBER, id));
        if (def.symbol.isFinal() ||
            def.expr instanceof ELNode.CLASSDEF ||
            def.expr instanceof ELNode.LAMBDA)
          throw reportError(pos, _T(EL_PROPERTY_NOT_WRITABLE, base.name, id));
        if (def.symbol.isStatic()) {
          current.emitPutStatic(base, id);
        } else {
          current.emitPushThis();
          current.emitPutField(base, id);
        }
        return true;
      }
    }
    return false;
  }

  public void visit(ELNode.ACCESS node) {
    if (node.index instanceof ELNode.STRINGVAL) {
      String key = ((ELNode.STRINGVAL)node.index).value;
      if (node.right instanceof ELNode.IDENT base && base.id.equals("this")) {
        Scope classScope = currentScope.enclosingClassScope();
        if (base.symbol == null || classScope == null ||
            base.symbol.scope != classScope)
          throw reportError(node.pos, "Dangling this reference");

        IRClass irc = currentScope.enclosingClass();
        boolean isSuperClass = false;
        do {
          Optional<ELNode.DEFINE> var =
            Stream.concat(irc.node.vars != null ? Arrays.stream(irc.node.vars)
                                                : Stream.empty(),
                          Arrays.stream(irc.node.ivars))
              .filter(def -> def.id.equals(key))
              .findFirst();

          if (var.isPresent()) {
            ELNode.DEFINE def = var.get();
            if (def.expr instanceof ELNode.CLASSDEF)
              throw reportError(node.pos, _T(EL_PROPERTY_NOT_FOUND, irc.name,
                                             key));
            if (isSuperClass && def.symbol.isPrivate())
              throw reportError(node.pos, _T(EL_ILLEGAL_ACCESS, irc.name, key));
            if (def.expr instanceof ELNode.LAMBDA fn)
              current.emitClosure(fn.symbol.func);
            else
              current.emitGetField(key);
            return;
          }

          if (Arrays.stream(irc.node.cvars).anyMatch(x -> x.id.equals(key)))
            throw reportError(node.pos,
                              _T(EL_PROPERTY_NOT_FOUND, irc.name, key));

          if (irc.base instanceof IRClass) {
            irc = (IRClass)irc.base;
            isSuperClass = true;
          } else {
            break;
          }
        } while (true);

        throw reportError(node.pos, _T(EL_PROPERTY_NOT_FOUND, irc.name, key));
      }

      IRClass irc = resolveIRClass(node.right);
      if (irc != null) {
        for (ELNode.DEFINE def : irc.node.cvars) {
          if (def.id.equals(key)) {
            if (!def.symbol.isPublic())
              throw reportError(
                node.pos, _T(EL_ILLEGAL_ACCESS, irc.name, key));
            if (def.symbol.clazz != null) {
              buildConst(def.symbol.clazz);
            } else if (def.symbol.func != null) {
              current.emitClosure(def.symbol.func);
            } else {
              current.emitGetStatic(irc, key);
            }
            return;
          }
        }
      }

      Symbol outerSym = getOuterClassMember(node.right, key);
      if (outerSym != null) {
        if (outerSym.func != null) {
          IRFunction fn = outerSym.func;
          fn.owner().closures.add(fn);
          current.emitClosure(fn);
        } else {
          IRClass outerClass = outerSym.scope.enclosingClass();
          IRClass currentClass = currentScope.enclosingClass();
          current.emitPushThis();
          while (currentClass != outerClass) {
            current.emitGetField(currentClass, "$outer");
            currentClass = currentClass.outer;
          }
          current.emitGetField(outerClass, key);
        }
        return;
      }

      Class<?> baseClass = resolveJavaClass(node.right);
      if (baseClass != null && tryBuildGetStatic(baseClass, key))
        return;
    }

    Class<?> cls = resolveJavaClass(node);
    if (cls != null) {
      buildConst(cls);
      return;
    }

    buildGetValueIndy(node.right, node.index);
  }

  private void buildGetValueIndy(ELNode target, ELNode index) {
    if (index instanceof ELNode.STRINGVAL) {
      current.emitPushEnv();
      build(target);
      current.emitInvokeDynamic(new Descriptors.Indy(
        getValueBootstrap, ((ELNode.STRINGVAL)index).value, Object.class,
        EvaluationContext.class, Object.class));
    } else {
      current.emitPushCtx();
      build(target);
      build(index);
      emitInvokeMethod(Runtime.class, "getValue", ELContext.class, Object.class,
                       Object.class);
    }
  }

  private void buildStoreProperty(ELNode.ACCESS node) {
    if (node.index instanceof ELNode.STRINGVAL) {
      String key = ((ELNode.STRINGVAL)node.index).value;
      if (node.right instanceof ELNode.IDENT base && base.id.equals("this")) {
        Scope classScope = currentScope.enclosingClassScope();
        if (base.symbol == null || classScope == null ||
            base.symbol.scope != classScope)
          throw reportError(node.pos, "Dangling this reference");

        // Access member variable via this reference. Traverse class hierarchy
        // to find the member variable.
        IRClass irc = currentScope.enclosingClass();
        boolean isSuperClass = false;
        do {
          Optional<ELNode.DEFINE> var =
            Stream.concat(irc.node.vars != null ? Arrays.stream(irc.node.vars)
                                                : Stream.empty(),
                          Arrays.stream(irc.node.ivars))
              .filter(def -> def.id.equals(key))
              .findFirst();

          if (var.isPresent()) {
            ELNode.DEFINE def = var.get();
            if (def.symbol.isFinal() ||
                def.expr instanceof ELNode.LAMBDA ||
                def.expr instanceof ELNode.CLASSDEF)
              throw reportError(
                node.pos, _T(EL_PROPERTY_NOT_WRITABLE, irc.name, key));
            if (isSuperClass && def.symbol.isPrivate())
              throw reportError(node.pos, _T(EL_ILLEGAL_ACCESS, irc.name, key));
            current.emitPutField(def.id);
            return;
          }

          if (Arrays.stream(irc.node.cvars).anyMatch(x -> x.id.equals(key)))
            throw reportError(node.pos, _T(EL_PROPERTY_NOT_FOUND, irc.name, key));

          if (irc.base instanceof IRClass) {
            irc = (IRClass)irc.base;
            isSuperClass = true;
          } else {
            break;
          }
        } while (true);

        throw reportError(node.pos, _T(EL_PROPERTY_NOT_FOUND, irc.name, key));
      }

      // Access a class static member variable.
      IRClass irc = resolveIRClass(node.right);
      if (irc != null) {
        for (ELNode.DEFINE def : irc.node.cvars) {
          if (def.id.equals(key)) {
            if (!def.symbol.isPublic())
              throw reportError(
                node.pos, _T(EL_ILLEGAL_ACCESS, irc.name, key));
            if (def.symbol.isFinal())
              throw reportError(
                node.pos, _T(EL_PROPERTY_NOT_WRITABLE, irc.name, key));
            if (def.symbol.clazz != null || def.symbol.func != null)
              throw reportError(
                node.pos, _T(EL_PROPERTY_NOT_WRITABLE, irc.name, key));
            current.emitPutStatic(irc, key);
            return;
          }
        }
      }

      // Access outer class member variable via `Outer.this.name`.
      Symbol outerSym = getOuterClassMember(node.right, key);
      if (outerSym != null) {
        if (outerSym.func != null || outerSym.isFinal())
          throw reportError(node.pos,
                            _T(EL_PROPERTY_NOT_WRITABLE, outerSym.name, key));

        IRClass outerClass = outerSym.scope.enclosingClass();
        IRClass currentClass = currentScope.enclosingClass();
        current.emitPushThis();
        while (currentClass != outerClass) {
          current.emitGetField(currentClass, "$outer");
          currentClass = currentClass.outer;
        }
        current.emitPutField(outerClass, key);
        return;
      }

      // Access Java class static field.
      Class<?> baseClass = resolveJavaClass(node.right);
      if (baseClass != null && tryBuildPutStatic(node.pos, baseClass, key))
        return;
    }

    // Set object property at runtime. Note that parameter order is reversed
    // because value is pushed first to stack.
    if (node.index instanceof ELNode.STRINGVAL) {
      current.emitDup();
      build(node.right);
      current.emitPushEnv();
      current.emitInvokeDynamic(new Descriptors.Indy(
        setValueBootstrap, ((ELNode.STRINGVAL)node.index).value, void.class,
        Object.class, Object.class, EvaluationContext.class));
    } else {
      build(node.right);
      build(node.index);
      current.emitPushCtx();
      emitInvokeMethod(Runtime.class, "setValue", Object.class,
                       Object.class, Object.class, ELContext.class);
    }
  }

  private IRClass resolveIRClass(ELNode node) {
    if (node instanceof ELNode.IDENT var) {
      if (var.symbol != null && var.symbol.clazz != null)
        return var.symbol.clazz;
    }

    if (node instanceof ELNode.ACCESS acc &&
        acc.index instanceof ELNode.STRINGVAL key) {
      IRClass c = resolveIRClass(acc.right);
      if (c != null) {
        for (ELNode.DEFINE def : c.node.cvars) {
          if (def.id.equals(key.value) && def.symbol.clazz != null &&
              def.symbol.isPublic() && def.symbol.isStatic())
            return def.symbol.clazz;
        }
      }
    }

    return null;
  }

  private Symbol getOuterClassMember(ELNode node, String key) {
    if (node instanceof ELNode.ACCESS acc &&
        acc.right instanceof ELNode.IDENT base &&
        base.symbol != null && base.symbol.clazz != null &&
        acc.index instanceof ELNode.STRINGVAL str &&
        str.value.equals("this")) {
      // Special case for access outer class member.
      IRClass outerClass = base.symbol.clazz;
      IRClass currentClass = currentScope.enclosingClass();
      for (IRClass c = currentClass; c != outerClass; c = c.outer) {
        if (c == null)
          throw reportError(node.pos, base.id + " is not an outer class of " +
                                      "current class");
      }

      Optional<ELNode.DEFINE> var =
        Stream.concat(outerClass.node.vars != null
                        ? Arrays.stream(outerClass.node.vars)
                        : Stream.empty(),
                      Arrays.stream(outerClass.node.ivars))
          .filter(def -> def.id.equals(key))
          .findFirst();
      if (var.isEmpty())
        throw reportError(node.pos, _T(EL_PROPERTY_NOT_FOUND, outerClass.name,
                                       key));
      return var.get().symbol;
    }
    return null;
  }

  private boolean tryBuildGetStatic(Class<?> cls, String name) {
    try {
      Field field = cls.getField(name);
      int mods = field.getModifiers();
      if (Modifier.isPublic(mods) && Modifier.isStatic(mods)) {
        if (Modifier.isFinal(mods)) {
          try {
            Object value = field.get(null);
            if (value == null || value instanceof Boolean ||
                value instanceof Byte || value instanceof Short ||
                value instanceof Character || value instanceof Integer ||
                value instanceof Long || value instanceof Float ||
                value instanceof Double || value instanceof String) {
              buildConst(value);
              return true;
            }
          } catch (IllegalArgumentException | IllegalAccessException ex) {
            // fallthrough
          }
        }
        current.emitGetStatic(field);
        return true;
      }
    } catch (NoSuchFieldException | SecurityException ex) { /* fallthrough */ }
    return false;
  }

  private boolean tryBuildPutStatic(int pos, Class<?> cls, String name) {
    try {
      Field field = cls.getField(name);
      int mods = field.getModifiers();
      if (Modifier.isPublic(mods) && Modifier.isStatic(mods)) {
        if (Modifier.isFinal(mods))
          throw reportError(pos, _T(EL_PROPERTY_NOT_WRITABLE, cls.getName(), name));
        current.emitPutStatic(field);
        return true;
      }
    } catch (NoSuchFieldException | SecurityException ex) { /* fallthrough */ }
    return false;
  }

  public void visit(ELNode.APPLY node) {
    ELNode base = node.right;

    if (base instanceof ELNode.IDENT ident) {
      if (ident.id.equals("super"))
        throw reportError(node.pos, _T(EL_DANGLING_SUPER));

      if (ident.symbol != null) {
        if (ident.symbol.func != null) {
          ELNode.LAMBDA lambda = (ELNode.LAMBDA)ident.symbol.def.expr;
          ELNode[] args = getCallArgs(node.pos, lambda, node.args, node.keys);
          if (inTailPosition && ident.symbol.func == this.func) {
            buildTailCall(lambda, args);
          } else {
            buildDirectCall(ident.symbol, args);
          }
          return;
        }

        if (ident.symbol.clazz != null) {
          buildClassCall(node.pos, ident.symbol.clazz, node.args, node.keys);
          return;
        }

        if (ident.symbol.def.expr instanceof ELNode.CLASS c) {
          Class<?> cls = loadClassAtCompileTime(ident.pos, c.name);
          if (buildNew(cls, node.args, null))
            return;
        }
      }

      if (ident.symbol == null) {
        // Resolve member function from super class.
        IRClass clazz = currentScope.enclosingClass();
        if (clazz != null) {
          for (; clazz.base instanceof IRClass zuper; clazz = zuper) {
            Optional<ELNode.DEFINE> var =
              Stream.concat(Arrays.stream(zuper.node.cvars),
                            Arrays.stream(zuper.node.ivars))
                .filter(x -> x.id.equals(ident.id) &&
                             x.expr instanceof ELNode.LAMBDA)
                .findFirst();

            if (var.isPresent()) {
              ELNode.DEFINE def = var.get();
              if (def.symbol.isPrivate())
                throw reportError(node.pos,
                  _T(EL_ILLEGAL_ACCESS, zuper.name, ident.id));
              if (currentScope.isStaticScope() && !def.symbol.isStatic())
                throw reportError(node.pos,
                  _T(EL_STATIC_CONTEXT_ACCESS_INSTANCE_MEMBER, ident.id));
              ELNode[] args = getCallArgs(node.pos, (ELNode.LAMBDA)def.expr,
                                          node.args, node.keys);
              buildDirectCall(def.symbol, args);
              return;
            }
          }
        }

        // Resolve builtin function.
        if (tryBuildGlobalMethodCall(ident.id, node.args))
          return;

        // Resolve java class.
        Class<?> cls = resolveClassAtCompileTime(ident.id);
        if (cls != null && buildNew(cls, node.args, null))
          return;

        // Resolve target at runtime if the given id is not a local var
        current.emitPushEnv();
        buildConst(ident.id);
        buildTuple(node.args);
        emitInvokeMethod(Runtime.class, "invokeTarget", EvaluationContext.class,
                         String.class, Object[].class);
        return;
      }
    }

    if (base instanceof ELNode.ACCESS acc) {
      // Try to resolve direct method for known Java types.
      if (acc.index instanceof ELNode.STRINGVAL) {
        String key = ((ELNode.STRINGVAL)acc.index).value;
        if (acc.right instanceof ELNode.IDENT ident &&
            ident.id.equals("this")) {
          Scope classScope = currentScope.enclosingClassScope();
          if (ident.symbol == null || classScope == null ||
              ident.symbol.scope != classScope)
            throw reportError(node.pos, "Dangling this reference");

          // Resolve member function from class hierarchy.
          IRClass irc = currentScope.enclosingClass();
          boolean isSuperClass = false;
          do {
            for (ELNode.DEFINE def : irc.node.ivars) {
              if (def.id.equals(key) && def.expr instanceof ELNode.LAMBDA fn) {
                if (isSuperClass && def.symbol.isPrivate())
                  throw reportError(node.pos,
                                    _T(EL_ILLEGAL_ACCESS, irc.name, key));
                ELNode[] args = getCallArgs(node.pos, fn, node.args, node.keys);
                if (inTailPosition && fn.symbol.func == this.func) {
                  buildTailCall(fn, args);
                } else {
                  buildDirectCall(fn.symbol, args);
                }
                return;
              }
            }
            if (irc.base instanceof IRClass) {
              irc = (IRClass)irc.base;
              isSuperClass = true;
            } else {
              break;
            }
          } while (true);

          throw reportError(node.pos, _T(EL_METHOD_NOT_FOUND, irc.name, key));
        }

        IRClass irc = resolveIRClass(acc.right);
        if (irc != null) {
          for (ELNode.DEFINE def : irc.node.cvars) {
            if (def.id.equals(key)) {
              if (!def.symbol.isPublic())
                throw reportError(
                  node.pos, _T(EL_ILLEGAL_ACCESS, irc.name, key));
              if (def.symbol.clazz != null) {
                buildClassCall(node.pos, def.symbol.clazz, node.args, node.keys);
                return;
              }
              if (def.expr instanceof ELNode.LAMBDA fn) {
                ELNode[] args = getCallArgs(node.pos, fn, node.args, node.keys);
                buildDirectCall(fn.symbol, args);
                return;
              }
            }
          }
        }

        Symbol outerSym = getOuterClassMember(acc.right, key);
        if (outerSym != null) {
          if (outerSym.def.expr instanceof ELNode.LAMBDA fn) {
            ELNode[] args = getCallArgs(node.pos, fn, node.args, node.keys);
            buildDirectCall(fn.symbol, args);
            return;
          } else {
            throw reportError(node.pos,
                              _T(EL_METHOD_NOT_FOUND, outerSym.clazz.name, key));
          }
        }

        if (tryBuildDirectMethodCall(acc.right, key, node.args))
          return;
      }

      Class<?> jc = resolveJavaClass(acc);
      if (jc != null && buildNew(jc, node.args, null))
        return;

      // Resolve method at runtime.
      current.emitPushEnv();
      build(acc.right);
      build(acc.index);
      buildTuple(node.args);
      emitInvokeMethod(Runtime.class, "invokeMember", EvaluationContext.class,
                       Object.class, Object.class, Object[].class);
      return;
    }

    current.emitPushCtx();
    build(base);

    if (base instanceof ELNode.LAMBDA lam && lam.symbol != null &&
        lam.symbol.func != null) {
      // Lambda closure no longer used.
      current.emitPop();
      current.emitPop();

      // One-shot lambda call. Let may be inlined to eliminate runtime overhead.
      ELNode[] args = getCallArgs(node.pos, lam, node.args, node.keys);
      buildDirectCall(lam.symbol, args);
      return;
    }

    // Evaluate base and generate dynamic call.
    buildTuple(node.args);
    emitInvokeMethod(ELEngine.class, "callTarget", ELContext.class,
                     Object.class, Object[].class);
  }

  private int indexOfVar(String name, ELNode.DEFINE[] vars, boolean varargs) {
    int nvars = vars.length - (varargs ? 1 : 0);
    for (int i = 0; i < nvars; i++) {
      if (name.equals(vars[i].id)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Build arguments for a direct call, handling default and named parameters.
   * Returns the total number of arguments built (including defaults).
   */
  private ELNode[] getCallArgs(int pos, ELNode.LAMBDA lambda, ELNode[] args,
                               String[] keys) {
    return getCallArgs(pos, lambda.name, lambda.vars, args, keys,
                       lambda.varargs);
  }

  private ELNode[] getCallArgs(int pos, String name, ELNode.DEFINE[] vars,
                               ELNode[] args, String[] keys, boolean varargs) {
    int argc = args.length;
    int nvars = vars.length;
    ELNode[] xargs = null;

    boolean hasDefaults = false;
    for (ELNode.DEFINE var : vars) {
      if (var.expr != null) {
        hasDefaults = true;
        break;
      }
    }

    if (argc < nvars && hasDefaults) {
      // Pad with default values.
      xargs = new ELNode[nvars];
    } else if (varargs ? (argc < nvars - 1) : (argc != nvars)) {
      throw reportError(pos, _T(EL_FN_BAD_ARG_COUNT, name, nvars, argc));
    }

    // Rearrange named arguments
    int k = nvars - 1; // index to vararg list
    if (keys != null) {
      for (int i = 0; i < argc; i++) {
        if (keys[i] != null) {
          int j = indexOfVar(keys[i], vars, varargs);
          if (j == -1) {
            if (!varargs || k >= argc)
              throw reportError(pos, _T(EL_UNKNOWN_ARG_NAME, keys[i]));
            if (xargs == null)
              xargs = new ELNode[argc];
            xargs[k++] = args[i];
          } else {
            if (xargs == null)
              xargs = new ELNode[argc];
            xargs[j] = args[i];
          }
        }
      }
    }

    if (xargs != null) {
      int j = 0;

      // Rearrange non-named arguments
      for (int i = 0; i < argc; i++) {
        if (keys == null || keys[i] == null) {
          while (xargs[j] != null)
            j++;
          xargs[j++] = args[i];
        }
      }

      // Assign default values.
      for (; j < xargs.length; j++) {
        if (xargs[j] == null) {
          if (vars[j].expr == null)
            throw reportError(pos, _T(EL_MISSING_ARG_VALUE, vars[j].id));
          xargs[j] = vars[j].expr;
        }
      }

      args = xargs;
    }

    return args;
  }

  private void buildCallArgs(ELNode.LAMBDA lambda, ELNode[] args) {
    int nvars = lambda.vars.length;

    // Build argument for argument list, exclude varargs.
    current.emitNewArray(nvars, Object.class);
    if (lambda.varargs)
      nvars--;
    for (int i = 0; i < nvars; i++) {
      current.emitDup();
      build(args[i]);
      current.emitStoreArray(i, Object.class);
    }

    // Build vararg list as an array and put the array into argument list.
    if (lambda.varargs) {
      current.emitDup();
      buildTuple(args, nvars, args.length - nvars);
      current.emitStoreArray(nvars, Object.class);
    }
  }

  private void buildClassCall(int pos, IRClass irc, ELNode... args) {
    buildClassCall(pos, irc, args, null);
  }

  private void buildClassCall(int pos, IRClass irc, ELNode[] args,
                              String[] keys) {
    if (irc.isSingleton()) {
      current.emitGetStatic(irc, "$singleton");
      return;
    }

    // If the class defines "valueOf" static member procedure, use the procedure
    // to initialize the new instance.
    for (ELNode.DEFINE def : irc.node.cvars) {
      if (def.id.equals("valueOf") && def.symbol.isPublic() &&
          def.expr instanceof ELNode.LAMBDA fn) {
        args = getCallArgs(pos, fn, args, keys);
        buildDirectCall(fn.symbol, args);
        return;
      }
    }

    args = getCallArgs(pos, irc.init_proc, args, keys);
    current.emitNew(irc);
    current.emitPushEnv();
    buildCallArgs(irc.init_proc, args);
    current.emitConstructor(irc);
  }

  private void buildTailCall(ELNode.LAMBDA lambda, ELNode[] args) {
    int nvars = lambda.vars.length;
    if (lambda.varargs)
      nvars--;

    // Build argument list, exclude varargs that must build to tuple.
    for (int i = 0; i < nvars; i++)
      build(args[i]);

    // Build tuple for var arg list.
    if (lambda.varargs)
      buildTuple(args, nvars, args.length - nvars);

    // Copy tail call arguments.
    for (int i = lambda.vars.length; --i >= 0; ) {
      current.emitStoreVar(i);
      current.emitPop();
    }

    // Jump to first block.
    current.emitJump(0);
  }

  private boolean isBaseClass(IRClass b, IRClass c) {
    while (c != b) {
      if (c.base instanceof IRClass)
        c = (IRClass) c.base;
      else
        return false;
    }
    return true;
  }

  private void buildDirectCall(Symbol sym, ELNode... args) {
    IRFunction fn = sym.func;
    ELNode.LAMBDA lambda = (ELNode.LAMBDA)sym.def.expr;

    if (sym.scope.isClassScope()) {
      IRClass currentClass = currentScope.enclosingClass();
      IRClass ownerClass = sym.scope.enclosingClass();
      if (currentClass != ownerClass) {
        if (!sym.isStatic()) {
          current.emitPushThis();
          if (!isBaseClass(ownerClass, currentClass)) {
            while (currentClass != ownerClass) {
              current.emitGetField(currentClass, "$outer");
              currentClass = currentClass.outer;
            }
          }
        }
        current.emitPushEnv();
        emitInvokeMethod(EvaluationContext.class, "pushContext");
        buildCallArgs(lambda, args);
        current.emitInvokeDirect(sym.func);
        return;
      }
    }

    if (!isEligibleToInline(sym)) {
      if (fn.owner() != null && !fn.isStatic())
        current.emitPushThis();
      current.emitPushEnv();
      emitInvokeMethod(EvaluationContext.class, "pushContext");
      buildCallArgs(lambda, args);
      current.emitInvokeDirect(sym.func);
      return;
    }

    // Scan for slots in inlined function.
    BitSet readSlots = new BitSet();
    BitSet modSlots = new BitSet();
    for (var v = new InstructionView(fn.code(), 0); v.inBounds(); v.advance()) {
      if (v.opcode() == PUSH_VAR)
        readSlots.set(v.varIndex());
      if (v.opcode() == STORE_VAR || v.opcode() == STORE_VAR_POP)
        modSlots.set(v.varIndex());
    }

    // Allocate slots for inline function local variables.
    Slot[] slots = new Slot[fn.maxLocals()];
    for (int i = 0; i < args.length; i++) {
      // If the slot never read, no need to allocate new slot. Argument still
      // need to build for side effects. Constant argument will be discarded
      // by peephole optimizer.
      if (!readSlots.get(i)) {
        build(args[i]);
        current.emitPop();
        continue;
      }

      // Reuse slot for read only slot.
      if (!modSlots.get(i)) {
        if (args[i] instanceof ELNode.IDENT ident &&
            ident.symbol != null && !ident.symbol.captured) {
          slots[i] = new Slot(ident);
          continue;
        }
        if (args[i] instanceof ELNode.Constant) {
          slots[i] = null;
          continue;
        }
      }

      // Build argument and store to local slot.
      build(args[i]);
      slots[i] = new Slot();
      slots[i].store();
      current.emitPop();
    }
    for (int i = args.length; i < fn.maxLocals(); i++) {
      if (readSlots.get(i))
        slots[i] = new Slot();
    }

    inlineInstructions(sym, slots, args);

    for (Slot slot : slots)
      release(slot);
  }

  private void buildDirectCall(Symbol sym, Slot... args) {
    IRFunction fn = sym.func;

    if (!isEligibleToInline(sym)) {
      if (fn.owner() != null && !fn.isStatic())
        current.emitPushThis();
      current.emitPushEnv();
      emitInvokeMethod(EvaluationContext.class, "pushContext");
      buildTuple(args);
      current.emitInvokeDirect(fn);
      return;
    }

    // Scan for slots in inlined function.
    BitSet readSlots = new BitSet();
    BitSet modSlots = new BitSet();
    for (var v = new InstructionView(fn.code(), 0); v.inBounds(); v.advance()) {
      if (v.opcode() == PUSH_VAR)
        readSlots.set(v.varIndex());
      if (v.opcode() == STORE_VAR || v.opcode() == STORE_VAR_POP)
        modSlots.set(v.varIndex());
    }

    // Allocate slots for function local variables.
    Slot[] slots = new Slot[fn.maxLocals()];
    for (int i = 0; i < args.length; i++) {
      if (readSlots.get(i)) {
        if (!modSlots.get(i)) {
          slots[i] = args[i];
        } else {
          slots[i] = new Slot();
          args[i].load();
          slots[i].store();
          current.emitPop();
        }
      }
    }
    for (int i = args.length; i < fn.maxLocals(); i++) {
      if (readSlots.get(i))
        slots[i] = new Slot();
    }

    inlineInstructions(sym, slots, null);

    for (Slot slot : slots)
      release(slot);
  }

  private void inlineInstructions(Symbol sym, Slot[] slots, ELNode[] args) {
    IRFunction fn = sym.func;

    // Build block map.
    int[] blockMap = new int[fn.blockCount()];
    for (int i = 0; i < fn.blockCount(); i++)
      blockMap[i] = allocBlockId();

    boolean hasCaptures = sym.def.expr.scope.hasCaptures();
    if (hasCaptures)
      current.emitEnterScope();

    for (var v = new InstructionView(fn.code(), 0); v.inBounds(); v.advance()) {
      int blockId = fn.blockOfPc(v.offset());
      if (blockId != -1) {
        current.emitJump(blockMap[blockId]);
        startBlock(blockMap[blockId]);
      }

      if (v.opcode() == PUSH_VAR) {
        Slot newSlot = slots[v.varIndex()];
        if (newSlot == null) {
          // Push constant.
          int idx = v.varIndex();
          assert idx < args.length && args[idx] instanceof ELNode.Constant;
          build(args[idx]);
        } else {
          // Remap local variable to new slot.
          current.emit(v.opcode(), v.payload(), newSlot.slot);
        }
      } else if (v.opcode() == STORE_VAR || v.opcode() == STORE_VAR_POP) {
        Slot newSlot = slots[v.varIndex()];
        if (newSlot == null) {
          // Ignore write only slot.
          if (v.opcode() == STORE_VAR_POP)
            current.emitPop();
        } else {
          // Remap local variable to new slot.
          current.emit(v.opcode(), v.payload(), newSlot.slot);
        }
      } else if (v.isJump()) {
        current.emit(v.opcode(), v.payload(), blockMap[v.jumpTarget()]);
      } else if (v.opcode() == RETURN) {
        // We have guaranteed single entry single exit.
        assert v.offset() == fn.code().length - 1;
        break;
      } else {
        current.emit(v.opcode(), v.payload(), v.operand());
      }
    }

    if (hasCaptures)
      current.emitLeaveScope();
  }

  private boolean isEligibleToInline(Symbol sym) {
    if (ELProgram.OPT_LEVEL == 0)
      return false;
    if (sym.func.isDeclaration())
      return false;
    if (((ELNode.LAMBDA)sym.def.expr).varargs)
      return false; // FIXME: handle varargs

    // Check @inline metadata.
    boolean forceInline = false;
    if (sym.def.meta != null) {
      for (ELNode.METADATA meta : sym.def.meta.metadata) {
        if (meta.type.equals("inline")) {
          if (meta.keys.length == 0) {
            forceInline = true;
          } else if (meta.keys.length == 1 && meta.keys[0].equals("value") &&
                     meta.values[0] instanceof ELNode.BOOLEANVAL b) {
            if (b.value)
              forceInline = true;
            else
              return false;
          }
        }
      }
    }
    if (!forceInline && sym.func.code().length > 50)
      return false;

    // Check self recursion function.
    InstructionView v = new InstructionView(sym.func.code(), 0);
    for (; v.inBounds(); v.advance()) {
      if (v.opcode() == INVOKE_DIRECT &&
          constants.get(v.poolIndex()) == sym.func)
        return false;
    }

    return true;
  }

  private boolean tryBuildGlobalMethodCall(String name, ELNode[] args) {
    var mc = MethodResolver.getInstance(elctx).resolveGlobalMethod(name);
    if (mc == null)
      return false;

    Method method = mc.getJavaMethod();
    if (method == null)
      return false;

    return buildMethodCall(method, null, args);
  }

  private boolean tryBuildDirectMethodCall(ELNode base, String name,
                                           ELNode[] args) {
    Method method = resolveStaticMethod(base, name);
    if (method != null)
      return buildMethodCall(method, null, args);

    method = resolveInstanceMethod(base, name);
    if (method != null)
      return buildMethodCall(method, base, args);

    return false;
  }

  private Method resolveStaticMethod(ELNode base, String name) {
    Class<?> cls = resolveJavaClass(base);
    if (cls == null)
      return null;

    MethodClosure mc = MethodResolver.getInstance(elctx)
      .resolveStaticMethod(cls, name);
    if (mc == null)
      return null;

    Method method = mc.getJavaMethod();
    if (method == null)
      return null;

    Expando expando = method.getAnnotation(Expando.class);
    if (expando != null &&
        !Arrays.asList(expando.scope()).contains(ExpandoScope.GLOBAL))
      return null;

    return method;
  }

  private Method resolveInstanceMethod(ELNode base, String name) {
    Class<?> baseClass = null;
    if (base instanceof ELNode.Constant) {
      Object value = base.getValue(null);
      if (value != null)
        baseClass = value.getClass();
    }
    if (baseClass == null)
      return null;

    var mc = MethodResolver.getInstance(elctx).resolveMethod(baseClass, name);
    if (mc == null)
      return null;
    return mc.getJavaMethod();
  }

  private Class<?> resolveJavaClass(ELNode node) {
    if (node instanceof ELNode.IDENT var && var.symbol != null &&
        var.symbol.def.expr instanceof ELNode.CLASS c) {
      return loadClassAtCompileTime(node.pos, c.name);
    }

    StringBuilder buf = new StringBuilder();
    while (node instanceof ELNode.ACCESS acc) {
      if (!(acc.index instanceof ELNode.STRINGVAL str))
        return null;
      buf.insert(0, str.value);
      buf.insert(0, '.');
      node = acc.right;
    }
    if (node instanceof ELNode.IDENT var && var.symbol == null) {
      buf.insert(0, var.id);
      return resolveClassAtCompileTime(buf.toString());
    }

    return null;
  }

  private boolean buildMethodCall(Method method, ELNode base, ELNode[] args) {
    Class<?>[] types = method.getParameterTypes();
    int nargs = types.length;
    int iarg = 0;
    boolean vargs = method.isVarArgs();
    boolean expando = base != null && Modifier.isStatic(method.getModifiers()) &&
                      method.getAnnotation(Expando.class) != null;

    if (nargs > 0 && types[0] == ELContext.class)
      iarg++;
    if (expando)
      iarg++;

    if (vargs) {
      if (args.length < nargs - iarg - 1)
        return false;
      nargs--;
    } else if (args.length != nargs - iarg) {
      return false;
    }

    if (buildBuiltin(method, base, args))
      return true;

    if (nargs > 0 && types[0] == ELContext.class)
      current.emitPushCtx();

    if (base != null)
      build(base);

    // Build fixed arguments.
    int i = 0;
    for (; iarg < nargs; iarg++, i++) {
      if (types[iarg] == Object.class) {
        build(args[i]);
      } else {
        current.emitPushCtx();
        build(args[i]);
        buildCoerce(TypeCoercion.getBoxedType(types[iarg]));
        if (types[iarg].isPrimitive())
          current.emitUnbox(types[iarg]);
      }
    }

    // Build variable arguments.
    if (vargs)
      buildTuple(types[nargs].getComponentType(), args, i, args.length - i);

    current.emitInvokeMethod(method);
    if (method.getReturnType() == Void.TYPE)
      current.emitPushNull();
    return true;
  }

  /**
   * Build direct IR for well known builtin functions.
   */
  private boolean buildBuiltin(Method method, ELNode base, ELNode[] args) {
    if (method.getDeclaringClass() == Builtin.class) {
      switch (method.getName()) {
      case "begin":
        if (args.length == 0) {
          current.emitPushNull();
          return true;
        }
        for (int i = 0; i < args.length - 1; i++) {
          build(args[i]);
          current.emitPop();
        }
        build(args[args.length - 1]);
        return true;

      case "coalesce": {
        if (args.length == 0) {
          current.emitPushNull();
          return true;
        }
        if (args.length == 1) {
          build(args[0]);
          return true;
        }

        // Create a chained coalesce expression and build it.
        ELNode exp = args[args.length - 1];
        for (int i = args.length - 2; i >= 0; i--) {
          exp = new ELNode.COALESCE(args[i].pos, args[i], exp);
        }
        build(exp);
        return true;
      }

      case "list":
        build(args);
        current.emitNil();
        for (int i = 0; i < args.length; i++)
          current.emitNewCons();
        return true;

      case "range":
        assert args.length == 3;
        build(args[0]);
        current.emitDup();
        build(args[2]);
        emitDynBinOp(Token.ADD);
        build(args[1]);
        emitInvokeMethod(Runtime.class, "newRange", Object.class, Object.class,
                         Object.class);
        return true;

      case "upto":
        return buildStepBuiltin(base, args[0], args[1], 1, Token.LE);
      case "downto":
        return buildStepBuiltin(base, args[0], args[1], -1, Token.GE);
      case "step":
        assert args.length == 3;
        if (args[1] instanceof ELNode.NUMBER n) {
          int step = n.value.intValue();
          if (step != 0)
            return buildStepBuiltin(base, args[0], args[2], step,
                                    step > 0 ? Token.LE : Token.GE);
        }
        return false;
      case "times":
        return buildStepBuiltin(new ELNode.NUMBER(0, 0), base, args[0], 1,
                                Token.LT);
      }
    }

    if (method.getDeclaringClass() == MathLib.class) {
      switch (method.getName()) {
      case "sum":
        return buildMathReduce(args, Token.ADD);
      case "difference":
        return buildMathReduce(args, Token.SUB);
      case "product":
        return buildMathReduce(args, Token.MUL);
      case "divide":
        return buildMathReduce(args, Token.DIV);

      case "remainder":
        build(args[0]);
        build(args[1]);
        emitDynBinOp(Token.REM);
        return true;

      case "pow":
        build(args[0]);
        build(args[1]);
        emitDynBinOp(Token.POW);
        return true;
      }
    }

    return false;
  }

  private boolean buildStepBuiltin(ELNode begin, ELNode end, ELNode body,
                                   int step, int cmpop) {
    // Build body to make sure one-shot lambda is built.
    build(body);

    // We have three path to call the step body:
    //  1) direct call, if and only if the body is a lambda
    //  2) global call, if the body is a global function reference (e.g. print)
    //  3) dynamic call, fallback for unresolved call targets.

    // Determine whether the body can be inlined or direct call.
    boolean direct = false;
    Symbol sym = (body instanceof ELNode.IDENT ident) ? ident.symbol :
                 (body instanceof ELNode.LAMBDA lambda) ? lambda.symbol : null;
    if (sym != null && sym.func != null) {
      if (sym.func.paramCount() > 1)
        throw reportError(body.pos, _T(EL_FN_BAD_ARG_COUNT, sym.func.name(),
                                       sym.func.paramCount(), 1));
      direct = true;
    }

    // Check if the body is a global function.
    Method global = direct ? null : getGlobalForStepBody(body);

    // Initialize temporary variables.
    Slot indSlot = new Slot();
    Slot endSlot = new Slot();
    Slot bodySlot = null;

    if (direct || global != null) {
      // Discard body closure if direct call or global call.
      current.emitPop();
    } else {
      // Store body reference for dynamic call.
      bodySlot = new Slot();
      bodySlot.store();
      current.emitPop();
    }

    build(begin);
    indSlot.store();
    current.emitPop();
    build(end);
    endSlot.store();
    current.emitPop();

    // Begin loop.
    int headerB = allocBlockId();
    int bodyB = allocBlockId();
    int exitB = allocBlockId();

    loopStack.push(new LoopTargets(headerB, exitB));
    current.emitJump(headerB);

    // Generate loop condition.
    startBlock(headerB);
    indSlot.load();
    endSlot.load();
    emitDynBinOp(cmpop);
    current.emitJumpIfTrue(bodyB);
    current.emitJump(exitB);

    // Generate loop body.
    startBlock(bodyB);
    if (direct) {
      if (sym.func.paramCount() == 1) {
        buildDirectCall(sym, indSlot);
      } else {
        buildDirectCall(sym, new Slot[0]);
      }
      current.emitPop();
    } else if (global != null) {
      Class<?>[] types = global.getParameterTypes();
      if (types[0] == ELContext.class)
        current.emitPushCtx();
      indSlot.load();
      current.emitInvokeMethod(global);
      if (global.getReturnType() != Void.TYPE)
        current.emitPop();
    } else {
      current.emitPushCtx();
      bodySlot.load();
      buildTuple(indSlot);
      emitInvokeMethod(ELEngine.class, "callTarget", ELContext.class,
                       Object.class, Object[].class);
      current.emitPop();
    }

    // Increment induction variable.
    indSlot.load();
    buildConst(Math.abs(step));
    emitDynBinOp(step > 0 ? Token.ADD : Token.SUB);
    indSlot.store();
    current.emitPop();
    current.emitJump(headerB);

    // Cleanup.
    startBlock(exitB);
    current.emitPushNull();
    loopStack.pop();
    release(endSlot);
    release(indSlot);
    release(bodySlot);
    return true;
  }

  private Method getGlobalForStepBody(ELNode body) {
    if (!(body instanceof ELNode.IDENT v))
      return null;

    if (v.symbol != null)
      return null;

    var mc = MethodResolver.getInstance(elctx).resolveGlobalMethod(v.id);
    if (mc == null)
      return null;

    Method method = mc.getJavaMethod();
    if (method == null)
      return null;

    int paramCount = method.getParameterCount();
    if (paramCount > 0 && method.getParameterTypes()[0] == ELContext.class)
      paramCount--;
    if (paramCount != 1 || method.isVarArgs())
      return null;
    return method;
  }

  private boolean buildMathReduce(ELNode[] args, int op) {
    if (args.length == 0) {
      buildConst(0);
      return true;
    }

    build(args[0]);
    for (int i = 1; i < args.length; i++) {
      build(args[i]);
      emitDynBinOp(op);
    }
    return true;
  }

  // ── Literals: list, map, tuple, range ──

  public void visit(ELNode.CONS node) {
    build(node.head);
    build(node.tail);
    if (node.delay)
      current.emitNewDelayCons();
    else
      current.emitNewCons();
  }

  public void visit(ELNode.NIL node) {
    current.emitNil();
  }

  public void visit(ELNode.MAP node) {
    emitNewInstance(LinkedHashMap.class);
    for (int i = 0; i < node.keys.length; i++) {
      current.emitDup();
      build(node.keys[i]);
      build(node.values[i]);
      emitInvokeMethod(LinkedHashMap.class, "put", Object.class, Object.class);
      current.emitPop();
    }
  }

  public void visit(ELNode.TUPLE node) {
    buildTuple(node.elems);
  }

  public void visit(ELNode.RANGE node) {
    build(node.begin);
    build(node.next);
    if (node.exclude && node.end != null) {
      // Exclusive range [begin..<end]: push end-1 for inclusive range end
      build(node.end);
      buildConst(1);
      emitDynBinOp(Token.SUB);
    } else {
      build(node.end);
    }
    emitInvokeMethod(Runtime.class, "newRange", Object.class, Object.class,
                     Object.class);
  }

  public void visit(ELNode.ARRAY node) {
    // Resolve component type at compile time, default to Object.class.
    Object componentType = resolveClassAtCompileTime(node.type);
    if (componentType == null)
      componentType = node.type; // use string that resolved at runtime

    if (componentType instanceof Class &&
        buildConstantDimensionArray(node, (Class<?>)componentType))
      return;

    current.emitPushCtx();
    buildConst(componentType);

    // Build dimension expressions into a tuple.
    if (node.dims == null) {
      current.emitPushNull();
    } else {
      buildTuple(node.dims);
    }

    // Build init expressions into a tuple.
    if (node.init == null) {
      current.emitPushNull();
    } else {
      buildTuple(node.init);
    }

    emitInvokeMethod(Runtime.class, "newArray", ELContext.class,
                     Object.class, Object[].class, Object[].class);
  }

  private boolean buildConstantDimensionArray(ELNode.ARRAY node, Class<?> type) {
    if (node.dims != null) {
      for (ELNode e : node.dims) {
        if (e instanceof ELNode.NUMBER n && n.value instanceof Integer)
          continue;
        return false;
      }
    }

    if (node.dims == null || node.dims.length == 1) {
      int length = 0;
      if (node.dims != null)
        length = ((ELNode.NUMBER)node.dims[0]).value.intValue();
      if (node.init != null && length < node.init.length)
        length = node.init.length;

      current.emitNewArray(length, type);

      if (node.init != null) {
        for (int i = 0; i < node.init.length; i++) {
          current.emitDup();
          build(node.init[i]);
          current.emitStoreArray(i, type);
        }
      }
    } else {
      buildConst(type);
      current.emitCheckCast(Class.class);
      current.emitNewArray(node.dims.length, Integer.TYPE);
      for (int i = 0; i < node.dims.length; i++) {
        current.emitDup();
        buildConst(((ELNode.NUMBER)node.dims[i]).value.intValue());
        current.emitStoreArray(i, Integer.TYPE);
      }
      emitInvokeMethod(Array.class, "newInstance", Class.class, int[].class);
    }

    // FIXME: handle multi dimensional array
    return true;
  }

  public void visit(ELNode.XML node) {
    int namespaces = 0;
    Slot[] tmpSlots = null;

    if (node.keys != null) {
      for (ELNode key : node.keys) {
        if (key instanceof ELNode.STRINGVAL str &&
            (str.value.equals("xmlns") || str.value.startsWith("xmlns:")))
          namespaces++;
      }
    }

    // Setup environment and declare namespaces.
    if (namespaces != 0) {
      current.emitEnterScope();
      for (int i = 0; i < node.keys.length; i++) {
        if (node.keys[i] instanceof ELNode.STRINGVAL str &&
            (str.value.equals("xmlns") || str.value.startsWith("xmlns:"))) {
          String prefix;
          if (str.value.equals("xmlns"))
            prefix = XMLConstants.DEFAULT_NS_PREFIX;
          else
            prefix = str.value.substring(6);
          if (node.values[i] instanceof ELNode.Constant) {
            build(node.values[i]);
          } else {
            if (tmpSlots == null)
              tmpSlots = new Slot[node.keys.length];
            tmpSlots[i] = new Slot();
            build(node.values[i]);
            tmpSlots[i].store();
          }
          current.emitDeclareNS(prefix);
        }
      }
    }

    // Build XML tag, attributes, and children.
    current.emitPushEnv();
    build(node.tag);

    if (node.keys == null) {
      current.emitPushNull().emitPushNull();
    } else {
      buildTuple(node.keys);

      current.emitNewArray(node.values.length, Object.class);
      for (int i = 0; i < node.values.length; i++) {
        current.emitDup();
        if (tmpSlots != null && tmpSlots[i] != null)
          tmpSlots[i].load();
        else
          build(node.values[i]);
        current.emitStoreArray(i, Object.class);
      }
    }

    if (node.children == null) {
      current.emitPushNull();
    } else {
      buildTuple(node.children);
    }

    emitInvokeMethod(Runtime.class, "newXML", EvaluationContext.class,
                     Object.class, Object[].class, Object[].class,
                     Object[].class);

    if (namespaces != 0) {
      current.emitLeaveScope();
    }

    if (tmpSlots != null) {
      for (Slot slot : tmpSlots)
        release(slot);
    }
  }

  public void visit(ELNode.IN node) {
    build(node.left);
    build(node.right);
    current.emitIn();
    if (node.negative)
      current.emitNot();
  }

  public void visit(ELNode.INSTANCEOF node) {
    IRClass irc = resolveIRClass(node.type);
    if (irc != null) {
      build(node.right);
      current.emitInstanceOf(irc);
      if (node.negative)
        current.emitNot();
      return;
    }

    Class<?> jc = resolveJavaClass(node.type);
    if (jc != null) {
      build(node.right);
      current.emitInstanceOf(jc);
      if (node.negative)
        current.emitNot();
      return;
    }

    build(node.right);
    current.emitInstanceOf(node.getTypeName());
    if (node.negative)
      current.emitNot();
  }

  private void emitInstanceOf(String name) {
    try {
      Class<?> cls = ClassResolver.getInstance(elctx).resolveClass(name);
      current.emitInstanceOf(cls);
    } catch (ClassNotFoundException e) {
      current.emitInstanceOf(name);
    }
  }

  // ── Binary and unary arithmetic ──

  public void visit(ELNode.ADD node)    { buildBinaryOp(node); }
  public void visit(ELNode.SUB node)    { buildBinaryOp(node); }
  public void visit(ELNode.MUL node)    { buildBinaryOp(node); }
  public void visit(ELNode.DIV node)    { buildBinaryOp(node); }
  public void visit(ELNode.REM node)    { buildBinaryOp(node); }
  public void visit(ELNode.POW node)    { buildBinaryOp(node); }
  public void visit(ELNode.SHL node)    { buildBinaryOp(node); }
  public void visit(ELNode.CAT node)    { buildBinaryOp(node); }
  public void visit(ELNode.SHR node)    { buildBinaryOp(node); }
  public void visit(ELNode.USHR node)   { buildBinaryOp(node); }
  public void visit(ELNode.BITAND node) { buildBinaryOp(node); }
  public void visit(ELNode.BITOR node)  { buildBinaryOp(node); }
  public void visit(ELNode.XOR node)    { buildBinaryOp(node); }
  public void visit(ELNode.EQ node)     { buildBinaryOp(node); }
  public void visit(ELNode.NE node)     { buildBinaryOp(node); }
  public void visit(ELNode.IDEQ node)   { buildBinaryOp(node); }
  public void visit(ELNode.IDNE node)   { buildBinaryOp(node); }
  public void visit(ELNode.LT node)     { buildBinaryOp(node); }
  public void visit(ELNode.LE node)     { buildBinaryOp(node); }
  public void visit(ELNode.GT node)     { buildBinaryOp(node); }
  public void visit(ELNode.GE node)     { buildBinaryOp(node); }
  public void visit(ELNode.CMP node)    { buildBinaryOp(node); }

  public void visit(ELNode.POS node)    { /* nop */ }
  public void visit(ELNode.NEG node)    { buildUnaryOp(node); }
  public void visit(ELNode.BITNOT node) { buildUnaryOp(node); }
  public void visit(ELNode.EMPTY node)  { buildUnaryOp(node); }

  private void buildBinaryOp(ELNode.Binary node) {
    build(node.left);
    build(node.right);
    emitDynBinOp(node.op);
  }

  private void emitDynBinOp(int op) {
    switch (op) {
    case Token.ADD    -> current.emitAdd();
    case Token.SUB    -> current.emitSub();
    case Token.MUL    -> current.emitMul();
    case Token.DIV    -> current.emitDiv();
    case Token.IDIV   -> current.emitIDiv();
    case Token.REM    -> current.emitRem();
    case Token.POW    -> current.emitPow();
    case Token.CAT    -> current.emitCat();
    case Token.SHL    -> current.emitShl();
    case Token.SHR    -> current.emitShr();
    case Token.USHR   -> current.emitUShr();
    case Token.BITAND -> current.emitBitAnd();
    case Token.BITOR  -> current.emitBitOr();
    case Token.XOR    -> current.emitXor();
    case Token.EQ     -> current.emitEq();
    case Token.NE     -> current.emitNe();
    case Token.IDEQ   -> current.emitIdEq();
    case Token.IDNE   -> current.emitIdNe();
    case Token.LT     -> current.emitLt();
    case Token.LE     -> current.emitLe();
    case Token.GT     -> current.emitGt();
    case Token.GE     -> current.emitGe();
    case Token.CMP    -> current.emitCmp();
    default -> throw new UnsupportedOperationException();
    }
  }

  private void buildUnaryOp(ELNode.Unary node) {
    build(node.right);
    emitDynUnOp(node.op);
  }

  private void emitDynUnOp(int op) {
    switch (op) {
    case Token.BITNOT -> current.emitBitNot();
    case Token.NEG    -> current.emitNeg();
    case Token.POS    -> { /* unary plus is a no-op: value already on stack */ }
    case Token.EMPTY  -> current.emitEmpty();
    default -> throw new UnsupportedOperationException();
    }
  }

  public void visit(ELNode.PREFIX node) {
    if (node.oper.symbol != null) {
      if (node.oper.symbol.func != null) {
        buildDirectCall(node.oper.symbol, node.right);
      } else if (node.oper.symbol.def.expr instanceof ELNode.IDENT var &&
                 var.symbol != null && var.symbol.clazz != null) {
        buildClassCall(node.pos, var.symbol.clazz, node.right);
      } else {
        current.emitPushCtx();
        build(node.oper);
        buildTuple(node.right);
        emitInvokeMethod(ELEngine.class, "callTarget", ELContext.class,
                         Object.class, Object[].class);
      }
    } else {
      current.emitPushEnv();
      buildConst(node.oper.id);
      build(node.right);
      emitInvokeMethod(Runtime.class, "invokeOperator", EvaluationContext.class,
                       String.class, Object.class);
    }
  }

  public void visit(ELNode.INFIX node) {
    if (node.oper.symbol != null) {
      if (node.oper.symbol.func != null) {
        buildDirectCall(node.oper.symbol, node.left, node.right);
      } else if (node.oper.symbol.def.expr instanceof ELNode.IDENT var &&
                 var.symbol != null && var.symbol.clazz != null) {
        buildClassCall(node.pos, var.symbol.clazz, node.left, node.right);
      } else {
        current.emitPushCtx();
        build(node.oper);
        buildTuple(node.left, node.right);
        emitInvokeMethod(ELEngine.class, "callTarget", ELContext.class,
                         Object.class, Object[].class);
      }
    } else {
      current.emitPushEnv();
      buildConst(node.oper.id);
      build(node.left);
      build(node.right);
      emitInvokeMethod(Runtime.class, "invokeOperator", EvaluationContext.class,
                       String.class, Object.class, Object.class);
    }
  }

  // ── Logical AND/OR/NOT ──

  public void visit(ELNode.AND node) {
    int cont = allocBlockId();
    build(node.left);
    current.emitDup();
    current.emitJumpIfFalse(cont);
    current.emitPop();
    build(node.right);
    current.emitJump(cont);
    startBlock(cont);
  }

  public void visit(ELNode.OR node) {
    int cont = allocBlockId();
    build(node.left);
    current.emitDup();
    current.emitJumpIfTrue(cont);
    current.emitPop();
    build(node.right);
    current.emitJump(cont);
    startBlock(cont);
  }

  public void visit(ELNode.NOT node) {
    build(node.right);
    current.emitNot();
  }

  // ── Conditional (if/else / ?:) ──
  public void visit(ELNode.COND node) {
    int thenB = allocBlockId();
    int elseB = allocBlockId();
    int mergeB = allocBlockId();

    build(node.cond);
    current.emitJumpIfTrue(thenB);
    current.emitJump(elseB);
    startBlock(thenB);
    buildTail(node.left);
    current.emitJump(mergeB);
    startBlock(elseB);
    buildTail(node.right);
    current.emitJump(mergeB);
    startBlock(mergeB);
  }

  public void visit(ELNode.COALESCE node) {
    int cont = allocBlockId();
    build(node.left);
    current.emitDup();
    current.emitJumpIfNonNull(cont);
    current.emitPop();
    build(node.right);
    current.emitJump(cont);
    startBlock(cont);
  }

  public void visit(ELNode.ASSIGN node) {
    if (node.left instanceof ELNode.IDENT ident) {
      build(node.right);
      buildStoreVariable(ident);
      return;
    }

    if (node.left instanceof ELNode.ACCESS access) {
      build(node.right);
      buildStoreProperty(access);
      return;
    }

    if (node.left instanceof ELNode.TUPLE lhs) {
      if (node.right instanceof ELNode.TUPLE rhs &&
          isAssignableTuple(lhs, rhs)) {
        buildTupleAssign(lhs, rhs);
      } else {
        int failBlock = allocBlockId();
        int doneBlock = allocBlockId();

        build(node.right);
        buildDynamicTupleAssign(lhs, failBlock);
        current.emitJump(doneBlock);
        startBlock(failBlock);
        buildConst("tuple pattern not match");
        current.emitThrow();
        current.emitJump(doneBlock);
        startBlock(doneBlock);
      }
      return;
    }

    // should not happen, parser disabled other assign syntax
    throw new AssertionError();
  }

  public void visit(ELNode.ASSIGNOP node) {
    // Invoke dynamic assignment operator
    current.emitPushCtx();
    buildConst(node.binary.op);
    build(node.left);
    build(node.right);
    emitInvokeMethod(Runtime.class, "invokeAssignOp", ELContext.class,
                     Integer.class, Object.class, Object.class);

    // Now perform assignment.
    if (node.left instanceof ELNode.IDENT ident) {
      buildStoreVariable(ident);
    } else if (node.left instanceof ELNode.ACCESS access) {
      buildStoreProperty(access);
    } else {
      // should not happen, parser disabled other assignop syntax
      throw new AssertionError();
    }
  }

  public void visit(ELNode.INC node) {
    buildIncDec(node.right, true, node.is_preincrement);
  }

  public void visit(ELNode.DEC node) {
    buildIncDec(node.right, false, node.is_preincrement);
  }

  /**
   * Expand ++x / x++ / --x / x-- for local variables.
   */
  private void buildIncDec(ELNode target, boolean isInc, boolean isPre) {
    // Evaluate right value.
    build(target);
    if (!isPre)
      current.emitDup();

    // Increment or decrement the value.
    buildConst(1);
    emitDynBinOp(isInc ? Token.ADD : Token.SUB);

    // Assign to right value itself.
    if (target instanceof ELNode.IDENT ident)
      buildStoreVariable(ident);
    else if (target instanceof ELNode.ACCESS access)
      buildStoreProperty(access);
    else
      throw reportError(target.pos, _T(EL_READONLY_EXPRESSION));

    // If preincrement, stack top is the return value, otherwise pop and
    // keep duped value on top.
    if (!isPre)
      current.emitPop();
  }

  private boolean isAssignableTuple(ELNode.TUPLE lhs, ELNode.TUPLE rhs) {
    if (lhs.elems.length != rhs.elems.length)
      return false;

    for (int i = 0; i < lhs.elems.length; i++) {
      ELNode elem = lhs.elems[i];
      if (elem instanceof ELNode.IDENT)
        continue;
      if (elem instanceof ELNode.ACCESS)
        continue;
      if (elem instanceof ELNode.TUPLE t1 &&
          rhs.elems[i] instanceof ELNode.TUPLE t2 &&
          isAssignableTuple(t1, t2))
        continue;
      return false;
    }

    return true;
  }

  private void buildTupleAssign(ELNode.TUPLE lhs, ELNode.TUPLE rhs) {
    assert (lhs.elems.length == rhs.elems.length);
    if (lhs.elems.length == 0) {
      current.emitNewTuple(0);
      return;
    }

    // Must evaluate all right values before assign to left values.
    List<Slot> tmpSlots = new ArrayList<>();
    buildFlattenTuple(rhs.elems, tmpSlots);

    // Assign to left values sequentially.
    buildAssignFlattenTuple(lhs.elems, tmpSlots);
  }

  private void buildFlattenTuple(ELNode[] elems, List<Slot> tmpSlots) {
    for (ELNode elem : elems) {
      if (elem instanceof ELNode.TUPLE tt) {
        buildFlattenTuple(tt.elems, tmpSlots);
      } else {
        Slot varSlot = new Slot();
        tmpSlots.add(varSlot);
        build(elem);
        varSlot.store();
        current.emitPop();
      }
    }
  }

  private void buildAssignFlattenTuple(ELNode[] elems, List<Slot> tmpSlots) {
    for (ELNode elem : elems) {
      if (elem instanceof ELNode.TUPLE tt) {
        buildAssignFlattenTuple(tt.elems, tmpSlots);
      } else if (elem instanceof ELNode.IDENT ident) {
        Slot slot = tmpSlots.remove(0);
        slot.load();
        slot.release();
        buildStoreVariable(ident);
      } else if (elem instanceof ELNode.ACCESS access) {
        Slot slot = tmpSlots.remove(0);
        slot.load();
        slot.release();
        buildStoreProperty(access);
      } else {
        assert (false); // already checked by isAssignableTuple
      }
    }

    // Elements kept in stack, build a tuple as assign result.
    current.emitNewTuple(elems.length);
  }

  private void buildDynamicTupleAssign(ELNode.TUPLE lhs, int failBlock) {
    Slot rhsSlot = new Slot();
    rhsSlot.store();

    emitInvokeMethod(Object.class, "getClass");
    emitInvokeMethod(Class.class, "isArray");
    current.emitJumpIfFalse(failBlock);

    rhsSlot.load();
    emitInvokeMethod(Array.class, "getLength", Object.class);
    buildConst(lhs.elems.length);
    current.emitEq(K_INT);
    current.emitJumpIfFalse(failBlock);

    for (int i = 0; i < lhs.elems.length; i++) {
      rhsSlot.load();
      buildConst(i);
      current.emitUnbox(Integer.TYPE);
      emitInvokeMethod(Array.class, "get", Object.class, int.class);
      if (lhs.elems[i] instanceof ELNode.IDENT ident)
        buildStoreVariable(ident);
      else if (lhs.elems[i] instanceof ELNode.ACCESS acc)
        buildStoreProperty(acc);
      else if (lhs.elems[i] instanceof ELNode.TUPLE t)
        buildDynamicTupleAssign(t, failBlock);
      else
        throw new UnsupportedOperationException();
    }

    // Tuple elements still on stack, build a tuple as return value
    current.emitNewTuple(lhs.elems.length);
    rhsSlot.release();
  }

  public void visit(ELNode.EXPR node) {
    build(node.right);
  }

  public void visit(ELNode.Composite node) {
    if (node.elems.length == 0) {
      buildConst("");
    } else {
      emitNewInstance(StringBuilder.class);
      for (int i = 0; i < node.elems.length; i++) {
        if (node.elems[i] instanceof ELNode.STRINGVAL ||
            node.elems[i] instanceof ELNode.LITERAL) {
          build(node.elems[i]);
          emitInvokeMethod(StringBuilder.class, "append", String.class);
        } else {
          current.emitPushCtx();
          build(node.elems[i]);
          buildCoerce(String.class);
          emitInvokeMethod(StringBuilder.class, "append", String.class);
        }
      }
      emitInvokeMethod(StringBuilder.class, "toString");
    }
  }

  public void visit(ELNode.COMPOUND node) {
    if (node.exps.length == 0) {
      current.emitPushNull();
      return;
    }

    for (int i = 0; i < node.exps.length - 1; i++) {
      if (current.isDead())
        return;
      build(node.exps[i]);
      current.emitPop();
    }

    if (!current.isDead())
      buildTail(node.exps[node.exps.length - 1]);
  }

  public void visit(ELNode.WHILE node) {
    int header = allocBlockId();
    int body = allocBlockId();
    int exit = allocBlockId();

    loopStack.push(new LoopTargets(header, exit));
    current.emitJump(header);

    startBlock(header);
    build(node.cond);
    current.emitJumpIfTrue(body);
    current.emitJump(exit);

    startBlock(body);
    build(node.body);
    current.emitPop();
    current.emitJump(header);

    startBlock(exit);
    current.emitPushNull();

    // Exit block falls through to next — add RETURN at toplevel by caller
    loopStack.pop();
  }

  public void visit(ELNode.FOR node) {
    int header = allocBlockId();
    int body = allocBlockId();
    int cont = allocBlockId();
    int exit = allocBlockId();

    loopStack.push(new LoopTargets(cont, exit));

    if (node.init != null) {
      for (ELNode e : node.init) {
        build(e);
        current.emitPop();
      }
    }
    current.emitJump(header);

    startBlock(header);
    build(node.cond);
    current.emitJumpIfTrue(body);
    current.emitJump(exit);

    startBlock(body);
    build(node.body);
    current.emitPop();
    current.emitJump(cont);

    startBlock(cont);
    if (node.step != null) {
      for (ELNode e : node.step) {
        build(e);
        current.emitPop();
      }
    }
    current.emitJump(header);

    startBlock(exit);
    current.emitPushNull();
    loopStack.pop();
  }

  public void visit(ELNode.FOREACH node) {
    if (node.range instanceof ELNode.RANGE r) {
      if (r.isConstant())
        buildConstantRangedFor(node.var, node.index, r, node.body);
      else
        buildDynamicRangedFor(node.var, node.index, r, node.body);
    } else {
      buildIterateFor(node);
    }
  }

  private void buildConstantRangedFor(ELNode.DEFINE var, ELNode.DEFINE index,
                                      ELNode.RANGE range, ELNode body) {
    // Optimize for constant range.
    long begin = ((ELNode.NUMBER)range.begin).value.longValue();
    long step = 1;
    if (range.next != null) {
      step = ((ELNode.NUMBER)range.next).value.longValue() - begin;
    }

    long count = -1;
    if (range.end != null) {
      long end = ((ELNode.NUMBER)range.end).value.longValue();
      if (range.exclude)
        end--;
      count = (end - begin) / step + 1;
      if (count <= 0) {
        current.emitPushNull();
        return;
      }
    }

    // Register loop variable first to claim its pre-allocated slot, then
    // allocate temp vars after it to avoid slot collisions.
    Slot varSlot = var.symbol != null ? new Slot(var) : null;
    Slot idxSlot = new Slot(index);

    buildConst(0L);
    idxSlot.define();
    current.emitPop();
    if (varSlot != null) {
      buildConst(begin);
      varSlot.define();
      current.emitPop();
    }

    // Begin loop.
    int bodyB = allocBlockId();
    int headerB = range.end != null ? allocBlockId() : bodyB;
    int contB = allocBlockId();
    int exitB = allocBlockId();

    loopStack.push(new LoopTargets(contB, exitB));
    current.emitJump(headerB);

    // Generate loop condition.
    if (range.end != null) {
      startBlock(headerB);
      idxSlot.load();
      buildConst(count);
      current.emitLt(K_INT);
      current.emitJumpIfTrue(bodyB);
      current.emitJump(exitB);
    }

    // Generate loop body.
    startBlock(bodyB);
    build(body);
    current.emitPop();
    current.emitJump(contB);

    // Generate loop step.
    startBlock(contB);
    idxSlot.load();
    buildConst(1L);
    current.emitAdd(K_LONG);
    idxSlot.store();
    current.emitPop();

    if (varSlot != null) {
      varSlot.load();
      buildConst(step);
      current.emitAdd(K_LONG);
      varSlot.store();
      current.emitPop();
    }
    current.emitJump(headerB);

    // Cleanup
    startBlock(exitB);
    current.emitPushNull();
    loopStack.pop();
    release(varSlot);
    release(idxSlot);
  }

  private void buildDynamicRangedFor(ELNode.DEFINE var, ELNode.DEFINE index,
                                     ELNode.RANGE range, ELNode body) {
    // Register loop variable first to claim its pre-allocated slot.
    Slot varSlot = new Slot(var);
    Slot idxSlot = new Slot(index);
    Slot stepSlot = null;
    Slot countSlot = null;

    // Initialize local variables.
    if (range.next != null) {
      stepSlot = new Slot();
      build(range.next);
      build(range.begin);
      varSlot.define();
      current.emitSub(K_LONG);
      stepSlot.store(); // step = next - begin
      current.emitPop();
    } else {
      build(range.begin);
      varSlot.define();
      current.emitPop();
    }

    if (range.end != null) {
      countSlot = new Slot();
      build(range.end);
      if (range.exclude) {
        buildConst(1L);
        current.emitSub(K_LONG);
      }
      varSlot.load();
      current.emitSub(K_LONG);
      if (stepSlot != null) {
        stepSlot.load();
        current.emitDiv(K_LONG);
      }
      buildConst(1L);
      current.emitAdd(K_LONG);
      countSlot.store(); // count = (end - begin) / step + 1
      current.emitPop();
    }

    buildConst(0L);
    idxSlot.store();
    current.emitPop();

    int bodyB = allocBlockId();
    int headerB = range.end != null ? allocBlockId() : bodyB;
    int contB = allocBlockId();
    int exitB = allocBlockId();

    loopStack.push(new LoopTargets(contB, exitB));
    current.emitJump(headerB);

    // Generate loop condition.
    if (countSlot != null) {
      startBlock(headerB);
      idxSlot.load();
      countSlot.load();
      current.emitLt(K_LONG);
      current.emitJumpIfTrue(bodyB);
      current.emitJump(exitB);
    }

    // Generate loop body.
    startBlock(bodyB);
    build(body);
    current.emitPop();
    current.emitJump(contB);

    // Generate loop step.
    startBlock(contB);
    idxSlot.load();
    buildConst(1L);
    current.emitAdd(K_LONG);
    idxSlot.store();
    current.emitPop();

    varSlot.load();
    if (stepSlot != null)
      stepSlot.load();
    else
      buildConst(1L);
    emitDynBinOp(Token.ADD);
    varSlot.store();
    current.emitPop();
    current.emitJump(headerB);

    // Cleanup
    startBlock(exitB);
    current.emitPushNull();
    loopStack.pop();
    release(varSlot);
    release(idxSlot);
    release(stepSlot);
    release(countSlot);
  }

  private void buildIterateFor(ELNode.FOREACH node) {
    int header = allocBlockId();
    int body = allocBlockId();
    int exit = allocBlockId();

    loopStack.push(new LoopTargets(header, exit));

    // Register loop variable first to claim its pre-allocated slot.
    Slot varSlot = node.var.symbol != null ? new Slot(node.var) : null;
    Slot idxSlot = null;
    if (node.index != null) {
      idxSlot = new Slot(node.index);
      buildConst(-1L);
      idxSlot.define();
      current.emitPop();
    }
    Slot iterSlot = new Slot();

    build(node.range);
    if (node.range instanceof ELNode.CONS) {
      emitInvokeMethod(Iterable.class, "iterator");
      iterSlot.store();
      current.emitPop();
    } else {
      emitInvokeMethod(Runtime.class, "getIterator", Object.class);
      iterSlot.store();
      current.emitJumpIfNull(exit);
    }
    current.emitJump(header);

    startBlock(header);
    iterSlot.load();
    emitInvokeMethod(Iterator.class, "hasNext");
    current.emitJumpIfFalse(exit);
    current.emitJump(body);

    startBlock(body);
    iterSlot.load();
    emitInvokeMethod(Iterator.class, "next");
    if (varSlot != null)
      varSlot.store();
    current.emitPop();

    if (node.index != null) {
      idxSlot.load();
      buildConst(1L);
      current.emitAdd(K_INT);
      idxSlot.store();
      current.emitPop();
    }

    build(node.body);
    current.emitPop();
    current.emitJump(header);

    startBlock(exit);
    current.emitPushNull();
    loopStack.pop();
    release(varSlot);
    release(idxSlot);
    release(iterSlot);
  }

  public void visit(ELNode.BREAK node) {
    if (loopStack.isEmpty())
      throw reportError(node.pos, _T(EL_STATEMENT_NOT_IN_LOOP, "break"));
    current.emitJump(loopStack.peek().breakBlock());
  }

  public void visit(ELNode.CONTINUE node) {
    if (loopStack.isEmpty())
      throw reportError(node.pos, _T(EL_STATEMENT_NOT_IN_LOOP, "continue"));
    current.emitJump(loopStack.peek().continueBlock());
  }

  public void visit(ELNode.RETURN node) {
    // Make sure single entry single exit.
    if (exitBlock == -1)
      exitBlock = allocBlockId();
    if (node.right != null) {
      buildTail(node.right);
      current.emitJump(exitBlock);
    } else {
      current.emitPushNull();
      current.emitJump(exitBlock);
    }
  }

  private void emitReturn(boolean returnVoid) {
    if (exitBlock != -1)
      startBlock(exitBlock);
    if (returnVoid) {
      current.emitPop();
      current.emitPushNull();
      current.emitReturn();
    } else {
      current.emitReturn();
    }
  }

  public void visit(ELNode.THROW node) {
    build(node.cause);
    current.emitThrow();
  }

  public void visit(ELNode.ASSERT node) {
    build(node.exp);
    if (node.msg != null)
      build(node.msg);
    current.emitAssert(node.msg == null ? 1 : 2);
  }

  public void visit(ELNode.TRY node) {
    // Compile try body (zero-param closure).
    IRFunction body = buildScopedLambda((ELNode.LAMBDA)node.body);

    // Handlers: each handler is a DEFINE(id = exception var, expr = body).
    int count = node.handlers != null ? node.handlers.length : 0;
    IRFunction[] handlers = new IRFunction[count];
    String[] types = new String[count];
    for (int i = 0; i < count; i++) {
      types[i] = node.types[i];
      handlers[i] = buildScopedLambda((ELNode.LAMBDA)node.handlers[i]);
    }

    // Finally (optional, zero-param closure).
    IRFunction finalizer = null;
    if (node.finalizer != null) {
      finalizer = buildScopedLambda((ELNode.LAMBDA)node.finalizer);
    }

    // Construct the TryDescriptor.
    current.emitTry(new Descriptors.Try(body, handlers, types, finalizer));
  }

  public void visit(ELNode.SYNCHRONIZED node) {
    IRFunction body = buildScopedLambda((ELNode.LAMBDA)node.body);
    build(node.exp);
    current.emitSynchronized(body);
  }

  public void visit(ELNode.LAMBDA node) {
    IRFunction func = buildScopedLambda(node);
    current.emitClosure(func);
  }

  private IRFunction buildScopedLambda(ELNode.LAMBDA node) {
    IRFunction func = buildLambda(node);
    IRClass enclosingClass = currentScope.enclosingClass();
    if (enclosingClass != null)
      enclosingClass.add(func);
    else
      program.add(func);
    return func;
  }

  private IRFunction buildLambda(ELNode.LAMBDA node) {
    return buildLambda(node, null);
  }

  private IRFunction buildLambda(ELNode.LAMBDA node, IRClass initClass) {
    IRFunction func;
    if (node.symbol != null)
      func = node.symbol.func;
    else {
      // For anonymous lambda, use a pseudo Symbol to store IRFunction skeleton
      // so call-site can emit direct call. The owner is used to generate inner
      // closure class if the lambda is defined by an instance procedure.
      IRClass owner = currentScope.enclosingClass();
      int modifiers = Modifier.PUBLIC;
      if (owner == null || currentScope.isStaticScope())
        modifiers |= Modifier.STATIC;
      func = new IRFunction(owner, "<lambda>", node.vars.length,
                            node.varargs, modifiers);
      ELNode.DEFINE tmpdef = new ELNode.DEFINE(node.pos, "", null, null, node);
      node.symbol = new Symbol(node.scope, tmpdef);
      node.symbol.func = func;
    }

    IRBuilder nested = new IRBuilder(this, func, node.scope);

    // Propagate source file from the AST node
    if (node.file != null)
      nested.currentFile = node.file;

    // Reserve slots for all pre-allocated variables in this lambda scope.
    // Temp vars allocated via allocLocalVar will then start above the max
    // pre-allocated slot, avoiding collisions.
    nested.reserveSlots(node.scope.maxSlots);

    if (initClass != null) {
      IRClass superClass = (IRClass)initClass.base;
      nested.current.emitPushThis();
      nested.current.emitPushEnv();
      ELNode[] args = nested.getCallArgs(node.pos, superClass.init_proc,
                                         initClass.super_args,
                                         initClass.super_keys);
      nested.buildCallArgs(superClass.init_proc, args);
      nested.current.emitConstructor(superClass);
    }

    for (ELNode.DEFINE var : node.vars) {
      // Define global for captured lamba parameters.
      if (var.symbol != null && var.symbol.captured) {
        nested.current.emitPushVar(var.symbol.slot);
        nested.current.emitDefineGlobal(var.id, var.symbol.isFinal());
      }
    }

    nested.buildTail(node.body);

    // Returns null for void function. Other return type has no meaning in
    // current implementation where we lacks type inferrer.
    nested.emitReturn("void".equals(node.rtype));

    return nested.finish().withDefaults(getDefaultValues(node.vars));
  }

  /**
   * Extract default parameter values from lambda definitions.
   */
  private Object[] getDefaultValues(ELNode.DEFINE[] vars) {
    Object[] defs = null;
    for (int i = 0; i < vars.length; i++) {
      if (vars[i].expr != null) {
        if (defs == null)
          defs = new Object[vars.length];
        defs[i] = const_value(vars[i].expr);
      }
    }
    return defs;
  }

  private Object const_value(ELNode node) {
    if (node instanceof ELNode.NUMBER x)
      return x.value;
    if (node instanceof ELNode.STRINGVAL x)
      return x.value;
    if (node instanceof ELNode.CHARVAL x)
      return x.value;
    if (node instanceof ELNode.BOOLEANVAL x)
      return x.value;
    if (node instanceof ELNode.SYMBOL x)
      return x.value;
    if (node instanceof ELNode.REGEXP x)
      return x.value;
    if (node instanceof ELNode.NIL)
      return Cons.nil();
    if (node instanceof ELNode.NULL)
      return null;

    if (node instanceof ELNode.TUPLE x) {
      Object[] a = new Object[x.elems.length];
      for (int i = 0; i < a.length; i++)
        a[i] = const_value(x.elems[i]);
      return a;
    }

    if (node instanceof ELNode.CONS x && !x.delay) {
      Object h = const_value(x.head);
      Object t = const_value(x.tail);
      if (t instanceof Seq)
        return new Cons(h, (Seq)t);
    }

    if (node instanceof ELNode.IDENT var && var.symbol != null &&
        var.symbol.clazz != null) {
      return var.symbol.clazz;
    }

    if (node instanceof ELNode.ACCESS acc &&
        acc.index instanceof ELNode.STRINGVAL key) {
      if (acc.right instanceof ELNode.IDENT base &&
          base.symbol != null && base.symbol.clazz != null) {
        for (ELNode.DEFINE def : base.symbol.clazz.node.cvars) {
          if (def.symbol.isPublic() &&
              !(def.symbol.def.expr instanceof ELNode.LAMBDA) &&
              !(def.symbol.def.expr instanceof ELNode.CLASSDEF)) {
            return new Descriptors.Field(base.symbol.clazz, key.value);
          }
        }
      }

      Class<?> c = resolveJavaClass(acc.right);
      if (c != null) {
        try {
          Field f = c.getField(key.value);
          if (Modifier.isPublic(f.getModifiers()) &&
              Modifier.isStatic(f.getModifiers()))
            return f;
        } catch (NoSuchFieldException e) { /* fallthrough */ }
      }
    }

    Class<?> c = resolveJavaClass(node);
    if (c != null)
      return c;

    throw reportError(node.pos, _T(EL_DEFAULT_VALUE_NOT_CONSTANT));
  }

  public void visit(ELNode.CLASSDEF node) {
    // Retrieve IRClass skeleton that created at SymbolTableBuilder.
    IRClass clazz = node.symbol.clazz;
    assert clazz != null;
    program.add(clazz);

    // Determine the base class. The base class must exist at compile time.
    Object base;
    if (node.base == null) {
      // No base class, defaults to java.lang.Object.
      base = Object.class;
    } else if ((base = resolveIRClass(node.base)) == null) {
      if (node.base instanceof ELNode.IDENT ident && ident.symbol != null) {
        if (ident.symbol.def.expr instanceof ELNode.CLASS c) {
          // The base class is an imported java class.
          base = loadClassAtCompileTime(node.pos, c.name);
        } else {
          throw reportError(node.pos, _T(EL_NOT_A_CLASS, ident.id));
        }
      } else {
        // The base class may be an implicitly imported java class or a full
        // class name.
        base = loadClassAtCompileTime(node.pos, node.getClassName());
      }
    }

    // Resolve interfaces.
    Class<?>[] interfaces;
    if (node.ifaces != null) {
      interfaces = new Class<?>[node.ifaces.length];
      for (int i = 0; i < interfaces.length; i++) {
        interfaces[i] = loadClassAtCompileTime(node.pos, node.ifaces[i]);
        if (!interfaces[i].isInterface())
          throw reportError(node.pos, node.ifaces[i] + " is not an interface");
      }
    } else {
      interfaces = new Class<?>[0];
    }

    if (base instanceof Class<?> c && c.isInterface()) {
      interfaces = Arrays.copyOf(interfaces, interfaces.length + 1);
      interfaces[interfaces.length - 1] = c;
      base = Object.class;
    }

    clazz.base = base;
    clazz.interfaces = interfaces;

    // Determine the outer class.
    if (!node.symbol.isStatic()) {
      clazz.outer = node.scope.parent.enclosingClass();
      if (clazz.outer != null)
        clazz.outer.inners.add(clazz);
    }

    // Recursively build nested classes.
    for (ELNode.DEFINE var : node.cvars) {
      if (var.expr instanceof ELNode.CLASSDEF)
        build(var.expr);
    }
    for (ELNode.DEFINE var : node.ivars) {
      if (var.expr instanceof ELNode.CLASSDEF)
        build(var.expr);
    }

    // Build class and instance procedures.
    for (ELNode.DEFINE var : node.cvars) {
      if (var.expr instanceof ELNode.LAMBDA) {
        build(var.expr);
        current.emitPop();
      }
    }
    for (ELNode.DEFINE var : node.ivars) {
      if (var.expr instanceof ELNode.LAMBDA) {
        build(var.expr);
        current.emitPop();
      }
    }

    // Build class init proc.
    if (clazz.clinit_proc != null)
      buildLambda(clazz.clinit_proc);

    // Build instance init proc.
    if (clazz.base instanceof IRClass) {
      buildLambda(clazz.init_proc, clazz);
    } else {
      buildLambda(clazz.init_proc);
    }

    buildConst(clazz);
  }

  // ── Pattern matching ──

  /**
   * Compile a MATCH expression as a series of if-else chains.
   */
  public void visit(ELNode.MATCH node) {
    // Evaluate all args, store in temp locals except it's already a local var.
    int nargs = node.args.length;
    Slot[] argSlots = new Slot[nargs];
    for (int i = 0; i < nargs; i++) {
      if (node.args[i] instanceof ELNode.IDENT ident &&
          ident.symbol != null && !ident.symbol.captured) {
        argSlots[i] = new Slot(ident);
      } else {
        argSlots[i] = new Slot();
        build(node.args[i]);
        argSlots[i].store();
        current.emitPop();
      }
    }

    // Allocate blocks for each case entry point
    int[] nextCase = new int[node.alts.length + 1]; // +1 for default
    for (int ci = 0; ci < node.alts.length; ci++)
      nextCase[ci] = allocBlockId();
    nextCase[node.alts.length] = allocBlockId(); // default/error block

    int exitBlock = allocBlockId();

    // Jump to first case
    current.emitJump(nextCase[0]);

    for (int ci = 0; ci < node.alts.length; ci++) {
      ELNode.CASE c = node.alts[ci];
      int failBlock = nextCase[ci + 1];

      startBlock(nextCase[ci]);

      // Each case gets its own control scope for variable bindings. On failure,
      // leaveScope discards bindings.
      Scope prevScope = currentScope;
      currentScope = c.scope;
      if (c.scope.hasCaptures())
        current.emitEnterScope();

      // Compile patterns for each column
      if (c.patterns != null) {
        for (int pi = 0; pi < c.patterns.length; pi++) {
          argSlots[pi].load();
          buildMatchPattern(argSlots[pi], (ELNode)c.patterns[pi], failBlock);
          current.emitJumpIfFalse(failBlock);
        }
      }

      if (c.guards == null) {
        // No guards, evaluate the single body.
        assert c.bodies != null && c.bodies.length == 1;
        buildTail(c.bodies[0]);
        current.emitJump(exitBlock);
      } else {
        // Evaluate each guard and body
        assert c.bodies.length == c.guards.length;
        for (int i = 0; i < c.guards.length; i++) {
          int nextGuard = -1;
          if (c.guards[i] != null) {
            if (i != c.guards.length - 1) {
              nextGuard = allocBlockId();
              build(c.guards[i]);
              current.emitJumpIfFalse(nextGuard);
            } else {
              build(c.guards[i]);
              current.emitJumpIfFalse(failBlock);
            }
          }
          buildTail(c.bodies[i]);
          current.emitJump(exitBlock);
          if (nextGuard != -1)
            startBlock(nextGuard);
        }
      }

      // Leave the case scope.
      if (c.scope.hasCaptures())
        current.emitLeaveScope();
      currentScope = prevScope;
    }

    // Default block.
    startBlock(nextCase[node.alts.length]);
    if (node.deflt != null) {
      buildTail(node.deflt);
    } else {
      current.emitNew(EvaluationException.class);
      current.emitPushCtx();
      buildConst(EL_PATTERN_NOT_MATCH);
      emitInvokeMethod(Resources.class, "getText", String.class);
      emitConstructor(EvaluationException.class, ELContext.class, String.class);
      current.emitThrowException();
    }
    current.emitJump(exitBlock);

    startBlock(exitBlock);
    for (Slot slot : argSlots)
      slot.release();
  }

  /**
   * Compile a single pattern check, leaving TRUE on stack if matched.
   */
  private void buildMatchPattern(Slot argSlot, ELNode pat, int failBlock) {
    if (pat instanceof ELNode.DEFINE def) {
      boolean argConsumed = false;

      // Type check if annotated.
      if (def.type != null) {
        argConsumed = true;
        emitInstanceOf(def.type);
        current.emitJumpIfFalse(failBlock);
      }

      // As-pattern check.
      if (def.expr != null) {
        if (argConsumed)
          argSlot.load();
        argConsumed = true;
        buildMatchPattern(argSlot, def.expr, failBlock);
        current.emitJumpIfFalse(failBlock);
      }

      // Wildcard: always matches.
      if ("_".equals(def.id)) {
        if (!argConsumed)
          current.emitPop();
        current.emitPushTrue();
        return;
      }

      // Variable binding -> bind to new pattern variable.
      if (argConsumed)
        argSlot.load();
      if (def.symbol.captured)
        current.emitDefineGlobal(def.id, def.symbol.isFinal());
      else
        current.emitStoreVarPop(def.symbol.slot);
      current.emitPushTrue();
      return;
    }

    if (pat instanceof ELNode.IDENT var) {
      if (var.symbol.captured)
        current.emitPushGlobal(var.id);
      else
        current.emitPushVar(var.symbol.slot);
      emitDynBinOp(Token.EQ);
      return;
    }

    if (pat instanceof ELNode.NOT not) {
      buildMatchPattern(argSlot, not.right, failBlock);
      current.emitNot();
      return;
    }

    if (pat instanceof ELNode.OR or) {
      int tryRight = allocBlockId();
      int done = allocBlockId();

      // Left branch, argSlot already on stack top.
      buildMatchPattern(argSlot, or.left, tryRight);
      current.emitJumpIfFalse(tryRight);
      current.emitJump(done); // matched -> skip right

      startBlock(tryRight);
      argSlot.load();
      buildMatchPattern(argSlot, or.right, failBlock);
      current.emitJumpIfFalse(failBlock);
      current.emitJump(done);
      startBlock(done);
      current.emitPushTrue();
      return;
    }

    if (pat instanceof ELNode.NUMBER n) {
      buildConst(n.value);
      emitDynBinOp(Token.EQ);
      return;
    }

    if (pat instanceof ELNode.STRINGVAL s) {
      buildConst(s.value);
      emitDynBinOp(Token.EQ);
      return;
    }

    if (pat instanceof ELNode.BOOLEANVAL b) {
      buildConst(b.value);
      emitDynBinOp(Token.EQ);
      return;
    }

    if (pat instanceof ELNode.CHARVAL c) {
      buildConst(c.value);
      emitDynBinOp(Token.EQ);
      return;
    }

    if (pat instanceof ELNode.NULL) {
      current.emitJumpIfNonNull(failBlock);
      current.emitPushTrue();
      return;
    }

    if (pat instanceof ELNode.SYMBOL sym) {
      buildConst(sym.value);
      current.emitIdEq();
      return;
    }

    if (pat instanceof ELNode.CLASS cls) {
      emitInstanceOf(cls.name);
      return;
    }

    if (pat instanceof ELNode.REGEXP re) {
      current.emitInstanceOf(String.class);
      current.emitJumpIfFalse(failBlock);
      buildConst(re.value); // the pattern
      current.emitCheckCast(java.util.regex.Pattern.class);
      argSlot.load();       // the string to match
      current.emitCheckCast(CharSequence.class);
      emitInvokeMethod(java.util.regex.Pattern.class, "matcher", CharSequence.class);
      emitInvokeMethod(java.util.regex.Matcher.class, "matches");
      return;
    }

    if (pat instanceof ELNode.EXPR e) {
      build(e.right);
      emitDynBinOp(Token.EQ);
      return;
    }

    if (pat instanceof ELNode.TUPLE t) {
      Slot tmpSlot = null;

      emitInvokeMethod(Object.class, "getClass");
      emitInvokeMethod(Class.class, "isArray");
      current.emitJumpIfFalse(failBlock);

      argSlot.load();
      emitInvokeMethod(Array.class, "getLength", Object.class);
      buildConst(t.elems.length);
      current.emitEq(K_INT);
      current.emitJumpIfFalse(failBlock);

      for (int i = 0; i < t.elems.length; i++) {
        if (ELNode.isWildcard(t.elems[i]))
          continue;
        argSlot.load();
        buildConst(i);
        current.emitUnbox(Integer.TYPE);
        emitInvokeMethod(Array.class, "get", Object.class, int.class);
        if (!isSimplePattern(t.elems[i])) {
          if (tmpSlot == null)
            tmpSlot = new Slot();
          tmpSlot.store();
        }
        buildMatchPattern(tmpSlot, t.elems[i], failBlock);
        current.emitJumpIfFalse(failBlock);
      }

      release(tmpSlot);
      current.emitPushTrue();
      return;
    }

    if (pat instanceof ELNode.CONS cons) {
      Slot seqSlot = new Slot();
      Slot tmpSlot = null;
      if (!isSimplePattern(cons.head) || !isSimplePattern(cons.tail))
        tmpSlot = new Slot();

      current.emitInstanceOf(List.class);
      current.emitJumpIfFalse(failBlock);
      current.emitPushCtx();
      argSlot.load();
      buildCoerce(Seq.class);
      seqSlot.store();
      emitInvokeMethod(List.class, "isEmpty");
      current.emitJumpIfTrue(failBlock);

      if (!ELNode.isWildcard(cons.head)) {
        seqSlot.load();
        emitInvokeMethod(Seq.class, "head");
        if (!isSimplePattern(cons.head))
          tmpSlot.store();
        buildMatchPattern(tmpSlot, cons.head, failBlock);
        current.emitJumpIfFalse(failBlock);
      }

      if (!ELNode.isWildcard(cons.tail)) {
        seqSlot.load();
        emitInvokeMethod(Seq.class, "tail");
        if (!isSimplePattern(cons.tail))
          tmpSlot.store();
        buildMatchPattern(tmpSlot, cons.tail, failBlock);
        current.emitJumpIfFalse(failBlock);
      }

      release(tmpSlot);
      release(seqSlot);
      current.emitPushTrue();
      return;
    }

    if (pat instanceof ELNode.NIL) {
      emitDynUnOp(Token.EMPTY);
      return;
    }

    if (pat instanceof ELNode.RANGE) {
      current.emitPop(); // Re-push arg after build tuple
      build(pat);
      argSlot.load();
      emitInvokeMethod(List.class, "contains", Object.class);
      return;
    }

    if (pat instanceof ELNode.MAP map) {
      current.emitPop(); // Re-push arg for each property.
      Slot tmpSlot = null;
      for (int i = 0; i < map.keys.length; i++) {
        assert map.keys[i] instanceof ELNode.STRINGVAL;
        current.emitPushEnv();
        argSlot.load();
        current.emitInvokeDynamic(new Descriptors.Indy(
          getValueBootstrap, ((ELNode.STRINGVAL)map.keys[i]).value, Object.class,
          EvaluationContext.class, Object.class));
        if (!isSimplePattern(map.values[i])) {
          if (tmpSlot == null)
            tmpSlot = new Slot();
          tmpSlot.store();
        }
        buildMatchPattern(tmpSlot, map.values[i], failBlock);
        current.emitJumpIfFalse(failBlock);
        if (i != map.keys.length - 1)
          argSlot.load();
      }
      release(tmpSlot);
      current.emitPushTrue();
      return;
    }

    if (pat instanceof ELNode.NEW data) {
      ELNode.IDENT base = (ELNode.IDENT)data.base;
      ELNode[] args = data.args;
      int argc = args.length;

      if (base.symbol != null &&
          base.symbol.def.expr instanceof ELNode.CLASSDEF cdef) {

        if (data.keys == null && (cdef.vars == null ||
                                  cdef.vars.length != argc)) {
          current.emitPushFalse();
          return;
        }

        IRClass clazz = cdef.symbol.clazz;
        Slot tmpSlot = null;

        current.emitInstanceOf(clazz);
        current.emitJumpIfFalse(failBlock);

        if (argc == 0) {
          current.emitPushTrue();
          return;
        }

        if (data.keys != null) {
          // Matches for object properties.
          for (int i = 0; i < argc; i++) {
            current.emitPushEnv();
            argSlot.load();
            current.emitInvokeDynamic(new Descriptors.Indy(
              getValueBootstrap, data.keys[i], Object.class,
              EvaluationContext.class, Object.class));
            if (!isSimplePattern(args[i])) {
              if (tmpSlot == null)
                tmpSlot = new Slot();
              tmpSlot.store();
            }
            buildMatchPattern(tmpSlot, args[i], failBlock);
            current.emitJumpIfFalse(failBlock);
          }
        } else {
          // Matches for constructor variables.
          for (int i = 0; i < argc; i++) {
            if (ELNode.isWildcard(args[i]))
              continue;
            argSlot.load();
            current.emitCheckCast(clazz);
            current.emitGetField(clazz, cdef.vars[i].id);
            if (!isSimplePattern(args[i])) {
              if (tmpSlot == null)
                tmpSlot = new Slot();
              tmpSlot.store();
            }
            buildMatchPattern(tmpSlot, args[i], failBlock);
            current.emitJumpIfFalse(failBlock);
          }
        }
        release(tmpSlot);
      } else {
        Class<?> cls;
        String[] slots = null;

        if (base.symbol != null &&
            base.symbol.def.expr instanceof ELNode.CLASS c) {
          cls = loadClassAtCompileTime(base.pos, c.name);
          slots = c.slots;
        } else {
          cls = resolveClassAtCompileTime(base.id);
          if (cls == null) {
            emitInstanceOf(base.id);
            return;
          }
        }

        if (argc == 0) {
          current.emitInstanceOf(cls);
          return;
        }

        if (data.keys != null) {
          slots = data.keys;
        } else {
          if (slots == null) {
            Data d = cls.getAnnotation(Data.class);
            if (d != null)
              slots = d.value();
          }
          if (slots == null || slots.length != argc) {
            current.emitPushFalse();
            return;
          }
        }

        current.emitInstanceOf(cls);
        current.emitJumpIfFalse(failBlock);

        Slot tmpSlot = null;
        for (int i = 0; i < argc; i++) {
          current.emitPushEnv();
          argSlot.load();
          current.emitInvokeDynamic(new Descriptors.Indy(
            getValueBootstrap, slots[i], Object.class,
            EvaluationContext.class, Object.class));
          if (!isSimplePattern(args[i])) {
            if (tmpSlot == null)
              tmpSlot = new Slot();
            tmpSlot.store();
          }
          buildMatchPattern(tmpSlot, args[i], failBlock);
          current.emitJumpIfFalse(failBlock);
        }
        release(tmpSlot);
      }

      current.emitPushTrue();
      return;
    }

    // Should not reach here.
    throw new UnsupportedOperationException();
  }

  private boolean isSimplePattern(ELNode pat) {
    if (pat instanceof ELNode.DEFINE def)
      return def.type == null && def.expr == null;

    if (pat instanceof ELNode.REGEXP)
      return false;

    return pat instanceof ELNode.Constant ||
           pat instanceof ELNode.IDENT ||
           pat instanceof ELNode.EXPR;
  }

  public void visit(ELNode.LET node) {
    Slot argSlot;
    if (node.right instanceof ELNode.IDENT ident &&
        ident.symbol != null && !ident.symbol.captured) {
      argSlot = new Slot(ident);
    } else {
      argSlot = new Slot();
      build(node.right);
      argSlot.store();
      current.emitPop();
    }

    int failBlock = allocBlockId();
    int exitBlock = allocBlockId();

    argSlot.load();
    buildMatchPattern(argSlot, node.left, failBlock);
    current.emitJumpIfFalse(failBlock);
    current.emitJump(exitBlock);

    startBlock(failBlock);
    buildConst("pattern not match");
    current.emitThrow();
    current.emitJump(exitBlock);

    startBlock(exitBlock);
    argSlot.load();
    argSlot.release();
  }

  public void visit(ELNode.NEW node) {
    // NEW can be used to create new instance of a Java class, a user defined
    // elite class or a data class. First let me lookup symbol table to see if
    // the target is a CLASSDEF or CLASS, then try Java class.

    IRClass irc = resolveIRClass(node.base);
    if (irc != null) {
      if (irc.isSingleton()) {
        current.emitGetStatic(irc, "$singleton");
      } else {
        ELNode[] args = getCallArgs(node.pos, irc.init_proc, node.args,
                                    node.keys);
        current.emitNew(irc);
        current.emitPushEnv();
        buildCallArgs(irc.init_proc, args);
        current.emitConstructor(irc);
      }
      return;
    }

    if (node.base.symbol != null &&
        node.base.symbol.def.expr instanceof ELNode.CLASS c &&
        c.slots == null) {
      Class<?> cls = loadClassAtCompileTime(node.pos, c.name);
      if (buildNew(cls, node.args, node.props))
        return;
    }

    Class<?> cls = resolveClassAtCompileTime(node.getClassName());
    if (cls != null && buildNew(cls, node.args, node.props))
      return;

    // Resolve Java class and constructor at runtime.
    current.emitPushEnv();
    buildConst(node.getClassName());
    buildTuple(node.args);
    if (node.props == null) {
      current.emitPushNull();
    } else {
      emitNewInstance(LinkedHashMap.class);
      for (int i = 0; i < node.props.keys.length; i++) {
        current.emitDup();
        build(node.props.keys[i]);
        build(node.props.values[i]);
        emitInvokeMethod(LinkedHashMap.class, "put", Object.class, Object.class);
        current.emitPop();
      }
    }
    emitInvokeMethod(Runtime.class, "newInstance", EvaluationContext.class,
                     String.class, Object[].class, Map.class);
  }

  private boolean buildNew(Class<?> cls, ELNode[] args, ELNode.MAP props) {
    Constructor<?> cons = resolveConstructor(cls, args.length);
    if (cons == null)
      return false;

    Class<?>[] types = cons.getParameterTypes();
    current.emitNew(cls);
    for (int i = 0; i < args.length; i++) {
      if (types[i] == Object.class) {
        build(args[i]);
      } else {
        current.emitPushCtx();
        build(args[i]);
        buildCoerce(TypeCoercion.getBoxedType(types[i]));
        if (types[i].isPrimitive())
          current.emitUnbox(types[i]);
      }
    }
    current.emitConstructor(cons);

    if (props != null) {
      Slot tmpSlot = new Slot();
      tmpSlot.store();
      current.emitPushCtx();
      emitInvokeMethod(ELContext.class, "getELResolver");
      for (int i = 0; i < props.keys.length; i++) {
        current.emitDup();
        current.emitPushCtx();
        tmpSlot.load();
        build(props.keys[i]);
        build(props.values[i]);
        emitInvokeMethod(ELResolver.class, "setValue", ELContext.class,
                         Object.class, Object.class, Object.class);
      }
      tmpSlot.release();
    }

    return true;
  }

  private Constructor<?> resolveConstructor(Class<?> cls, int nargs) {
    Constructor<?> cons = null;
    for (Constructor<?> c : cls.getConstructors()) {
      if (c.getParameterCount() == nargs && !c.isVarArgs()) {
        if (cons != null)
          return null;
        cons = c;
      }
    }
    return cons;
  }

  public void visitNode(ELNode node) {
    throw reportError(node.pos, "Unknown node type");
  }

  // ── Block management ──

  private int allocBlockId() {
    return nextBlockId++;
  }

  /**
   * Seal current block into blockMap and start a new block with the given ID.
   */
  private void startBlock(int blockId) {
    assert blockId != currentBlockId;
    int[] code = current.toArray();
    blocks.add(new Block(currentBlockId, code, linePcMapping));
    current.clear();
    currentBlockId = blockId;
    linePcMapping.clear();
  }

  // ── Local management ──

  private class Slot {
    private final int slot;
    private final boolean captured;
    private final boolean isTemporary;

    Slot() {
      slot = allocLocalVar();
      captured = false;
      isTemporary = true;
    }

    Slot(ELNode.DEFINE var) {
      if (var != null && var.symbol != null) {
        captured = var.symbol.captured;
        slot = captured ? putConstant(var.id) : var.symbol.slot;
        isTemporary = false;
      } else {
        slot = allocLocalVar();
        captured = false;
        isTemporary = true;
      }
    }

    Slot(ELNode.IDENT var) {
      if (var != null && var.symbol != null) {
        captured = var.symbol.captured;
        slot = captured ? putConstant(var.id) : var.symbol.slot;
        isTemporary = false;
      } else {
        slot = allocLocalVar();
        captured = false;
        isTemporary = true;
      }
    }

    void define() {
      if (captured) {
        current.emitDefineGlobal(slot);
        current.emitPushNull();
      } else {
        current.emitStoreVar(slot);
      }
    }

    void store() {
      if (captured)
        current.emitStoreGlobal(slot);
      else
        current.emitStoreVar(slot);
    }

    void load() {
      if (captured)
        current.emitPushGlobal(slot);
      else
        current.emitPushVar(slot);
    }

    void release() {
      if (isTemporary)
        freeSlots.push(slot);
    }

    private int allocLocalVar() {
      if (!freeSlots.isEmpty()) {
        return freeSlots.pop();
      }
      int slot = nextTempSlot++;
      maxLocals = Math.max(maxLocals, nextTempSlot);
      return slot;
    }
  }

  private void release(Slot slot) {
    if (slot != null)
      slot.release();
  }

  /**
   * Reserve space in `varNames` up to (and including) the given slot index.
   * Temp vars allocated after this call will start above the reserved range.
   */
  private void reserveSlots(int maxSlots) {
    maxLocals = nextTempSlot = maxSlots;
  }

  // ── Finalization ──

  IRFunction finish() {
    // Finish current block.
    blocks.add(new Block(currentBlockId, current.toArray(), linePcMapping));

    // Scan instructions to build CFG.
    Map<Integer, Block> blockMap = new HashMap<>();
    for (Block block : blocks)
      blockMap.put(block.id, block);
    for (Block block : blocks) {
      for (var v = new InstructionView(block.code); v.inBounds(); v.advance()) {
        if (v.isJump()) {
          Block target = blockMap.get(v.jumpTarget());
          block.successors.set(target.id);
          target.predecessors.set(block.id);
        }
      }
    }

    // Run optimization passes.
    boolean opt = ELProgram.OPT_LEVEL != 0;
    if (opt) {
      runJumpThreadingOpt(blockMap);
      runDeadBlockElim(blockMap);
      runDeadStoreElim();
      runBranchFolder(blockMap);
    }

    // Merge block into contiguous code.
    IntList merged = new IntList();
    for (Block block : blocks) {
      if (!block.isDead()) {
        // Swap jump condition to make fallthrough opportunity.
        if (merged.size() >= 2) {
          var v1 = new InstructionView(merged.data(), merged.size() - 2,
                                       merged.size());
          var v2 = v1.peek();
          if (v1.isJump() && v1.opcode() != JUMP &&
              v1.jumpTarget() == block.id && // fallthrough
              v2.opcode() == JUMP) {
            int target1 = v1.jumpTarget();
            int target2 = v2.jumpTarget();
            v1.replace(inverseJump(v1.opcode()), 0, target2);
            v2.replace(JUMP, 0, target1);
          }
        }

        // Remove fallthrough jump.
        if (IRFormat.match(merged.back(), JUMP, block.id))
          merged.reset(merged.size() - 1);

        block.pc = merged.size();
        merged.addAll(block.code);
      }
    }

    int[] offsets = buildBlockOffsets(merged);

    func.populate(merged.toArray(), maxLocals, offsets,
                  constants.toArray(new Object[0]),
                  buildDebugInfo(), null);
    return func;
  }

  private static int inverseJump(int opcode) {
    return switch (opcode) {
      case JUMP_IF_TRUE -> JUMP_IF_FALSE;
      case JUMP_IF_FALSE -> JUMP_IF_TRUE;
      case JUMP_IF_NULL -> JUMP_IF_NONNULL;
      case JUMP_IF_NONNULL -> JUMP_IF_NULL;
      default -> opcode;
    };
  }

  private void runJumpThreadingOpt(Map<Integer, Block> blockMap) {
    Map<Integer, Integer> threadingJumps = new HashMap<>();
    for (Block block : blocks) {
      // If the only instruction in a block is a jump, threading jumps to target.
      if (block.code.size() == 1) {
        int inst = block.code.get(0);
        if (IRFormat.opcode(inst) == JUMP) {
          int target = IRFormat.operand(inst);
          threadingJumps.put(block.id, target);
          threadingJumps.replaceAll((k, v) -> v == block.id ? target : v);
        }
      }
    }

    // Update CFG.
    for (Map.Entry<Integer, Integer> e : threadingJumps.entrySet()) {
      Block from = blockMap.get(e.getKey());
      Block to = blockMap.get(e.getValue());
      for (int i = from.predecessors.nextSetBit(0); i >= 0;
           i = from.predecessors.nextSetBit(i + 1)) {
        Block pred = blockMap.get(i);
        pred.successors.clear(from.id);
        pred.successors.set(to.id);
        to.predecessors.set(pred.id);
      }
      from.predecessors.clear(); // dead
    }

    // Apply all threading jumps. May produce dead blocks.
    if (!threadingJumps.isEmpty()) {
      for (Block block : blocks) {
        InstructionView v = new InstructionView(block.code);
        for (; v.inBounds(); v.advance()) {
          if (v.isJump()) {
            int target = threadingJumps.getOrDefault(v.jumpTarget(), -1);
            if (target != -1)
              v.replace(v.opcode(), 0, target);
          }
        }
      }
    }
  }

  private void runDeadBlockElim(Map<Integer, Block> blockMap) {
    BitSet visited = new BitSet();
    boolean changed;
    do {
      changed = false;
      for (Block block : blocks) {
        if (visited.get(block.id))
          continue;
        if (block.isDead()) {
          // Make all successors dead.
          for (int i = block.successors.nextSetBit(0); i >= 0;
               i = block.successors.nextSetBit(i + 1)) {
            Block succ = blockMap.get(i);
            succ.predecessors.clear(block.id);
          }
          block.successors.clear();
          visited.set(block.id);
          changed = true;
        }
      }
    } while (changed);
  }

  /**
   * Eliminate dead stores: STORE_VAR/STORE_VAR_POP whose slot is never read
   * (no matching PUSH_VAR).  Dead STORE_VAR becomes NOP (value stays on stack),
   * dead STORE_VAR_POP becomes POP.  Unused slots are then compacted away via
   * remapping.
   */
  private boolean runDeadStoreElim() {
    // Collect used slots.
    BitSet usedSlots = new BitSet();
    for (Block block : blocks) {
      if (!block.isDead()) {
        final int[] data = block.code.data();
        final int n = block.code.size();
        for (int i = 0; i < n; i++) {
          // We only check read slot, write only slots are dead.
          if (IRFormat.opcode(data[i]) == PUSH_VAR)
            usedSlots.set(IRFormat.operand(data[i]));
        }
      }
    }

    // Preserve function parameters.
    usedSlots.set(0, func.paramCount());

    // Remove unused slots.
    int[] slotMap = new int[maxLocals];
    int nextSlot = 0;
    for (int i = 0; i < maxLocals; i++) {
      if (usedSlots.get(i))
        slotMap[i] = nextSlot++;
      else
        slotMap[i] = -1;
    }

    // Nothing to remap if all slots are used and contiguous.
    if (nextSlot == maxLocals)
      return false;

    // Remap slot indices and remove dead stores.
    boolean changed = false;
    for (Block block : blocks) {
      if (block.isDead())
        continue;

      final int[] data = block.code.data();
      final int n = block.code.size();
      for (int i = 0; i < n; i++) {
        int inst = data[i];
        int op = IRFormat.opcode(inst);
        int varIndex = IRFormat.operand(inst);

        switch (op) {
        case PUSH_VAR:
          data[i] = IRFormat.pack(PUSH_VAR, 0, slotMap[varIndex]);
          break;

        case STORE_VAR:
          if ((varIndex = slotMap[varIndex]) == -1) {
            // Remove dead store.
            data[i] = IRFormat.pack(NOP, 0, 0);
            changed = true;
          } else {
            data[i] = IRFormat.pack(STORE_VAR, 0, varIndex);
          }
          break;

        case STORE_VAR_POP:
          if ((varIndex = slotMap[varIndex]) == -1) {
            // Dead store and pop, replace with POP.
            data[i] = IRFormat.pack(POP, 0, 0);
            changed = true;
          } else {
            data[i] = IRFormat.pack(STORE_VAR_POP, 0, varIndex);
          }
          break;
        }
      }
    }

    maxLocals = nextSlot;
    return changed;
  }

  /**
   * Fold fallthrough blocks. Run peephole optimizer on every block.
   * Eliminate dead blocks after optimized.
   */
  private void runBranchFolder(Map<Integer, Block> blockMap) {
    boolean changed;
    int iteration = 0;

    do {
      changed = false;
      iteration++;

      Map<Integer, BitSet> killed = new HashMap<>();
      BitSet survived = new BitSet();
      Block pred = null;

      for (Block block : blocks) {
        if (block.isDead())
          continue;

        int currentBlockId = block.id;
        IntList code, optCode;

        if (pred != null && canFoldBranch(pred, block)) {
          // Fold current block into predecessor.
          changed = true;
          currentBlockId = pred.id;
          code = block.code;
          optCode = pred.code;
          optCode.reset(optCode.size() - 1); // remove fallthrough jump

          // Transfer successors of current block to predecessor block.
          pred.successors.clear(block.id);
          for (int id = block.successors.nextSetBit(0);
               id != -1; id = block.successors.nextSetBit(id + 1)) {
            Block succ = blockMap.get(id);
            succ.predecessors.clear(block.id);
            succ.predecessors.set(pred.id);
            pred.successors.set(succ.id);
          }

          // Current block is dead.
          block.predecessors.clear();
          block.successors.clear();

          // Consolidate debug line table.
          pred.lineMap.putAll(block.lineMap);
        } else if (iteration == 1) {
          // Run the full peephole optimization for first iteration.
          code = new IntList(block.code.toArray());
          optCode = block.code;
          optCode.clear();
          pred = block;
        } else {
          pred = block;
          continue;
        }

        loop:
        for (var v = new InstructionView(code); v.inBounds(); v.advance()) {
          boolean conditionalJump = v.isJump() && v.opcode() != JUMP;
          int jumpTarget = conditionalJump ? v.jumpTarget() : -1;

          if (peephole.run(optCode, v.opcode(), v.operand())) {
            if (conditionalJump) {
              switch (IRFormat.opcode(optCode.back())) {
              case JUMP:
                // A conditional jump is optimized to unconditional jump, the
                // current block is dead.
                for (v.advance(); v.inBounds(); v.advance()) {
                  // Scan dead code to find other jump target and kill them.
                  if (v.isJump())
                    killed.computeIfAbsent(v.jumpTarget(), x -> new BitSet())
                      .set(currentBlockId);
                }
                break loop;
              case JUMP_IF_TRUE, JUMP_IF_FALSE, JUMP_IF_NULL, JUMP_IF_NONNULL:
                if (jumpTarget == IRFormat.operand(optCode.back())) {
                  // The conditional jump still here (may inversed condition),
                  // continue to process.
                  continue;
                }
                // fallthrough
              default:
                // The conditional jump has gone, the target may dead.
                killed.computeIfAbsent(jumpTarget, x -> new BitSet())
                  .set(currentBlockId);
                continue;
              }
            }
          } else {
            if (v.isJump())
              survived.set(v.jumpTarget()); // the jump target is survived
            optCode.add(v.inst());
          }
        }
      }

      // Process killed blocks.
      killed.keySet().removeIf(survived::get);
      for (var e : killed.entrySet()) {
        Block kill = blockMap.get(e.getKey());
        for (int id = e.getValue().nextSetBit(0);
             id != -1; id = e.getValue().nextSetBit(id + 1)) {
          Block from = blockMap.get(id);
          from.successors.clear(kill.id);
          kill.predecessors.clear(from.id);
        }
      }

      // Recursively remove dead blocks.
      runDeadBlockElim(blockMap);

      // Eliminate dead stores for merged or optimized code. Rerun full peephole
      // optimization if dead stores eliminated.
      if (changed || iteration == 1) {
        if (runDeadStoreElim())
          iteration = 0;
      }
    } while (changed);
  }

  private boolean canFoldBranch(Block from, Block to) {
    if (from.successors.cardinality() != 1 ||
        to.predecessors.cardinality() != 1)
      return false;
    if (!IRFormat.match(from.code.back(), JUMP, to.id))
      return false;
    for (var v = new InstructionView(from.code); v.inBounds(); v.advance()) {
      if (v.isJump())
        return v.opcode() == JUMP && v.jumpTarget() == to.id &&
               v.offset() == from.code.size() - 1;
    }
    return false;
  }

  private int[] buildBlockOffsets(IntList code) {
    // Get all reachable blocks.
    BitSet targets = new BitSet();
    targets.set(0);
    for (var v = new InstructionView(code); v.inBounds(); v.advance()) {
      if (v.isJump())
        targets.set(v.jumpTarget());
    }

    // Remove hole in block IDs.
    List<Block> compactBlocks = new ArrayList<>();
    Map<Integer, Integer> remap = new HashMap<>();
    Map<Integer, Integer> pcMap = new HashMap<>();
    for (Block block : blocks) {
      if (targets.get(block.id)) {
        int dupId = pcMap.getOrDefault(block.pc, -1);
        if (dupId != -1) {
          block.mappedId = dupId;
          remap.put(block.id, block.mappedId);
        } else {
          block.mappedId = compactBlocks.size();
          compactBlocks.add(block);
          remap.put(block.id, block.mappedId);
          pcMap.put(block.pc, block.mappedId);
        }
      }
    }

    // Remap block IDs after dead block eliminated.
    for (var v = new InstructionView(code.data(), 0, code.size());
         v.inBounds(); v.advance()) {
      if (v.isJump()) {
        int mappedId = remap.get(v.jumpTarget());
        v.replace(v.opcode(), 0, mappedId);
      }
    }


    // Build offset table.
    int[] offsets = new int[compactBlocks.size()];
    for (Block block : compactBlocks) {
      offsets[block.mappedId] = block.pc;
    }
    return offsets;
  }

  private DebugInfo buildDebugInfo() {
    // Consolidate debug info.
    SortedMap<Integer, Integer> pcLineMapping = new TreeMap<>();
    for (Block block : blocks) {
      if (!block.isDead()) {
        for (var kv : block.lineMap.entrySet()) {
          pcLineMapping.put(kv.getValue() + block.pc, kv.getKey());
        }
      }
    }

    // Build the pc to line mapping table.
    IntList pcLineTable = new IntList();
    for (var kv : pcLineMapping.entrySet()) {
      pcLineTable.add(kv.getKey());
      pcLineTable.add(kv.getValue());
    }

    return new DebugInfo(currentFile, pcLineTable.toArray());
  }

  // ── Convenience emits ──

  int putConstant(Object value) {
    return constIndex.computeIfAbsent(value, k -> {
      constants.add(k);
      return constants.size() - 1;
    });
  }

  Object getConstant(int index) {
    return constants.get(index);
  }

  private void buildConst(Object value) {
    if (value == null)
      current.emitPushNull();
    else if (value instanceof Boolean)
      buildConst((Boolean)value);
    else
      current.emitPushConst(value);
  }

  private void buildConst(Boolean value) {
    if (value)
      current.emitPushTrue();
    else
      current.emitPushFalse();
  }

  private void buildCoerce(Class<?> type) {
    current.emitInvokeDynamic(new Descriptors.Indy(
      coerceBootstrap, "coerce", type, ELContext.class, Object.class));
  }

  private void emitInvokeMethod(Class<?> c, String name, Class<?>... types) {
    try {
      Method method = c.getMethod(name, types);
      current.emitInvokeMethod(method);
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  private void emitNewInstance(Class<?> c) {
    try {
      current.emitNew(c);
      current.emitConstructor(c.getConstructor());
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  private void emitConstructor(Class<?> c, Class<?>... types) {
    try {
      current.emitConstructor(c.getConstructor(types));
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  private void buildTuple(Class<?> type, ELNode... elems) {
    buildTuple(type, elems, 0, elems.length);
  }

  private void buildTuple(Class<?> type, ELNode[] elems, int start, int len) {
    current.emitNewArray(len, type);
    for (int i = 0; i < len; i++) {
      current.emitDup();
      build(elems[start + i]);
      if (type != Object.class)
        current.emitCheckCast(type);
      current.emitStoreArray(i, type);
    }
  }

  private void buildTuple(ELNode... args) {
    buildTuple(Object.class, args);
  }

  private void buildTuple(ELNode[] args, int start, int len) {
    buildTuple(Object.class, args, start, len);
  }

  private void buildTuple(Slot... slots) {
    current.emitNewArray(slots.length, Object.class);
    for (int i = 0; i < slots.length; i++) {
      current.emitDup();
      slots[i].load();
      current.emitStoreArray(i, Object.class);
    }
  }

  // ── Static API ──

  public static IRFunction compile(ELContext elctx, ELNode node) {
    SymbolTable symTable = SymbolTableBuilder.build(node);
    IRFunction func = new IRFunction("<expr>", 0, false);
    IRBuilder b = new IRBuilder(elctx, new IRProgram(null), func,
                                symTable.currentScope());
    b.build(node);
    b.current.emitReturn();
    return b.finish();
  }

  public static IRProgram compile(ELContext elctx, ELProgram program) {
    SymbolTable symTable = SymbolTableBuilder.build(program);
    reportSymbolTableError(program, symTable);

    List<ELNode> defs = program.getDefinitions();
    List<ELNode> exps = program.getExpressions();

    IRFunction func = new IRFunction("<program>", 0, false);
    IRProgram output = new IRProgram(func);
    IRBuilder b = new IRBuilder(elctx, output, func, symTable.currentScope());
    b.setFile(program.getFilename());

    // Reserve slots for all pre-allocated program-level variables. After this,
    // allocLocalVar will allocate temp vars above the max slot.
    b.reserveSlots(symTable.currentScope().maxSlots);

    // Compile definitions for forward declaration.
    for (ELNode def : defs) {
      b.build(def);
      b.current.emitPop();
    }

    // Compile expressions
    if (!exps.isEmpty()) {
      for (int i = 0; i < exps.size() - 1; i++) {
        b.build(exps.get(i));
        b.current.emitPop();
      }
      b.build(exps.get(exps.size() - 1));
      b.emitReturn(false);
    } else {
      b.current.emitPushNull();
      b.emitReturn(false);
    }

    b.finish();
    return output;
  }

  private static void reportSymbolTableError(ELProgram prog,
                                             SymbolTable symTable) {
    if (symTable.getErrors().isEmpty())
      return;

    StringBuilder sb = new StringBuilder();
    String file = prog.getFilename();
    for (SymbolTable.Error e : symTable.getErrors()) {
      sb.append("\n");
      if (file != null)
        sb.append(file).append(':');
      sb.append(Position.line(e.pos())).append(':')
        .append(Position.column(e.pos())).append(": ");
      sb.append(e.message());
    }
    throw new ParseException(file, 1, 1, sb.toString());
  }
}
