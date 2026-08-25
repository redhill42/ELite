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

import elite.lang.Closure;
import elite.lang.Decimal;
import elite.lang.Rational;
import elite.lang.Symbol;
import org.elite.eval.ELEngine;
import org.elite.eval.EvaluationContext;
import org.elite.eval.EvaluationException;
import org.elite.eval.MethodDelegate;
import org.elite.eval.MethodResolvable;
import org.elite.eval.SystemScope;
import org.elite.eval.TypeCoercion;
import org.elite.eval.closure.MethodClosure;
import org.elite.eval.closure.TargetMethodClosure;
import org.elite.eval.seq.Cons;
import org.elite.resolver.MethodResolver;
import org.elite.util.BeanUtils;
import javax.el.ELContext;
import javax.el.ELException;
import javax.el.MethodNotFoundException;
import javax.el.PropertyNotFoundException;
import javax.el.PropertyNotWritableException;
import java.beans.IntrospectionException;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.elite.ir.BootstrapCommon.*;
import static java.lang.invoke.MethodType.*;
import static java.lang.invoke.MethodHandles.*;
import static org.elite.eval.ELUtils.*;
import static org.elite.resources.Resources.*;

public final class DynamicBootstrap {

  private DynamicBootstrap() {}

  private static final MethodHandle MH_getValueDispatcher;
  private static final MethodHandle MH_getIndexedValueDispatcher;
  private static final MethodHandle MH_dynamicGetValue;
  private static final MethodHandle MH_setValueDispatcher;
  private static final MethodHandle MH_setIndexedValueDispatcher;
  private static final MethodHandle MH_dynamicSetValue;
  private static final MethodHandle MH_invokeDispatcher;
  private static final MethodHandle MH_callDispatcher;
  private static final MethodHandle MH_constructDispatcher;
  private static final MethodHandle MH_resolveConstructorDispatcher;

  private static final MethodHandle MH_mapGet;
  private static final MethodHandle MH_mapPut;
  private static final MethodHandle MH_listGet;
  private static final MethodHandle MH_listSet;

  private static final MethodHandle MH_callClosure;

  private static final MethodHandle MH_setValueTypesEqual;
  private static final MethodHandle MH_invokeTypesEqual;
  private static final MethodHandle MH_permuteNamedArgs;
  private static final MethodHandle MH_fillDefaultArgs;
  private static final MethodHandle MH_fillConstantDefaultArgs;
  private static final MethodHandle MH_buildVarArgs;
  private static final MethodHandle MH_buildDynamicVarArgs;
  private static final MethodHandle MH_coerceArgs;
  private static final MethodHandle MH_invokeMethodResolvable;
  private static final MethodHandle MH_getCallArgs;

  static {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();

      MH_getValueDispatcher = lookup.findStatic(
        DynamicBootstrap.class, "getValueDispatcher",
        methodType(Object.class, MethodHandles.Lookup.class,
                   MutableCallSite.class, String.class,
                   EvaluationContext.class, Object.class));

      MH_getIndexedValueDispatcher = lookup.findStatic(
        DynamicBootstrap.class, "getIndexedValueDispatcher",
        methodType(Object.class, MethodHandles.Lookup.class,
                   MutableCallSite.class, EvaluationContext.class,
                   Object.class, Object.class));

      MH_dynamicGetValue = lookup.findStatic(
        DynamicBootstrap.class, "dynamicGetValue",
        methodType(Object.class, ELContext.class, Object.class, Object.class));

      MH_setValueDispatcher = lookup.findStatic(
        DynamicBootstrap.class, "setValueDispatcher",
        methodType(void.class, MethodHandles.Lookup.class,
                   MutableCallSite.class, String.class, Object.class,
                   Object.class, EvaluationContext.class));

      MH_setIndexedValueDispatcher = lookup.findStatic(
        DynamicBootstrap.class, "setIndexedValueDispatcher",
        methodType(void.class, MethodHandles.Lookup.class,
                   MutableCallSite.class, Object.class, Object.class,
                   Object.class, EvaluationContext.class));

      MH_dynamicSetValue = lookup.findStatic(
        DynamicBootstrap.class, "setValue",
        methodType(void.class, ELContext.class, Object.class, Object.class,
                   Object.class));

      MH_invokeDispatcher = lookup.findStatic(
        DynamicBootstrap.class, "invokeDispatcher",
        methodType(Object.class, Lookup.class, MutableCallSite.class,
                   String.class, boolean.class, String[].class,
                   EvaluationContext.class, Object.class, Object[].class));

      MH_callDispatcher = lookup.findStatic(
        DynamicBootstrap.class, "callDispatcher",
        methodType(Object.class, Lookup.class, MutableCallSite.class,
                   String[].class, EvaluationContext.class, Object.class,
                   Object[].class));

      MH_constructDispatcher = lookup.findStatic(
        DynamicBootstrap.class, "constructDispatcher",
        methodType(Object.class, Lookup.class, MutableCallSite.class,
                   Class.class, EvaluationContext.class, Object[].class));

      MH_resolveConstructorDispatcher = lookup.findStatic(
        DynamicBootstrap.class, "resolveConstructorDispatcher",
        methodType(int.class, MutableCallSite.class, Class.class, Object[].class));

      MH_mapGet = lookup.findVirtual(
        Map.class, "get", methodType(Object.class, Object.class));
      MH_mapPut = lookup.findVirtual(
        Map.class, "put", methodType(Object.class, Object.class, Object.class));
      MH_listGet = lookup.findVirtual(
        List.class, "get", methodType(Object.class, int.class));
      MH_listSet = lookup.findVirtual(
        List.class, "set", methodType(Object.class, int.class, Object.class));

      MH_callClosure = lookup.findVirtual(
        Closure.class, "call",
        methodType(Object.class, ELContext.class, String[].class,
                   Object[].class));

      MH_permuteNamedArgs = lookup.findStatic(
        DynamicBootstrap.class, "permuteNamedArgs",
        methodType(Object[].class, Object[].class, int[].class,
                   boolean.class, Value[].class));

      MH_fillDefaultArgs = lookup.findStatic(
        DynamicBootstrap.class, "fillDefaultArgs",
        methodType(Object[].class, Object[].class, int.class, Value[].class));

      MH_fillConstantDefaultArgs = lookup.findStatic(
        DynamicBootstrap.class, "fillConstantDefaultArgs",
        methodType(Object[].class, Object[].class, Object[].class));

      MH_buildVarArgs = lookup.findStatic(
        DynamicBootstrap.class, "buildVarArgs",
        methodType(Object[].class, Object[].class, int.class));

      MH_buildDynamicVarArgs = lookup.findStatic(
        DynamicBootstrap.class, "buildDynamicVarArgs",
        methodType(Object[].class, Object[].class, String.class,
                   String[].class));

      MH_invokeMethodResolvable = lookup.findVirtual(
        MethodResolvable.class, "invoke",
        methodType(Object.class, ELContext.class, String.class,
                   Closure[].class));

      MH_getCallArgs = lookup.findStatic(
        ELEngine.class, "getCallArgs",
        methodType(Closure[].class, Object[].class));

      MH_coerceArgs = lookup.findStatic(
        DynamicBootstrap.class, "coerceArgs",
        methodType(Object[].class, Object[].class, Class[].class, int.class,
                   boolean.class));

      MH_setValueTypesEqual = lookup.findStatic(
        DynamicBootstrap.class, "setValueTypesEqual",
        methodType(boolean.class, Object.class, Object.class, Object.class,
                   Class.class, Class.class, Class.class));

      MH_invokeTypesEqual = lookup.findStatic(
        DynamicBootstrap.class, "invokeTypesEqual",
        methodType(boolean.class, Object.class, Object[].class, Class[].class));

    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static Class<?> typeOf(Object obj) {
    return obj == null ? null : obj.getClass();
  }

  private static Class<?>[] typeOf(Object obj, Object[] args) {
    Class<?>[] types = new Class<?>[args.length + 1];
    types[0] = typeOf(obj);
    for (int i = 0; i < args.length; i++)
      types[i + 1] = typeOf(args[i]);
    return types;
  }

  private static boolean invokeTypesEqual(Object obj, Object[] args,
                                          Class<?>[] types) {
    if (typeOf(obj) != types[0])
      return false;
    if (args.length != types.length - 1)
      return false;
    for (int i = 0; i < args.length; i++)
      if (typeOf(args[i]) != types[i + 1])
        return false;
    return true;
  }

  //=------------------------------------------------------------------------=//

  @SuppressWarnings("unused")
  public static CallSite getValueBootstrap(MethodHandles.Lookup lookup,
                                           String name,
                                           MethodType callSiteType) {
    MutableCallSite cs = new MutableCallSite(callSiteType);
    MethodHandle target = insertArguments(MH_getValueDispatcher, 0,
                                          lookup, cs, demangle(name));
    target = target.asType(callSiteType);
    cs.setTarget(target);
    return cs;
  }

  private static Object getValueDispatcher(MethodHandles.Lookup lookup,
                                           MutableCallSite cs, String name,
                                           EvaluationContext env, Object obj)
      throws Throwable
  {
    MethodHandle target = dispatchGetValue(lookup, env, obj, name);
    target = target.asType(cs.type());

    // Guard condition: obj != null && obj.getClass() == cachedClass
    Class<?> cachedClass = obj == null ? null : obj.getClass();
    MethodHandle guard = insertArguments(MH_classEquals, 1, cachedClass);

    // Adapt to (EvaluationContext, Object)boolean, ignore env.
    guard = dropArguments(guard, 0, EvaluationContext.class);

    // Create PIC guard.
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly invoke target for current call.
    return target.invokeExact(env, obj);
  }

  private static MethodHandle dispatchGetValue(MethodHandles.Lookup lookup,
                                               EvaluationContext env,
                                               Object obj, String name) {
    if (obj == null) {
      MethodHandle mh = constant(Object.class, null);
      return dropArguments(mh, 0, EvaluationContext.class, Object.class);
    }

    if (obj instanceof Map) {
      MethodHandle mh = insertArguments(MH_mapGet, 1, name);
      return dropArguments(mh, 0, EvaluationContext.class);
    }

    if (isELiteObject(obj)) {
      if (lookup.lookupClass() == obj.getClass()) {
        try {
          // First try declared fields to bypass access control.
          Field f = getField(obj.getClass(), name);
          if (f != null) {
            MethodHandle mh = lookup.unreflectGetter(f);
            return dropArguments(mh, 0, EvaluationContext.class);
          }
        } catch (IllegalAccessException ex) {
          // fallthrough
        }
      }

      // Try getter with prototype Object getXxx(EvaluationContext,
      // Object[].class)
      try {
        MethodHandle mh = getGetterMethod(lookup, obj.getClass(),
                                          "get" + capitalize(name));
        if (mh != null)
          return mh;
      } catch (NoSuchMethodException | IllegalAccessException ex) {
        // fallthrough
      }

      // Try getter with prototype Object isXxx(EvaluationContext,
      // Object[].class)
      try {
        MethodHandle mh = getGetterMethod(lookup, obj.getClass(),
                                          "is" + capitalize(name));
        if (mh != null)
          return mh;
      } catch (NoSuchMethodException | IllegalAccessException e) {
        // fallthrough
      }
    }

    if (obj instanceof Class<?> c) {
      // Get the static field with the given name.
      try {
        Field f = getStaticField(c, name);
        if (f != null) {
          MethodHandle mh = lookup.unreflectGetter(f);
          return dropArguments(mh, 0, EvaluationContext.class, Object.class);
        }
      } catch (IllegalAccessException ex) {
        // fallthrough
      }
    }

    // Invoke read method of a property.
    try {
      Method m = BeanUtils.getReadMethod(obj.getClass(), name);
      if (m != null) {
        MethodHandle mh = lookup.unreflect(m);
        return dropArguments(mh, 0, EvaluationContext.class);
      }
    } catch (IntrospectionException | IllegalAccessException ex) {
      // fallthrough
    }

    // Get the field with given name.
    try {
      Field f = getField(obj.getClass(), name);
      if (f != null) {
        MethodHandle mh = lookup.unreflectGetter(f);
        return dropArguments(mh, 0, EvaluationContext.class);
      }
    } catch (IllegalAccessException ex) {
      // fallthrough
    }

    MethodHandle mh = dispatchGetValueExpando(lookup, env, obj, name);
    if (mh != null)
      return insertArguments(mh, 2, name);

    // Fallback to dynamic getValue.
    MethodHandle fallback = insertArguments(MH_dynamicGetValue, 2, name);
    return filterArguments(fallback, 0, MH_getELContext);
  }

  private static MethodHandle dispatchGetValueExpando(MethodHandles.Lookup lookup,
                                                      EvaluationContext env,
                                                      Object obj, Object index) {
    if (isELiteObject(obj)) {
      try {
        Method m = obj.getClass().getMethod(
          mangle("[]"), EvaluationContext.class, Object[].class);
        MetaMethod meta = m.getAnnotation(MetaMethod.class);
        if (!Modifier.isStatic(m.getModifiers()) && meta != null &&
            meta.arity() == 1 && !meta.varargs()) {
          // (obj, env, [index]) -> (env, obj, index)
          MethodHandle mh = lookup.unreflect(m).asCollector(Object[].class, 1);
          return permuteArguments(mh, 1, 0, 2);
        }
      } catch (NoSuchMethodException | IllegalAccessException ex) {
        // fallthrough
      }
    } else {
      ELContext elctx = env.getELContext();
      MethodResolver resolver = MethodResolver.getInstance(elctx);
      MethodClosure mc = resolver.resolveMethod(obj.getClass(), "[]");

      if (mc != null) {
        Method m = mc.getJavaMethod(elctx, obj, index);
        if (m == null) {
          return dropArguments(
            throwEvaluationException(_T(EL_FN_NO_SUCH_METHOD, "[]", "[]",
                                        obj.getClass().getName())),
            1, Object.class, Object.class);
        }

        try {
          MethodHandle mh = lookup.unreflect(m);
          if (m.getParameterTypes()[0] == ELContext.class) {
            if (Modifier.isStatic(m.getModifiers())) {
              // For expando method, (elctx, obj, index), already in order
            } else {
              // For instance method, (obj, elctx, index) -> (env, obj, index)
              mh = permuteArguments(mh, 1, 0, 2);
            }
            mh = filterArguments(mh, 0, MH_getELContext);
          } else {
            mh = dropArguments(mh, 0, EvaluationContext.class);
          }
          return makeCoerce(mh, 1, obj, index);
        } catch (IllegalAccessException ex) {
          // fallthrough
        }
      }
    }

    return null;
  }

  private static String typeName(Object obj) {
    if (obj == null)
      return null;

    MetaClass meta = obj.getClass().getAnnotation(MetaClass.class);
    if (meta != null)
      return meta.name();

    return obj.getClass().getName();
  }

  private static Object dynamicGetValue(ELContext elctx, Object obj, Object key) {
    if (obj == null || key == null)
      return null;

    try {
      elctx.setPropertyResolved(false);
      Object value = elctx.getELResolver().getValue(elctx, obj, key);
      if (elctx.isPropertyResolved())
        return value;
    } catch (PropertyNotFoundException ex) {
      // fallthrough
    } catch (EvaluationException ex) {
      throw ex;
    } catch (ELException ex) {
      throw new EvaluationException(elctx, ex.getMessage(), ex.getCause());
    } catch (RuntimeException ex) {
      throw new EvaluationException(elctx, ex);
    }

    if (key instanceof String) {
      Closure method = resolveMethod(elctx, obj, (String)key);
      if (method != null)
        return method;
    }

    throw new EvaluationException(elctx, _T(EL_PROPERTY_NOT_FOUND,
                                            typeName(obj), key));
  }

  private static Closure resolveMethod(ELContext elctx, Object base,
                                       String name) {
    MethodResolver resolver = MethodResolver.getInstance(elctx);
    if (base == SystemScope.SINGLETON) {
      return resolver.resolveSystemMethod(name);
    } else if (base instanceof Class) {
      MethodClosure c = resolver.resolveStaticMethod((Class<?>)base, name);
      if (c != null)
        return c;
      c = resolver.resolveMethod((Class<?>)base, name);
      if (c != null)
        return c;
      c = resolver.resolveMethod(Class.class, name);
      return (c == null) ? null : new TargetMethodClosure(base, c);
    } else {
      MethodClosure c = resolver.resolveMethod(base.getClass(), name);
      return c == null ? null : new TargetMethodClosure(base, c);
    }
  }

  private static MethodHandle getGetterMethod(MethodHandles.Lookup lookup,
                                              Class<?> clazz, String name)
    throws NoSuchMethodException, IllegalAccessException
  {
    Method m = clazz.getMethod(name, EvaluationContext.class, Object[].class);
    if (!Modifier.isStatic(m.getModifiers())) {
      MetaMethod ann = m.getAnnotation(MetaMethod.class);
      if (ann != null && ann.arity() == 0 && !ann.varargs()) {
        MethodHandle mh = lookup.unreflect(m);
        mh = insertArguments(mh, 2, (Object)new Object[0]);
        return permuteArguments(mh, 1, 0);
      }
    }
    return null;
  }

  private static MethodHandle getSetterMethod(MethodHandles.Lookup lookup,
                                              Class<?> clazz, String name)
    throws NoSuchMethodException, IllegalAccessException
  {
    Method m = clazz.getMethod(name, EvaluationContext.class, Object[].class);
    if (!Modifier.isStatic(m.getModifiers())) {
      MetaMethod ann = m.getAnnotation(MetaMethod.class);
      if (ann != null && ann.arity() == 1 && !ann.varargs())
        return lookup.unreflect(m);
    }
    return null;
  }

  private static Field getField(Class<?> c, String name) {
    while (c != null) {
      try {
        Field f = c.getDeclaredField(name);
        return !Modifier.isStatic(f.getModifiers()) ? f : null;
      } catch (NoSuchFieldException e) {
        // continue
      }
      c = c.getSuperclass();
    }
    return null;
  }

  private static Field getStaticField(Class<?> c, String name) {
    while (c != null) {
      try {
        Field f = c.getDeclaredField(name);
        return Modifier.isStatic(f.getModifiers()) ? f : null;
      } catch (NoSuchFieldException e) {
        // continue
      }
      c = c.getSuperclass();
    }
    return null;
  }

  //=------------------------------------------------------------------------=//

  @SuppressWarnings("unused")
  public static CallSite getIndexedValueBootstrap(MethodHandles.Lookup lookup,
                                                  String name,
                                                  MethodType callsiteType) {
    MutableCallSite cs = new MutableCallSite(callsiteType);
    MethodHandle target = insertArguments(MH_getIndexedValueDispatcher, 0,
                                          lookup, cs);
    target = target.asType(callsiteType);
    cs.setTarget(target);
    return cs;
  }

  private static Object getIndexedValueDispatcher(MethodHandles.Lookup lookup,
                                                  MutableCallSite cs,
                                                  EvaluationContext env,
                                                  Object obj, Object index)
    throws Throwable
  {
    MethodHandle target = dispatchGetIndexedValue(lookup, env, obj, index);
    target = target.asType(cs.type());

    Class<?> objClass = obj == null ? null : obj.getClass();
    Class<?> indexClass = index == null ? null : index.getClass();
    MethodHandle guard = insertArguments(MH_classesEqual, 2, objClass, indexClass);
    guard = dropArguments(guard, 0, EvaluationContext.class);

    // Create PIC guard.
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly invoke target for current call.
    return target.invokeExact(env, obj, index);
  }

  private static MethodHandle dispatchGetIndexedValue(MethodHandles.Lookup lookup,
                                                      EvaluationContext env,
                                                      Object obj, Object index) {
    if (obj == null || index == null) {
      MethodHandle mh = constant(Object.class, null);
      return dropArguments(mh, 0, EvaluationContext.class, Object.class,
                           Object.class);
    }

    if (obj instanceof Map)
      return dropArguments(MH_mapGet, 0, EvaluationContext.class);

    if (index instanceof Number) {
      if (obj.getClass().isArray()) {
        MethodHandle mh = MethodHandles.arrayElementGetter(obj.getClass());
        mh = dropArguments(mh, 0, EvaluationContext.class);
        mh = makeCoerce(mh, 2, index);
        return catchException(
          mh, ArrayIndexOutOfBoundsException.class,
          dropArguments(constant(Object.class, null),
                        0, ArrayIndexOutOfBoundsException.class));
      }

      if (obj instanceof List) {
        MethodHandle mh = dropArguments(MH_listGet, 0, EvaluationContext.class);
        mh = makeCoerce(mh, 2, index);
        return catchException(
          mh, IndexOutOfBoundsException.class,
          dropArguments(constant(Object.class, null), 0,
                        IndexOutOfBoundsException.class));
      }
    }

    MethodHandle mh = dispatchGetValueExpando(lookup, env, obj, index);
    if (mh != null)
      return mh;

    // Fallback to dynamic getValue.
    return filterArguments(MH_dynamicGetValue, 0, MH_getELContext);
  }

  //=------------------------------------------------------------------------=//

  @SuppressWarnings("unused")
  public static CallSite setValueBootstrap(MethodHandles.Lookup lookup,
                                           String name,
                                           MethodType callSiteType) {
    MutableCallSite cs = new MutableCallSite(callSiteType);
    MethodHandle target = insertArguments(MH_setValueDispatcher, 0,
                                          lookup, cs, demangle(name));
    target = target.asType(callSiteType);
    cs.setTarget(target);
    return cs;
  }

  private static void setValueDispatcher(MethodHandles.Lookup lookup,
                                         MutableCallSite cs, String name,
                                         Object value, Object obj,
                                         EvaluationContext env)
    throws Throwable
  {
    MethodHandle target = dispatchSetValue(lookup, env, name, obj, value);
    target = target.asType(cs.type());

    // Guard condition: obj != null && obj.getClass() == cachedClass.
    Class<?> receiverClass = obj == null ? null : obj.getClass();
    Class<?> valueClass = value == null ? null : value.getClass();
    MethodHandle guard = insertArguments(
      MH_classesEqual, 2, valueClass, receiverClass);

    // Adapt to (Object, Object, EvaluationContext)boolean.
    guard = dropArguments(guard, 2, EvaluationContext.class);

    // Create PIC guard.
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly invoke target for current call.
    target.invokeExact(value, obj, env);
  }

  private static MethodHandle dispatchSetValue(MethodHandles.Lookup lookup,
                                               EvaluationContext env,
                                               String name, Object obj,
                                               Object value) {
    if (obj == null) {
      return dropArguments(throwNullPointerException(), 0,
                           Object.class, Object.class);
    }

    if (obj instanceof Map) {
      MethodHandle mh = insertArguments(MH_mapPut, 1, name);
      return permuteReceiverAndValue(mh, value);
    }

    if (isELiteObject(obj)) {
      if (lookup.lookupClass() == obj.getClass()) {
        // First try declared fields to bypass access control.
        try {
          Field f = getField(obj.getClass(), name);
          if (f != null && !Modifier.isFinal(f.getModifiers()))
            return permuteReceiverAndValue(lookup.unreflectSetter(f), value);
        } catch (IllegalAccessException e) {
          // fallthrough
        }
      }

      // Try setter with prototype Object setXxx(EvaluationContext,
      // Object[].class)
      try {
        MethodHandle mh = getSetterMethod(lookup, obj.getClass(),
                                          "set" + capitalize(name));
        if (mh != null) {
          // (receiver, env, value) -> (value, receiver, env)
          mh = mh.asCollector(Object[].class, 1);
          return permuteArguments(mh, 2, 0, 1);
        }
      } catch (NoSuchMethodException | IllegalAccessException e) {
        // fallthrough
      }
    }

    if (obj instanceof Class<?> c) {
      // Set the static field with the given name.
      try {
        Field f = getStaticField(c, name);
        if (f != null && !Modifier.isFinal(f.getModifiers())) {
          MethodHandle mh = lookup.unreflectSetter(f);
          mh = dropArguments(mh, 0, Object.class);
          return permuteReceiverAndValue(mh, value);
        }
      } catch (IllegalAccessException e) {
        // fallthrough
      }
    }

    // Invoke write method of a property.
    try {
      Method m = BeanUtils.getWriteMethod(obj.getClass(), name);
      if (m != null)
        return permuteReceiverAndValue(lookup.unreflect(m), value);
    } catch (IntrospectionException | IllegalAccessException e) {
      // fallthrough
    }

    // Set the field with given name.
    try {
      Field f = getField(obj.getClass(), name);
      if (f != null && !Modifier.isFinal(f.getModifiers()))
        return permuteReceiverAndValue(lookup.unreflectSetter(f), value);
    } catch (IllegalAccessException ex) {
      // fallthrough
    }

    MethodHandle mh = dispatchSetValueExpando(lookup, env, obj, name, value);
    if (mh != null)
      return insertArguments(mh, 2, name);

    // Fallback to dynamic setValue.
    // (elctx, obj, index, value) -> (value, obj, (name), env)
    MethodHandle fallback = permuteArguments(MH_dynamicSetValue, 3, 1, 2, 0);
    fallback = insertArguments(fallback, 2, name);
    return filterArguments(fallback, 2, MH_getELContext);
  }

  private static MethodHandle permuteReceiverAndValue(MethodHandle mh,
                                                      Object value) {
    mh = permuteArguments(mh, 1, 0);
    mh = makeCoerce(mh, 0, value);
    return dropArguments(mh, 2, EvaluationContext.class);
  }

  private static MethodHandle dispatchSetValueExpando(MethodHandles.Lookup lookup,
                                                      EvaluationContext env,
                                                      Object obj, Object index,
                                                      Object value) {
    if (isELiteObject(obj)) {
      try {
        Method m = obj.getClass().getMethod(
          mangle("[]="), EvaluationContext.class, Object[].class);
        MetaMethod meta = m.getAnnotation(MetaMethod.class);
        if (!Modifier.isStatic(m.getModifiers()) && meta != null &&
            meta.arity() == 2 && !meta.varargs()) {
          // (obj, env, [index, value]) -> (value, obj, index, env)
          MethodHandle mh = lookup.unreflect(m).asCollector(Object[].class, 2);
          return permuteArguments(mh, 3, 0, 2, 1);
        }
      } catch (NoSuchMethodException | IllegalAccessException e) {
        // fallthrough
      }
    } else {
      ELContext elctx = env.getELContext();
      MethodResolver resolver = MethodResolver.getInstance(elctx);
      MethodClosure mc = resolver.resolveMethod(obj.getClass(), "[]=");

      if (mc != null) {
        Method m = mc.getJavaMethod(elctx, obj, index, value);
        if (m == null) {
          return dropArguments(
            throwEvaluationException(_T(EL_FN_NO_SUCH_METHOD, "[]=", "[]=",
                                        obj.getClass().getName())),
            0, Object.class, Object.class, Object.class);
        }

        try {
          MethodHandle mh = lookup.unreflect(m);
          if (m.getParameterTypes()[0] == ELContext.class) {
            if (Modifier.isStatic(m.getModifiers())) {
              // For expando method, (elctx, obj, index, value) ->
              //                     (value, obj, index, env)
              mh = permuteArguments(mh, 3, 1, 2, 0);
            } else {
              // For instance method, (obj, elctx, index, value) ->
              //                      (value, obj, index, env)
              mh = permuteArguments(mh, 3, 0, 2, 1);
            }
            mh = filterArguments(mh, 3, MH_getELContext);
          } else {
            // (obj, index, value) -> (value, obj, index, (env))
            mh = permuteArguments(mh, 2, 0, 1);
            mh = dropArguments(mh, 3, EvaluationContext.class);
          }
          return makeCoerce(mh, 0, value, obj, index);
        } catch (IllegalAccessException e) {
          // fallthrough
        }
      }
    }

    return null;
  }

  private static void setValue(ELContext elctx, Object obj, Object index,
                               Object value) {
    if (obj == null || index == null) {
      throw new EvaluationException(elctx, _T(EL_PROPERTY_NOT_FOUND,
                                              typeName(obj), index));
    }

    try {
      elctx.setPropertyResolved(false);
      elctx.getELResolver().setValue(elctx, obj, index, value);
      if (elctx.isPropertyResolved())
        return;
    } catch (PropertyNotFoundException ex) {
      // fallthrough
    } catch (PropertyNotWritableException ex) {
      throw new EvaluationException(elctx, _T(EL_PROPERTY_NOT_WRITABLE,
                                              typeName(obj), index));
    } catch (EvaluationException ex) {
      throw ex;
    } catch (ELException ex) {
      throw new EvaluationException(elctx, ex.getMessage(), ex.getCause());
    } catch (RuntimeException ex) {
      throw new EvaluationException(elctx, ex);
    }

    throw new EvaluationException(elctx, _T(EL_PROPERTY_NOT_FOUND,
                                            typeName(obj), index));
  }

  //=------------------------------------------------------------------------=//

  @SuppressWarnings("unused")
  public static CallSite setIndexedValueBootstrap(MethodHandles.Lookup lookup,
                                                  String name,
                                                  MethodType callSiteType) {
    MutableCallSite cs = new MutableCallSite(callSiteType);
    MethodHandle target = insertArguments(MH_setIndexedValueDispatcher, 0,
                                          lookup, cs);
    target = target.asType(callSiteType);
    cs.setTarget(target);
    return cs;
  }

  private static boolean setValueTypesEqual(
      Object   value,    Object    obj,     Object   index,
      Class<?> valueType, Class<?> objType, Class<?> indexType) {
    return typeOf(value) == valueType &&
           typeOf(obj)   == objType &&
           typeOf(index) == indexType;
  }

  private static void setIndexedValueDispatcher(MethodHandles.Lookup lookup,
                                                MutableCallSite cs, Object value,
                                                Object obj, Object index,
                                                EvaluationContext env)
    throws Throwable
  {
    MethodHandle target = dispatchSetIndexedValue(lookup, env, obj, index, value);
    target = target.asType(cs.type());

    Class<?> objType = obj == null ? null : obj.getClass();
    Class<?> indexType = index == null ? null : index.getClass();
    Class<?> valueType = value == null ? null : value.getClass();
    MethodHandle guard = insertArguments(
      MH_setValueTypesEqual, 3, valueType, objType, indexType);
    guard =dropArguments(guard, 3, EvaluationContext.class);

    // Create PIC guard.
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly invoke target for current call.
    target.invokeExact(value, obj, index, env);
  }

  private static MethodHandle dispatchSetIndexedValue(MethodHandles.Lookup lookup,
                                                      EvaluationContext env,
                                                      Object obj, Object index,
                                                      Object value) {
    if (obj == null || index == null) {
      return dropArguments(throwNullPointerException(), 0, Object.class,
                           Object.class, Object.class);
    }

    if (obj instanceof Map) {
      MethodHandle mh = permuteArguments(MH_mapPut, 2, 0, 1);
      return dropArguments(mh, 3, EvaluationContext.class);
    }

    if (index instanceof Number) {
      if (obj.getClass().isArray()) {
        MethodHandle mh = MethodHandles.arrayElementSetter(obj.getClass());
        mh = makeCoerce(mh, 1, index, value);
        mh = permuteArguments(mh, 2, 0, 1);
        return dropArguments(mh, 3, EvaluationContext.class);
      }

      if (obj instanceof List) {
        MethodHandle mh = permuteArguments(MH_listSet, 2, 0, 1);
        mh = makeCoerce(mh, 2, index);
        return dropArguments(mh, 3, EvaluationContext.class);
      }
    }

    MethodHandle mh = dispatchSetValueExpando(lookup, env, obj, index, value);
    if (mh != null)
      return mh;

    // Fallback to dynamic setValue.
    // (elctx, obj, index, value) -> (value, obj, index, env)
    MethodHandle fallback = permuteArguments(MH_dynamicSetValue, 3, 1, 2, 0);
    return filterArguments(fallback, 3, MH_getELContext);
  }

  //=------------------------------------------------------------------------=//

  @SuppressWarnings("unused")
  public static CallSite invokeBootstrap(MethodHandles.Lookup lookup,
                                         String name, MethodType callsiteType,
                                         int isSuper, String... keys) {
    MutableCallSite cs = new MutableCallSite(callsiteType);
    MethodHandle target = insertArguments(MH_invokeDispatcher, 0, lookup, cs,
                                          demangle(name), isSuper != 0, keys);
    target = target.asType(callsiteType);
    cs.setTarget(target);
    return cs;
  }

  private static Object invokeDispatcher(MethodHandles.Lookup lookup,
                                         MutableCallSite cs, String name,
                                         boolean isSuper, String[] keys,
                                         EvaluationContext env,
                                         Object obj, Object[] args)
    throws Throwable
  {
    MethodHandle target = dispatchInvoke(lookup, env, name, isSuper, keys,
                                         obj, args);
    target = target.asType(cs.type());

    // Guard condition: obj and argument types are not changed.
    Class<?>[] types = typeOf(obj, args);
    MethodHandle guard = insertArguments(MH_invokeTypesEqual, 2, (Object)types);
    guard = dropArguments(guard, 0, EvaluationContext.class);

    // Create PIC guard.
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly invoke target for current call.
    return target.invokeExact(env, obj, args);
  }

  private static MethodHandle reportMethodNotFound(Object obj, String name) {
    String type;
    Class<?> c = obj instanceof Class ? (Class<?>)obj : obj.getClass();
    if (isELiteClass(c))
      type = c.getAnnotation(MetaClass.class).name();
    else if (obj instanceof Closure)
      type = obj.toString();
    else
      type = c.getName();

    return dropArguments(
      throwEvaluationException(_T(EL_METHOD_NOT_FOUND, type, name)),
      1, Object.class, Object[].class);
  }

  static MethodHandle dispatchInvoke(MethodHandles.Lookup lookup,
                                     EvaluationContext env,
                                     String name, boolean isSuper,
                                     String[] keys, Object obj,
                                     Object[] args) {
    if (obj == null)
      return dropArguments(throwNullPointerException(), 1, Object.class,
                           Object[].class);

    // Dispatch to ELite method.
    if (isELiteObject(obj)) {
      MethodHandle mh = dispatchELiteMethod(
        lookup, obj.getClass(), name, isSuper, keys, obj, args);
      if (mh != null)
        return mh;

      if (!isSuper) {
        mh = dispatchELiteDynamicInvoke(lookup, obj.getClass(), name, keys, args);
        if (mh != null)
          return mh;
      }
    } else if (obj instanceof Class<?> c && isELiteClass(c)) {
      MethodHandle mh = dispatchELiteMethod(
        lookup, c, name, false, keys, null, args);
      if (mh != null)
        return mh;
    }

    if (keys.length != 0) {
      String unknowKeys =
        Arrays.stream(keys).filter(s -> !s.isEmpty())
          .collect(Collectors.joining(", "));
      return dropArguments(
        throwEvaluationException(_T(EL_UNKNOWN_ARG_NAME, unknowKeys)),
        1, Object.class, Object[].class);
    }

    // Dispatch to Java method.
    if (!(obj instanceof MethodDelegate)) {
      try {
        MethodResolver resolver =
          MethodResolver.getInstance(env.getELContext());
        if (obj instanceof SystemScope) {
          assert obj == SystemScope.SINGLETON;
          MethodClosure mc = resolver.resolveSystemMethod(name);
          if (mc != null)
            return dispatchJavaMethod(lookup, env, mc, false, null, args);
        } else if (obj instanceof Class<?> c) {
          MethodClosure mc = resolver.resolveStaticMethod(c, name);
          if (mc != null)
            return dispatchJavaMethod(lookup, env, mc, false, null, args);
        } else if (isSuper) {
          MethodClosure mc =
            resolver.resolveProtectedMethod(obj.getClass().getSuperclass(), name);
          if (mc != null)
            return dispatchJavaMethod(lookup, env, mc, true, obj, args);
        } else {
          MethodClosure mc = resolver.resolveMethod(obj.getClass(), name);
          if (mc == null && lookup.lookupClass() == obj.getClass())
            mc = resolver.resolveProtectedMethod(obj.getClass(), name);
          if (mc != null)
            return dispatchJavaMethod(lookup, env, mc, false, obj, args);
        }
      } catch (MethodNotFoundException e) {
        return reportMethodNotFound(obj, name);
      }
    }

    if (obj instanceof MethodResolvable) {
      // obj.(MethodResolvable.invoke)(elctx, name, args)
      MethodHandle mh = MH_invokeMethodResolvable;
      mh = insertArguments(mh, 2, name);
      mh = filterArguments(mh, 1, MH_getELContext, MH_getCallArgs);
      return permuteArguments(mh, 1, 0, 2);
    }

    return reportMethodNotFound(obj, name);
  }

  private static MethodHandle dispatchELiteMethod(MethodHandles.Lookup lookup,
                                                  Class<?> c, String name,
                                                  boolean isSuper,
                                                  String[] keys, Object obj,
                                                  Object[] args) {
    try {
      Method m = getELiteMethod(isSuper ? c.getSuperclass() : c, mangle(name));
      if (m == null)
        return null;
      if (Modifier.isStatic(m.getModifiers()) != (obj == null))
        return reportMethodNotFound(c, name);

      // Check arguments.
      MetaMethod meta = m.getAnnotation(MetaMethod.class);
      int arity = meta.arity();
      boolean varargs = meta.varargs();
      Value[] defaults = meta.defaults();
      int argc = args.length;

      if (varargs ? (argc < arity - 1)
                  : (argc > arity || argc + defaults.length < arity)) {
        return dropArguments(
          throwEvaluationException(_T(EL_FN_BAD_ARG_COUNT, name, arity, argc)),
          1, Object.class, Object[].class);
      }

      MethodHandle mh = isSuper && obj != null
                        ? lookup.unreflectSpecial(m, c)
                        : lookup.unreflect(m);

      if (obj == null) {
        mh = dropArguments(mh, 1, Object.class);
      } else {
        // (obj, env, args) -> (env, obj, args)
        mh = permuteArguments(mh, 1, 0, 2);
      }

      return reorderArguments(mh, meta.keys(), keys, varargs, defaults, argc);
    } catch (IllegalAccessException e) {
      return reportMethodNotFound(c, name);
    }
  }

  private static MethodHandle
  dispatchELiteDynamicInvoke(MethodHandles.Lookup lookup, Class<?> c,
                             String name, String[] keys, Object[] args) {
    try {
      Method m = getELiteMethod(c, "__invoke__");
      if (m == null)
        return null;
      if (Modifier.isStatic(m.getModifiers()))
        return reportMethodNotFound(c, name);

      // Check arguments.
      // The dynamic invoke method must have the prototype:
      //     __invoke__(name, args...)
      MetaMethod meta = m.getAnnotation(MetaMethod.class);
      if (!meta.varargs() || meta.arity() != 2)
        return reportMethodNotFound(c, name);

      if (keys.length == 0 && args.length != 0) {
        keys = new String[args.length];
        Arrays.fill(keys, "");
      }

      // (obj, env, args) -> (env, obj, args)
      MethodHandle mh = permuteArguments(lookup.unreflect(m), 1, 0, 2);
      MethodHandle filter = insertArguments(MH_buildDynamicVarArgs, 1, name, keys);
      return filterArguments(mh, 2, filter);
    } catch (IllegalAccessException e) {
      return reportMethodNotFound(c, name);
    }
  }

  private static MethodHandle dispatchELiteConstructor(
    MethodHandles.Lookup lookup, Class<?> c, String name, String[] keys,
    Object[] args)
  {
    try {
      Constructor<?> cons = c.getConstructor(EvaluationContext.class,
                                             Object[].class);

      // Check arguments.
      MetaMethod meta = cons.getAnnotation(MetaMethod.class);
      int arity = meta.arity();
      boolean varargs = meta.varargs();
      Value[] defaults = meta.defaults();
      int argc = args.length;

      if (varargs ? (argc < arity - 1)
                  : (argc > arity || argc + defaults.length < arity)) {
        return dropArguments(
          throwEvaluationException(_T(EL_FN_BAD_ARG_COUNT, name, arity, argc)),
          1, Object.class, Object[].class);
      }

      MethodHandle mh = lookup.unreflectConstructor(cons);
      mh = dropArguments(mh, 1, Object.class);
      return reorderArguments(mh, meta.keys(), keys, varargs, defaults, argc);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return reportMethodNotFound(c, name);
    }
  }

  private static Method getELiteMethod(Class<?> c, String name) {
    for (; c != null; c = c.getSuperclass()) {
      try {
        Method m = c.getDeclaredMethod(name, EvaluationContext.class,
                                       Object[].class);
        return m.isAnnotationPresent(MetaMethod.class) ? m : null;
      } catch (NoSuchMethodException e) {
        // continue
      }
    }
    return null;
  }

  private static MethodHandle reorderArguments(MethodHandle mh, String[] names,
                                               String[] keys, boolean varargs,
                                               Value[] defaults, int argc) {
    // Build the permutation table for named arguments.
    int[] perm = null;
    if (keys.length != 0) {
      try {
        perm = buildPermutation(names, keys, varargs, defaults);
      } catch (IllegalArgumentException e) {
        return dropArguments(throwEvaluationException(e.getMessage()), 1,
                             Object.class, Object[].class);
      }
    }

    if (perm != null) {
      // Reorder named arguments using the permutation table.
      MethodHandle filter = insertArguments(MH_permuteNamedArgs, 1, perm,
                                            varargs, defaults);
      mh = filterArguments(mh, 2, filter);
    } else if (!varargs && argc != names.length) {
      if (Arrays.stream(defaults).allMatch(DynamicBootstrap::isConstantValue)) {
        int delta = names.length - argc;
        Object[] defaultValues = new Object[delta];
        for (int i = 0; i < delta; i++) {
          defaultValues[i] = getDefaultValue(
            defaults[defaults.length - delta + i]);
        }

        MethodHandle filter = insertArguments(MH_fillConstantDefaultArgs, 1,
                                              (Object)defaultValues);
        mh = filterArguments(mh, 2, filter);
      } else {
        MethodHandle filter = insertArguments(MH_fillDefaultArgs, 1,
                                              names.length, defaults);
        mh = filterArguments(mh, 2, filter);
      }
    } else if (varargs) {
      // Build argument list for varargs.
      MethodHandle filter = insertArguments(MH_buildVarArgs, 1, names.length);
      mh = filterArguments(mh, 2, filter);
    }

    return mh;
  }

  private static int indexOfArg(String name, String[] names, boolean varargs) {
    int arity = names.length - (varargs ? 1 : 0);
    for (int i = 0; i < arity; i++) {
      if (name.equals(names[i]))
        return i;
    }
    return -1;
  }

  private static int[] buildPermutation(String[] names, String[] keys,
                                        boolean varargs, Value[] defaults) {
    int argc = keys.length;
    int[] perm = new int[names.length];
    Arrays.fill(perm, -1);

    // Rearrange named arguments.
    for (int i = 0; i < argc; i++) {
      if (!keys[i].isEmpty()) {
        int j = indexOfArg(keys[i], names, varargs);
        if (j == -1)
          throw new IllegalArgumentException(_T(EL_UNKNOWN_ARG_NAME, keys[i]));
        perm[j] = i;
      }
    }

    // Rearrange non-named arguments.
    int j = 0;
    for (int i = 0; i < argc; i++) {
      if (keys[i].isEmpty()) {
        while (perm[j] != -1)
          j++;
        perm[j++] = i;
      }
    }

    // Fill default values;
    int fixed = names.length - defaults.length;
    for (; j < perm.length; j++) {
      if (perm[j] == -1) {
        if (j < fixed)
          throw new IllegalArgumentException(_T(EL_MISSING_ARG_VALUE, names[j]));
        perm[j] = -(j - fixed + 1); // Use negative index for default values
      }
    }

    // Check if the permutation is ordered.
    for (int i = 0; i < perm.length; i++) {
      if (perm[i] != i)
        return perm;
    }

    return null;
  }

  private static Object[] permuteNamedArgs(Object[] args, int[] perm,
                                           boolean varargs, Value[] defaults) {
    int argc = args.length;
    int arity = perm.length;
    int nvargs = varargs ? argc - arity + 1 : 0;
    Object[] xargs = new Object[arity];
    Object[] vargs = varargs ? new Object[nvargs] : null;

    for (int i = 0; i < arity; i++) {
      int j = perm[i];
      if (!varargs || i < arity - 1) {
        if (j >=0)
          xargs[i] = args[j];
        else
          xargs[i] = getDefaultValue(defaults[-j - 1]);
      } else {
        vargs[i - arity + 1] = args[j];
      }
    }

    if (varargs)
      xargs[arity - 1] = vargs;

    return xargs;
  }

  private static Object[] fillDefaultArgs(Object[] args, int arity,
                                          Value[] defaults) {
    Object[] xargs = new Object[arity];
    System.arraycopy(args, 0, xargs, 0, args.length);
    for (int i = args.length, j = defaults.length - (arity - args.length);
         i < arity; i++, j++)
      xargs[i] = getDefaultValue(defaults[j]);
    return xargs;
  }

  private static Object[] fillConstantDefaultArgs(Object[] args,
                                                  Object[] defaults) {
    Object[] xargs = new Object[args.length + defaults.length];
    System.arraycopy(args, 0, xargs, 0, args.length);
    System.arraycopy(defaults, 0, xargs, args.length, defaults.length);
    return xargs;
  }

  private static Object[] buildVarArgs(Object[] args, int arity) {
    int nvargs = args.length - arity + 1;
    Object[] xargs = new Object[arity];
    Object[] vargs = new Object[nvargs];
    System.arraycopy(args, 0, xargs, 0, arity - 1);
    System.arraycopy(args, arity - 1, vargs, 0, nvargs);
    xargs[arity - 1] = vargs;
    return xargs;
  }

  private static Object[] buildDynamicVarArgs(Object[] args, String name,
                                              String[] keys) {
    return new Object[] { name, new VarArgList(keys, args) };
  }

  private static Object getDefaultValue(Value value) {
    return switch (value.kind()) {
    case NULL   -> null;
    case NIL    -> Cons.nil();
    case BOOL   -> value.boolValue();
    case CHAR   -> value.charValue();
    case INT    -> value.intValue();
    case LONG   -> value.longValue();
    case FLOAT  -> value.floatValue();
    case DOUBLE -> value.doubleValue();
    case STRING -> value.stringValue();
    case SYMBOL -> Symbol.valueOf(value.stringValue());
    case CLASS  -> value.classValue();
    case FIELD  -> getFieldValue(value.classValue(), value.stringValue());
    case CONST  -> getConstValue(value.classValue(), value.intValue());
    };
  }

  private static Object getFieldValue(Class<?> c, String name) {
    try {
      return c.getField(name).get(null);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  private static Object getConstValue(Class<?> c, int index) {
    Object[] constants = (Object[])getFieldValue(c, "$C");
    return constants[index];
  }

  private static boolean isConstantValue(Value value) {
    if (value.kind() != ValueKind.FIELD)
      return true;
    try {
      Field f = value.classValue().getField(value.stringValue());
      return Modifier.isFinal(f.getModifiers());
    } catch (NoSuchFieldException e) {
      return false;
    }
  }

  private static MethodHandle dispatchJavaMethod(Lookup lookup,
                                                 EvaluationContext env,
                                                 MethodClosure mc,
                                                 boolean isSuper, Object obj,
                                                 Object[] args) {
    try {
      Method m = mc.getJavaMethod(env.getELContext(), obj, args);
      if (m == null)
        throw new MethodNotFoundException();

      boolean expando = obj != null && Modifier.isStatic(m.getModifiers());
      Class<?>[] types = m.getParameterTypes();
      int arity = types.length;
      int start = 0;
      if (arity > 0 && types[0] == ELContext.class) {
        arity--;
        start++;
      }
      if (expando) {
        arity--;
        start++;
      }
      assert m.isVarArgs() ? (args.length >= arity - 1) : (args.length == arity);

      MethodHandle mh = isSuper && obj != null
                        ? lookup.unreflectSpecial(m, obj.getClass())
                        : lookup.unreflect(m);

      if (expando) {
        // Expando method. (elctx, obj, args...) -> (env, obj, args...)
        if (types.length > 0 && types[0] == ELContext.class)
          mh = filterArguments(mh, 0, MH_getELContext);
        else
          mh = dropArguments(mh, 0, EvaluationContext.class);
      } else {
        // Ignore receiver for static method. (args...) -> (obj, args...)
        if (Modifier.isStatic(m.getModifiers()))
          mh = dropArguments(mh, 0, Object.class);

        // (obj, args...) -> (obj, env, args...)
        if (types.length > 0 && types[0] == ELContext.class)
          mh = filterArguments(mh, 1, MH_getELContext);
        else
          mh = dropArguments(mh, 1, EvaluationContext.class);
      }

      // Spread arguments. (obj, env, args...) -> (obj, env, args[])
      if (arity == 0)
        mh = dropArguments(mh, 2, Object[].class);
      else
        mh = mh.asSpreader(2, Object[].class, arity);

      // Build argument list for varargs.
      if (m.isVarArgs()) {
        MethodHandle filter = insertArguments(MH_buildVarArgs, 1, arity);
        mh = filterArguments(mh, 2, filter);
      }

      // Filter with coercion.
      if (needCoerce(args, types, start, m.isVarArgs())) {
        MethodHandle filter = insertArguments(MH_coerceArgs, 1, types, start,
                                              m.isVarArgs());
        mh = filterArguments(mh, 2, filter);
      }

      // (obj, env, args[]) -> (env, obj, args[])
      if (!expando)
        mh = permuteArguments(mh, 1, 0, 2);

      return mh;
    } catch (IllegalAccessException e) {
      throw new MethodNotFoundException();
    }
  }

  private static boolean needCoerce(Object[] args, Class<?>[] types, int start,
                                    boolean varargs) {
    int length = types.length - (varargs ? 1 : 0);
    for (int i = start; i < length; i++) {
      Object arg = args[i - start];
      if (arg != null && !TypeCoercion.getBoxedType(types[i]).isInstance(arg))
        return true;
    }
    return false;
  }

  private static Object[] coerceArgs(Object[] args, Class<?>[] types, int start,
                                     boolean varargs) {
    int length = types.length - (varargs ? 1 : 0);
    for (int i = start; i < length; i++) {
      Object arg = TypeCoercion.coerce(args[i - start], types[i]);
      args[i - start] = arg;
    }
    return args;
  }

  //=------------------------------------------------------------------------=//

  @SuppressWarnings("unused")
  public static CallSite callBootstrap(MethodHandles.Lookup lookup,
                                       String name, MethodType callSiteType,
                                       String... keys) {
    MutableCallSite cs = new MutableCallSite(callSiteType);
    MethodHandle target = insertArguments(MH_callDispatcher, 0, lookup, cs, keys);
    target = target.asType(callSiteType);
    cs.setTarget(target);
    return cs;
  }

  private static Object callDispatcher(MethodHandles.Lookup lookup,
                                       MutableCallSite cs, String[] keys,
                                       EvaluationContext env, Object receiver,
                                       Object[] args)
    throws Throwable
  {
    MethodHandle target = dispatchCall(lookup, env, keys, receiver, args);
    target = target.asType(cs.type());

    // Guard condition: obj and argument types are not changed.
    Class<?>[] types = typeOf(receiver, args);
    MethodHandle guard = insertArguments(MH_invokeTypesEqual, 2, (Object)types);
    guard = dropArguments(guard, 0, EvaluationContext.class);

    // Create PIC guard.
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly invoke target for current call.
    return target.invokeExact(env, receiver, args);
  }

  private static MethodHandle dispatchCall(MethodHandles.Lookup lookup,
                                           EvaluationContext env,
                                           String[] keys, Object obj,
                                           Object[] args) {
    if (obj == null)
      return dropArguments(throwNullPointerException(), 1, Object.class,
                           Object[].class);

    if (obj instanceof Closure) {
      MethodHandle mh = filterArguments(MH_callClosure, 1, MH_getELContext);
      mh = insertArguments(mh, 2, (Object)keys);
      return permuteArguments(mh, 1, 0, 2);
    }

    if (obj instanceof Class<?> c) {
      MethodHandle mh = dispatchClassCall(lookup, env, c, keys, args);
      if (mh != null)
        return mh;
    }

    if (isFunctionalInterfaceProxy(obj))
      return dispatchFunctionInterfaceCall(lookup, obj, keys, args);

    // If the object has a __call__ instance method, then call this method.
    if (isELiteObject(obj)) {
      MethodHandle mh = dispatchELiteMethod(lookup, obj.getClass(), "__call__",
                                            false, keys, obj, args);
      if (mh != null)
        return mh;
    } else {
      MethodResolver resolver = MethodResolver.getInstance(env.getELContext());
      MethodClosure mc = resolver.resolveMethod(obj.getClass(), "__call__");
      if (mc != null)
        return dispatchJavaMethod(lookup, env, mc, false, obj, args);
    }

    return reportMethodNotFound(obj, "__call__");
  }

  private static MethodHandle dispatchClassCall(MethodHandles.Lookup lookup,
                                                EvaluationContext env,
                                                Class<?> c, String[] keys,
                                                Object[] args) {
    // int(x) for type coercion.
    if (args.length == 1 &&
        (TypeCoercion.getUnboxedType(c).isPrimitive() ||
         c == String.class || c == BigInteger.class || c == BigDecimal.class ||
         c == Decimal.class || c == Rational.class ||
         (isFunctionalInterface(c) && args[0] instanceof Closure))) {
      if (c == Void.TYPE) {
        return dropArguments(constant(Object.class, null), 0,
                             EvaluationContext.class, Object.class,
                             Object[].class);
      } else {
        MethodHandle mh = CoercionBootstrap.dispatchCoerce(args[0], c);
        mh = filterArguments(mh, 0, MH_getELContext);
        mh = dropArguments(mh, 1, Object.class);
        return mh.asSpreader(2, Object[].class, 1);
      }
    }

    // If the class has a static method of valueOf, then call the method
    // to initialize the class instance, otherwise, invoke the class
    // constructor.
    if (isELiteClass(c)) {
      MethodHandle mh = dispatchELiteMethod(lookup, c, "valueOf", false, keys,
                                            null, args);
      if (mh != null)
        return mh;

      String name = c.getAnnotation(MetaClass.class).name();
      return dispatchELiteConstructor(lookup, c, name, keys, args);
    } else {
      if (keys.length != 0) {
        String unknowKeys =
          Arrays.stream(keys).filter(s -> !s.isEmpty())
            .collect(Collectors.joining(", "));
        return dropArguments(
          throwEvaluationException(_T(EL_UNKNOWN_ARG_NAME, unknowKeys)),
          1, Object.class, Object[].class);
      }

      MethodResolver resolver = MethodResolver.getInstance(env.getELContext());
      MethodClosure mc = resolver.resolveStaticMethod(c, "valueOf");
      if (mc != null)
        return dispatchJavaMethod(lookup, env, mc, false, null, args);

      MethodHandle mh = dispatchJavaConstructor(lookup, env, c, args);
      if (mh == null)
        return null;
      return dropArguments(mh, 0, EvaluationContext.class, Object.class);
    }
  }

  private static MethodHandle dispatchJavaConstructor(
    MethodHandles.Lookup lookup, EvaluationContext env, Class<?> c,
    Object[] args)
  {
    try {
      Constructor<?> cons = ELEngine.resolveConstructor(
        env.getELContext(), c, ELEngine.getCallArgs(args));
      if (cons == null)
        return null;

      MethodHandle mh = lookup.unreflectConstructor(cons);
      mh = mh.asSpreader(Object[].class, args.length);

      Class<?>[] types = cons.getParameterTypes();
      if (needCoerce(args, types, 0, false)) {
        MethodHandle filter = insertArguments(MH_coerceArgs, 1, types, 0, false);
        mh = filterArguments(mh, 0, filter);
      }

      return mh;
    } catch (IllegalAccessException e) {
      return null;
    }
  }

  private static MethodHandle dispatchFunctionInterfaceCall(
    MethodHandles.Lookup lookup, Object obj, String[] keys, Object[] args)
  {
    if (keys.length != 0) {
      String unknowKeys =
        Arrays.stream(keys).filter(s -> !s.isEmpty())
          .collect(Collectors.joining(", "));
      return dropArguments(
        throwEvaluationException(_T(EL_UNKNOWN_ARG_NAME, unknowKeys)),
        1, Object.class, Object[].class);
    }

    Method m = getFunctionalInterfaceMethod(obj.getClass().getInterfaces()[0]);
    try {
      MethodHandle mh = lookup.unreflect(m);
      mh = dropArguments(mh, 0, EvaluationContext.class);
      if (m.getParameterCount() == 0)
        mh = dropArguments(mh, 2, Object[].class);
      else
        mh = mh.asSpreader(Object[].class, m.getParameterCount());

      Class<?>[] types = m.getParameterTypes();
      if (needCoerce(args, types, 0, false)) {
        MethodHandle filter = insertArguments(MH_coerceArgs, 1, types, 0, false);
        mh = filterArguments(mh, 2, filter);
      }

      return mh;
    } catch (IllegalAccessException e) {
      return reportMethodNotFound(obj, m.getName());
    }
  }

  private static boolean isFunctionalInterface(Class<?> c) {
    if (c.isAnnotationPresent(FunctionalInterface.class))
      return true;

    Method method = null;
    for (Method m : c.getMethods()) {
      if (Modifier.isAbstract(m.getModifiers())) {
        if (method != null)
          return false;
        method = m;
      }
    }
    return method != null;
  }

  private static boolean isFunctionalInterfaceProxy(Object obj) {
    return Proxy.isProxyClass(obj.getClass()) &&
           obj.getClass().getInterfaces().length == 1 &&
           isFunctionalInterface(obj.getClass().getInterfaces()[0]);
  }

  private static Method getFunctionalInterfaceMethod(Class<?> c) {
    for (Method m : c.getMethods()) {
      if (Modifier.isAbstract(m.getModifiers())) {
        return m;
      }
    }
    throw new AssertionError("Method not found in functional interface");
  }

  //=------------------------------------------------------------------------=//

  @SuppressWarnings("unused")
  public static CallSite constructBootstrap(MethodHandles.Lookup lookup,
                                            String name,
                                            MethodType callSiteType,
                                            Class<?> c) {
    MutableCallSite cs = new MutableCallSite(callSiteType);
    MethodHandle target = insertArguments(MH_constructDispatcher,
                                          0, lookup, cs, c);
    target = target.asType(callSiteType);
    cs.setTarget(target);
    return cs;
  }

  private static Object constructDispatcher(MethodHandles.Lookup lookup,
                                            MutableCallSite cs, Class<?> c,
                                            EvaluationContext env,
                                            Object[] args)
    throws Throwable
  {
    MethodHandle target = dispatchJavaConstructor(lookup, env, c, args);

    if (target == null) {
      target = throwEvaluationException("Constructor not found: " +
                                        cs.type().returnType().getName());
      target = dropArguments(target, 1, Object[].class);
    } else {
      target = dropArguments(target, 0, EvaluationContext.class);
    }
    target = target.asType(cs.type());

    // Guard condition, constructor argument types are not changed.
    Class<?>[] types = typeOf(null, args);
    MethodHandle guard = insertArguments(MH_invokeTypesEqual, 0, (Object)null);
    guard = insertArguments(guard, 1, (Object)types);
    guard = dropArguments(guard, 0, EvaluationContext.class);

    // Create PIC guard.
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly invoke target for current call.
    return target.invokeExact(env, args);
  }

  //=------------------------------------------------------------------------=//

  @SuppressWarnings("unused")
  public static CallSite resolveConstructorBootstrap(MethodHandles.Lookup lookup,
                                                     String name,
                                                     MethodType callSiteType,
                                                     Class<?> superClass) {
    MutableCallSite cs = new MutableCallSite(callSiteType);
    MethodHandle target = insertArguments(MH_resolveConstructorDispatcher,
                                          0, cs, superClass);
    cs.setTarget(target);
    return cs;
  }

  private static int resolveConstructorDispatcher(MutableCallSite cs,
                                                  Class<?> superClass,
                                                  Object[] args) {
    int index = resolveConstructor(superClass, args);
    MethodHandle target = constant(int.class, index);
    target = dropArguments(target, 0, Object[].class);

    Class<?>[] types = typeOf(null, args);
    MethodHandle guard = insertArguments(MH_invokeTypesEqual, 0, (Object)null);
    guard = insertArguments(guard, 1, (Object)types);

    // Create PIC guard.
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly return index for current call.
    return index;
  }

  private static int resolveConstructor(Class<?> superClass, Object[] args) {
    int candidateIndex = -1;
    int shortestDistance = Integer.MAX_VALUE;
    int index = 0;

    for (Constructor<?> cons : superClass.getDeclaredConstructors()) {
      if (cons.getParameterCount() != args.length ||
          !(Modifier.isPublic(cons.getModifiers()) ||
            Modifier.isProtected(cons.getModifiers())))
        continue;

      Class<?>[] types = cons.getParameterTypes();
      int d = distanceof(types, args);
      if (d == 0)
        return index;
      if (d != -1 && d < shortestDistance) {
        candidateIndex = index;
        shortestDistance = d;
      }
      index++;
    }

    return candidateIndex;
  }

  private static int distanceof(Class<?>[] types, Object[] args) {
    int distance = 0;
    for (int i = 0; i < types.length; i++) {
      int d = distanceof(args[i], types[i]);
      if (d == -1)
        return -1;
      distance += d;
    }
    return distance;
  }

  private static int distanceof(Object arg, Class<?> type) {
    if (arg == null)
      return TypeCoercion.GUESSED_DISTANCE;
    return TypeCoercion.distanceof(arg.getClass(), type);
  }
}
