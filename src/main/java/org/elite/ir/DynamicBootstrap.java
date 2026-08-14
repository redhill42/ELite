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
import org.elite.eval.ELEngine;
import org.elite.eval.EvaluationContext;
import org.elite.eval.MethodDelegate;
import org.elite.eval.MethodResolvable;
import org.elite.eval.Runtime;
import org.elite.eval.SystemScope;
import org.elite.eval.TypeCoercion;
import org.elite.eval.closure.MethodClosure;
import org.elite.resolver.MethodResolver;
import org.elite.util.BeanUtils;
import javax.el.ELContext;
import javax.el.MethodNotFoundException;
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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
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
  private static final MethodHandle MH_dynamicGetValue;
  private static final MethodHandle MH_setValueDispatcher;
  private static final MethodHandle MH_dynamicSetValue;
  private static final MethodHandle MH_invokeDispatcher;
  private static final MethodHandle MH_callDispatcher;
  private static final MethodHandle MH_dynamicCall;

  private static final MethodHandle MH_mapGet;
  private static final MethodHandle MH_mapPut;

  private static final MethodHandle MH_callClosure;

  private static final MethodHandle MH_typesEqual;
  private static final MethodHandle MH_permuteNamedArgs;
  private static final MethodHandle MH_buildVarArgs;
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

      MH_dynamicGetValue = lookup.findStatic(
        Runtime.class, "getValue",
        methodType(Object.class, ELContext.class, Object.class, Object.class));

      MH_setValueDispatcher = lookup.findStatic(
        DynamicBootstrap.class, "setValueDispatcher",
        methodType(void.class, MethodHandles.Lookup.class,
                   MutableCallSite.class, String.class, Object.class,
                   Object.class, EvaluationContext.class));

      MH_dynamicSetValue = lookup.findStatic(
        Runtime.class, "setValue",
        methodType(Object.class, Object.class, Object.class, Object.class,
                   ELContext.class));

      MH_invokeDispatcher = lookup.findStatic(
        DynamicBootstrap.class, "invokeDispatcher",
        methodType(Object.class, Lookup.class, MutableCallSite.class,
                   String.class, String[].class, EvaluationContext.class,
                   Object.class, Object[].class));

      MH_callDispatcher = lookup.findStatic(
        DynamicBootstrap.class, "callDispatcher",
        methodType(Object.class, Lookup.class, MutableCallSite.class,
                   String[].class, EvaluationContext.class, Object.class,
                   Object[].class));

      MH_dynamicCall = lookup.findStatic(
        ELEngine.class, "callTarget",
        methodType(Object.class, ELContext.class, Object.class, Object[].class));

      MH_mapGet = lookup.findVirtual(
        Map.class, "get", methodType(Object.class, Object.class));

      MH_mapPut = lookup.findVirtual(
        Map.class, "put", methodType(Object.class, Object.class, Object.class));

      MH_callClosure =
        permuteArguments(
          filterArguments(
            lookup.findVirtual(Closure.class, "call",
                               methodType(Object.class, ELContext.class,
                                          Object[].class)),
            1, MH_getELContext),
          1, 0, 2);

      MH_permuteNamedArgs = lookup.findStatic(
        DynamicBootstrap.class, "permuteNamedArgs",
        methodType(Object[].class, Object[].class, int[].class, int.class,
                   boolean.class));

      MH_buildVarArgs = lookup.findStatic(
        DynamicBootstrap.class, "buildVarArgs",
        methodType(Object[].class, Object[].class, int.class));

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

      MH_typesEqual = lookup.findStatic(
        DynamicBootstrap.class, "typesEqual",
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

  private static boolean typesEqual(Object obj, Object[] args, Class<?>[] types) {
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
                                          lookup, cs, name);
    target = target.asType(callSiteType);
    cs.setTarget(target);
    return cs;
  }

  private static Object getValueDispatcher(MethodHandles.Lookup lookup,
                                           MutableCallSite cs, String name,
                                           EvaluationContext env, Object obj)
      throws Throwable
  {
    MethodHandle target = dispatchGetValue(lookup, obj, name);
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

    // Fallback to dynamic getValue.
    MethodHandle fallback = insertArguments(MH_dynamicGetValue, 2, name);
    return filterArguments(fallback, 0, MH_getELContext);
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
  public static CallSite setValueBootstrap(MethodHandles.Lookup lookup,
                                           String name,
                                           MethodType callSiteType) {
    MutableCallSite cs = new MutableCallSite(callSiteType);
    MethodHandle target = insertArguments(MH_setValueDispatcher, 0,
                                          lookup, cs, name);
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
    MethodHandle target = dispatchSetValue(lookup, name, obj, value);
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

    // Fallback to dynamic setValue.
    MethodHandle fallback = insertArguments(MH_dynamicSetValue, 2, name);
    return filterArguments(fallback, 2, MH_getELContext);
  }

  private static MethodHandle permuteReceiverAndValue(MethodHandle mh,
                                                      Object value) {
    mh = permuteArguments(mh, 1, 0);
    mh = makeCoerce(mh, 0, value);
    return dropArguments(mh, 2, EvaluationContext.class);
  }

  //=------------------------------------------------------------------------=//

  @SuppressWarnings("unused")
  public static CallSite invokeBootstrap(MethodHandles.Lookup lookup,
                                         String name, MethodType callsiteType,
                                         String... keys) {
    MutableCallSite cs = new MutableCallSite(callsiteType);
    MethodHandle target = insertArguments(MH_invokeDispatcher, 0,
                                          lookup, cs, name, keys);
    target = target.asType(callsiteType);
    cs.setTarget(target);
    return cs;
  }

  private static Object invokeDispatcher(MethodHandles.Lookup lookup,
                                         MutableCallSite cs, String name,
                                         String[] keys, EvaluationContext env,
                                         Object obj, Object[] args)
    throws Throwable
  {
    MethodHandle target = dispatchInvoke(lookup, env, name, keys, obj, args);
    target = target.asType(cs.type());

    // Guard condition: obj and argument types are not changed.
    Class<?>[] types = typeOf(obj, args);
    MethodHandle guard = insertArguments(MH_typesEqual, 2, (Object)types);
    guard = dropArguments(guard, 0, EvaluationContext.class);

    // Create PIC guard.
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly invoke target for current call.
    return target.invoke(env, obj, args);
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

  private static MethodHandle dispatchInvoke(MethodHandles.Lookup lookup,
                                             EvaluationContext env,
                                             String name, String[] keys,
                                             Object obj, Object[] args) {
    if (obj == null)
      return dropArguments(throwNullPointerException(), 1, Object.class,
                           Object[].class);

    // Dispatch to ELite method.
    if (isELiteObject(obj)) {
      MethodHandle mh = dispatchELiteMethod(
        lookup, obj.getClass(), name, keys, obj, args);
      if (mh != null)
        return mh;
    } else if (obj instanceof Class<?> c && isELiteClass(c)) {
      MethodHandle mh = dispatchELiteMethod(
        lookup, c, name, keys, null, args);
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
            return dispatchJavaMethod(lookup, env, mc, null, args);
        } else if (obj instanceof Class<?> c) {
          MethodClosure mc = resolver.resolveStaticMethod(c, name);
          if (mc != null)
            return dispatchJavaMethod(lookup, env, mc, null, args);
        } else {
          MethodClosure mc = resolver.resolveMethod(obj.getClass(), name);
          if (mc != null)
            return dispatchJavaMethod(lookup, env, mc, obj, args);
        }
      } catch (MethodNotFoundException e) {
        return reportMethodNotFound(obj, name);
      }
    }

    if (obj instanceof MethodResolvable) {
      // obj.(MethodResolvable.invoke)(elctx, name, args)
      MethodHandle mh = MH_invokeMethodResolvable;
      mh = insertArguments(mh, 2, name);
      mh = filterArguments(mh, 3, MH_getCallArgs);
      return permuteArguments(mh, 1, 0, 2, 3);
    }

    return reportMethodNotFound(obj, name);
  }

  private static MethodHandle dispatchELiteMethod(MethodHandles.Lookup lookup,
                                                  Class<?> c, String name,
                                                  String[] keys,
                                                  Object obj, Object[] args) {
    try {
      Method m = getELiteMethod(c, mangle(name));
      if (m == null)
        return null;
      if (Modifier.isStatic(m.getModifiers()) != (obj == null))
        return reportMethodNotFound(obj, name);

      // Check arguments.
      MetaMethod meta = m.getAnnotation(MetaMethod.class);
      int arity = meta.arity();
      boolean varargs = meta.varargs();
      int argc = args.length;

      if (varargs ? (argc < arity - 1) : (argc != arity)) {
        return dropArguments(
          throwEvaluationException(_T(EL_FN_BAD_ARG_COUNT, name, arity, argc)),
          1, Object.class, Object[].class);
      }

      MethodHandle mh = lookup.unreflect(m);

      if (obj == null) {
        mh = dropArguments(mh, 1, Object.class);
      } else {
        // (obj, env, args) -> (env, obj, args)
        mh = permuteArguments(mh, 1, 0, 2);
      }

      // Build the permutation table for named arguments.
      int[] perm = null;
      if (keys.length != 0) {
        try {
          perm = buildPermutation(meta.parameterNames(), keys, varargs);
        } catch (IllegalArgumentException e) {
          return dropArguments(throwEvaluationException(e.getMessage()),
                               1, Object.class, Object[].class);
        }
      }

      if (perm != null) {
        // Reorder named arguments using the permutation table.
        MethodHandle filter = insertArguments(MH_permuteNamedArgs,
                                              1, perm, arity, varargs);
        mh = filterArguments(mh, 2, filter);
      } else if (varargs) {
        // Build argument list for varargs.
        MethodHandle filter = insertArguments(MH_buildVarArgs, 1, arity);
        mh = filterArguments(mh, 2, filter);
      }

      return mh;
    } catch (IllegalAccessException e) {
      return reportMethodNotFound(obj, name);
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

  private static int indexOfArg(String name, String[] names, boolean varargs) {
    int arity = names.length - (varargs ? 1 : 0);
    for (int i = 0; i < arity; i++) {
      if (name.equals(names[i]))
        return i;
    }
    return -1;
  }

  private static int[] buildPermutation(String[] names, String[] keys,
                                        boolean varargs) {
    int argc = keys.length;
    int[] perm = new int[argc];
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

    // Check if the permutation is ordered.
    for (int i = 0; i < argc; i++) {
      if (perm[i] != i)
        return perm;
    }

    return null;
  }

  private static Object[] permuteNamedArgs(Object[] args, int[] perm,
                                           int arity, boolean varargs) {
    int argc = args.length;
    int nvargs = varargs ? argc - arity + 1 : 0;
    Object[] xargs = new Object[arity];
    Object[] vargs = varargs ? new Object[nvargs] : null;

    for (int i = 0; i < argc; i++) {
      int j = perm[i];
      if (!varargs || i < arity - 1)
        xargs[i] = args[j];
      else
        vargs[i - arity + 1] = args[j];
    }

    if (varargs)
      xargs[arity - 1] = vargs;

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

  private static MethodHandle dispatchJavaMethod(Lookup lookup,
                                                 EvaluationContext env,
                                                 MethodClosure mc,
                                                 Object obj, Object[] args) {
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

      MethodHandle mh = lookup.unreflect(m);

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
    MethodHandle guard = insertArguments(MH_typesEqual, 2, (Object)types);
    guard = dropArguments(guard, 0, EvaluationContext.class);

    // Create PIC guard.
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly invoke target for current call.
    return target.invoke(env, receiver, args);
  }

  private static MethodHandle dispatchCall(MethodHandles.Lookup lookup,
                                           EvaluationContext env,
                                           String[] keys, Object obj,
                                           Object[] args) {
    if (obj == null)
      return dropArguments(throwNullPointerException(), 1, Object.class,
                           Object[].class);

    if (obj instanceof Closure)
      return MH_callClosure;

    if (obj instanceof Class<?> c) {
      MethodHandle mh = dispatchClassCall(lookup, env, c, keys, args);
      if (mh != null)
        return mh;
    }

    // If the object has a __call__ instance method, then call this method.
    if (isELiteObject(obj)) {
      MethodHandle mh = dispatchELiteMethod(lookup, obj.getClass(), "__call__",
                                            keys, obj, args);
      if (mh != null)
        return mh;
    } else {
      MethodResolver resolver = MethodResolver.getInstance(env.getELContext());
      MethodClosure mc = resolver.resolveMethod(obj.getClass(), "__call__");
      if (mc != null)
        return dispatchJavaMethod(lookup, env, mc, obj, args);
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
         c == Decimal.class || c == Rational.class)) {
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
    // to initialize the class instance.
    if (isELiteClass(c)) {
      MethodHandle mh = dispatchELiteMethod(lookup, c, "valueOf", keys, null,
                                            args);
      if (mh != null)
        return mh;
    } else {
      MethodResolver resolver = MethodResolver.getInstance(env.getELContext());
      MethodClosure mc = resolver.resolveStaticMethod(c, "valueOf");
      if (mc != null)
        return dispatchJavaMethod(lookup, env, mc, null, args);
    }

    return dispatchConstructor(lookup, env, c, args);
  }

  private static MethodHandle dispatchConstructor(MethodHandles.Lookup lookup,
                                                  EvaluationContext env,
                                                  Class<?> c, Object[] args) {
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

      return dropArguments(mh, 0, EvaluationContext.class, Object.class);
    } catch (IllegalAccessException e) {
      return null;
    }
  }
}
