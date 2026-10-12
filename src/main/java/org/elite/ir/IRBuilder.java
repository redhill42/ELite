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
import elite.lang.Closure;
import elite.lang.Decimal;
import elite.lang.MathLib;
import elite.lang.Rational;
import elite.lang.Seq;
import elite.lang.annotation.Data;
import elite.lang.annotation.Expando;
import org.elite.eval.ELProgram;
import org.elite.eval.ELUtils;
import org.elite.eval.EvaluationContext;
import org.elite.eval.EvaluationException;
import org.elite.eval.GlobalScope;
import org.elite.eval.Runtime;
import org.elite.eval.TypeCoercion;
import org.elite.eval.closure.MethodClosure;
import org.elite.eval.seq.Cons;
import org.elite.eval.seq.DelayCons;
import org.elite.parser.ELNode;
import org.elite.parser.ParseError;
import org.elite.parser.ParseException;
import org.elite.parser.Position;
import org.elite.parser.Token;
import org.elite.resolver.ClassResolver;
import org.elite.resolver.MethodResolver;
import org.elite.resources.Resources;
import org.elite.type.ArrayType;
import org.elite.type.ClassObjectType;
import org.elite.type.ConstructorType;
import org.elite.type.ELiteClassObjectType;
import org.elite.type.ELiteClassType;
import org.elite.type.FieldType;
import org.elite.type.FunctionType;
import org.elite.type.MemberType;
import org.elite.type.PrimitiveType;
import org.elite.type.Types;
import org.elite.util.BeanUtils;

import javax.el.ELContext;
import javax.xml.XMLConstants;
import java.beans.IntrospectionException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.elite.ir.SymbolTable.Symbol;
import static org.elite.ir.SymbolTable.Scope;

import static org.elite.eval.TypeCoercion.*;
import static org.elite.ir.IRFormat.*;
import static org.elite.ir.Opcode.*;
import static org.elite.eval.ELUtils.*;
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

  // The class for an expando closure and a map for expando closures.
  private Class<?> expandoClass;
  private final ExpandoMap expandoMap;

  // Used to pass custom build action to another build method.
  interface CustomAction {
    void run();
  }

  private static final ELNode PUSH_THIS = new ELNode.NULL(0);

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
  private final IREmitter emitter;
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

  // Recorded errors.
  private final List<ParseError> errors;

  private static final Method getValueBootstrap;
  private static final Method getIndexedValueBootstrap;
  private static final Method setValueBootstrap;
  private static final Method setIndexedValueBootstrap;
  private static final Method invokeBootstrap;
  private static final Method callBootstrap;
  private static final Method constructBootstrap;
  private static final Method coerceBootstrap;
  private static final Method assignOpBootstrap;

  static {
    try {
      getValueBootstrap = DynamicBootstrap.class.getMethod(
        "getValueBootstrap", MethodHandles.Lookup.class, String.class,
        MethodType.class);
      getIndexedValueBootstrap = DynamicBootstrap.class.getMethod(
        "getIndexedValueBootstrap", MethodHandles.Lookup.class, String.class,
        MethodType.class);
      setValueBootstrap = DynamicBootstrap.class.getMethod(
        "setValueBootstrap", MethodHandles.Lookup.class, String.class,
        MethodType.class);
      setIndexedValueBootstrap = DynamicBootstrap.class.getMethod(
        "setIndexedValueBootstrap", MethodHandles.Lookup.class, String.class,
        MethodType.class);
      invokeBootstrap = DynamicBootstrap.class.getMethod(
        "invokeBootstrap", MethodHandles.Lookup.class, String.class,
        MethodType.class, int.class, String[].class);
      callBootstrap = DynamicBootstrap.class.getMethod(
        "callBootstrap", MethodHandles.Lookup.class, String.class,
        MethodType.class, String[].class);
      constructBootstrap = DynamicBootstrap.class.getMethod(
        "constructBootstrap", MethodHandles.Lookup.class, String.class,
        MethodType.class, Class.class);
      coerceBootstrap = CoercionBootstrap.class.getMethod(
        "coerceBootstrap", MethodHandles.Lookup.class, String.class,
        MethodType.class);
      assignOpBootstrap = OperatorBootstrap.class.getMethod(
        "assignOpBootstrap", MethodHandles.Lookup.class, String.class,
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
    this.emitter = new IREmitter(this);
    this.currentScope = scope;
    this.constants = new ArrayList<>();
    this.constIndex = new HashMap<>();
    this.expandoMap = new ExpandoMap();
    this.errors = new ArrayList<>();
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
    this.emitter = new IREmitter(this);
    this.currentScope = scope;
    this.currentFile = parent.currentFile;
    this.expandoMap = parent.expandoMap;
    this.errors = parent.errors;

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

  Class<?> loadClassAtCompileTime(int pos, String name) {
    try {
      return ClassResolver.getInstance(elctx).resolveClass(name);
    } catch (ClassNotFoundException ex) {
      reportError(pos, _T(EL_CLASS_NOT_FOUND, name));
      return Object.class;
    }
  }

  private void reportError(int pos, String message) {
    errors.add(new ParseError(pos, message, null));
    if (errors.size() >= 20)
      throw new ParseException(currentFile, errors);

    // Suppress further code generation.
    emitter.clear();
    emitter.setDead();
  }

  private void failIfErrors() {
    if (!errors.isEmpty())
      throw new ParseException(currentFile, errors);
  }

  // ============ MAIN DISPATCH ============

  private void buildNode(ELNode node) {
    if (node == null) {
      emitter.emitPushNull();
      return;
    }

    if (node.scope != null) {
      Scope prevScope = currentScope;
      currentScope = node.scope;
      if (!(node instanceof ELNode.LAMBDA) &&
          !(node instanceof ELNode.CLASSDEF) &&
          node.scope.hasCaptures()) {
        // Set up new evaluation context if any variables captured in this scope.
        emitter.emitEnterScope();
        node.accept(this);
        emitter.emitLeaveScope();
      } else {
        node.accept(this);
      }
      currentScope = prevScope;
    } else {
      node.accept(this);
    }

    if (node.pos != Position.NOPOS) {
      int line = Position.line(node.pos);
      int pc = emitter.size();
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
    emitter.emitPushNull();
  }

  public void visit(ELNode.REGEXP node) {
    buildConst(node.value);
  }

  public void visit(ELNode.SYMBOL node) {
    buildConst(node.value);
  }

  public void visit(ELNode.CONST node) {
    if (node.value instanceof CustomAction action)
      action.run();
    else
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
      buildConst(loadClassAtCompileTime(node.pos, c.getClassName()));
    } else {
      build(node.expr);
    }

    // Define global or local variable according to it's captured flag.
    if (node.symbol.captured) {
      if (node.symbol.scope.isProgramScope()) {
        // CLASS nodes and CLASSDEF node needn't store to a variable. They are
        // already resolved at compile time.
        if (node.expr instanceof ELNode.CLASS ||
            node.expr instanceof ELNode.CLASSDEF)
          return;

        program.addGlobal(node.id);
        emitter.emitPutStatic(null, node.id);
      } else {
        emitter.emitDefineGlobal(node.id, node.symbol.isFinal() ||
                                          node.symbol.clazz != null);
        emitter.emitPushNull();
      }
    } else {
      emitter.emitStoreVar(node.symbol.slot);
    }
  }

  public void visit(ELNode.IDENT node) {
    if (node.symbol != null && node.symbol.clazz != null) {
      buildConst(node.symbol.clazz);
    } else if (node.symbol != null && node.symbol.scope.isClassScope()) {
      IRClass currentClass = currentScope.enclosingClass();
      IRClass ownerClass = node.symbol.scope.frontier.symbol.clazz;
      if (ownerClass == currentClass) {
        // If the variable defined in a class scope, and current enclosing scope
        // (it must be a lambda scope) is directly enclosed in this class scope,
        // then the variable is referencing the class' instance variable.
        if (node.id.equals("this") || node.id.equals("self"))
          emitter.emitPushThis();
        else if (node.symbol.def.expr instanceof ELNode.LAMBDA) {
          emitter.emitClosure(node.symbol.func);
        } else if (node.symbol.isStatic())
          emitter.emitGetStatic(currentClass, node.id);
        else {
          emitter.emitGetField(node.id);
        }
      } else {
        if (node.symbol.def.expr instanceof ELNode.LAMBDA) {
          emitter.emitClosure(node.symbol.func);
        } else if (node.symbol.isStatic()) {
          emitter.emitGetStatic(ownerClass, node.id);
        } else {
          IRClass outer = currentClass.outer;
          emitter.emitPushThis();
          emitter.emitGetField(currentClass, "$outer");
          while (outer != ownerClass) {
            emitter.emitGetField(outer, "$outer");
            outer = outer.outer;
          }
          emitter.emitGetField(ownerClass, node.id);
        }
      }
    } else {
      if (node.symbol == null) {
        if (expandoClass != null && buildLoadExpandoProperty(node.id))
          return;

        if (buildLoadBaseClassField(node.pos, node.id, false))
          return;

        Class<?> c = resolveJavaClass(node);
        if (c != null) {
          buildConst(c);
          return;
        }
      }

      if (node.symbol != null && node.symbol.captured &&
          node.symbol.scope.isProgramScope())
        emitter.emitGetStatic(null, node.id);
      else if (node.symbol == null || node.symbol.captured)
        emitter.emitPushGlobal(node.id);
      else
        emitter.emitPushVar(node.symbol.slot);
    }
  }

  private void buildStoreVariable(ELNode.IDENT node) {
    if (node.symbol != null &&
        (node.symbol.clazz != null || node.symbol.func != null)) {
      reportError(node.pos, _T(EL_VARIABLE_NOT_WRITABLE, node.id));
      return;
    }

    if (node.symbol != null && node.symbol.scope.isClassScope()) {
      IRClass currentClass = currentScope.enclosingClass();
      IRClass ownerClass = node.symbol.scope.frontier.symbol.clazz;
      if (ownerClass == currentClass) {
        if (node.id.equals("this")) {
          reportError(node.pos,
                      _T(EL_PROPERTY_NOT_WRITABLE, currentClass.name, node.id));
          return;
        }

        if (node.symbol.isFinal() &&
            !func.name().equals("<init>") && !func.name().equals("<clinit>")) {
          reportError(node.pos,
                      _T(EL_PROPERTY_NOT_WRITABLE, currentClass.name, node.id));
          return;
        }

        if (node.symbol.isStatic())
          emitter.emitPutStatic(currentClass, node.id);
        else
          emitter.emitPutField(node.id);
      } else {
        if (node.symbol.isFinal()) {
          reportError(node.pos,
                      _T(EL_PROPERTY_NOT_WRITABLE, currentClass.name, node.id));
          return;
        }

        if (node.symbol.isStatic())
          emitter.emitPutStatic(ownerClass, node.id);
        else {
          IRClass outer = currentClass.outer;
          emitter.emitPushThis();
          emitter.emitGetField(currentClass, "$outer");
          while (outer != ownerClass) {
            emitter.emitGetField(outer, "$outer");
            outer = outer.outer;
          }
          emitter.emitPutField(ownerClass, node.id);
        }
      }
    } else {
      if (node.symbol != null && node.symbol.isFinal()) {
        reportError(node.pos, _T(EL_VARIABLE_NOT_WRITABLE, node.id));
        return;
      }

      if (node.symbol == null) {
        if (expandoClass != null && buildStoreExpandoProperty(node.pos, node.id))
          return;
        if (buildStoreBaseClassField(node.pos, node.id, false))
          return;
      }

      if (node.symbol != null && node.symbol.captured &&
          node.symbol.scope.isProgramScope())
        emitter.emitPutStatic(null, node.id);
      else if (node.symbol == null || node.symbol.captured)
        emitter.emitStoreGlobal(node.id);
      else
        emitter.emitStoreVar(node.symbol.slot);
    }
  }

  private boolean buildLoadBaseClassField(int pos, String id, boolean isSuper) {
    IRClass clazz = currentScope.enclosingClass();
    if (clazz == null)
      return false;

    if (!isSuper || clazz.base instanceof IRClass) {
      if (isSuper)
        clazz = (IRClass)clazz.base;
      boolean isSuperClass = isSuper;
      do {
        Optional<ELNode.DEFINE> var =
          Stream.concat(
              Stream.concat(Arrays.stream(clazz.node.cvars),
                            Arrays.stream(clazz.node.ivars)),
              clazz.node.vars != null ? Arrays.stream(clazz.node.vars)
                                      : Stream.empty())
            .filter(x -> x.id.equals(id))
            .findFirst();

        if (var.isPresent()) {
          ELNode.DEFINE def = var.get();
          if (def.expr instanceof ELNode.CLASSDEF)
            reportError(pos, _T(EL_PROPERTY_NOT_FOUND, clazz.name, id));
          if (isSuperClass && def.symbol.isPrivate())
            reportError(pos, _T(EL_ILLEGAL_ACCESS, clazz.name, id));
          if (currentScope.isStaticScope() && !def.symbol.isStatic())
            reportError(pos, _T(EL_STATIC_CONTEXT_ACCESS_INSTANCE_MEMBER, id));

          if (def.expr instanceof ELNode.CLASSDEF cdef) {
            buildConst(cdef.symbol.clazz);
          } else if (def.expr instanceof ELNode.LAMBDA fn) {
            emitter.emitClosure(fn.symbol.func);
          } else if (def.symbol.isStatic()) {
            emitter.emitGetStatic(clazz, id);
          } else {
            emitter.emitPushThis();
            emitter.emitGetField(clazz, id);
          }
          return true;
        }

        if (clazz.base instanceof IRClass) {
          clazz = (IRClass)clazz.base;
          isSuperClass = true;
        } else {
          break;
        }
      } while (true);
    }

    for (Class<?> baseClass = (Class<?>)clazz.base; baseClass != null;
         baseClass = baseClass.getSuperclass()) {
      try {
        Field field = baseClass.getDeclaredField(id);
        if (Modifier.isPublic(field.getModifiers()) ||
            Modifier.isProtected(field.getModifiers())) {
          if (Modifier.isStatic(field.getModifiers())) {
            emitter.emitGetStatic(field);
          } else {
            emitter.emitPushThis();
            emitter.emitGetField(field);
          }
          return true;
        }
      } catch (NoSuchFieldException e) {
        // continue
      }
    }

    return false;
  }

  private boolean buildStoreBaseClassField(int pos, String id, boolean isSuper) {
    IRClass clazz = currentScope.enclosingClass();
    if (clazz == null)
      return false;

    if (!isSuper || clazz.base instanceof IRClass) {
      if (isSuper)
        clazz = (IRClass)clazz.base;
      boolean isSuperClass = isSuper;
      do {
        Optional<ELNode.DEFINE> var =
          Stream.concat(
              Stream.concat(Arrays.stream(clazz.node.cvars),
                            Arrays.stream(clazz.node.ivars)),
              clazz.node.vars != null ? Arrays.stream(clazz.node.vars)
                                     : Stream.empty())
            .filter(x -> x.id.equals(id))
            .findFirst();

        if (var.isPresent()) {
          ELNode.DEFINE def = var.get();
          if (isSuperClass && def.symbol.isPrivate())
            reportError(pos, _T(EL_ILLEGAL_ACCESS, clazz.name, id));
          if (currentScope.isStaticScope() && !def.symbol.isStatic())
            reportError(pos, _T(EL_STATIC_CONTEXT_ACCESS_INSTANCE_MEMBER, id));
          if (def.symbol.isFinal() ||
              def.expr instanceof ELNode.CLASSDEF ||
              def.expr instanceof ELNode.LAMBDA)
            reportError(pos, _T(EL_PROPERTY_NOT_WRITABLE, clazz.name, id));

          if (def.symbol.isStatic()) {
            emitter.emitPutStatic(clazz, id);
          } else {
            emitter.emitPushThis();
            emitter.emitPutField(clazz, id);
          }
          return true;
        }

        if (clazz.base instanceof IRClass) {
          clazz = (IRClass)clazz.base;
          isSuperClass = true;
        } else {
          break;
        }
      } while (true);
    }

    for (Class<?> baseClass = (Class<?>)clazz.base; baseClass != null;
         baseClass = baseClass.getSuperclass()) {
      try {
        Field field = baseClass.getDeclaredField(id);
        if (Modifier.isPublic(field.getModifiers()) ||
            Modifier.isProtected(field.getModifiers())) {
          if (Modifier.isFinal(field.getModifiers()))
            reportError(pos, _T(EL_PROPERTY_NOT_WRITABLE, baseClass.getName(), id));
          if (Modifier.isStatic(field.getModifiers())) {
            emitter.emitPutStatic(field);
          } else {
            emitter.emitPushThis();
            emitter.emitPutField(field);
          }
          return true;
        }
      } catch (NoSuchFieldException e) {
        // continue
      }
    }

    return false;
  }

  private boolean buildLoadExpandoProperty(String name) {
    try {
      Method method = BeanUtils.getReadMethod(expandoClass, name);
      if (method != null) {
        emitter.emitPushVar(0);
        emitter.emitCheckCast(expandoClass);
        emitter.emitInvokeMethod(method);
        return true;
      }
    } catch (IntrospectionException e) {
      // fallthrough
    }

    try {
      Field field = expandoClass.getField(name);
      if (Modifier.isStatic(field.getModifiers())) {
        emitter.emitGetStatic(field);
      } else {
        emitter.emitPushVar(0);
        emitter.emitCheckCast(expandoClass);
        emitter.emitGetField(field);
      }
      return true;
    } catch (NoSuchFieldException e) {
      // fallthrough
    }

    return false;
  }

  private boolean buildStoreExpandoProperty(int pos, String name) {
    try {
      if (BeanUtils.getWriteMethod(expandoClass, name) != null) {
        // FIXME: cannot invoke write method because value pushed to stack
        //        before receiver.
        emitter.emitDup();
        emitter.emitPushVar(0);
        emitter.emitCheckCast(expandoClass);
        emitter.emitPushEnv();
        emitter.emitInvokeDynamic(new Descriptors.Indy(
          setValueBootstrap, mangle(name), void.class,
          Object.class, Object.class, EvaluationContext.class));
        return true;
      }
    } catch (IntrospectionException e) {
      // fallthrough
    }

    try {
      Field field = expandoClass.getField(name);
      if (Modifier.isFinal(field.getModifiers()))
        reportError(pos, _T(EL_VARIABLE_NOT_WRITABLE, name, name));
      if (Modifier.isStatic(field.getModifiers())) {
        emitter.emitPutStatic(field);
      } else {
        emitter.emitPushVar(0);
        emitter.emitCheckCast(expandoClass);
        emitter.emitPutField(field);
      }
      return true;
    } catch (NoSuchFieldException e) {
      // fallthrough
    }

    return false;
  }

  public void visit(ELNode.ACCESS node) {
    if (node.index instanceof ELNode.STRINGVAL) {
      String key = ((ELNode.STRINGVAL)node.index).value;

      if (node.right instanceof ELNode.IDENT var &&
          (var.id.equals("this") || var.id.equals("super"))) {
        if (var.id.equals("this") && var.symbol != null &&
            var.symbol.scope.isLambdaScope()) {
          // "this" may be the expando method receiver parameter.
          ELNode.LAMBDA lambda = (ELNode.LAMBDA)var.symbol.scope.frontier;
          if (expandoClass != null && var.symbol == lambda.vars[0].symbol) {
            if (buildLoadExpandoProperty(key))
              return;
          }
        } else {
          Scope classScope = currentScope.enclosingClassScope();
          if (var.symbol == null || classScope == null ||
              var.symbol.scope != classScope)
            reportError(node.pos, _T(EL_DANGLING_REFERENCE, var.id));
          if (!buildLoadBaseClassField(node.pos, key, var.id.equals("super")))
            reportError(node.pos,
              _T(EL_PROPERTY_NOT_FOUND, currentScope.enclosingClass().name, key));
          return;
        }
      }

      if (node.right.inferredType instanceof ELiteClassObjectType ct) {
        if (buildLoadELiteProperty(ct.classObject(), null, key, true))
          return;
      } else if (node.right.inferredType instanceof ELiteClassType ct) {
        if (buildLoadELiteProperty(ct.irc(), node.right, key, false))
          return;
      }

      Symbol outerSym = getOuterClassMember(node.right, key);
      if (outerSym != null) {
        if (outerSym.func != null) {
          emitter.emitClosure(outerSym.func);
        } else {
          IRClass outerClass = outerSym.scope.enclosingClass();
          IRClass currentClass = currentScope.enclosingClass();
          emitter.emitPushThis();
          while (currentClass != outerClass) {
            emitter.emitGetField(currentClass, "$outer");
            currentClass = currentClass.outer;
          }
          emitter.emitGetField(outerClass, key);
        }
        return;
      }

      if (node.memberType instanceof FieldType ft) {
        if (ft.isStatic()) {
          emitter.emitGetStatic(ft.field());
        } else {
          build(node.right);
          emitter.emitGetField(ft.field());
        }
        return;
      }

      if (node.memberType instanceof org.elite.type.MethodType mt) {
        // Invoke getter method.
        buildMethodCall(mt.method(), node.right, new ELNode[0]);
        return;
      }
    }

    if (node.right.inferredType instanceof ArrayType at &&
        node.index.inferredType == Types.INT) {
      Class<?> arrayType = at.javaClass();
      if (arrayType != null) {
        Class<?> componentType = arrayType.getComponentType();
        build(node.right);
        emitter.emitCheckCast(at.javaClass());
        build(node.index);
        emitter.emitUnbox(Integer.TYPE);
        emitter.emitLoadArray(componentType);
        if (componentType.isPrimitive())
          emitter.emitBox(componentType);
        return;
      }
    }

    if (node.inferredType instanceof ClassObjectType cobj) {
      buildConst(cobj.classObject());
      return;
    }

    buildGetValueIndy(node.right, node.index);
  }

  private boolean buildLoadELiteProperty(IRClass eclass, ELNode receiver,
                                         String name, boolean isStatic) {
    IRClass irc = eclass;
    while (true) {
      Optional<ELNode.DEFINE> var = Stream.concat(
        irc.node.vars != null ? Arrays.stream(irc.node.vars) : Stream.empty(),
        Stream.concat(Arrays.stream(irc.node.ivars),
                      Arrays.stream(irc.node.cvars)))
        .filter(def -> {
          if (def.symbol.isStatic() != isStatic)
            return false;
          if (!isStatic) {
            String property = ELUtils.capitalize(name);
            if ((def.id.equals("get" + property) ||
                 def.id.equals("is" + property)) &&
                def.expr instanceof ELNode.LAMBDA fn &&
                fn.vars.length == 0 && !fn.varargs)
              return true;
          }
          return def.id.equals(name);
        }).findFirst();

      if (var.isPresent()) {
        ELNode.DEFINE def = var.get();

        if (!def.symbol.isPublic())
          return false;

        if (isStatic) {
          if (def.symbol.clazz != null) {
            buildConst(def.symbol.clazz);
          } else if (def.symbol.func != null) {
            emitter.emitClosure(def.symbol.func);
          } else {
            emitter.emitGetStatic(irc, name);
          }
        } else if (def.id.equals(name)) {
          if (def.symbol.clazz != null || def.symbol.func != null)
            return false;
          build(receiver);
          emitter.emitCheckCast(eclass);
          emitter.emitGetField(eclass, name);
        } else {
          ELNode.LAMBDA fn = (ELNode.LAMBDA)def.expr;
          build(receiver);
          emitter.emitCheckCast(eclass);
          emitter.emitPushEnv();
          emitter.emitPushNull();
          emitter.emitInvokeDirect(fn.symbol.func);
        }
        return true;
      }

      if (irc.base instanceof IRClass)
        irc = (IRClass)irc.base;
      else
        return false;
    }
  }

  private void buildGetValueIndy(ELNode target, ELNode index) {
    if (index instanceof ELNode.STRINGVAL key) {
      emitter.emitPushEnv();
      build(target);
      emitter.emitInvokeDynamic(new Descriptors.Indy(
        getValueBootstrap, mangle(key.value), Object.class,
        EvaluationContext.class, Object.class));
    } else {
      emitter.emitPushEnv();
      build(target);
      build(index);
      emitter.emitInvokeDynamic(new Descriptors.Indy(
        getIndexedValueBootstrap, "getValue", Object.class,
        EvaluationContext.class, Object.class, Object.class));
    }
  }

  private void buildStoreProperty(ELNode.ACCESS node) {
    if (node.index instanceof ELNode.STRINGVAL) {
      String key = ((ELNode.STRINGVAL)node.index).value;

      // Access member variable via this or super reference. Traverse class
      // hierarchy to find the member variable.
      if (node.right instanceof ELNode.IDENT var &&
          (var.id.equals("this") || var.id.equals("super"))) {
        if (var.id.equals("this") && var.symbol != null &&
            var.symbol.scope.isLambdaScope()) {
          // "this" may be the expando method receiver parameter.
          ELNode.LAMBDA lambda = (ELNode.LAMBDA)var.symbol.scope.frontier;
          if (expandoClass != null && var.symbol == lambda.vars[0].symbol) {
            if (buildStoreExpandoProperty(node.pos, key))
              return;
          }
        } else {
          Scope classScope = currentScope.enclosingClassScope();
          if (var.symbol == null || classScope == null ||
              var.symbol.scope != classScope) {
            reportError(node.pos, _T(EL_DANGLING_REFERENCE, var.id));
            return;
          }

          if (!buildStoreBaseClassField(node.pos, key, var.id.equals("super"))) {
            reportError(node.pos, _T(EL_PROPERTY_NOT_FOUND,
                                     currentScope.enclosingClass().name, key));
          }

          return;
        }
      }

      // Access a class static member variable.
      IRClass irc = resolveIRClass(node.right);
      if (irc != null) {
        for (ELNode.DEFINE def : irc.node.cvars) {
          if (def.id.equals(key)) {
            if (!def.symbol.isPublic())
              reportError(node.pos, _T(EL_ILLEGAL_ACCESS, irc.name, key));
            if (def.symbol.isFinal())
              reportError(node.pos, _T(EL_PROPERTY_NOT_WRITABLE, irc.name, key));
            if (def.symbol.clazz != null || def.symbol.func != null)
              reportError(node.pos, _T(EL_PROPERTY_NOT_WRITABLE, irc.name, key));
            emitter.emitPutStatic(irc, key);
            return;
          }
        }
      }

      // Access outer class member variable via `Outer.this.name`.
      Symbol outerSym = getOuterClassMember(node.right, key);
      if (outerSym != null) {
        if (outerSym.func != null || outerSym.isFinal()) {
          reportError(node.pos,
                      _T(EL_PROPERTY_NOT_WRITABLE, outerSym.name, key));
          return;
        }

        IRClass outerClass = outerSym.scope.enclosingClass();
        IRClass currentClass = currentScope.enclosingClass();
        emitter.emitPushThis();
        while (currentClass != outerClass) {
          emitter.emitGetField(currentClass, "$outer");
          currentClass = currentClass.outer;
        }
        emitter.emitPutField(outerClass, key);
        return;
      }

      Class<?> javaClass = resolveJavaClass(node.right);
      if (javaClass != null) {
        // Access Java class static field.
        if (tryBuildPutStatic(node.pos, javaClass, key))
          return;

        // Cannot mutate a Java method reference.
        if (Arrays.stream(javaClass.getMethods())
                  .anyMatch(m -> m.getName().equals(key))) {
          reportError(node.pos, _T(EL_PROPERTY_NOT_WRITABLE,
                                   javaClass.getName(), key));
          return;
        }
      }
    }

    // Set object property at runtime. Note that parameter order is reversed
    // because value is pushed first to stack.
    if (node.index instanceof ELNode.STRINGVAL key) {
      emitter.emitDup();
      build(node.right);
      emitter.emitPushEnv();
      emitter.emitInvokeDynamic(new Descriptors.Indy(
        setValueBootstrap, mangle(key.value), void.class,
        Object.class, Object.class, EvaluationContext.class));
    } else {
      emitter.emitDup();
      build(node.right);
      build(node.index);
      emitter.emitPushEnv();
      emitter.emitInvokeDynamic(new Descriptors.Indy(
        setIndexedValueBootstrap, "setValue", void.class, Object.class,
        Object.class, Object.class, EvaluationContext.class));
    }
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
        if (c == null) {
          reportError(node.pos,
                      base.id + " is not an outer class of " + "current class");
          return null;
        }
      }

      Optional<ELNode.DEFINE> var =
        Stream.concat(outerClass.node.vars != null
                        ? Arrays.stream(outerClass.node.vars)
                        : Stream.empty(),
                      Arrays.stream(outerClass.node.ivars))
          .filter(def -> def.id.equals(key))
          .findFirst();
      if (var.isEmpty()) {
        reportError(node.pos, _T(EL_PROPERTY_NOT_FOUND, outerClass.name, key));
        return null;
      }
      return var.get().symbol;
    }
    return null;
  }

  private boolean tryBuildPutStatic(int pos, Class<?> cls, String name) {
    try {
      Field field = cls.getField(name);
      int mods = field.getModifiers();
      if (Modifier.isPublic(mods) && Modifier.isStatic(mods)) {
        if (Modifier.isFinal(mods))
          reportError(pos, _T(EL_PROPERTY_NOT_WRITABLE, cls.getName(), name));
        emitter.emitPutStatic(field);
        return true;
      }
    } catch (NoSuchFieldException | SecurityException ex) { /* fallthrough */ }
    return false;
  }

  public void visit(ELNode.APPLY node) {
    // Handle special function.
    if (node.right instanceof ELNode.IDENT ident &&
        ident.id.equals("*attach*")) {
      Class<?> javaClass = loadJavaClass(node.args[0]);
      String name = ((ELNode.STRINGVAL)node.args[1]).value;
      ELNode.LAMBDA lambda = (ELNode.LAMBDA)node.args[2];
      buildExpandoClosure(javaClass, name, lambda);
      return;
    }

    if (node.right instanceof ELNode.IDENT) {
      buildCall(node);
      return;
    }

    if (node.right instanceof ELNode.ACCESS) {
      buildInvoke(node);
      return;
    }

    buildDynamicCall(node.pos, node.right, node.args, node.keys);
  }

  private void buildCall(ELNode.APPLY node) {
    int pos = node.pos;
    ELNode.IDENT target = (ELNode.IDENT)node.right;
    ELNode[] args = node.args;
    String[] keys = node.keys;

    if (target.id.equals("super")) {
      reportError(pos, _T(EL_MISPLACED_SUPER));
      return;
    }

    if (target.symbol != null) {
      if (target.symbol.func != null) {
        ELNode.LAMBDA lambda = (ELNode.LAMBDA)target.symbol.def.expr;
        args = getCallArgs(pos, lambda, args, keys);
        if (inTailPosition && target.symbol.func == this.func) {
          buildTailCall(lambda, args);
        } else {
          buildDirectCall(target.symbol, args);
        }
        return;
      }

      if (target.symbol.clazz != null) {
        buildClassCall(pos, target.symbol.clazz, args, keys);
        return;
      }

      if (target.symbol.def.expr instanceof ELNode.CLASS c) {
        Class<?> cls = loadClassAtCompileTime(target.pos, c.getClassName());
        buildJavaClassCall(node, cls, args);
        return;
      }
    } else {
      // Resolve member function from super class.
      if (buildThisCall(node, target.id, false, args, keys))
        return;

      // Resolve expando method.
      if (expandoClass != null && buildExpandoCall(node, target.id, args, keys))
        return;

      if (node.memberType instanceof org.elite.type.MethodType mt) {
        buildMethodCall(mt.method(), null, args);
        return;
      }

      if (node.memberType instanceof ConstructorType ct) {
        Class<?>[] types = ct.ctor().getParameterTypes();
        emitter.emitNew(ct.ctor().getDeclaringClass());
        for (int i = 0; i < types.length; i++)
          buildCoerce(args[i], types[i]);
        emitter.emitConstructor(ct.ctor());
        return;
      }

      // Resolve java class.
      Class<?> javaClass = resolveJavaClass(target);
      if (javaClass != null) {
        buildJavaClassCall(node, javaClass, args);
        return;
      }

      // Resolve target at runtime if the given id is not a local var
      emitter.emitPushEnv();
      emitter.emitPushEnv();
      buildConst(target.id);
      emitInvokeMethod(Runtime.class, "resolveCallTarget",
                       EvaluationContext.class, String.class);
      buildTuple(Object.class, args);
      emitCallIndy(keys);
      return;
    }

    buildDynamicCall(pos, target, args, keys);
  }

  private void buildDynamicCall(int pos, ELNode target, ELNode[] args,
                                String[] keys) {
    emitter.emitPushEnv();
    build(target);

    if (target instanceof ELNode.LAMBDA lam && lam.symbol != null &&
        lam.symbol.func != null) {
      // Lambda closure no longer used.
      emitter.emitPop();
      emitter.emitPop();

      // One-shot lambda call. "Let" may be inlined to eliminate runtime overhead.
      args = getCallArgs(pos, lam, args, keys);
      buildDirectCall(lam.symbol, args);
      return;
    }

    // Evaluate target and generate dynamic call.
    buildTuple(Object.class, args);
    emitCallIndy(keys);
  }

  private void buildInvoke(ELNode.APPLY e) {
    int pos = e.pos;
    ELNode.ACCESS acc = (ELNode.ACCESS)e.right;
    ELNode[] args = e.args;
    String[] keys = e.keys;

    // Try to resolve direct method for known Java types.
    if (acc.index instanceof ELNode.STRINGVAL) {
      String name = ((ELNode.STRINGVAL)acc.index).value;

      if (acc.right instanceof ELNode.IDENT var &&
          (var.id.equals("this") || var.id.equals("super"))) {
        if (var.id.equals("this") && var.symbol != null &&
            var.symbol.scope.isLambdaScope()) {
          // "this" may be the expando method receiver parameter.
          ELNode.LAMBDA lambda = (ELNode.LAMBDA)var.symbol.scope.frontier;
          if (expandoClass != null && var.symbol == lambda.vars[0].symbol) {
            if (buildExpandoCall(e, name, args, keys))
              return;
          }
        } else {
          Scope classScope = currentScope.enclosingClassScope();
          if (var.symbol == null || classScope == null ||
              var.symbol.scope != classScope) {
            reportError(pos, _T(EL_DANGLING_REFERENCE, var.id));
            return;
          }

          if (!buildThisCall(e, name, var.id.equals("super"), args, keys)) {
            reportError(pos, _T(EL_METHOD_NOT_FOUND,
                                currentScope.enclosingClass().name, name));
          }

          return;
        }
      }

      if (acc.right.inferredType instanceof ELiteClassType ct &&
          !((name.equals("toString")  && e.args.length == 0) ||
            (name.equals("equals")    && e.args.length == 1) ||
            (name.equals("hashCode")  && e.args.length == 0) ||
            (name.equals("compareTo") && e.args.length == 1))) {
        for (Object cls = ct.irc(); cls instanceof IRClass irc; cls = irc.base) {
          for (ELNode.DEFINE def : irc.node.ivars) {
            if (def.id.equals(name)) {
              if (!def.symbol.isPublic()) {
                reportError(pos, _T(EL_ILLEGAL_ACCESS, irc.name, name));
                return;
              }
              if (def.expr instanceof ELNode.LAMBDA fn) {
                build(acc.right);
                emitter.emitCheckCast(irc);
                emitter.emitPushEnv();
                emitInvokeMethod(EvaluationContext.class, "pushContext");
                buildCallArgs(fn, getCallArgs(pos, fn, args, keys));
                emitter.emitInvokeDirect(fn.symbol.func);
                return;
              }
            }
          }
        }
      }

      Object cls = resolveIRClass(acc.right);
      if (cls == null && acc.right.inferredType instanceof ELiteClassType ct)
        cls = ct.irc();
      for (; cls instanceof IRClass irc ; cls = irc.base) {
        for (ELNode.DEFINE def : irc.node.cvars) {
          if (def.id.equals(name)) {
            if (!def.symbol.isPublic()) {
              reportError(pos, _T(EL_ILLEGAL_ACCESS, irc.name, name));
              return;
            }
            if (def.symbol.clazz != null) {
              buildClassCall(pos, def.symbol.clazz, args, keys);
              return;
            }
            if (def.expr instanceof ELNode.LAMBDA fn) {
              args = getCallArgs(pos, fn, args, keys);
              buildDirectCall(fn.symbol, args);
              return;
            }
          }
        }
      }

      Symbol outerSym = getOuterClassMember(acc.right, name);
      if (outerSym != null) {
        if (outerSym.def.expr instanceof ELNode.LAMBDA fn) {
          args = getCallArgs(pos, fn, args, keys);
          buildDirectCall(fn.symbol, args);
          return;
        } else {
          reportError(pos, _T(EL_METHOD_NOT_FOUND, outerSym.clazz.name, name));
          return;
        }
      }

      if (buildJavaMethodCall(e))
        return;
    }

    Class<?> javaClass = resolveJavaClass(acc);
    if (javaClass != null) {
      buildJavaClassCall(e, javaClass, args);
      return;
    }

    // Resolve method at runtime.
    if (acc.index instanceof ELNode.STRINGVAL key) {
      emitter.emitPushEnv();
      build(acc.right);
      buildTuple(Object.class, args);
      emitInvokeIndy(key.value, keys, false);
    } else {
      emitter.emitPushEnv();
      build(acc);
      buildTuple(Object.class, args);
      emitCallIndy(keys);
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
          if (def.id.equals(key.value) && def.symbol.clazz != null) {
            if (!def.symbol.isPublic())
              reportError(node.pos, _T(EL_ILLEGAL_ACCESS, c.name, def.id));
            return def.symbol.clazz;
          }
        }

        for (ELNode.DEFINE def : c.node.ivars) {
          if (def.id.equals(key.value) && def.symbol.clazz != null) {
            reportError(node.pos,
                        _T(EL_STATIC_CONTEXT_ACCESS_INSTANCE_MEMBER, def.id));
            return null;
          }
        }
      }
    }

    return null;
  }

  private Class<?> resolveJavaClass(ELNode base) {
    return resolveJavaClass(base, false);
  }

  private Class<?> loadJavaClass(ELNode base) {
    return resolveJavaClass(base, true);
  }

  private Class<?> resolveJavaClass(ELNode node, boolean load) {
    ClassResolver resolver = ClassResolver.getInstance(elctx);

    if (node instanceof ELNode.IDENT var && var.symbol != null &&
        var.symbol.def.expr instanceof ELNode.CLASS c) {
      try {
        return resolver.resolveClass(c.getClassName());
      } catch (ClassNotFoundException e) {
        reportError(node.pos, _T(EL_CLASS_NOT_FOUND, c.getClassName()));
        return Object.class;
      }
    }

    StringBuilder buf = new StringBuilder();
    while (node instanceof ELNode.ACCESS acc) {
      if (!(acc.index instanceof ELNode.STRINGVAL str))
        return null;
      buf.insert(0, str.value);
      buf.insert(0, '.');
      node = acc.right;
    }

    if (node instanceof ELNode.IDENT var) {
      buf.insert(0, var.id);
      if (var.symbol == null || load) {
        try {
          return resolver.resolveClass(buf.toString());
        } catch (ClassNotFoundException e) {
          // fallthrough
        }
      }
    }

    if (load) {
      reportError(node.pos, _T(EL_CLASS_NOT_FOUND, buf.toString()));
      return Object.class;
    }

    return null;
  }

  Object resolveClass(ELNode node) {
    IRClass irc = resolveIRClass(node);
    if (irc != null)
      return irc;
    return loadJavaClass(node);
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

  private ELNode[] getCallArgs(int pos, IRClass irc, ELNode[] args,
                               String[] keys) {
    if (irc.node.vars != null)
      return getCallArgs(pos, "<init>", irc.node.vars, args, keys, false);
    else
      return getCallArgs(pos, irc.init_proc, args, keys);
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
      reportError(pos, _T(EL_FN_BAD_ARG_COUNT, name, nvars, argc));

      // Suppress java.lang.ArrayIndexOutOfBoundsException.
      xargs = new ELNode[nvars];
      Arrays.fill(xargs, new ELNode.NULL(0));
      return xargs;
    }

    // Rearrange named arguments
    if (keys != null) {
      for (int i = 0; i < argc; i++) {
        if (keys[i] != null) {
          int j = indexOfVar(keys[i], vars, varargs);
          if (j == -1) {
            reportError(pos, _T(EL_UNKNOWN_ARG_NAME, keys[i]));
            continue;
          }
          if (xargs == null)
            xargs = new ELNode[argc];
          xargs[j] = args[i];
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
          if (vars[j].expr == null) {
            reportError(pos, _T(EL_MISSING_ARG_VALUE, vars[j].id));
            xargs[j] = new ELNode.NULL(0);
          } else {
            xargs[j] = vars[j].expr;
          }
        }
      }

      args = xargs;
    }

    return args;
  }

  private void buildCallArgs(ELNode.LAMBDA lambda, ELNode[] args) {
    int nvars = lambda.vars.length;

    if (nvars == 0) {
      emitter.emitPushNull();
      return;
    }

    // Build argument for argument list, exclude varargs.
    buildConstUnbox(nvars);
    emitter.emitNewArray(Object.class);
    if (lambda.varargs)
      nvars--;
    for (int i = 0; i < nvars; i++) {
      emitter.emitDup();
      buildConstUnbox(i);
      build(args[i]);
      emitter.emitStoreArray(Object.class);
    }

    // Build vararg list as an array and put the array into argument list.
    if (lambda.varargs) {
      emitter.emitDup();
      buildConstUnbox(nvars);
      buildTuple(Object.class, args, nvars, args.length - nvars);
      emitter.emitStoreArray(Object.class);
    }
  }

  private void buildClassCall(int pos, IRClass irc, ELNode... args) {
    buildClassCall(pos, irc, args, null);
  }

  private void buildClassCall(int pos, IRClass irc, ELNode[] args,
                              String[] keys) {
    if (irc.isSingleton()) {
      emitter.emitGetStatic(irc, "$singleton");
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

    args = getCallArgs(pos, irc, args, keys);
    emitter.emitNew(irc);
    emitter.emitPushEnv();
    buildCallArgs(irc.init_proc, args);
    emitter.emitConstructor(irc);
  }

  private void buildJavaClassCall(ELNode.APPLY node, Class<?> javaClass,
                                  ELNode[] args) {
    if (args.length == 1) {
      if (javaClass == Void.TYPE) {
        build(args[0]);
        emitter.emitPop();
        emitter.emitPushNull();
        return;
      }

      if (TypeCoercion.getUnboxedType(javaClass).isPrimitive() ||
          javaClass == String.class || javaClass == BigInteger.class ||
          javaClass == BigDecimal.class || javaClass == Decimal.class ||
          javaClass == Rational.class) {
        buildCoerce(args[0], TypeCoercion.getBoxedType(javaClass));
        return;
      }
    }

    if (node.memberType instanceof org.elite.type.MethodType mt) {
      assert mt.method().getName().equals("valueOf");
      buildMethodCall(mt.method(), null, args);
      return;
    }

    if (node.memberType instanceof ConstructorType ct) {
      Class<?>[] types = ct.ctor().getParameterTypes();
      emitter.emitNew(javaClass);
      for (int i = 0; i < args.length; i++)
        buildCoerce(args[i], types[i]);
      emitter.emitConstructor(ct.ctor());
      return;
    }

    buildNew(node.pos, javaClass, args);
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
      buildTuple(Object.class, args, nvars, args.length - nvars);

    // Copy tail call arguments.
    for (int i = lambda.vars.length; --i >= 0; ) {
      emitter.emitStoreVar(i);
      emitter.emitPop();
    }

    // Jump to first block.
    emitter.emitJump(0);
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
          emitter.emitPushThis();
          if (!isBaseClass(ownerClass, currentClass)) {
            while (currentClass != ownerClass) {
              emitter.emitGetField(currentClass, "$outer");
              currentClass = currentClass.outer;
            }
          }
        }
        emitter.emitPushEnv();
        emitInvokeMethod(EvaluationContext.class, "pushContext");
        buildCallArgs(lambda, args);
        emitter.emitInvokeDirect(sym.func);
        return;
      }
    }

    if ((fn.owner() != null && !fn.isStatic()) || !isEligibleToInline(sym)) {
      if (fn.owner() != null && !fn.isStatic())
        emitter.emitPushThis();
      emitter.emitPushEnv();
      emitInvokeMethod(EvaluationContext.class, "pushContext");
      buildCallArgs(lambda, args);
      emitter.emitInvokeDirect(sym.func);
      return;
    }

    // Scan for slots in inlined function.
    BitSet readSlots = new BitSet();
    BitSet modSlots = new BitSet();
    Map<Integer, Integer> primSlots = new HashMap<>();
    for (var v = new InstructionView(fn.code(), 0); v.inBounds(); v.advance()) {
      if (v.opcode() == PUSH_VAR) {
        readSlots.set(v.varIndex());
        if (v.payload() != K_NONE)
          primSlots.put(v.varIndex(), v.payload());
      }
      if (v.opcode() == STORE_VAR || v.opcode() == STORE_VAR_POP) {
        modSlots.set(v.varIndex());
        if (v.payload() != K_NONE)
          primSlots.put(v.varIndex(), v.payload());
      }
    }

    // Allocate slots for inline function local variables.
    Slot[] slots = new Slot[fn.maxLocals()];
    Class<?>[] paramTypes = fn.paramTypes();
    for (int i = 0; i < args.length; i++) {
      // If the slot never read, no need to allocate new slot. Argument still
      // need to build for side effects. Constant argument will be discarded
      // by peephole optimizer.
      if (!readSlots.get(i)) {
        build(args[i]);
        emitter.emitPop();
        continue;
      }

      // Reuse slot for read only slot.
      if (!modSlots.get(i)) {
        if (args[i] instanceof ELNode.IDENT ident &&
            ident.symbol != null && !ident.symbol.captured) {
          slots[i] = new Slot(ident);
          continue;
        }
        if (args[i] instanceof ELNode.Constant &&
            (paramTypes == null || paramTypes[i] == null)) {
          slots[i] = null;
          continue;
        }
      }

      // Build argument and store to local slot.
      if (paramTypes != null && paramTypes[i] != null)
        buildCoerce(args[i], paramTypes[i]);
      else
        build(args[i]);
      slots[i] = new Slot();
      slots[i].store();
      emitter.emitPop();
    }
    for (int i = args.length; i < fn.maxLocals(); i++) {
      if (readSlots.get(i)) {
        if (primSlots.containsKey(i))
          slots[i] = new PrimSlot(IRFormat.kindType(primSlots.get(i)));
        else
          slots[i] = new Slot();
      }
    }

    inlineInstructions(sym, slots, args);

    for (Slot slot : slots)
      release(slot);
  }

  private void buildDirectCall(Symbol sym, Slot... args) {
    IRFunction fn = sym.func;

    if (!isEligibleToInline(sym)) {
      if (fn.owner() != null && !fn.isStatic())
        emitter.emitPushThis();
      emitter.emitPushEnv();
      emitInvokeMethod(EvaluationContext.class, "pushContext");
      buildTuple(args);
      emitter.emitInvokeDirect(fn);
      return;
    }

    // Scan for slots in inlined function.
    BitSet readSlots = new BitSet();
    BitSet modSlots = new BitSet();
    Map<Integer, Integer> primSlots = new HashMap<>();
    for (var v = new InstructionView(fn.code(), 0); v.inBounds(); v.advance()) {
      if (v.opcode() == PUSH_VAR) {
        readSlots.set(v.varIndex());
        if (v.payload() != K_NONE)
          primSlots.put(v.varIndex(), v.payload());
      }
      if (v.opcode() == STORE_VAR || v.opcode() == STORE_VAR_POP) {
        modSlots.set(v.varIndex());
        if (v.payload() != K_NONE)
          primSlots.put(v.varIndex(), v.payload());
      }
    }

    // Allocate slots for function local variables.
    Slot[] slots = new Slot[fn.maxLocals()];
    Class<?>[] paramTypes = fn.paramTypes();
    for (int i = 0; i < args.length; i++) {
      if (readSlots.get(i)) {
        if (!modSlots.get(i)) {
          slots[i] = args[i];
        } else {
          slots[i] = new Slot();
          args[i].load();
          if (paramTypes != null && paramTypes[i] != null)
            buildCoerce(paramTypes[i]);
          slots[i].store();
          emitter.emitPop();
        }
      }
    }
    for (int i = args.length; i < fn.maxLocals(); i++) {
      if (readSlots.get(i)) {
        if (primSlots.containsKey(i))
          slots[i] = new PrimSlot(IRFormat.kindType(primSlots.get(i)));
        else
          slots[i] = new Slot();
      }
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
      emitter.emitEnterScope();

    for (var v = new InstructionView(fn.code(), 0); v.inBounds(); v.advance()) {
      int blockId = fn.blockOfPc(v.offset());
      if (blockId != -1) {
        emitter.emitJump(blockMap[blockId]);
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
          if (v.payload() != K_NONE) {
            assert newSlot instanceof PrimSlot;
            ((PrimSlot)newSlot).loadPrim();
          } else {
            newSlot.load();
          }
        }
      } else if (v.opcode() == STORE_VAR || v.opcode() == STORE_VAR_POP) {
        Slot newSlot = slots[v.varIndex()];
        if (newSlot == null) {
          // Ignore write only slot.
          if (v.opcode() == STORE_VAR_POP)
            emitter.emitPop();
        } else {
          // Remap local variable to new slot.
          emitter.emit(v.opcode(), v.payload(), newSlot.slot);
        }
      } else if (v.isJump()) {
        emitter.emit(v.opcode(), v.payload(), blockMap[v.jumpTarget()]);
      } else if (v.opcode() == RETURN) {
        // We have guaranteed single entry single exit.
        assert v.offset() == fn.code().length - 1;
        break;
      } else {
        emitter.emit(v.opcode(), v.payload(), v.operand());
      }
    }

    if (hasCaptures)
      emitter.emitLeaveScope();
  }

  private boolean isEligibleToInline(Symbol sym) {
    if (ELProgram.OPT_LEVEL < 2)
      return false;
    if (sym.func.isDeclaration())
      return false;
    if (((ELNode.LAMBDA)sym.def.expr).varargs)
      return false; // FIXME: handle varargs

    // Check @inline/@noinline metadata.
    if (sym.def.isAnnotationPresent("noinline"))
      return false;
    if (!sym.def.isAnnotationPresent("inline") && sym.func.code().length > 35)
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

  private boolean buildThisCall(ELNode.APPLY node, String name, boolean isSuper,
                                ELNode[] args, String[] keys) {
    int pos = node.pos;
    IRClass clazz = currentScope.enclosingClass();
    if (clazz == null)
      return false;

    if (!isSuper || clazz.base instanceof IRClass) {
      if (isSuper)
        clazz = (IRClass)clazz.base;
      boolean isSuperClass = isSuper;
      do {
        Optional<ELNode.DEFINE> var =
          Stream.concat(Arrays.stream(clazz.node.cvars),
                        Arrays.stream(clazz.node.ivars))
            .filter(x -> x.id.equals(name) &&
                         x.expr instanceof ELNode.LAMBDA)
            .findFirst();

        if (var.isPresent()) {
          ELNode.DEFINE def = var.get();
          if (isSuperClass && def.symbol.isPrivate())
            reportError(pos, _T(EL_ILLEGAL_ACCESS, clazz.name, name));
          if (currentScope.isStaticScope() && !def.symbol.isStatic())
            reportError(pos, _T(EL_STATIC_CONTEXT_ACCESS_INSTANCE_MEMBER, name));
          args = getCallArgs(pos, (ELNode.LAMBDA)def.expr, args, keys);
          if (!isSuper && inTailPosition && def.symbol.func == this.func)
            buildTailCall((ELNode.LAMBDA)def.expr, args);
          else
            buildDirectCall(def.symbol, args);
          return true;
        }

        if (clazz.base instanceof IRClass) {
          clazz = (IRClass)clazz.base;
          isSuperClass = true;
        } else {
          break;
        }
      } while (true);
    }

    if (node.memberType instanceof org.elite.type.MethodType mt) {
      if (mt.isStatic()) {
        if (buildMethodCall(mt.method(), null, args))
          return true;
      } else {
        if (buildMethodCall(mt.method(), PUSH_THIS, args, isSuper))
          return true;
      }
    }

    Class<?> baseClass = (Class<?>)clazz.base;
    MethodResolver resolver = MethodResolver.getInstance(elctx);
    MethodClosure mc;
    boolean isStatic = true;

    mc = resolver.resolveStaticMethod(baseClass, name);
    if (mc == null) {
      mc = resolver.resolveProtectedMethod(baseClass, name);
      isStatic = false;
      if (mc != null && currentScope.isStaticScope()) {
        reportError(pos, _T(EL_STATIC_CONTEXT_ACCESS_INSTANCE_MEMBER, name));
        return true;
      }
    }

    if (mc != null) {
      emitter.emitPushEnv();
      if (isStatic)
        buildConst(clazz.base);
      else
        emitter.emitPushThis();
      buildTuple(Object.class, args);
      emitInvokeIndy(name, keys, isSuper && !isStatic);
      return true;
    }

    return false;
  }

  private boolean buildExpandoCall(ELNode.APPLY node, String name, ELNode[] args,
                                   String[] keys) {
    ELNode.LAMBDA expando = expandoMap.find(expandoClass, name);
    if (expando != null) {
      ELNode.DEFINE[] vars = new ELNode.DEFINE[expando.vars.length - 1];
      System.arraycopy(expando.vars, 1, vars, 0, vars.length);
      args = getCallArgs(node.pos, name, vars, args, keys, expando.varargs);

      ELNode[] xargs = new ELNode[args.length + 1];
      xargs[0] = new ELNode.CONST(
        node.pos, (CustomAction)() -> emitter.emitPushVar(0));
      System.arraycopy(args, 0, xargs, 1, args.length);

      emitter.emitPushEnv();
      buildCallArgs(expando, xargs);
      emitter.emitInvokeDirect(expando.symbol.func);
      return true;
    }

    if (node.memberType instanceof org.elite.type.MethodType mt) {
      if (mt.isStatic())
        return buildMethodCall(mt.method(), null, args);
      else {
        ELNode action = new ELNode.CONST(
          0, (CustomAction)() -> emitter.emitPushVar(0));
        return buildMethodCall(mt.method(), action, args);
      }
    }

    return false;
  }

  private void emitInvokeIndy(String name, String[] keys, boolean isSuper) {
    Object[] bootstrapArgs = new Object[keys == null ? 1 : keys.length + 1];
    bootstrapArgs[0] = isSuper;
    if (keys != null) {
      for (int i = 0; i < keys.length; i++)
        bootstrapArgs[i + 1] = keys[i] != null ? keys[i] : "";
    }

    emitter.emitInvokeDynamic(new Descriptors.Indy(
      invokeBootstrap, mangle(name), bootstrapArgs, Object.class,
      EvaluationContext.class, Object.class, Object[].class));
  }

  private boolean buildJavaMethodCall(ELNode.APPLY e) {
    int pos = e.pos;
    ELNode.ACCESS acc = (ELNode.ACCESS)e.right;
    ELNode target = acc.right;
    String name = ((ELNode.STRINGVAL)acc.index).value;
    ELNode[] args = e.args;
    String[] keys = e.keys;

    Class<?> javaClass = target.inferredType.javaClass();
    if (javaClass != null) {
      ELNode.LAMBDA expando = expandoMap.find(javaClass, name);
      if (expando != null) {
        ELNode.DEFINE[] vars = new ELNode.DEFINE[expando.vars.length - 1];
        System.arraycopy(expando.vars, 1, vars, 0, vars.length);
        args = getCallArgs(pos, name, vars, args, keys, expando.varargs);

        ELNode[] xargs = new ELNode[args.length + 1];
        xargs[0] = target;
        System.arraycopy(args, 0, xargs, 1, args.length);

        emitter.emitPushEnv();
        buildCallArgs(expando, xargs);
        emitter.emitInvokeDirect(expando.symbol.func);
        return true;
      }
    }

    if (e.memberType instanceof org.elite.type.MethodType mt) {
      Method method = mt.method();
      if (mt.isStatic())
        return buildMethodCall(method, null, args);
      else
        return buildMethodCall(method, target, args);
    }

    if (e.memberType instanceof ClassObjectType cobj) {
      var mc = MethodResolver.getInstance(elctx).resolveMethod(Class.class, name);
      if (mc != null) {
        Method m = mc.getJavaMethod(args.length);
        if (m != null)
          return buildMethodCall(m, new ELNode.CONST(pos, cobj.classObject()),
                                 args);
      }
    }

    javaClass = resolveJavaClass(target);
    if (javaClass != null) {
      var mc = MethodResolver.getInstance(elctx).resolveMethod(Class.class, name);
      if (mc != null) {
        Method m = mc.getJavaMethod(args.length);
        if (m != null)
          return buildMethodCall(m, new ELNode.CONST(pos, javaClass), args);
      }
    }

    return false;
  }

  private void emitCallIndy(String[] keys) {
    String[] keyArgs;
    if (keys == null)
      keyArgs = new String[0];
    else {
      keyArgs = new String[keys.length];
      for (int i = 0; i < keys.length; i++)
        keyArgs[i] = keys[i] != null ? keys[i] : "";
    }

    emitter.emitInvokeDynamic(new Descriptors.Indy(
      callBootstrap, "call", keyArgs, Object.class, EvaluationContext.class,
      Object.class, Object[].class));
  }

  private boolean buildMethodCall(Method method, ELNode receiver, ELNode[] args) {
    return buildMethodCall(method, receiver, args, false);
  }

  private boolean buildMethodCall(Method method, ELNode receiver, ELNode[] args,
                                  boolean isSuper) {
    Class<?>[] types = method.getParameterTypes();
    int nargs = types.length;
    int iarg = 0;
    boolean vargs = method.isVarArgs();
    boolean expando = receiver != null &&
                      Modifier.isStatic(method.getModifiers()) &&
                      method.getAnnotation(Expando.class) != null;
    Class<?> receiverType = method.getDeclaringClass();

    if (nargs > 0 && types[0] == ELContext.class)
      iarg++;
    if (expando) {
      receiverType = types[iarg++];
    }

    if (vargs) {
      if (args.length < nargs - iarg - 1)
        return false;
      nargs--;
    } else if (args.length != nargs - iarg) {
      return false;
    }

    if (buildBuiltin(method, receiver, args))
      return true;

    if (expando && nargs > 0 && types[0] == ELContext.class)
      emitter.emitPushCtx();

    if (receiver != null) {
      if (receiver == PUSH_THIS) {
        emitter.emitPushThis();
      } else {
        build(receiver);
        if (needCast(receiver, receiverType))
          emitter.emitCheckCast(receiverType);
      }
    }

    if (!expando && nargs > 0 && types[0] == ELContext.class)
      emitter.emitPushCtx();

    // Build fixed arguments.
    int i = 0;
    for (; iarg < nargs; iarg++, i++)
      buildCoerce(args[i], types[iarg]);

    // Build variable arguments.
    if (vargs)
      buildTuple(types[nargs].getComponentType(), args, i, args.length - i);

    if (isSuper)
      emitter.emitInvokeSuper(method);
    else
      emitter.emitInvokeMethod(method);
    if (method.getReturnType() == Void.TYPE)
      emitter.emitPushNull();
    return true;
  }

  private boolean needCast(ELNode node, Class<?> type) {
    if (type == Object.class)
      return false;

    if (node instanceof ELNode.Constant) {
      return !type.isInstance(node.getValue(null));
    }

    if (node.memberType != null) {
      Class<?> resultType =
        ((MemberType)node.memberType).getResultType().javaClass();
      if (resultType == null)
        return true;
      if (resultType == Void.TYPE)
        return false;
      return !type.isAssignableFrom(resultType);
    }

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
          emitter.emitPushNull();
          return true;
        }
        for (int i = 0; i < args.length - 1; i++) {
          build(args[i]);
          emitter.emitPop();
        }
        build(args[args.length - 1]);
        return true;

      case "coalesce": {
        if (args.length == 0) {
          emitter.emitPushNull();
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
        for (ELNode arg : args) {
          emitter.emitNew(Cons.class);
          build(arg);
        }
        emitInvokeMethod(Cons.class, "nil");
        for (int i = 0; i < args.length; i++) {
          emitConstructor(Cons.class, Object.class, Seq.class);
        }
        return true;

      case "range":
        assert args.length == 3;
        build(args[0]);
        emitter.emitDup();
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
      case "times": {
        ELNode begin;
        if (base.inferredType == Types.LONG) {
          begin = new ELNode.NUMBER(0, 0L);
          begin.inferredType = Types.LONG;
        } else {
          begin = new ELNode.NUMBER(0, 0);
          begin.inferredType = Types.INT;
        }
        return buildStepBuiltin(begin, base, args[0], 1, Token.LT);
      }

      case "attach":
        assert args.length == 2;
        if (base instanceof ELNode.CONST cst &&
            cst.value instanceof Class<?> javaClass &&
            args[0] instanceof ELNode.STRINGVAL name &&
            args[1] instanceof ELNode.LAMBDA lambda) {
          buildExpandoClosure(javaClass, name.value, lambda);
          return true;
        }
      }
    }

    if (method.getDeclaringClass() == MathLib.class) {
      switch (method.getName()) {
      case "sum":
        buildMathReduce(args, Token.ADD);
        return true;
      case "difference":
        buildMathReduce(args, Token.SUB);
        return true;
      case "product":
        buildMathReduce(args, Token.MUL);
        return true;
      case "divide":
        buildMathReduce(args, Token.DIV);
        return true;

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
    int indKind = K_DYNAMIC;
    Class<?> indType = null;
    if (begin.inferredType == Types.INT && end.inferredType == Types.INT) {
      indKind = K_INT;
      indType = int.class;
    } else if (begin.inferredType == Types.LONG && end.inferredType == Types.LONG) {
      indKind = K_LONG;
      indType = long.class;
    }

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
      direct = true;
      if (sym.func.paramCount() > 1) {
        reportError(body.pos, _T(EL_FN_BAD_ARG_COUNT, sym.func.name(),
                                 sym.func.paramCount(), 1));
        return false;
      }
    }

    // Check if the body is a global function.
    Method global = direct ? null : getGlobalForStepBody(body);

    // Initialize temporary variables.
    PrimSlot indSlot = new PrimSlot(indType);
    PrimSlot endSlot = new PrimSlot(indType);
    Slot bodySlot = null;

    if (direct || global != null) {
      // Discard body closure if direct call or global call.
      emitter.emitPop();
    } else {
      // Store body reference for dynamic call.
      bodySlot = new Slot();
      bodySlot.store();
      emitter.emitPop();
    }

    build(begin);
    indSlot.storeBoxedPrim();
    build(end);
    endSlot.storeBoxedPrim();

    // Begin loop.
    int headerB = allocBlockId();
    int bodyB = allocBlockId();
    int exitB = allocBlockId();

    loopStack.push(new LoopTargets(headerB, exitB));
    emitter.emitJump(headerB);

    // Generate loop condition.
    startBlock(headerB);
    indSlot.loadPrim();
    endSlot.loadPrim();
    emitBinOp(indKind, cmpop);
    emitter.emitJumpIfTrue(bodyB);
    emitter.emitJump(exitB);

    // Generate loop body.
    startBlock(bodyB);
    if (direct) {
      if (sym.func.paramCount() == 1) {
        buildDirectCall(sym, indSlot);
      } else {
        buildDirectCall(sym, new Slot[0]);
      }
      emitter.emitPop();
    } else if (global != null) {
      Class<?>[] types = global.getParameterTypes();
      if (types[0] == ELContext.class)
        emitter.emitPushCtx();
      indSlot.load();
      emitter.emitInvokeMethod(global);
      if (global.getReturnType() != Void.TYPE)
        emitter.emitPop();
    } else {
      emitter.emitPushEnv();
      bodySlot.load();
      buildTuple(indSlot);
      emitCallIndy(null);
      emitter.emitPop();
    }

    // Increment induction variable.
    if (indType == int.class) {
      indSlot.loadPrim();
      buildConstUnbox(Math.abs(step));
      emitBinOp(K_INT, step > 0 ? Token.ADD : Token.SUB);
      indSlot.storePrim();
    } else if (indType == long.class) {
      indSlot.loadPrim();
      buildConstUnbox((long)Math.abs(step));
      emitBinOp(K_LONG, step > 0 ? Token.ADD : Token.SUB);
      indSlot.storePrim();
    } else {
      indSlot.load();
      buildConst(Math.abs(step));
      emitDynBinOp(step > 0 ? Token.ADD : Token.SUB);
      indSlot.store();
      emitter.emitPop();
    }
    emitter.emitJump(headerB);

    // Cleanup.
    startBlock(exitB);
    emitter.emitPushNull();
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

    Method method = mc.getJavaMethod(1);
    if (method == null)
      return null;

    int paramCount = method.getParameterCount();
    if (paramCount > 0 && method.getParameterTypes()[0] == ELContext.class)
      paramCount--;
    if (paramCount != 1 || method.isVarArgs())
      return null;
    return method;
  }

  private void buildExpandoClosure(Class<?> javaClass, String name,
                                   ELNode.LAMBDA lambda) {
    expandoMap.put(javaClass, name, lambda);

    emitter.emitPushCtx();
    buildConst(javaClass);
    buildConst(name);

    // Build the expando lambda to attach to Java class. The expando lambda must
    // be defined at top level, so add the generated IRFunction to program
    // function list.
    IRFunction func = buildLambda(lambda, null, javaClass);
    program.add(func);
    emitter.emitClosure(func);

    emitInvokeMethod(Builtin.class, "attach", ELContext.class, Class.class,
                     String.class, Closure.class);
    emitter.emitPushNull();
  }

  private void buildMathReduce(ELNode[] args, int op) {
    if (args.length == 0) {
      buildConst(0);
      return;
    }

    build(args[0]);
    for (int i = 1; i < args.length; i++) {
      build(args[i]);
      emitDynBinOp(op);
    }
  }

  // ── Literals: list, map, tuple, range ──

  public void visit(ELNode.CONS node) {
    if (node.delay) {
      emitter.emitNew(DelayCons.class);
      build(node.head);
      build(node.tail);
      emitConstructor(DelayCons.class, Closure.class, Closure.class);
      return;
    }

    int count = 0;
    ELNode e = node;

    while (e instanceof ELNode.CONS cons) {
      emitter.emitNew(Cons.class);
      build(cons.head);
      e = cons.tail;
      count++;
    }

    if (e instanceof ELNode.NIL) {
      emitInvokeMethod(Cons.class, "nil");
    } else {
      emitter.emitPushCtx();
      build(e);
      buildCoerce(Seq.class);
    }

    while (count-- != 0) {
      emitConstructor(Cons.class, Object.class, Seq.class);
    }
  }

  public void visit(ELNode.NIL node) {
    emitInvokeMethod(Cons.class, "nil");
  }

  public void visit(ELNode.MAP node) {
    emitNewInstance(LinkedHashMap.class);
    for (int i = 0; i < node.keys.length; i++) {
      emitter.emitDup();
      build(node.keys[i]);
      build(node.values[i]);
      emitInvokeMethod(LinkedHashMap.class, "put", Object.class, Object.class);
      emitter.emitPop();
    }
  }

  public void visit(ELNode.TUPLE node) {
    buildTuple(Object.class, node.elems);
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
    Class<?> componentType;
    if (resolveIRClass(node.type) != null)
      componentType = Object.class;
    else
      componentType = loadJavaClass(node.type);

    if (node.dims == null || node.dims.length == 1) {
      if (node.dims == null) {
        buildConstUnbox(node.init != null ? node.init.elems.length : 0);
      } else if (node.dims[0] instanceof ELNode.NUMBER n &&
                 n.value instanceof Integer) {
        buildConstUnbox(n.value);
      } else {
        emitter.emitPushCtx();
        build(node.dims[0]);
        buildCoerce(int.class);
      }
      emitter.emitNewArray(componentType);

      if (node.init != null) {
        for (int i = 0; i < node.init.elems.length; i++) {
          emitter.emitDup();
          buildConstUnbox(i);
          buildCoerce(node.init.elems[i], componentType);
          emitter.emitStoreArray(componentType);
        }
      }
    } else {
      for (int i = 0; i < node.dims.length; i++) {
        if (node.dims[i] instanceof ELNode.NUMBER n &&
                n.value instanceof Integer) {
          buildConstUnbox(n.value);
        } else {
          emitter.emitPushCtx();
          build(node.dims[i]);
          buildCoerce(int.class);
        }
      }
      emitter.emitNewMultiArray(node.dims.length, componentType);
      // TODO: handle multi dimension array initializer.
    }
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
      emitter.emitEnterScope();
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
          emitter.emitDeclareNS(prefix);
        }
      }
    }

    // Build XML tag, attributes, and children.
    emitter.emitPushEnv();
    build(node.tag);

    if (node.keys == null) {
      emitter.emitPushNull();
      emitter.emitPushNull();
    } else {
      buildTuple(Object.class, node.keys);

      buildConstUnbox(node.values.length);
      emitter.emitNewArray(Object.class);
      for (int i = 0; i < node.values.length; i++) {
        emitter.emitDup();
        buildConstUnbox(i);
        if (tmpSlots != null && tmpSlots[i] != null)
          tmpSlots[i].load();
        else
          build(node.values[i]);
        emitter.emitStoreArray(Object.class);
      }
    }

    if (node.children == null) {
      emitter.emitPushNull();
    } else {
      buildTuple(Object.class, node.children);
    }

    emitInvokeMethod(Runtime.class, "newXML", EvaluationContext.class,
                     Object.class, Object[].class, Object[].class,
                     Object[].class);

    if (namespaces != 0) {
      emitter.emitLeaveScope();
    }

    if (tmpSlots != null) {
      for (Slot slot : tmpSlots)
        release(slot);
    }
  }

  public void visit(ELNode.IN node) {
    build(node.left);
    build(node.right);
    emitter.emitIn();
    if (node.negative)
      emitter.emitNot();
  }

  public void visit(ELNode.INSTANCEOF node) {
    build(node.right);
    emitter.emitInstanceOf(resolveClass(node.type));
    if (node.negative)
      emitter.emitNot();
  }

  // ── Binary and unary arithmetic ──

  public void visit(ELNode.ADD node)    { buildBinaryOp(node); }
  public void visit(ELNode.SUB node)    { buildBinaryOp(node); }
  public void visit(ELNode.MUL node)    { buildBinaryOp(node); }
  public void visit(ELNode.DIV node)    { buildBinaryOp(node); }
  public void visit(ELNode.REM node)    { buildBinaryOp(node); }
  public void visit(ELNode.POW node)    { buildBinaryOp(node); }
  public void visit(ELNode.CAT node)    { buildBinaryOp(node); }
  public void visit(ELNode.SHL node)    { buildShiftOp(node); }
  public void visit(ELNode.SHR node)    { buildShiftOp(node); }
  public void visit(ELNode.USHR node)   { buildShiftOp(node); }
  public void visit(ELNode.BITAND node) { buildBitwiseOp(node); }
  public void visit(ELNode.BITOR node)  { buildBitwiseOp(node); }
  public void visit(ELNode.XOR node)    { buildBitwiseOp(node); }
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
    switch (node.op) {
    case Token.EQ: case Token.NE:
      if (node.left instanceof ELNode.NULL ||
          node.right instanceof ELNode.NULL) {
        build(node.left);
        build(node.right);
        emitDynBinOp(node.op == Token.EQ ? Token.IDEQ : Token.IDNE);
        return;
      }
      // fallthrough
    case Token.LT: case Token.LE: case Token.GT: case Token.GE:
    case Token.ADD: case Token.SUB: case Token.MUL: case Token.REM:
      if (buildPrimitiveBinary(node))
        return;
      break;
    }

    build(node.left);
    build(node.right);
    emitDynBinOp(node.op);
  }

  private void buildShiftOp(ELNode.Binary node) {
    if (node.left.inferredType == Types.BIGINT) {
      String name = node.op == Token.SHL ? "shiftLeft" : "shiftRight";
      build(node.left);
      emitter.emitCheckCast(BigInteger.class);
      buildCoerce(node.right, Integer.TYPE);
      emitInvokeMethod(BigInteger.class, name, Integer.TYPE);
      return;
    }

    Consumer<Integer> emitShift = (kind) -> {
      switch (node.op) {
      case Token.SHL -> emitter.emitShl(kind);
      case Token.SHR -> emitter.emitShr(kind);
      case Token.USHR -> emitter.emitUShr(kind);
      default -> throw new AssertionError();
      }
    };

    if (node.left.inferredType == Types.LONG ||
        node.left.inferredType == Types.DOUBLE ||
        node.left.inferredType == Types.FLOAT) {
      buildCoerce(node.left, Long.TYPE);
      buildCoerce(node.right, Integer.TYPE);
      emitShift.accept(K_LONG);
      emitter.emitBox(Long.TYPE);
      return;
    }

    if (node.left.inferredType == Types.INT ||
        node.left.inferredType == Types.BYTE ||
        node.left.inferredType == Types.SHORT ||
        node.left.inferredType == Types.CHAR) {
      buildCoerce(node.left, Integer.TYPE);
      buildCoerce(node.right, Integer.TYPE);
      emitShift.accept(K_INT);
      emitter.emitBox(Integer.TYPE);
      return;
    }

    build(node.left);
    build(node.right);
    emitDynBinOp(node.op);
  }

  private void buildBitwiseOp(ELNode.Binary node) {
    if (node.left.inferredType instanceof PrimitiveType &&
        node.right.inferredType instanceof PrimitiveType) {
      if (node.left.inferredType == Types.BIGINT ||
          node.right.inferredType == Types.BIGINT) {
        buildCoerce(node.left, BigInteger.class);
        buildCoerce(node.right, BigInteger.class);
        switch (node.op) {
        case Token.BITAND:
          emitInvokeMethod(BigInteger.class, "and", BigInteger.class);
          break;
        case Token.BITOR:
          emitInvokeMethod(BigInteger.class, "or", BigInteger.class);
          break;
        case Token.XOR:
          emitInvokeMethod(BigInteger.class, "xor", BigInteger.class);
          break;
        default:
          throw new AssertionError();
        }
        return;
      }

      Consumer<Integer> emitBitwise = (kind) -> {
        switch (node.op) {
        case Token.BITAND -> emitter.emitBitAnd(kind);
        case Token.BITOR -> emitter.emitBitOr(kind);
        case Token.XOR -> emitter.emitXor(kind);
        default -> throw new AssertionError();
        }
      };

      if (node.left.inferredType == Types.BOOLEAN &&
          node.right.inferredType == Types.BOOLEAN) {
        build(node.left);
        emitter.emitUnbox(Boolean.TYPE);
        build(node.right);
        emitter.emitUnbox(Boolean.TYPE);
        emitBitwise.accept(K_INT);
        emitter.emitBox(Boolean.TYPE);
        return;
      }

      if (node.left.inferredType == Types.LONG ||
          node.left.inferredType == Types.FLOAT ||
          node.left.inferredType == Types.DOUBLE ||
          node.right.inferredType == Types.LONG ||
          node.right.inferredType == Types.FLOAT ||
          node.right.inferredType == Types.DOUBLE) {
        buildCoerce(node.left, Long.TYPE);
        buildCoerce(node.right, Long.TYPE);
        emitBitwise.accept(K_LONG);
        emitter.emitBox(Long.TYPE);
        return;
      }

      if (node.left.inferredType == Types.INT ||
          node.left.inferredType == Types.BYTE ||
          node.left.inferredType == Types.SHORT ||
          node.left.inferredType == Types.CHAR ||
          node.right.inferredType == Types.INT ||
          node.right.inferredType == Types.BYTE ||
          node.right.inferredType == Types.SHORT ||
          node.right.inferredType == Types.CHAR) {
        buildCoerce(node.left, Integer.TYPE);
        buildCoerce(node.right, Integer.TYPE);
        emitBitwise.accept(K_INT);
        emitter.emitBox(Integer.TYPE);
        return;
      }
    }

    build(node.left);
    build(node.right);
    emitDynBinOp(node.op);
  }

  private boolean buildPrimitiveBinary(ELNode.Binary node) {
    Class<?> lt = node.left.inferredType.javaClass();
    Class<?> rt = node.right.inferredType.javaClass();
    if (lt == null || rt == null)
      return false;

    Class<?> ltPrimitive = TypeCoercion.getUnboxedType(lt);
    Class<?> rtPrimitive = TypeCoercion.getUnboxedType(rt);
    if ((!ltPrimitive.isPrimitive() || ltPrimitive == Void.TYPE ||
         ltPrimitive == Boolean.TYPE) ||
        (!rtPrimitive.isPrimitive() || rtPrimitive == Void.TYPE ||
         rtPrimitive == Boolean.TYPE))
      return false;

    if (ltPrimitive == Double.TYPE || rtPrimitive == Double.TYPE ||
        ltPrimitive == Float.TYPE || rtPrimitive == Float.TYPE) {
      switch (node.op) {
      case Token.BITAND: case Token.BITOR: case Token.XOR:
      case Token.SHL: case Token.SHR: case Token.USHR:
        return false;
      }
    }

    final boolean isComparison = switch (node.op) {
      case Token.EQ, Token.NE,
           Token.LT, Token.LE,
           Token.GT, Token.GE -> true;
      default -> false;
    };
    Consumer<Class<?>> box = (t) -> { if (!isComparison) emitter.emitBox(t); };

    if (ltPrimitive == Double.TYPE || rtPrimitive == Double.TYPE) {
      buildCoerce(node.left, Double.TYPE);
      buildCoerce(node.right, Double.TYPE);
      emitBinOp(K_DOUBLE, node.op);
      box.accept(Double.TYPE);
      return true;
    }

    if (ltPrimitive == Float.TYPE || rtPrimitive == Float.TYPE) {
      buildCoerce(node.left, Float.TYPE);
      buildCoerce(node.right, Float.TYPE);
      emitBinOp(K_FLOAT, node.op);
      box.accept(Float.TYPE);
      return true;
    }

    if (ltPrimitive == Long.TYPE || rtPrimitive == Long.TYPE) {
      buildCoerce(node.left, Long.TYPE);
      buildCoerce(node.right, Long.TYPE);
      emitBinOp(K_LONG, node.op);
      box.accept(Long.TYPE);
      return true;
    }

    if (GlobalScope.isBigIntegerEnabled(elctx) &&
        (node.op == Token.ADD || node.op == Token.SUB || node.op == Token.MUL))
      return false;

    buildCoerce(node.left, Integer.TYPE);
    buildCoerce(node.right, Integer.TYPE);
    emitBinOp(K_INT, node.op);
    box.accept(Integer.TYPE);
    return true;
  }

  private void emitDynBinOp(int op) {
    emitBinOp(K_DYNAMIC, op);
  }

  private void emitBinOp(int kind, int op) {
    switch (op) {
    case Token.ADD    -> emitter.emitAdd(kind);
    case Token.SUB    -> emitter.emitSub(kind);
    case Token.MUL    -> emitter.emitMul(kind);
    case Token.DIV    -> emitter.emitDiv(kind);
    case Token.IDIV   -> emitter.emitIDiv(kind);
    case Token.REM    -> emitter.emitRem(kind);
    case Token.POW    -> emitter.emitPow();
    case Token.CAT    -> emitter.emitCat();
    case Token.SHL    -> emitter.emitShl(kind);
    case Token.SHR    -> emitter.emitShr(kind);
    case Token.USHR   -> emitter.emitUShr(kind);
    case Token.BITAND -> emitter.emitBitAnd(kind);
    case Token.BITOR  -> emitter.emitBitOr(kind);
    case Token.XOR    -> emitter.emitXor(kind);
    case Token.EQ     -> emitter.emitEq(kind);
    case Token.NE     -> emitter.emitNe(kind);
    case Token.IDEQ   -> emitter.emitIdEq();
    case Token.IDNE   -> emitter.emitIdNe();
    case Token.LT     -> emitter.emitLt(kind);
    case Token.LE     -> emitter.emitLe(kind);
    case Token.GT     -> emitter.emitGt(kind);
    case Token.GE     -> emitter.emitGe(kind);
    case Token.CMP    -> emitter.emitCmp();
    default -> throw new UnsupportedOperationException();
    }
  }

  private void buildUnaryOp(ELNode.Unary node) {
    build(node.right);
    emitDynUnOp(node.op);
  }

  private void emitDynUnOp(int op) {
    switch (op) {
    case Token.BITNOT -> emitter.emitBitNot();
    case Token.NEG    -> emitter.emitNeg();
    case Token.POS    -> { /* unary plus is a no-op: value already on stack */ }
    case Token.EMPTY  -> emitter.emitEmpty();
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
        emitter.emitPushEnv();
        build(node.oper);
        buildTuple(Object.class, node.right);
        emitCallIndy(null);
      }
    } else {
      reportError(node.pos, _T(EL_UNDEFINED_IDENTIFIER, node.oper.id));
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
        emitter.emitPushEnv();
        build(node.oper);
        buildTuple(Object.class, node.left, node.right);
        emitCallIndy(null);
      }
    } else {
      reportError(node.pos, _T(EL_UNDEFINED_IDENTIFIER, node.oper.id));
    }
  }

  // ── Logical AND/OR/NOT ──

  public void visit(ELNode.AND node) {
    int cont = allocBlockId();
    build(node.left);
    emitter.emitDup();
    emitter.emitJumpIfFalse(cont);
    emitter.emitPop();
    build(node.right);
    emitter.emitJump(cont);
    startBlock(cont);
  }

  public void visit(ELNode.OR node) {
    int cont = allocBlockId();
    build(node.left);
    emitter.emitDup();
    emitter.emitJumpIfTrue(cont);
    emitter.emitPop();
    build(node.right);
    emitter.emitJump(cont);
    startBlock(cont);
  }

  public void visit(ELNode.NOT node) {
    build(node.right);
    emitter.emitNot();
  }

  // ── Conditional (if/else / ?:) ──
  public void visit(ELNode.COND node) {
    int thenB = allocBlockId();
    int elseB = allocBlockId();
    int mergeB = allocBlockId();

    build(node.cond);
    emitter.emitJumpIfTrue(thenB);
    emitter.emitJump(elseB);
    startBlock(thenB);
    buildTail(node.left);
    emitter.emitJump(mergeB);
    startBlock(elseB);
    buildTail(node.right);
    emitter.emitJump(mergeB);
    startBlock(mergeB);
  }

  public void visit(ELNode.COALESCE node) {
    int cont = allocBlockId();
    build(node.left);
    emitter.emitDup();
    emitter.emitJumpIfNonNull(cont);
    emitter.emitPop();
    build(node.right);
    emitter.emitJump(cont);
    startBlock(cont);
  }

  public void visit(ELNode.ASSIGN node) {
    if (node.left instanceof ELNode.IDENT ident) {
      build(node.right);
      buildStoreVariable(ident);
      return;
    }

    if (node.left instanceof ELNode.ACCESS acc) {
      if (acc.right.inferredType instanceof ArrayType at &&
          acc.index.inferredType == Types.INT) {
        Class<?> arrayType = at.javaClass();
        if (arrayType != null) {
          Class<?> componentType = arrayType.getComponentType();
          build(acc.right);
          emitter.emitCheckCast(at.javaClass());
          build(acc.index);
          emitter.emitUnbox(Integer.TYPE);
          buildCoerce(node.right, componentType);
          emitter.emitStoreArray(componentType);
          emitter.emitPushNull();
          return;
        }
      }

      build(node.right);
      buildStoreProperty(acc);
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
        emitter.emitJump(doneBlock);
        startBlock(failBlock);
        buildConst("tuple pattern not match");
        emitter.emitThrow();
        emitter.emitJump(doneBlock);
        startBlock(doneBlock);
      }
      return;
    }

    // should not happen, parser disabled other assign syntax
    throw new AssertionError();
  }

  public void visit(ELNode.ASSIGNOP node) {
    int b1 = allocBlockId(), done = allocBlockId();

    // Invoke dynamic assignment operator
    build(node.left);
    build(node.right);
    emitter.emitPushEnv();
    emitter.emitInvokeDynamic(new Descriptors.Indy(
      assignOpBootstrap, mangle(ELNode.opIdentifiers[node.binary.op]),
      Either.class, Object.class, Object.class, EvaluationContext.class));

    // Check the result to see whether the result need to be assigned.
    emitter.emitDup();
    emitInvokeMethod(Either.class, "isLeft");
    emitter.emitJumpIfFalse(b1);
    emitInvokeMethod(Either.class, "value");
    emitter.emitJump(done);

    // Assign the result to variable or object property.
    startBlock(b1);
    emitInvokeMethod(Either.class, "value");
    if (node.left instanceof ELNode.IDENT ident) {
      buildStoreVariable(ident);
    } else if (node.left instanceof ELNode.ACCESS access) {
      buildStoreProperty(access);
    } else {
      // should not happen, parser disabled other assignop syntax
      throw new AssertionError();
    }
    emitter.emitJump(done);

    startBlock(done);
  }

  public void visit(ELNode.INC node) {
    buildIncDec(node.right, Token.ADD, node.is_preincrement);
  }

  public void visit(ELNode.DEC node) {
    buildIncDec(node.right, Token.SUB, node.is_preincrement);
  }

  /**
   * Expand ++x / x++ / --x / x-- for local variables.
   */
  private void buildIncDec(ELNode target, int op, boolean isPre) {
    // Evaluate right value.
    build(target);
    if (!isPre)
      emitter.emitDup();

    Class<?> type = target.inferredType.javaClass();
    if (type != null)
      type = TypeCoercion.getUnboxedType(type);

    // Increment or decrement the value.
    if (type == Double.TYPE) {
      emitter.emitUnbox(Double.TYPE);
      buildConstUnbox(1.0d);
      emitBinOp(K_DOUBLE, op);
      emitter.emitBox(Double.TYPE);
    } else if (type == Float.TYPE) {
      emitter.emitUnbox(Float.TYPE);
      buildConstUnbox(1.0f);
      emitBinOp(K_FLOAT, op);
      emitter.emitBox(Float.TYPE);
    } else if (type == Long.TYPE) {
      emitter.emitUnbox(Long.TYPE);
      buildConstUnbox(1L);
      emitBinOp(K_LONG, op);
      emitter.emitBox(Long.TYPE);
    } else if (type == Integer.TYPE || type == Byte.TYPE ||
               type == Short.TYPE || type == Character.TYPE) {
      emitter.emitUnbox(type);
      buildConstUnbox(1);
      emitBinOp(K_INT, op);
      emitter.emitBox(Integer.TYPE);
    } else {
      buildConst(1);
      emitDynBinOp(op);
    }

    // Assign to right value itself.
    if (target instanceof ELNode.IDENT ident)
      buildStoreVariable(ident);
    else if (target instanceof ELNode.ACCESS access)
      buildStoreProperty(access);
    else
      reportError(target.pos, _T(EL_READONLY_EXPRESSION));

    // If preincrement, stack top is the return value, otherwise pop and
    // keep duped value on top.
    if (!isPre)
      emitter.emitPop();
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
      emitter.emitNewTuple(0);
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
        emitter.emitPop();
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
    emitter.emitNewTuple(elems.length);
  }

  private void buildDynamicTupleAssign(ELNode.TUPLE lhs, int failBlock) {
    Slot rhsSlot = new Slot();
    rhsSlot.store();

    emitInvokeMethod(Object.class, "getClass");
    emitInvokeMethod(Class.class, "isArray");
    emitter.emitJumpIfFalse(failBlock);

    rhsSlot.load();
    emitInvokeMethod(Array.class, "getLength", Object.class);
    emitter.emitUnbox(int.class);
    buildConstUnbox(lhs.elems.length);
    emitter.emitEq(K_INT);
    emitter.emitJumpIfFalse(failBlock);

    for (int i = 0; i < lhs.elems.length; i++) {
      rhsSlot.load();
      buildConstUnbox(i);
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
    emitter.emitNewTuple(lhs.elems.length);
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
          emitter.emitPushCtx();
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
      emitter.emitPushNull();
      return;
    }

    for (int i = 0; i < node.exps.length - 1; i++) {
      if (emitter.isDead())
        return;
      build(node.exps[i]);
      emitter.emitPop();
    }

    if (!emitter.isDead())
      buildTail(node.exps[node.exps.length - 1]);
  }

  public void visit(ELNode.WHILE node) {
    int header = allocBlockId();
    int body = allocBlockId();
    int exit = allocBlockId();

    loopStack.push(new LoopTargets(header, exit));
    emitter.emitJump(header);

    startBlock(header);
    build(node.cond);
    emitter.emitJumpIfTrue(body);
    emitter.emitJump(exit);

    startBlock(body);
    build(node.body);
    emitter.emitPop();
    emitter.emitJump(header);

    startBlock(exit);
    emitter.emitPushNull();

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
        emitter.emitPop();
      }
    }
    emitter.emitJump(header);

    startBlock(header);
    build(node.cond);
    emitter.emitJumpIfTrue(body);
    emitter.emitJump(exit);

    startBlock(body);
    build(node.body);
    emitter.emitPop();
    emitter.emitJump(cont);

    startBlock(cont);
    if (node.step != null) {
      for (ELNode e : node.step) {
        build(e);
        emitter.emitPop();
      }
    }
    emitter.emitJump(header);

    startBlock(exit);
    emitter.emitPushNull();
    loopStack.pop();
  }

  public void visit(ELNode.FOREACH node) {
    if (node.range instanceof ELNode.RANGE r) {
      if (isStaticTypedRange(r))
        buildStaticRangedFor(node.var, node.index, r, node.body);
      else
        buildDynamicRangedFor(node.var, node.index, r, node.body);
    } else {
      buildIterateFor(node);
    }
  }

  private boolean isStaticTypedRange(ELNode.RANGE r) {
    if (r.end == null) {
      return r.begin.inferredType == Types.INT ||
             r.begin.inferredType == Types.LONG;
    }

    return (r.begin.inferredType == Types.INT ||
            r.begin.inferredType == Types.LONG) &&
           (r.end.inferredType == Types.INT ||
            r.end.inferredType == Types.LONG);
  }

  private void buildStaticRangedFor(ELNode.DEFINE var, ELNode.DEFINE index,
                                    ELNode.RANGE range, ELNode body) {
    assert (var.inferredType == Types.INT || var.inferredType == Types.LONG) &&
           (index == null || index.inferredType == var.inferredType);

    int indKind = var.inferredType == Types.INT ? K_INT : K_LONG;
    Class<?> indType = indKind == K_INT ? int.class : long.class;

    Object ZERO = indKind == K_INT ? (Object)0 : (Object)0L;
    Object ONE  = indKind == K_INT ? (Object)1 : (Object)1L;

    PrimSlot varSlot = new PrimSlot(indType);
    PrimSlot idxSlot = new PrimSlot(indType);
    PrimSlot stepSlot = null;
    PrimSlot countSlot = null;

    Slot shadowVarSlot = new Slot(var);
    Slot shadowIdxSlot = index != null ? new Slot(index) : null;

    // Initialize local variables.
    buildCoerce(range.begin, indType);
    varSlot.storePrim();
    varSlot.load();
    shadowVarSlot.define();
    emitter.emitPop();

    if (range.next != null) {
      stepSlot = new PrimSlot(indType);
      buildCoerce(range.next, indType);
      varSlot.loadPrim();
      emitter.emitSub(indKind);
      stepSlot.storePrim();
    }

    if (range.end != null) {
      countSlot = new PrimSlot(indType);
      buildCoerce(range.end, indType);
      if (range.exclude) {
        buildConstUnbox(ONE);
        emitter.emitSub(indKind);
      }
      varSlot.loadPrim();
      emitter.emitSub(indKind);
      if (stepSlot != null) {
        stepSlot.loadPrim();
        emitter.emitIDiv(indKind);
      }
      buildConstUnbox(ONE);
      emitter.emitAdd(indKind);
      countSlot.storePrim();
    }

    buildConstUnbox(ZERO);
    idxSlot.storePrim();
    if (shadowIdxSlot != null) {
      buildConst(ZERO);
      shadowIdxSlot.define();
      emitter.emitPop();
    }

    int bodyB = allocBlockId();
    int headerB = range.end != null ? allocBlockId() : bodyB;
    int contB = allocBlockId();
    int exitB = allocBlockId();

    loopStack.push(new LoopTargets(contB, exitB));
    emitter.emitJump(headerB);

    // Generate loop condition.
    if (countSlot != null) {
      startBlock(headerB);
      idxSlot.loadPrim();
      countSlot.loadPrim();
      emitter.emitLt(indKind);
      emitter.emitJumpIfTrue(bodyB);
      emitter.emitJump(exitB);
    }

    // Generate loop body.
    startBlock(bodyB);
    build(body);
    emitter.emitPop();
    emitter.emitJump(contB);

    // Generate loop step.
    startBlock(contB);
    idxSlot.loadPrim();
    buildConstUnbox(ONE);
    emitter.emitAdd(indKind);
    idxSlot.storePrim();
    if (shadowIdxSlot != null) {
      idxSlot.load();
      shadowIdxSlot.store();
      emitter.emitPop();
    }

    varSlot.loadPrim();
    if (stepSlot != null)
      stepSlot.loadPrim();
    else
      buildConstUnbox(ONE);
    emitBinOp(indKind, Token.ADD);
    varSlot.storePrim();
    varSlot.load();
    shadowVarSlot.store();
    emitter.emitPop();
    emitter.emitJump(headerB);

    // Cleanup
    startBlock(exitB);
    emitter.emitPushNull();
    loopStack.pop();
    release(varSlot);
    release(idxSlot);
    release(stepSlot);
    release(countSlot);
    release(shadowVarSlot);
    release(shadowIdxSlot);
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
      emitter.emitSub();
      stepSlot.store(); // step = next - begin
      emitter.emitPop();
    } else {
      build(range.begin);
      varSlot.define();
      emitter.emitPop();
    }

    if (range.end != null) {
      countSlot = new Slot();
      build(range.end);
      if (range.exclude) {
        buildConst(1);
        emitter.emitSub();
      }
      varSlot.load();
      emitter.emitSub();
      if (stepSlot != null) {
        stepSlot.load();
        emitter.emitDiv();
      }
      buildConst(1);
      emitter.emitAdd();
      countSlot.store(); // count = (end - begin) / step + 1
      emitter.emitPop();
    }

    buildConst(0);
    idxSlot.store();
    emitter.emitPop();

    int bodyB = allocBlockId();
    int headerB = range.end != null ? allocBlockId() : bodyB;
    int contB = allocBlockId();
    int exitB = allocBlockId();

    loopStack.push(new LoopTargets(contB, exitB));
    emitter.emitJump(headerB);

    // Generate loop condition.
    if (countSlot != null) {
      startBlock(headerB);
      idxSlot.load();
      countSlot.load();
      emitter.emitLt();
      emitter.emitJumpIfTrue(bodyB);
      emitter.emitJump(exitB);
    }

    // Generate loop body.
    startBlock(bodyB);
    build(body);
    emitter.emitPop();
    emitter.emitJump(contB);

    // Generate loop step.
    startBlock(contB);
    idxSlot.load();
    buildConst(1);
    emitter.emitAdd();
    idxSlot.store();
    emitter.emitPop();

    varSlot.load();
    if (stepSlot != null)
      stepSlot.load();
    else
      buildConst(1);
    emitDynBinOp(Token.ADD);
    varSlot.store();
    emitter.emitPop();
    emitter.emitJump(headerB);

    // Cleanup
    startBlock(exitB);
    emitter.emitPushNull();
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
      emitter.emitPop();
    }
    Slot iterSlot = new Slot();

    build(node.range);
    if (node.range instanceof ELNode.CONS) {
      emitInvokeMethod(Iterable.class, "iterator");
      iterSlot.store();
      emitter.emitPop();
    } else {
      emitInvokeMethod(Runtime.class, "iterator", Object.class);
      iterSlot.store();
      emitter.emitJumpIfNull(exit);
    }
    emitter.emitJump(header);

    startBlock(header);
    iterSlot.load();
    emitInvokeMethod(Iterator.class, "hasNext");
    emitter.emitJumpIfFalse(exit);
    emitter.emitJump(body);

    startBlock(body);
    iterSlot.load();
    emitInvokeMethod(Iterator.class, "next");
    if (varSlot != null)
      varSlot.store();
    emitter.emitPop();

    if (node.index != null) {
      idxSlot.load();
      buildConst(1L);
      emitter.emitAdd();
      idxSlot.store();
      emitter.emitPop();
    }

    build(node.body);
    emitter.emitPop();
    emitter.emitJump(header);

    startBlock(exit);
    emitter.emitPushNull();
    loopStack.pop();
    release(varSlot);
    release(idxSlot);
    release(iterSlot);
  }

  public void visit(ELNode.BREAK node) {
    if (loopStack.isEmpty())
      reportError(node.pos, _T(EL_STATEMENT_NOT_IN_LOOP, "break"));
    else
      emitter.emitJump(loopStack.peek().breakBlock());
  }

  public void visit(ELNode.CONTINUE node) {
    if (loopStack.isEmpty())
      reportError(node.pos, _T(EL_STATEMENT_NOT_IN_LOOP, "continue"));
    else
      emitter.emitJump(loopStack.peek().continueBlock());
  }

  public void visit(ELNode.RETURN node) {
    // Make sure single entry single exit.
    if (exitBlock == -1)
      exitBlock = allocBlockId();
    if (node.right != null) {
      buildTail(node.right);
      emitter.emitJump(exitBlock);
    } else {
      emitter.emitPushNull();
      emitter.emitJump(exitBlock);
    }
  }

  private void emitReturn(ELNode rtype) {
    if (exitBlock != -1)
      startBlock(exitBlock);
    if (rtype instanceof ELNode.IDENT ident && ident.id.equals("void")) {
      emitter.emitPop();
      emitter.emitPushNull();
      emitter.emitReturn();
    } else {
      emitter.emitReturn();
    }
  }

  public void visit(ELNode.THROW node) {
    build(node.cause);
    emitter.emitThrow();
  }

  public void visit(ELNode.ASSERT node) {
    build(node.exp);
    if (node.msg != null)
      build(node.msg);
    emitter.emitAssert(node.msg == null ? 1 : 2);
  }

  public void visit(ELNode.TRY node) {
    // Compile try body (zero-param closure).
    IRFunction body = buildScopedLambda((ELNode.LAMBDA)node.body);

    // Handlers: each handler is a DEFINE(id = exception var, expr = body).
    int count = node.handlers != null ? node.handlers.length : 0;
    IRFunction[] handlers = new IRFunction[count];
    Class<?>[] types = new Class<?>[count];
    for (int i = 0; i < count; i++) {
      if (node.types[i] != null)
        types[i] = loadJavaClass(node.types[i]);
      handlers[i] = buildScopedLambda((ELNode.LAMBDA)node.handlers[i]);
    }

    // Finally (optional, zero-param closure).
    IRFunction finalizer = null;
    if (node.finalizer != null) {
      finalizer = buildScopedLambda((ELNode.LAMBDA)node.finalizer);
    }

    // Construct the TryDescriptor.
    emitter.emitTry(new Descriptors.Try(body, handlers, types, finalizer));
  }

  public void visit(ELNode.SYNCHRONIZED node) {
    IRFunction body = buildScopedLambda((ELNode.LAMBDA)node.body);
    build(node.exp);
    emitter.emitSynchronized(body);
  }

  public void visit(ELNode.LAMBDA node) {
    IRFunction func = buildScopedLambda(node);
    emitter.emitClosure(func);
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
    return buildLambda(node, null, null);
  }

  private IRFunction buildLambda(ELNode.LAMBDA node, IRClass initClass,
                                 Class<?> expandoClass) {
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
      func = new IRFunction(owner,
                            node.name != null ? node.name : "<lambda>",
                            node.vars.length,
                            Arrays.stream(node.vars)
                              .map(x -> x.id)
                              .toArray(String[]::new),
                            node.varargs, modifiers);
      ELNode.DEFINE tmpdef = new ELNode.DEFINE(node.pos, "", null, null, node);
      node.symbol = new Symbol(node.scope, tmpdef);
      node.symbol.func = func;
    }

    IRBuilder nested = new IRBuilder(this, func, node.scope);
    nested.currentFile = node.file;
    nested.expandoClass = expandoClass;

    return nested.buildNestedFunction(node, initClass);
  }

  private IRFunction buildNestedFunction(ELNode.LAMBDA node, IRClass initClass) {
    // Reserve slots for all pre-allocated variables in this lambda scope.
    // Temp vars allocated via allocLocalVar will then start above the max
    // pre-allocated slot, avoiding collisions.
    reserveSlots(node.scope.maxSlots);

    // Define globals for captured lamba parameters.
    for (ELNode.DEFINE var : node.vars) {
      if (var.symbol != null && var.symbol.captured) {
        emitter.emitPushVar(var.symbol.slot);
        emitter.emitDefineGlobal(var.id, var.symbol.isFinal());
      }
    }

    if (initClass != null)
      emitSuperConstructor(node.pos, initClass);

    // Build the function body.
    buildTail(node.body);

    // Returns null for void function. Other return type has no meaning in
    // current implementation where we lacks type inferrer.
    emitReturn(node.rtype);

    // Initialize function parameters.
    Class<?>[] paramTypes = null;
    if (node.inferredType instanceof FunctionType ftype) {
      assert ftype.params().length == node.vars.length;
      paramTypes = new Class<?>[node.vars.length];
      for (int i = 0; i < node.vars.length; i++) {
        Class<?> paramType = ftype.params()[i].javaClass();
        if (paramType != null && paramType != Object.class &&
            !(node.varargs && i == node.vars.length - 1) &&
            (expandoClass == null || i != 0)) {
          paramTypes[i] = paramType;
        }
      }
    }

    return finish(paramTypes, getDefaultValues(node.vars));
  }

  private void emitSuperConstructor(int pos, IRClass initClass) {
    ELNode[] args = initClass.super_args;

    if (initClass.base instanceof IRClass superClass) {
      emitter.emitPushThis();
      emitter.emitPushEnv();
      args = getCallArgs(pos, superClass, args, initClass.super_keys);
      buildCallArgs(superClass.init_proc, args);
      emitter.emitConstructor(superClass);
    } else {
      // Collect all constructor with the given arity.
      Class<?> baseClass = (Class<?>)initClass.base;
      List<Constructor<?>> constructors = new ArrayList<>();
      for (Constructor<?> c : baseClass.getDeclaredConstructors()) {
        if (c.getParameterCount() == args.length &&
            (Modifier.isPublic(c.getModifiers()) ||
             Modifier.isProtected(c.getModifiers())))
          constructors.add(c);
      }

      if (constructors.isEmpty()) {
        reportError(pos, _T(EL_CONSTRUCTOR_NOT_FOUND, baseClass.getName()));
        return;
      }

      if (constructors.size() == 1) {
        // For non-overload constructor, generate the constructor invocation.
        Constructor<?> cons = constructors.get(0);
        Class<?>[] types = cons.getParameterTypes();
        emitter.emitPushThis();
        for (int i = 0; i < args.length; i++)
          buildCoerce(args[i], types[i]);
        emitter.emitConstructor(cons);
      } else {
        // For overload constructor, resolve construct at runtime.
        buildTuple(Object.class, args);
        emitter.emitConstructor(args.length, baseClass);
      }
    }
  }

  /**
   * Extract default parameter values from lambda definitions.
   */
  private Object[] getDefaultValues(ELNode.DEFINE[] vars) {
    List<Object> values = new ArrayList<>();
    for (ELNode.DEFINE var : vars) {
      if (var.expr != null)
        values.add(const_value(var.expr));
    }
    return values.isEmpty() ? null : values.toArray();
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

    if (node instanceof ELNode.MAP m) {
      LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
      for (int i = 0; i < m.keys.length; i++)
        map.put(const_value(m.keys[i]), const_value(m.values[i]));
      return map;
    }

    if (node instanceof ELNode.RANGE r && r.isConstant()) {
      return r.getValue(null);
    }

    if (node instanceof ELNode.IDENT && node.symbol != null) {
      if (node.symbol.clazz != null)
        return node.symbol.clazz;
      if (node.symbol.scope.isClassScope() && node.symbol.isStatic() &&
          node.symbol.func == null) {
        IRClass ownerClass = node.symbol.scope.frontier.symbol.clazz;
        return new Descriptors.Field(ownerClass, node.symbol.name);
      }
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

    reportError(node.pos, _T(EL_DEFAULT_VALUE_NOT_CONSTANT));
    return new ELNode.NULL(0);
  }

  public void visit(ELNode.CLASSDEF node) {
    // Retrieve IRClass skeleton that created at SymbolTableBuilder.
    IRClass clazz = node.symbol.clazz;
    assert clazz != null;
    program.add(clazz);

    // Recursively build nested classes.
    for (ELNode.DEFINE var : node.cvars) {
      if (var.expr instanceof ELNode.CLASSDEF) {
        build(var.expr);
        emitter.emitPop();
      }
    }
    for (ELNode.DEFINE var : node.ivars) {
      if (var.expr instanceof ELNode.CLASSDEF) {
        build(var.expr);
        emitter.emitPop();
      }
    }

    // Build class and instance procedures.
    for (ELNode.DEFINE var : node.cvars) {
      if (var.expr instanceof ELNode.LAMBDA) {
        buildScopedLambda((ELNode.LAMBDA)var.expr);
      }
    }
    for (ELNode.DEFINE var : node.ivars) {
      if (var.expr instanceof ELNode.LAMBDA) {
        buildScopedLambda((ELNode.LAMBDA)var.expr);
      }
    }

    // Build class init proc.
    if (clazz.clinit_proc != null)
      buildLambda(clazz.clinit_proc);

    // Build instance init proc.
    buildLambda(clazz.init_proc, clazz, null);

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
        emitter.emitPop();
      }
    }

    // Allocate blocks for each case entry point
    int[] nextCase = new int[node.alts.length + 1]; // +1 for default
    for (int ci = 0; ci < node.alts.length; ci++)
      nextCase[ci] = allocBlockId();
    nextCase[node.alts.length] = allocBlockId(); // default/error block

    int exitBlock = allocBlockId();

    // Jump to first case
    emitter.emitJump(nextCase[0]);

    for (int ci = 0; ci < node.alts.length; ci++) {
      ELNode.CASE c = node.alts[ci];
      int failBlock = nextCase[ci + 1];

      startBlock(nextCase[ci]);

      // Each case gets its own control scope for variable bindings. On failure,
      // leaveScope discards bindings.
      Scope prevScope = currentScope;
      currentScope = c.scope;
      if (c.scope.hasCaptures())
        emitter.emitEnterScope();

      // Compile patterns for each column
      if (c.patterns != null) {
        for (int pi = 0; pi < c.patterns.length; pi++) {
          argSlots[pi].load();
          buildMatchPattern(argSlots[pi], (ELNode)c.patterns[pi], failBlock);
          emitter.emitJumpIfFalse(failBlock);
        }
      }

      if (c.guards == null) {
        // No guards, evaluate the single body.
        assert c.bodies != null && c.bodies.length == 1;
        buildTail(c.bodies[0]);
        emitter.emitJump(exitBlock);
      } else {
        // Evaluate each guard and body
        assert c.bodies.length == c.guards.length;
        for (int i = 0; i < c.guards.length; i++) {
          int nextGuard = -1;
          if (c.guards[i] != null) {
            if (i != c.guards.length - 1) {
              nextGuard = allocBlockId();
              build(c.guards[i]);
              emitter.emitJumpIfFalse(nextGuard);
            } else {
              build(c.guards[i]);
              emitter.emitJumpIfFalse(failBlock);
            }
          }
          buildTail(c.bodies[i]);
          emitter.emitJump(exitBlock);
          if (nextGuard != -1)
            startBlock(nextGuard);
        }
      }

      // Leave the case scope.
      if (c.scope.hasCaptures())
        emitter.emitLeaveScope();
      currentScope = prevScope;
    }

    // Default block.
    startBlock(nextCase[node.alts.length]);
    if (node.deflt != null) {
      buildTail(node.deflt);
    } else {
      emitter.emitNew(EvaluationException.class);
      emitter.emitPushCtx();
      buildConst(EL_PATTERN_NOT_MATCH);
      emitInvokeMethod(Resources.class, "getText", String.class);
      emitConstructor(EvaluationException.class, ELContext.class, String.class);
      emitter.emitThrowException();
    }
    emitter.emitJump(exitBlock);

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
        emitter.emitInstanceOf(resolveClass(def.type));
        emitter.emitJumpIfFalse(failBlock);
      }

      // As-pattern check.
      if (def.expr != null) {
        if (argConsumed)
          argSlot.load();
        argConsumed = true;
        buildMatchPattern(argSlot, def.expr, failBlock);
        emitter.emitJumpIfFalse(failBlock);
      }

      // Wildcard: always matches.
      if ("_".equals(def.id)) {
        if (!argConsumed)
          emitter.emitPop();
        emitter.emitPushTrue();
        return;
      }

      // Variable binding -> bind to new pattern variable.
      if (argConsumed)
        argSlot.load();
      if (def.symbol.captured)
        emitter.emitDefineGlobal(def.id, def.symbol.isFinal());
      else
        emitter.emitStoreVarPop(def.symbol.slot);
      emitter.emitPushTrue();
      return;
    }

    if (pat instanceof ELNode.IDENT var) {
      if (var.symbol.captured) {
        if (var.symbol.scope.isProgramScope())
          emitter.emitGetStatic(null, var.id);
        else
          emitter.emitPushGlobal(var.id);
      } else {
        emitter.emitPushVar(var.symbol.slot);
      }
      emitDynBinOp(Token.EQ);
      return;
    }

    if (pat instanceof ELNode.NOT not) {
      buildMatchPattern(argSlot, not.right, failBlock);
      emitter.emitNot();
      return;
    }

    if (pat instanceof ELNode.OR or) {
      int tryRight = allocBlockId();
      int done = allocBlockId();

      // Left branch, argSlot already on stack top.
      buildMatchPattern(argSlot, or.left, tryRight);
      emitter.emitJumpIfFalse(tryRight);
      emitter.emitJump(done); // matched -> skip right

      startBlock(tryRight);
      argSlot.load();
      buildMatchPattern(argSlot, or.right, failBlock);
      emitter.emitJumpIfFalse(failBlock);
      emitter.emitJump(done);
      startBlock(done);
      emitter.emitPushTrue();
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
      emitter.emitJumpIfNonNull(failBlock);
      emitter.emitPushTrue();
      return;
    }

    if (pat instanceof ELNode.SYMBOL sym) {
      buildConst(sym.value);
      emitter.emitIdEq();
      return;
    }

    if (pat instanceof ELNode.CLASS cls) {
      emitter.emitInstanceOf(resolveClass(cls.type));
      return;
    }

    if (pat instanceof ELNode.REGEXP re) {
      emitter.emitInstanceOf(String.class);
      emitter.emitJumpIfFalse(failBlock);
      buildConst(re.value); // the pattern
      emitter.emitCheckCast(java.util.regex.Pattern.class);
      argSlot.load();       // the string to match
      emitter.emitCheckCast(CharSequence.class);
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
      emitter.emitJumpIfFalse(failBlock);

      argSlot.load();
      emitInvokeMethod(Array.class, "getLength", Object.class);
      emitter.emitUnbox(int.class);
      buildConstUnbox(t.elems.length);
      emitter.emitEq(K_INT);
      emitter.emitJumpIfFalse(failBlock);

      for (int i = 0; i < t.elems.length; i++) {
        if (ELNode.isWildcard(t.elems[i]))
          continue;
        argSlot.load();
        buildConstUnbox(i);
        emitInvokeMethod(Array.class, "get", Object.class, int.class);
        if (!isSimplePattern(t.elems[i])) {
          if (tmpSlot == null)
            tmpSlot = new Slot();
          tmpSlot.store();
        }
        buildMatchPattern(tmpSlot, t.elems[i], failBlock);
        emitter.emitJumpIfFalse(failBlock);
      }

      release(tmpSlot);
      emitter.emitPushTrue();
      return;
    }

    if (pat instanceof ELNode.CONS cons) {
      Slot seqSlot = new Slot();
      Slot tmpSlot = new Slot();

      emitter.emitInstanceOf(List.class);
      emitter.emitJumpIfFalse(failBlock);
      emitter.emitPushCtx();
      argSlot.load();
      buildCoerce(Seq.class);
      seqSlot.store();
      emitInvokeMethod(List.class, "isEmpty");
      emitter.emitJumpIfTrue(failBlock);

      if (!ELNode.isWildcard(cons.head)) {
        seqSlot.load();
        emitInvokeMethod(Seq.class, "head");
        if (!isSimplePattern(cons.head))
          tmpSlot.store();
        buildMatchPattern(tmpSlot, cons.head, failBlock);
        emitter.emitJumpIfFalse(failBlock);
      }

      if (!ELNode.isWildcard(cons.tail)) {
        seqSlot.load();
        emitInvokeMethod(Seq.class, "tail");
        if (!isSimplePattern(cons.tail))
          tmpSlot.store();
        buildMatchPattern(tmpSlot, cons.tail, failBlock);
        emitter.emitJumpIfFalse(failBlock);
      }

      release(tmpSlot);
      release(seqSlot);
      emitter.emitPushTrue();
      return;
    }

    if (pat instanceof ELNode.NIL) {
      emitDynUnOp(Token.EMPTY);
      return;
    }

    if (pat instanceof ELNode.RANGE) {
      emitter.emitPop(); // Re-push arg after build tuple
      build(pat);
      argSlot.load();
      emitInvokeMethod(List.class, "contains", Object.class);
      return;
    }

    if (pat instanceof ELNode.MAP map) {
      emitter.emitPop(); // Re-push arg for each property.
      Slot tmpSlot = null;
      for (int i = 0; i < map.keys.length; i++) {
        assert map.keys[i] instanceof ELNode.STRINGVAL;
        emitter.emitPushEnv();
        argSlot.load();
        emitter.emitInvokeDynamic(new Descriptors.Indy(
          getValueBootstrap, ((ELNode.STRINGVAL)map.keys[i]).value, Object.class,
          EvaluationContext.class, Object.class));
        if (!isSimplePattern(map.values[i])) {
          if (tmpSlot == null)
            tmpSlot = new Slot();
          tmpSlot.store();
        }
        buildMatchPattern(tmpSlot, map.values[i], failBlock);
        emitter.emitJumpIfFalse(failBlock);
      }
      release(tmpSlot);
      emitter.emitPushTrue();
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
          emitter.emitPushFalse();
          return;
        }

        IRClass clazz = cdef.symbol.clazz;
        Slot tmpSlot = null;

        emitter.emitInstanceOf(clazz);
        emitter.emitJumpIfFalse(failBlock);

        if (argc == 0) {
          emitter.emitPushTrue();
          return;
        }

        if (data.keys != null) {
          // Matches for object properties.
          for (int i = 0; i < argc; i++) {
            emitter.emitPushEnv();
            argSlot.load();
            emitter.emitInvokeDynamic(new Descriptors.Indy(
              getValueBootstrap, data.keys[i], Object.class,
              EvaluationContext.class, Object.class));
            if (!isSimplePattern(args[i])) {
              if (tmpSlot == null)
                tmpSlot = new Slot();
              tmpSlot.store();
            }
            buildMatchPattern(tmpSlot, args[i], failBlock);
            emitter.emitJumpIfFalse(failBlock);
          }
        } else {
          // Matches for constructor variables.
          for (int i = 0; i < argc; i++) {
            if (ELNode.isWildcard(args[i]))
              continue;
            argSlot.load();
            emitter.emitCheckCast(clazz);
            emitter.emitGetField(clazz, cdef.vars[i].id);
            if (!isSimplePattern(args[i])) {
              if (tmpSlot == null)
                tmpSlot = new Slot();
              tmpSlot.store();
            }
            buildMatchPattern(tmpSlot, args[i], failBlock);
            emitter.emitJumpIfFalse(failBlock);
          }
        }
        release(tmpSlot);
      } else {
        Class<?> cls;
        String[] slots = null;

        if (base.symbol != null &&
            base.symbol.def.expr instanceof ELNode.CLASS c) {
          cls = loadClassAtCompileTime(base.pos, c.getClassName());
          slots = c.slots;
        } else {
          cls = loadClassAtCompileTime(base.pos, base.id);
        }

        if (argc == 0) {
          emitter.emitInstanceOf(cls);
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
            emitter.emitPushFalse();
            return;
          }
        }

        emitter.emitInstanceOf(cls);
        emitter.emitJumpIfFalse(failBlock);

        Slot tmpSlot = null;
        for (int i = 0; i < argc; i++) {
          emitter.emitPushEnv();
          argSlot.load();
          emitter.emitInvokeDynamic(new Descriptors.Indy(
            getValueBootstrap, slots[i], Object.class,
            EvaluationContext.class, Object.class));
          if (!isSimplePattern(args[i])) {
            if (tmpSlot == null)
              tmpSlot = new Slot();
            tmpSlot.store();
          }
          buildMatchPattern(tmpSlot, args[i], failBlock);
          emitter.emitJumpIfFalse(failBlock);
        }
        release(tmpSlot);
      }

      emitter.emitPushTrue();
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
      emitter.emitPop();
    }

    int failBlock = allocBlockId();
    int exitBlock = allocBlockId();

    argSlot.load();
    buildMatchPattern(argSlot, node.left, failBlock);
    emitter.emitJumpIfFalse(failBlock);
    emitter.emitJump(exitBlock);

    startBlock(failBlock);
    buildConst("pattern not match");
    emitter.emitThrow();
    emitter.emitJump(exitBlock);

    startBlock(exitBlock);
    argSlot.load();
    argSlot.release();
  }

  public void visit(ELNode.NEW node) {
    // NEW can be used to create new instance of a Java class, a user defined
    // elite class or a data class. First let me lookup symbol table to see if
    // the target is a CLASSDEF or CLASS, then try Java class.

    if (node.inferredType instanceof ELiteClassType ct) {
      IRClass irc = ct.irc();
      if (irc.isSingleton()) {
        emitter.emitGetStatic(irc, "$singleton");
      } else {
        ELNode[] args = getCallArgs(node.pos, irc, node.args, node.keys);
        emitter.emitNew(irc);
        emitter.emitPushEnv();
        buildCallArgs(irc.init_proc, args);
        emitter.emitConstructor(irc);
      }
    } else if (node.memberType instanceof ConstructorType ct) {
      Class<?>[] ptypes = ct.ctor().getParameterTypes();
      emitter.emitNew(ct.ctor().getDeclaringClass());
      for (int i = 0; i < ptypes.length; i++)
        buildCoerce(node.args[i], ptypes[i]);
      emitter.emitConstructor(ct.ctor());
    } else if (node.base.inferredType instanceof ClassObjectType ct) {
      Class<?> javaClass = ct.classObject();
      buildNew(node.pos, javaClass, node.args);
    } else {
      reportError(node.pos, _T(EL_CLASS_NOT_FOUND, node.getClassName()));
      return;
    }

    if (node.props != null) {
      Slot tmpSlot = new Slot();
      tmpSlot.store();
      for (int i = 0; i < node.props.keys.length; i++) {
        if (node.props.keys[i] instanceof ELNode.STRINGVAL key) {
          build(node.props.values[i]);
          tmpSlot.load();
          emitter.emitPushEnv();
          emitter.emitInvokeDynamic(new Descriptors.Indy(
            setValueBootstrap, mangle(key.value), void.class, Object.class,
            Object.class, EvaluationContext.class));
        } else {
          build(node.props.values[i]);
          tmpSlot.load();
          build(node.props.keys[i]);
          emitter.emitPushCtx();
          emitInvokeMethod(Runtime.class, "setValue", Object.class,
                           Object.class, Object.class, ELContext.class);
          emitter.emitPop();
        }
      }
      tmpSlot.release();
    }
  }

  private void buildNew(int pos, Class<?> cls, ELNode[] args) {
    Constructor<?> ctor = null;
    boolean overload = false;
    for (Constructor<?> c : cls.getConstructors()) {
      if (c.getParameterCount() == args.length && !c.isVarArgs()) {
        if (ctor != null)
          overload = true;
        ctor = c;
      }
    }

    if (ctor == null) {
      reportError(pos, "Constructor not found for class: " + cls.getName());
      return;
    }

    if (!overload) {
      Class<?>[] types = ctor.getParameterTypes();
      emitter.emitNew(cls);
      for (int i = 0; i < args.length; i++)
        buildCoerce(args[i], types[i]);
      emitter.emitConstructor(ctor);
    } else {
      emitter.emitPushEnv();
      buildTuple(Object.class, args);
      emitter.emitInvokeDynamic(new Descriptors.Indy(
        constructBootstrap, cls.getSimpleName(), new Object[]{cls},
        Object.class, EvaluationContext.class, Object[].class));
    }
  }

  public void visitNode(ELNode node) {
    reportError(node.pos, "Unknown node type");
    failIfErrors();
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
    int[] code = emitter.toArray();
    blocks.add(new Block(currentBlockId, code, linePcMapping));
    emitter.clear();
    currentBlockId = blockId;
    linePcMapping.clear();
  }

  // ── Local management ──

  private class Slot {
    final int slot;
    final boolean captured;
    final String field;
    final boolean isTemporary;

    Slot() {
      slot = allocLocalVar();
      field = null;
      captured = false;
      isTemporary = true;
    }

    Slot(ELNode.DEFINE var) {
      if (var != null && var.symbol != null) {
        captured = var.symbol.captured;
        field = captured && var.symbol.scope.isProgramScope() ? var.id : null;
        slot = captured ? putConstant(var.id) : var.symbol.slot;
        isTemporary = false;
      } else {
        slot = allocLocalVar();
        field = null;
        captured = false;
        isTemporary = true;
      }
    }

    Slot(ELNode.IDENT var) {
      if (var != null && var.symbol != null) {
        captured = var.symbol.captured;
        field = captured && var.symbol.scope.isProgramScope() ? var.id : null;
        slot = captured ? putConstant(var.id) : var.symbol.slot;
        isTemporary = false;
      } else {
        slot = allocLocalVar();
        field = null;
        captured = false;
        isTemporary = true;
      }
    }

    void define() {
      assert field == null;
      if (captured) {
        emitter.emitDefineGlobal(slot);
        emitter.emitPushNull();
      } else {
        emitter.emitStoreVar(slot);
      }
    }

    void store() {
      if (field != null)
        emitter.emitPutStatic(null, field);
      else if (captured)
        emitter.emitStoreGlobal(slot);
      else
        emitter.emitStoreVar(slot);
    }

    void load() {
      if (field != null)
        emitter.emitGetStatic(null, field);
      else if (captured)
        emitter.emitPushGlobal(slot);
      else
        emitter.emitPushVar(slot);
    }

    void release() {
      if (isTemporary)
        freeSlots.push(slot);
    }

    int allocLocalVar() {
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

  class PrimSlot extends Slot {
    private final Class<?> type;
    private final int extraSlot;

    PrimSlot(Class<?> type) {
      this.type = type;
      if (type == long.class || type == double.class)
        extraSlot = allocLocalVar();
      else
        extraSlot = -1;
    }

    void storePrim() {
      assert type != null;
      emitter.emitStoreVarPop(slot, type);
    }

    void storeBoxedPrim() {
      if (type != null) {
        emitter.emitUnbox(type);
        emitter.emitStoreVarPop(slot, type);
      } else {
        emitter.emitStoreVar(slot);
      }
    }

    void loadPrim() {
      if (type != null)
        emitter.emitPushVar(slot, type);
      else
        emitter.emitPushVar(slot);
    }

    @Override
    void store() {
      if (type != null) {
        emitter.emitUnbox(type);
        emitter.emitStoreVarPop(slot, type);
      } else {
        emitter.emitStoreVar(slot);
      }
    }

    @Override
    void load() {
      if (type != null) {
        emitter.emitPushVar(slot, type);
        emitter.emitBox(type);
      } else {
        emitter.emitPushVar(slot);
      }
    }

    @Override
    void release() {
      freeSlots.push(slot);
      if (extraSlot != -1)
        freeSlots.push(extraSlot);
    }
  }

  /**
   * Reserve space in `varNames` up to (and including) the given slot index.
   * Temp vars allocated after this call will start above the reserved range.
   */
  private void reserveSlots(int maxSlots) {
    maxLocals = nextTempSlot = maxSlots;
  }

  // ── Finalization ──

  IRFunction finish(Class<?>[] paramTypes, Object[] defaultValues) {
    // Finish current block.
    blocks.add(new Block(currentBlockId, emitter.toArray(), linePcMapping));

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
    boolean opt = ELProgram.OPT_LEVEL >= 2;
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
                  buildDebugInfo(), paramTypes, defaultValues);
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
    BitSet wideSlots = new BitSet();
    for (Block block : blocks) {
      if (!block.isDead()) {
        final int[] data = block.code.data();
        final int n = block.code.size();
        for (int i = 0; i < n; i++) {
          // We only check read slot, write only slots are dead.
          if (IRFormat.opcode(data[i]) == PUSH_VAR) {
            int slot = IRFormat.operand(data[i]);
            int kind = IRFormat.payload(data[i]);
            usedSlots.set(slot);
            if (kind == K_LONG || kind == K_DOUBLE)
              wideSlots.set(slot);
          }
        }
      }
    }

    // Preserve function parameters.
    usedSlots.set(0, func.paramCount());

    // Remove unused slots.
    int[] slotMap = new int[maxLocals];
    int nextSlot = 0;
    for (int i = 0; i < maxLocals; i++) {
      if (usedSlots.get(i)) {
        slotMap[i] = nextSlot++;
        if (wideSlots.get(i))
          nextSlot++;
      } else {
        slotMap[i] = -1;
      }
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
        int payload = IRFormat.payload(inst);

        switch (op) {
        case PUSH_VAR:
          data[i] = IRFormat.pack(op, payload, slotMap[varIndex]);
          break;

        case STORE_VAR:
          if ((varIndex = slotMap[varIndex]) == -1) {
            // Remove dead store.
            data[i] = IRFormat.pack(NOP, 0, 0);
            changed = true;
          } else {
            data[i] = IRFormat.pack(op, payload, varIndex);
          }
          break;

        case STORE_VAR_POP:
          if ((varIndex = slotMap[varIndex]) == -1) {
            // Dead store and pop, replace with POP.
            data[i] = IRFormat.pack(POP, 0, 0);
            changed = true;
          } else {
            data[i] = IRFormat.pack(op, payload, varIndex);
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
      emitter.emitPushNull();
    else if (value instanceof Boolean)
      buildConst((Boolean)value);
    else
      emitter.emitPushConst(value);
  }

  private void buildConst(Boolean value) {
    if (value)
      emitter.emitPushTrue();
    else
      emitter.emitPushFalse();
  }

  private void buildConstUnbox(Object value) {
    buildConst(value);
    emitter.emitUnbox(getUnboxedType(value.getClass()));
  }

  private void buildCoerce(Class<?> type) {
    emitter.emitInvokeDynamic(new Descriptors.Indy(
      coerceBootstrap, "coerce", type, ELContext.class, Object.class));
  }

  private void buildCoerce(ELNode node, Class<?> toType) {
    // No coercion for Object.
    if (toType == Object.class) {
      build(node);
      return;
    }

    Class<?> nodeType = node.inferredType.javaClass();
    if (nodeType != null) {
      if (toType.isAssignableFrom(nodeType)) {
        build(node);
        if (!(node instanceof ELNode.Constant))
          emitter.emitCheckCast(toType);
        return;
      }

      // Optimize primitive coercion.
      Class<?> from = TypeCoercion.getUnboxedType(nodeType);
      Class<?> to = TypeCoercion.getUnboxedType(toType);
      if (from.isPrimitive() && to.isPrimitive()) {
        if (from == to) {
          build(node);
          if (!nodeType.isPrimitive() && toType.isPrimitive())
            emitter.emitUnbox(toType);
          else if (nodeType.isPrimitive() && !toType.isPrimitive())
            emitter.emitBox(toType);
          return;
        }

        if (from == Void.TYPE || from == Boolean.TYPE ||
            to == Void.TYPE || to == Boolean.TYPE) {
          reportError(node.pos, "Cannot convert " + from.getName() + " to " +
                                to.getName());
          return;
        }

        if (node instanceof ELNode.Constant) {
          buildConstUnbox(TypeCoercion.coerce(node.getValue(null), to));
          if (!toType.isPrimitive())
            emitter.emitBox(to);
        } else {
          build(node);
          emitter.emitCoerce(nodeType, toType);
        }
        return;
      }

      if (from.isPrimitive() && to == BigInteger.class) {
        if (node instanceof ELNode.NUMBER num) {
          buildConstUnbox(num.value.longValue());
        } else {
          build(node);
          emitter.emitCoerce(nodeType, long.class);
        }
        emitInvokeMethod(BigInteger.class, "valueOf", long.class);
        return;
      }
    }

    // Coerce at runtime.
    emitter.emitPushCtx();
    build(node);
    buildCoerce(toType);
  }

  private void emitInvokeMethod(Class<?> c, String name, Class<?>... types) {
    try {
      Method method = c.getMethod(name, types);
      emitter.emitInvokeMethod(method);
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  private void emitNewInstance(Class<?> c) {
    try {
      emitter.emitNew(c);
      emitter.emitConstructor(c.getConstructor());
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  private void emitConstructor(Class<?> c, Class<?>... types) {
    try {
      emitter.emitConstructor(c.getConstructor(types));
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  private void buildTuple(Class<?> type, ELNode... elems) {
    buildTuple(type, elems, 0, elems.length);
  }

  private void buildTuple(Class<?> type, ELNode[] elems, int start, int len) {
    buildConstUnbox(len);
    emitter.emitNewArray(type);
    for (int i = 0; i < len; i++) {
      emitter.emitDup();
      buildConstUnbox(i);
      buildCoerce(elems[start + i], type);
      emitter.emitStoreArray(type);
    }
  }

  private void buildTuple(Slot... slots) {
    buildConstUnbox(slots.length);
    emitter.emitNewArray(Object.class);
    for (int i = 0; i < slots.length; i++) {
      emitter.emitDup();
      buildConstUnbox(i);
      slots[i].load();
      emitter.emitStoreArray(Object.class);
    }
  }

  // ── Static API ──

  public static IRFunction compile(ELContext elctx, ELNode node) {
    SymbolTable symTable = SymbolTableBuilder.build(elctx, node);
    IRFunction func = new IRFunction("<expr>", 0, new String[0], false);
    IRBuilder b = new IRBuilder(elctx, new IRProgram(null, false), func,
                                symTable.currentScope());
    b.build(node);
    b.emitter.emitReturn();
    b.failIfErrors();
    return b.finish(null, null);
  }

  public static IRProgram compile(ELContext elctx, ELProgram program) {
    SymbolTable symTable = SymbolTableBuilder.build(elctx, program);
    reportSymbolTableError(program, symTable);
    TypeInference.infer(elctx, program, symTable.currentScope());

    List<ELNode> defs = program.getDefinitions();
    List<ELNode> exps = program.getExpressions();

    IRFunction func = new IRFunction("<program>", 0, new String[0], false);
    IRProgram output = new IRProgram(func, program.isStandalone());
    IRBuilder b = new IRBuilder(elctx, output, func, symTable.currentScope());
    b.setFile(program.getFilename());

    // Reserve slots for all pre-allocated program-level variables. After this,
    // allocLocalVar will allocate temp vars above the max slot.
    b.reserveSlots(symTable.currentScope().maxSlots);

    // Compile definitions for forward declaration.
    for (ELNode def : defs) {
      b.build(def);
      b.emitter.emitPop();
    }

    // Compile expressions
    if (!exps.isEmpty()) {
      for (int i = 0; i < exps.size() - 1; i++) {
        b.build(exps.get(i));
        b.emitter.emitPop();
      }
      b.build(exps.get(exps.size() - 1));
      b.emitReturn(null);
    } else {
      b.emitter.emitPushNull();
      b.emitReturn(null);
    }

    b.failIfErrors();
    b.finish(null, null);
    return output;
  }

  private static void reportSymbolTableError(ELProgram prog,
                                             SymbolTable symTable) {
    if (!symTable.getErrors().isEmpty())
      throw new ParseException(prog.getFilename(), symTable.getErrors());
  }
}
