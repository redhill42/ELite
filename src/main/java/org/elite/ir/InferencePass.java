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

import elite.lang.annotation.Expando;
import elite.lang.annotation.ExpandoScope;
import elite.xml.XmlNode;
import org.elite.eval.ELEngine;
import org.elite.eval.ELProgram;
import org.elite.eval.ELUtils;
import org.elite.eval.TypeCoercion;
import org.elite.eval.closure.MethodClosure;
import org.elite.parser.DefaultVisitor;
import org.elite.parser.ELNode;
import org.elite.parser.ParseError;
import org.elite.parser.ParseException;
import org.elite.parser.Token;
import org.elite.resolver.ClassResolver;
import org.elite.resolver.MethodResolver;
import org.elite.type.*;
import org.elite.util.BeanUtils;

import javax.el.ELContext;
import java.beans.IntrospectionException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.elite.resources.Resources.*;
import static org.elite.ir.SymbolTable.*;

final class InferencePass extends DefaultVisitor {

  private final ELContext elctx;
  private String filename;

  private final List<ParseError> errors = new ArrayList<>();

  private boolean changed;

  // Tracking current scope.
  private Scope currentScope;

  // The class for an expando closure and a map for expando closures.
  private Class<?> expandoClass;
  private final ExpandoMap expandoMap = new ExpandoMap();

  InferencePass(ELContext elctx) {
    this.elctx = elctx;
  }

  void run(ELProgram program, Scope scope) {
    this.filename = program.getFilename();
    this.currentScope = scope;
    this.changed = false;

    for (ELNode node : program.getDefinitions())
      scan(node);
    for (ELNode node : program.getExpressions())
      scan(node);

    failIfErrors();
  }

  boolean finished() {
    return !changed;
  }

  @Override
  public void scan(ELNode node) {
    if (node == null)
      return;

    if (node.scope != null) {
      Scope prevScope = currentScope;
      currentScope = node.scope;
      node.accept(this);
      currentScope = prevScope;
    } else {
      node.accept(this);
    }
  }

  @Override
  public void visitConstant(ELNode.Constant e) {
    setType(e, Type.of(e.getValue(null).getClass()));
  }

  @Override
  public void visit(ELNode.NULL e) {
    setType(e, Types.ANY);
  }

  @Override
  public void visit(ELNode.CLASS e) {
    // CLASS node (from import), resolve to java class object type.
    try {
      ClassResolver resolver = ClassResolver.getInstance(elctx);
      Class<?> clazz = resolver.resolveClass(e.getClassName());
      setType(e, new ClassObjectType(clazz));
    } catch (ClassNotFoundException ex) {
      reportError(e.pos, _T(EL_CLASS_NOT_FOUND, e.getClassName()));
      setType(e, Types.ERROR);
    }
  }

  @Override
  public void visit(ELNode.Composite e) {
    super.visit(e);
    setType(e, Type.of(String.class));
  }

  @Override
  public void visit(ELNode.CONS e) {
    super.visit(e);
    if (e.delay) {
      setType(e, Types.ANY);
    } else {
      Type head = e.head.inferredType;
      Type tail = e.tail.inferredType;
      if (e.tail instanceof ELNode.NIL) {
        setType(e, new ListType(head));
      } else if (tail instanceof ListType lt) {
        setType(e, new ListType(merge(head, lt.element())));
      } else {
        setType(e, new ListType(Types.ANY));
      }
    }
  }

  @Override
  public void visit(ELNode.NIL e) {
    setType(e, new ListType(Types.ANY));
  }

  @Override
  public void visit(ELNode.TUPLE e) {
    super.visit(e);
    Type[] elems = new Type[e.elems.length];
    for (int i = 0; i < e.elems.length; i++)
      elems[i] = valueTypeOf(e.elems[i]);
    setType(e, new TupleType(elems));
  }

  @Override
  public void visit(ELNode.MAP e) {
    super.visit(e);
    setType(e, new MapType(Types.ANY, Types.ANY));
  }

  @Override
  public void visit(ELNode.ARRAY e) {
    super.visit(e);
    int dims = e.dims != null ? e.dims.length : 1;
    Type element = resolveAnnotatedType(e.type);
    setType(e, new ArrayType(element, dims));
  }

  @Override
  public void visit(ELNode.RANGE e) {
    super.visit(e);

    if (e.end == null || e.begin.inferredType == e.end.inferredType)
      setType(e, new ListType(e.begin.inferredType));
    else
      setType(e, new ListType(Types.ANY));
  }

  @Override
  public void visit(ELNode.XML e) {
    super.visit(e);
    setType(e, Type.of(XmlNode.class));
  }

  @Override
  public void visit(ELNode.DEFINE e) {
    assert e.symbol != null;

    if (e.inferredType == Types.UNKNOWN) {
      if (e.type != null) {
        // Resolve annotated type.
        Type type = resolveAnnotatedType(e.type);
        setType(e.type, type);
        setType(e, type);
      } else if (e.expr instanceof ELNode.CLASSDEF) {
        // Preset ELite class type so class member can see the type.
        setType(e, new ELiteClassObjectType(e.symbol.clazz));
      }
    }

    // Infer initialize expression.
    scan(e.expr);

    // Check if the initializer type is compatible to annotated type.
    if (e.expr != null && e.expr.inferredType != Types.UNKNOWN &&
        e.type != null) {
      if (!compatible(e.inferredType, e.expr.inferredType))
        reportError(e.pos, _T(IR_TYPE_MISMATCH, e.inferredType,
                              e.expr.inferredType));
    }

    if (e.inferredType == Types.UNKNOWN && e.symbol != null) {
      if (e.symbol.scope.isClassScope()) {
        // Inference class member type.
       if (e.expr != null &&
           (e.symbol.func != null || e.symbol.clazz != null ||
            (e.symbol.isStatic() && e.symbol.isFinal())))
          setType(e, e.expr.inferredType);
       else
         setType(e, Types.ANY);
      } else {
        // Inference variable type from initializer for immutable variable.
        if (e.expr != null && e.symbol.immutable)
          setType(e, e.expr.inferredType);
      }
    }
  }

  @Override
  public void visit(ELNode.IDENT e) {
    if (e.inferredType != Types.UNKNOWN)
      return;

    if (e.symbol != null) {
      if (e.symbol.def.inferredType != Types.UNKNOWN) {
        setType(e, e.symbol.def.inferredType);
        return;
      }

      if (e.id.equals("this")) {
        if (e.symbol.scope.isClassScope()) {
          IRClass irc = e.symbol.scope.enclosingClass();
          setType(e, new ELiteClassType(irc));
          return;
        }
      }
    }

    Class<?> javaClass = resolveJavaClass(e);
    if (javaClass != null) {
      setType(e, new ClassObjectType(javaClass));
      return;
    }
  }

  @Override
  public void visit(ELNode.CLASSDEF e) {
    super.visit(e);
    if (e.symbol.clazz.clinit_proc != null)
      scan(e.symbol.clazz.clinit_proc);
    scan(e.symbol.clazz.init_proc);
    setType(e, new ELiteClassObjectType(e.symbol.clazz));
  }

  @Override
  public void visit(ELNode.LAMBDA e) {
    Type[] params = new Type[e.vars.length];
    for (int i = 0; i < e.vars.length; i++) {
      params[i] = resolveAnnotatedType(e.vars[i].type);
      if (e.varargs && i == e.vars.length - 1)
        params[i] = new ArrayType(params[i], 1);
      setType(e.vars[i], params[i]);
      scan(e.vars[i].expr);
    }

    Type ret = resolveAnnotatedType(e.rtype);
    if (ret != Types.UNKNOWN)
      setType(e, new FunctionType(ret, params));
    scan(e.body);

    if (e.body != null && ret == Types.UNKNOWN) {
      // Unannotated: merge the body type with every RETURN expression.
      List<ELNode.RETURN> returns = new ArrayList<>();
      collectReturns(e.body, returns);
      if (returns.isEmpty()) {
        ret = valueTypeOf(e.body);
        setType(e, new FunctionType(ret, params));
      }
    }

    if (e.inferredType == Types.UNKNOWN)
      setType(e, new FunctionType(ret, params));
  }

  @Override
  public void visit(ELNode.ACCESS e) {
    super.visit(e);

    if (e.inferredType != Types.UNKNOWN)
      return;

    if (e.right.inferredType != Types.UNKNOWN &&
        e.index instanceof ELNode.STRINGVAL) {
      String name = ((ELNode.STRINGVAL)e.index).value;

      if (e.right instanceof ELNode.IDENT var &&
          (var.id.equals("this") || var.id.equals("super"))) {
        if (var.id.equals("this") && var.symbol != null &&
            var.symbol.scope.isLambdaScope()) {
          // "this" may be the expando method receiver parameter.
          ELNode.LAMBDA lambda = (ELNode.LAMBDA)var.symbol.scope.frontier;
          if (expandoClass != null && var.symbol == lambda.vars[0].symbol) {
            if (inferJavaClassMember(e, expandoClass, name, false))
              return;
          }
        } else if (e.symbol != null) {
          IRClass irc = e.symbol.scope.enclosingClass();
          if (var.id.equals("super")) {
            if (irc.base instanceof IRClass) {
              if (inferELiteClassMember(e, (IRClass)irc.base, name, false))
                return;
            } else {
              if (inferJavaClassMember(e, (Class<?>)irc.base, name, false))
                return;
            }
          } else {
            if (inferELiteClassMember(e, irc, name, false))
              return;
          }
        }
      }

      Type receiverType = e.right.inferredType;
      if (receiverType instanceof ELiteClassObjectType ct) {
        if (inferELiteClassMember(e, ct.classObject(), name, true))
          return;
      } else if (receiverType instanceof ELiteClassType ct) {
        if (inferELiteClassMember(e, ct.irc(), name, false))
          return;
      } else if (receiverType instanceof ClassObjectType ct) {
        if (inferJavaClassMember(e, ct.classObject(), name, true))
          return;
      } else {
        Class<?> javaClass = receiverType.javaClass();
        if (javaClass != null && inferJavaClassMember(e, javaClass, name, false))
          return;
      }
    }

    IRClass irc = resolveIRClass(e);
    if (irc != null) {
      setType(e, new ELiteClassObjectType(irc));
      return;
    }

    Class<?> javaClass = resolveJavaClass(e);
    if (javaClass != null) {
      setType(e, new ClassObjectType(javaClass));
      return;
    }
  }

  private boolean inferELiteClassMember(ELNode e, IRClass irc, String name,
                                        boolean isStatic) {
    Class<?> baseClass;

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
        if (def.symbol.isPublic()) {
          if (def.id.equals(name)) {
            setType(e, def.inferredType);
          } else if (def.inferredType instanceof FunctionType ft) {
            setType(e, ft.returnType());
          }
        }
        return true;
      }

      if (irc.base instanceof IRClass)
        irc = (IRClass)irc.base;
      else {
        baseClass = (Class<?>)irc.base;
        break;
      }
    }

    return inferJavaClassMember(e, baseClass, name, isStatic);
  }

  private boolean inferJavaClassMember(ELNode e, Class<?> javaClass, String name,
                                       boolean isStatic) {
    if (isStatic) {
      try {
        Field f = javaClass.getField(name);
        int mods = f.getModifiers();
        if (Modifier.isPublic(mods) && Modifier.isStatic(mods)) {
          setType(e, new FieldType(f));
          return true;
        }
      } catch (NoSuchFieldException ex) {
        // fallthrough
      }
    } else {
      try {
        Method m = BeanUtils.getReadMethod(javaClass, name);
        if (m != null) {
          setType(e, new MethodType(m, false));
          return true;
        }
      } catch (IntrospectionException ex) {
        // fallthrough
      }

      try {
        Field f = javaClass.getField(name);
        int mods = f.getModifiers();
        if (Modifier.isPublic(mods) && !Modifier.isStatic(mods)) {
          setType(e, new FieldType(f));
          return true;
        }
      } catch (NoSuchFieldException ex) {
        // fallthrough
      }
    }

    return false;
  }

  @Override
  public void visit(ELNode.APPLY e) {
    // Handle special function.
    if (e.right instanceof ELNode.IDENT ident &&
        ident.id.equals("*attach*")) {
      Class<?> javaClass = loadJavaClass(e.args[0]);
      String name = ((ELNode.STRINGVAL)e.args[1]).value;
      ELNode.LAMBDA lambda = (ELNode.LAMBDA)e.args[2];

      expandoMap.put(javaClass, name, lambda);
      expandoClass =  javaClass;
      scan(lambda);
      expandoClass = null;

      setType(e, new ClassObjectType(Void.TYPE));
      return;
    }

    super.visit(e);

    if (e.right instanceof ELNode.IDENT) {
      if (inferCall(e))
        return;
    }

    if (e.right instanceof ELNode.ACCESS) {
      if (inferInvoke(e))
        return;
    }
  }

  private Class<?>[] getArgTypes(ELNode[] args) {
    Class<?>[] argTypes = new Class<?>[args.length];
    for (int i = 0; i < args.length; i++)
      argTypes[i] = args[i].inferredType.javaClass();
    return argTypes;
  }

  private boolean inferCall(ELNode.APPLY e) {
    ELNode.IDENT target = (ELNode.IDENT)e.right;

    if (target.id.equals("super"))
      return true;

    if (target.inferredType instanceof FunctionType ft) {
      setType(e, ft.returnType());
      return true;
    }

    if (target.inferredType instanceof ELiteClassObjectType ct) {
      setType(e, new ELiteClassType(ct.classObject()));
      return true;
    }

    if (inferThisCall(e, target.id, false))
      return true;

    if (expandoClass != null && inferExpandoCall(e, target.id, e.args))
      return true;

    Class<?>[] argTypes = getArgTypes(e.args);
    MethodResolver resolver = MethodResolver.getInstance(elctx);
    MethodClosure mc = resolver.resolveGlobalMethod(target.id);
    if (mc != null) {
      Method m = mc.getJavaMethod(null, argTypes);
      if (m != null) {
        setType(e, new MethodType(m, true));
        return true;
      }
    }

    Class<?> javaClass = resolveJavaClass(target);
    if (javaClass != null) {
      setType(target, new ClassObjectType(javaClass));
      return inferJavaClassCall(e, javaClass, argTypes);
    }

    return false;
  }

  private boolean inferThisCall(ELNode.APPLY e, String name, boolean isSuper) {
    IRClass clazz = currentScope.enclosingClass();
    if (clazz == null)
      return false;

    if (!isSuper || clazz.base instanceof IRClass) {
      if (isSuper)
        clazz = (IRClass)clazz.base;
      do {
        Optional<ELNode.DEFINE> var =
          Stream.concat(Arrays.stream(clazz.node.cvars),
                        Arrays.stream(clazz.node.ivars))
            .filter(x -> x.id.equals(name) &&
                         x.expr instanceof ELNode.LAMBDA)
            .findFirst();
        if (var.isPresent())
          return true;

        if (clazz.base instanceof IRClass) {
          clazz = (IRClass)clazz.base;
        } else {
          break;
        }
      } while (true);
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
        reportError(e.pos, _T(EL_STATIC_CONTEXT_ACCESS_INSTANCE_MEMBER, name));
        return true;
      }
    }

    if (mc == null)
      return false;
    Method method = mc.getJavaMethod(baseClass, getArgTypes(e.args));
    if (method == null)
      return false;
    setType(e, new MethodType(method, isStatic));
    return true;
  }

  private boolean inferExpandoCall(ELNode.APPLY e, String name, ELNode[] args) {
    ELNode.LAMBDA expando = expandoMap.find(expandoClass, name);
    if (expando != null) {
      setType(e, Types.ANY);
      return true;
    }

    MethodResolver resolver = MethodResolver.getInstance(elctx);
    boolean isStatic = true;

    MethodClosure mc = resolver.resolveStaticMethod(expandoClass, name);
    if (mc == null) {
      mc = resolver.resolveMethod(expandoClass, name);
      isStatic = false;
    }

    if (mc == null)
      return false;

    Class<?>[] argTypes = getArgTypes(args);
    Method method = mc.getJavaMethod(isStatic ? null : expandoClass, argTypes);
    if (method == null)
      return false;

    setType(e, new MethodType(method, isStatic));
    return true;
  }

  private boolean inferJavaClassCall(ELNode.APPLY e, Class<?> javaClass,
                                     Class<?>[] argTypes) {
    MethodResolver resolver = MethodResolver.getInstance(elctx);

    if (argTypes.length == 1 && Type.of(javaClass) instanceof PrimitiveType) {
      // Pass through to IRBuilder for coercion.
      setType(e, Type.of(javaClass));
      return true;
    }

    if (argTypes.length != 0) {
      MethodClosure mc = resolver.resolveStaticMethod(javaClass, "valueOf");
      if (mc != null) {
        Method m = mc.getJavaMethod(null, argTypes);
        if (m != null && m.getReturnType() == javaClass) {
          setType(e, new MethodType(m, true));
          return true;
        }
      }
    }

    Constructor<?> ctor = ELEngine.resolveConstructor(javaClass, argTypes);
    if (ctor != null) {
      setType(e, new ConstructorType(ctor));
      return true;
    }

    return false;
  }

  private boolean inferInvoke(ELNode.APPLY e) {
    ELNode.ACCESS acc = (ELNode.ACCESS)e.right;

    if (acc.index instanceof ELNode.STRINGVAL) {
      ELNode target = acc.right;
      String name = ((ELNode.STRINGVAL)acc.index).value;

      if (target instanceof ELNode.IDENT var &&
          (var.id.equals("this") || var.id.equals("super"))) {
        if (var.id.equals("this") && var.symbol != null &&
            var.symbol.scope.isLambdaScope()) {
          // "this" may be the expando method receiver parameter.
          ELNode.LAMBDA lambda = (ELNode.LAMBDA)var.symbol.scope.frontier;
          if (expandoClass != null && var.symbol == lambda.vars[0].symbol)
            return inferExpandoCall(e, name, e.args);
        } else {
          inferThisCall(e, name, var.id.equals("super"));
        }
        return true;
      }

      if (target.inferredType instanceof ELiteClassType ct &&
          !((name.equals("toString")  && e.args.length == 0) ||
            (name.equals("equals")    && e.args.length == 1) ||
            (name.equals("hashCode")  && e.args.length == 0) ||
            (name.equals("compareTo") && e.args.length == 1))) {
        Object cls = ct.irc();
        for (; cls instanceof IRClass irc; cls = irc.base) {
          for (ELNode.DEFINE def : irc.node.ivars) {
            if (def.id.equals(name))
              return true;
          }
          for (ELNode.DEFINE def : irc.node.cvars) {
            if (def.id.equals(name))
              return true;
          }
        }

        Class<?> baseClass = (Class<?>)cls;
        MethodResolver resolver = MethodResolver.getInstance(elctx);
        boolean isStatic = true;

        MethodClosure mc = resolver.resolveStaticMethod(baseClass, name);
        if (mc == null) {
          mc = resolver.resolveMethod(baseClass, name);
          isStatic = false;
        }
        if (mc != null) {
          Method m = mc.getJavaMethod(baseClass, getArgTypes(e.args));
          if (m != null)
            setType(e, new MethodType(m, isStatic));
        }
        return true;
      }

      Class<?>[] argTypes = getArgTypes(e.args);
      Method method = resolveStaticMethod(target, name, argTypes);
      if (method != null) {
        setType(e, new MethodType(method, true));
        return true;
      }

      Class<?> javaClass = target.inferredType.javaClass();
      if (javaClass != null) {
        method = resolveInstanceMethod(target, name, argTypes);
        if (method != null) {
          setType(e, new MethodType(method, false));
          return true;
        }
      }

      javaClass = resolveJavaClass(acc);
      if (javaClass != null)
        return inferJavaClassCall(e, javaClass, argTypes);
    }

    return false;
  }

  private Method resolveStaticMethod(ELNode target, String name,
                                     Class<?>[] argTypes) {
    Class<?> javaClass = resolveJavaClass(target);
    if (javaClass == null)
      return null;

    MethodClosure mc = MethodResolver.getInstance(elctx)
      .resolveStaticMethod(javaClass, name);
    if (mc == null)
      return null;

    Method method = mc.getJavaMethod(null, argTypes);
    if (method == null)
      return null;

    Expando expando = method.getAnnotation(Expando.class);
    if (expando != null &&
        !Arrays.asList(expando.scope()).contains(ExpandoScope.GLOBAL))
      return null;

    return method;
  }

  private Method resolveInstanceMethod(ELNode target, String name,
                                       Class<?>[] argTypes) {
    if (target.inferredType == Types.UNKNOWN)
      return null;
    Class<?> javaClass = target.inferredType.javaClass();
    if (javaClass == null)
      return null;

    MethodClosure mc = MethodResolver.getInstance(elctx)
      .resolveMethod(javaClass, name);
    if (mc == null)
      return null;
    return mc.getJavaMethod(null, argTypes);
  }

  @Override
  public void visit(ELNode.XFORM e) {
    super.visit(e);
  }

  @Override
  public void visit(ELNode.NEW e) {
    super.visit(e);

    if (e.inferredType != Types.UNKNOWN)
      return;

    if (e.base.inferredType instanceof ELiteClassObjectType ct) {
      setType(e, new ELiteClassType(ct.classObject()));
      return;
    }

    Class<?> javaClass = resolveJavaClass(e.base);
    if (javaClass != null) {
      Class<?>[] argTypes = getArgTypes(e.args);
      Constructor<?> ctor = ELEngine.resolveConstructor(javaClass, argTypes);
      if (ctor != null) {
        setType(e, new ConstructorType(ctor));
        return;
      }

      setType(e, Type.of(javaClass));
      return;
    }
  }

  @Override
  public void visit(ELNode.EXPR e) {
    super.visit(e);
    if (e.right.inferredType != Types.UNKNOWN)
      setType(e, e.right.inferredType);
  }

  @Override
  public void visit(ELNode.COMPOUND e) {
    super.visit(e);
    if (e.exps.length == 0)
      setType(e, Types.ANY);
    else
      setType(e, e.exps[e.exps.length - 1].inferredType);
  }

  @Override
  public void visit(ELNode.EQ e) {
    super.visit(e);
    setType(e, Type.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.NE e) {
    super.visit(e);
    setType(e, Type.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.IDEQ e) {
    super.visit(e);
    setType(e, Type.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.IDNE e) {
    super.visit(e);
    setType(e, Type.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.LT e) {
    super.visit(e);
    setType(e, Type.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.LE e) {
    super.visit(e);
    setType(e, Type.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.GT e) {
    super.visit(e);
    setType(e, Type.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.GE e) {
    super.visit(e);
    setType(e, Type.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.CMP e) {
    super.visit(e);
    setType(e, Type.of(Integer.class));
  }

  @Override
  public void visit(ELNode.OR e) {
    super.visit(e);
    setType(e, Type.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.AND e) {
    super.visit(e);
    setType(e, Type.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.NOT e) {
    super.visit(e);
    setType(e, Type.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.EMPTY e) {
    super.visit(e);
    setType(e, Type.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.INSTANCEOF e) {
    super.visit(e);
    setType(e, Type.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.IN e) {
    super.visit(e);
    setType(e, Type.of(Boolean.class));
  }

  @Override
  public void visit(ELNode.CAT e) {
    super.visit(e);
  }

  @Override
  public void visit(ELNode.ASSIGN e) {
    super.visit(e);

    if (!compatible(e.left.inferredType, e.right.inferredType)) {
      reportError(e.pos, _T(IR_TYPE_MISMATCH, e.left.inferredType,
                            e.right.inferredType));
      setType(e, Types.ERROR);
      return;
    }

    setType(e, e.right.inferredType);
  }

  @Override
  public void visit(ELNode.ASSIGNOP e) {
    // A compound assignment does not change the variable type.
    super.visit(e);
  }

  @Override
  public void visit(ELNode.RETURN e) {
    super.visit(e);
    setType(e, e.right.inferredType);
  }

  @Override
  public void visit(ELNode.FOREACH e) {
    if (e.index != null)
      setType(e.index, Types.INT);
    scan(e.range);
    if (e.range.inferredType instanceof ListType t)
      setType(e.var, t.element());
    scan(e.body);
    setType(e, Types.ANY);
  }

  @Override
  public void visit(ELNode.WHILE e) {
    super.visit(e);
    setType(e, Types.ANY);
  }

  @Override
  public void visit(ELNode.TRY e) {
    super.visit(e);
    setType(e, e.body.inferredType);
  }

  @Override
  public void visit(ELNode.THROW e) {
    super.visit(e);
    setType(e, Types.ANY);
  }

  @Override
  public void visit(ELNode.SYNCHRONIZED e) {
    super.visit(e);
    setType(e, e.body.inferredType);
  }

  private void visitArithmetic(ELNode.Binary e) {
    scan(e.left);
    scan(e.right);

    Class<?> lt = e.left.inferredType.javaClass();
    Class<?> rt = e.right.inferredType.javaClass();
    if (lt == null || rt == null)
      return;

    lt = TypeCoercion.getUnboxedType(lt);
    rt = TypeCoercion.getUnboxedType(rt);
    if (!lt.isPrimitive() || !rt.isPrimitive() ||
        lt == Void.TYPE || lt == Boolean.TYPE ||
        rt == Void.TYPE || rt == Boolean.TYPE)
      return;

    if (lt == Double.TYPE || rt == Double.TYPE) {
      setType(e, Types.DOUBLE);
    } if (lt == Float.TYPE || rt == Float.TYPE) {
      setType(e, Types.FLOAT);
    } if (lt == Long.TYPE || rt == Long.TYPE) {
      setType(e, Types.LONG);
    } else {
      setType(e, Types.INT);
    }
  }

  @Override public void visit(ELNode.ADD e) { visitArithmetic(e); }
  @Override public void visit(ELNode.SUB e) { visitArithmetic(e); }
  @Override public void visit(ELNode.MUL e) { visitArithmetic(e); }
  @Override public void visit(ELNode.REM e) { visitArithmetic(e); }

  @Override
  public void visit(ELNode.DIV e) {
    super.visit(e);
  }

  @Override
  public void visit(ELNode.POW e) {
    super.visit(e);
  }

  @Override
  public void visit(ELNode.SHL e) {
    inferShift(e);
  }

  @Override public void visit(ELNode.SHR e) {
    inferShift(e);
  }

  @Override public void visit(ELNode.USHR e) {
    inferShift(e);
  }

  private void inferShift(ELNode.Binary e) {
    scan(e.left);
    scan(e.right);

    if (e.left.inferredType == Types.BIGINT) {
      setType(e, Types.BIGINT);
    } else if (e.left.inferredType == Types.LONG ||
               e.left.inferredType == Types.DOUBLE ||
               e.left.inferredType == Types.FLOAT) {
      setType(e, Types.LONG);
    } else if (e.left.inferredType == Types.INT ||
               e.left.inferredType == Types.BYTE ||
               e.left.inferredType == Types.SHORT ||
               e.left.inferredType == Types.CHAR) {
      setType(e, Types.INT);
    }
  }

  @Override
  public void visit(ELNode.BITAND e) {
    inferBitwise(e);
  }

  @Override
  public void visit(ELNode.BITOR e) {
    inferBitwise(e);
  }

  @Override
  public void visit(ELNode.XOR e) {
    inferBitwise(e);
  }

  private void inferBitwise(ELNode.Binary e) {
    scan(e.left);
    scan(e.right);

    if (e.left.inferredType instanceof PrimitiveType) {
      if (e.left.inferredType == Types.BIGINT ||
          e.right.inferredType == Types.BIGINT) {
        setType(e, Types.BIGINT);
      } else if (e.left.inferredType == Types.BOOLEAN ||
                 e.right.inferredType == Types.BOOLEAN) {
        if (e.left.inferredType != Types.BOOLEAN) {
          reportError(e.pos,
                      _T(JSPRT_COERCE_ERROR, e.left.inferredType, "boolean"));
          setType(e, Types.ERROR);
          return;
        }
        if (e.right.inferredType != Types.BOOLEAN) {
          reportError(e.pos,
                      _T(JSPRT_COERCE_ERROR, e.right.inferredType, "boolean"));
          setType(e, Types.ERROR);
          return;
        }
        setType(e, Types.BOOLEAN);
      } else if (e.left.inferredType == Types.LONG ||
                 e.left.inferredType == Types.FLOAT ||
                 e.left.inferredType == Types.DOUBLE ||
                 e.right.inferredType == Types.LONG ||
                 e.right.inferredType == Types.FLOAT ||
                 e.right.inferredType == Types.DOUBLE) {
        setType(e, Types.LONG);
      } else if (e.left.inferredType == Types.INT ||
                 e.left.inferredType == Types.BYTE ||
                 e.left.inferredType == Types.SHORT ||
                 e.left.inferredType == Types.CHAR ||
                 e.right.inferredType == Types.INT ||
                 e.right.inferredType == Types.BYTE ||
                 e.right.inferredType == Types.SHORT ||
                 e.right.inferredType == Types.CHAR) {
        setType(e, Types.INT);
      }
    }
  }

  @Override
  public void visit(ELNode.NEG e) {
    super.visit(e);
  }

  @Override
  public void visit(ELNode.POS e) {
    super.visit(e);
  }

  @Override
  public void visit(ELNode.BITNOT e) {
    super.visit(e);
  }

  @Override
  public void visit(ELNode.INC e) {
    inferIncDec(e);
  }

  @Override
  public void visit(ELNode.DEC e) {
    inferIncDec(e);
  }

  private void inferIncDec(ELNode.Unary e) {
    scan(e.right);

    Class<?> type = e.right.inferredType.javaClass();
    if (type != null) {
      type = TypeCoercion.getUnboxedType(type);
      if (type == Double.TYPE || type == Float.TYPE || type == Long.TYPE) {
        setType(e, e.right.inferredType);
      } else if (type == Integer.TYPE || type == Byte.TYPE ||
                 type == Short.TYPE || type == Character.TYPE) {
        setType(e, Types.INT);
      }
    }
  }

  @Override
  public void visit(ELNode.PREFIX e) {
    super.visit(e);
  }

  @Override
  public void visit(ELNode.INFIX e) {
    super.visit(e);
  }

  @Override
  public void visit(ELNode.FOR e) {
    super.visit(e);
  }

  @Override
  public void visit(ELNode.MATCH e) {
    super.visit(e);
  }

  @Override
  public void visit(ELNode.CASE e) {
    scan(e.bodies);
  }

  @Override
  public void visit(ELNode.LET e) {
    scan(e.right);
  }

  @Override
  public void visit(ELNode.COND e) {
    super.visit(e);
  }

  @Override
  public void visit(ELNode.COALESCE e) {
    super.visit(e);
  }

  @Override
  public void visitNode(ELNode e) {
    // Anything not covered above stays untyped (reads as Any).
  }
  
  //--------------------------------------------------------------------------//

  void reportError(int pos, String message) {
    errors.add(new ParseError(pos, message, null));
    if (errors.size() >= 20) {
      failIfErrors();
    }
  }

  void failIfErrors() throws ParseException {
    if (!errors.isEmpty()) {
      throw new ParseException(filename, errors);
    }
  }

  void setType(ELNode node, Type type) {
    if (type == Types.UNKNOWN)
      return;

    if (type instanceof MemberType) {
      if (node.memberType == null || !node.memberType.equals(type)) {
        node.inferredType = ((MemberType)type).getResultType();
        node.memberType = type;
        changed = true;
      }
    } else {
      if (node.inferredType == Types.UNKNOWN || !node.inferredType.equals(type)) {
        node.inferredType = type;
        node.memberType = null;
        changed = true;
      }
    }
  }

  /**
   * The Java class a value of this node would evaluate to at runtime — the
   * receiver for further member access. A Java constructor's value is its
   * declaring class; a Java field's value is the field's type. Anything else
   * keeps its own type (so a JavaMethod reference stays a JavaMethodType).
   */
  Type valueTypeOf(ELNode node) {
    return node == null ? null : node.inferredType;
  }

  /**
   * Merge two types: errors absorb, Any is the identity (so [1,2,3] builds
   * List[Int] from NIL's List[Any]), equal types merge to themselves,
   * numeric pairs normalize, anything else degrades to Any.
   */
  Type merge(Type a, Type b) {
    if (a == Types.UNKNOWN || b == Types.UNKNOWN)
      return Types.UNKNOWN;
    if (a == Types.ERROR || b == Types.ERROR)
      return Types.ERROR;
    if (a == Types.ANY || b == Types.ANY)
      return Types.ANY;
    if (a.equals(b))
      return a;
    if (isNumeric(a) && isNumeric(b))
      return normalize(a, b);
    return Types.ANY;
  }

  /** Widening rank per TypeCoercion's numeric distance order. */
  private static final List<String> NUMERIC_ORDER = List.of(
    "Byte", "Short", "Char", "Int", "Long", "Float", "Double",
    "Decimal", "Rational", "BigInt", "BigDecimal");

  private int rank(PrimitiveType pt) {
    int i = NUMERIC_ORDER.indexOf(pt.toString());
    return i >= 0 ? i : -1;
  }

  boolean isIntFamily(Type t) {
    if (t == Types.ANY_INT) return true;
    if (t instanceof PrimitiveType pt && pt.javaClass() != null) {
      return pt.javaClass() == Byte.class || pt.javaClass() == Short.class ||
             pt.javaClass() == Character.class ||
             pt.javaClass() == Integer.class || pt.javaClass() == Long.class ||
             pt.javaClass() == java.math.BigInteger.class;
    }
    return false;
  }

  boolean isFloatFamily(Type t) {
    if (t == Types.ANY_FLOAT) return true;
    if (t instanceof PrimitiveType pt && pt.javaClass() != null) {
      return pt.javaClass() == Float.class || pt.javaClass() == Double.class ||
             pt.javaClass() == BigDecimal.class;
    }
    return false;
  }

  /** Normalizes two concrete numeric types to the wider one per the
   *  TypeCoercion distance order; ANY_INT/ANY_FLOAT follow family rules. */
  private Type normalize(Type a, Type b) {
    if (a == Types.ANY_INT && isIntFamily(b))
      return Types.ANY_INT;
    if (b == Types.ANY_INT && isIntFamily(a))
      return Types.ANY_INT;
    if (a == Types.ANY_FLOAT || b == Types.ANY_FLOAT)
      return Types.ANY_FLOAT;
    if (isFloatFamily(a) && (isFloatFamily(b) || isIntFamily(b)))
      return a;
    if (isFloatFamily(b) && (isFloatFamily(a) || isIntFamily(a)))
      return b;
    int ra = rank((PrimitiveType)a);
    int rb = rank((PrimitiveType)b);
    if (ra < 0 || rb < 0)
      return Types.ANY;
    return ra >= rb ? a : b;
  }

  private boolean isNumeric(Type t) {
    if (t == Types.ANY_INT || t == Types.ANY_FLOAT)
      return true;
    if (t instanceof PrimitiveType pt && pt.javaClass() != null) {
      return Number.class.isAssignableFrom(pt.javaClass()) ||
             pt.javaClass() == Character.class;
    }
    return false;
  }

  /**
   * Whether a value of type actual may be stored into a variable of the
   * annotated type expected. Cannot judge Any / promotion markers /
   * errors; both-numeric is allowed here and refined (widening check)
   * in the numeric task.
   */
  boolean compatible(Type expected, Type actual) {
    if (expected == Types.UNKNOWN | expected == Types.ANY ||
        expected == Types.ERROR) {
      return true;   // an Any/Error-typed slot accepts anything; never worsen
    }

    if (actual == Types.UNKNOWN || actual == Types.ANY || actual == Types.ERROR ||
        actual == Types.ANY_INT || actual == Types.ANY_FLOAT) {
      return true;   // cannot judge
    }

    if (expected.equals(actual)) {
      return true;
    }

    // Numeric assignment is allowed only when the value widens to the
    // annotated type (e.g. Int -> Long ok, Double -> Int rejected).
    if (isNumeric(expected) && isNumeric(actual)) {
      return normalize(actual, expected).equals(expected);
    }

    if (expected instanceof ClassType lt && actual instanceof ClassType rt) {
      return lt.javaClass().isAssignableFrom(rt.javaClass());
    }

    if (expected instanceof FunctionType lt &&
        actual instanceof FunctionType rt) {
      if (!compatible(lt.returnType(), rt.returnType()))
        return false;
      if (lt.params().length != rt.params().length)
        return false;
      for (int i = 0; i < lt.params().length; i++)
        if (!lt.params()[i].equals(rt.params()[i]))
          return false;
      return true;
    }

    if (expected instanceof TupleType lt && actual instanceof TupleType rt) {
      if (lt.elems().length != rt.elems().length)
        return false;
      for (int i = 0; i < lt.elems().length; i++) {
        if (!compatible(lt.elems()[i], rt.elems()[i]))
          return false;
      }
      return true;
    }

    if (expected instanceof ListType lt && actual instanceof ListType rt) {
      return compatible(lt.element(), rt.element());
    }

    return false;
  }

  /** Collects every RETURN node under the given subtree. */
  void collectReturns(ELNode node, List<ELNode.RETURN> out) {
    if (node == null)
      return;
    if (node instanceof ELNode.RETURN r) {
      out.add(r);
      return;
    }
    if (node instanceof ELNode.LAMBDA) {
      return;   // do not descend into nested lambdas
    }

    new DefaultVisitor() {
      @Override
      public void visit(ELNode.RETURN r) {
        out.add(r);
      }

      @Override
      public void visit(ELNode.LAMBDA l) {
        // skip nested lambdas
      }
    }.scan(node);
  }

  // ---- Java class loading / annotation resolution ----

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

  /**
   * Copies of IRBuilder's private class-loading helpers (IRBuilder.java:1283
   * loadJavaClass / resolveJavaClass / loadClassAtCompileTime). Loads are
   * required because Java classes must be compile-time visible; a failed
   * load is an error: report it and return null (the caller types the node
   * ErrorType). TODO: refactor into a shared helper later.
   */
  Class<?> loadJavaClass(ELNode base) {
    return resolveJavaClass(base, true);
  }

  Class<?> resolveJavaClass(ELNode node) {
    return resolveJavaClass(node, false);
  }

  private Class<?> resolveJavaClass(ELNode node, boolean load) {
    ClassResolver resolver = ClassResolver.getInstance(elctx);

    if (node instanceof ELNode.IDENT var && var.symbol != null &&
        var.symbol.def.expr instanceof ELNode.CLASS c) {
      try {
        return resolver.resolveClass(c.getClassName());
      } catch (ClassNotFoundException e) {
        reportError(node.pos, _T(EL_CLASS_NOT_FOUND, c.getClassName()));
        return null;
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
        } catch (ClassNotFoundException ex) {
          if (load)
            reportError(node.pos, _T(EL_CLASS_NOT_FOUND, buf.toString()));
          return null;
        }
      }
    }

    if (load)
      reportError(node.pos, _T(EL_CLASS_NOT_FOUND, buf.toString()));
    return null;
  }

  Type resolveAnnotatedType(ELNode typeNode) {
    if (typeNode == null)
      return Types.UNKNOWN;

    if (typeNode instanceof ELNode.IDENT id) {
      SymbolTable.Symbol sym = typeNode.symbol;
      if (sym != null && sym.clazz != null) {
        return new ELiteClassType(sym.clazz);
      }

      if (id.id.equals("AnyInt"))
        return Types.ANY_INT;
      if (id.id.equals("AnyFloat"))
        return Types.ANY_FLOAT;
    }

    if (typeNode instanceof ELNode.IDENT || typeNode instanceof ELNode.ACCESS) {
      // Java class — must be compile-time visible; failure is an error.
      Class<?> javaClass = loadJavaClass(typeNode);
      if (javaClass == null)
        return Types.ERROR;
      return Type.of(javaClass);
    }

    if (typeNode instanceof ELNode.CONS c) {
      return new ListType(resolveAnnotatedType(c.head));
    }

    if (typeNode instanceof ELNode.TUPLE t) {
      Type[] elementTypes = new Type[t.elems.length];
      for (int i = 0; i < t.elems.length; i++)
        elementTypes[i] = resolveAnnotatedType(t.elems[i]);
      return new TupleType(elementTypes);
    }

    if (typeNode instanceof ELNode.MAP m) {
      return new MapType(resolveAnnotatedType(m.keys[0]),
                         resolveAnnotatedType(m.values[0]));
    }

    if (typeNode instanceof ELNode.ARRAY a) {
      return new ArrayType(resolveAnnotatedType(a.type), a.dims.length);
    }

    if (typeNode instanceof ELNode.APPLY a) {
      Type[] ptypes = new Type[a.args.length];
      for (int i = 0; i < a.args.length; i++)
        ptypes[i] = resolveAnnotatedType(a.args[i]);
      Type rtype = resolveAnnotatedType(a.right);
      return new FunctionType(rtype, ptypes);
    }

    throw new AssertionError();
  }
}
