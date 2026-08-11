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

import org.elite.eval.DynamicDispatcher;
import org.elite.eval.EvaluationContext;
import org.elite.eval.Runtime;
import org.elite.util.BeanUtils;
import javax.el.ELContext;
import java.beans.IntrospectionException;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import static org.elite.ir.BootstrapCommon.*;
import static java.lang.invoke.MethodType.*;
import static java.lang.invoke.MethodHandles.*;
import static org.elite.eval.ELUtils.*;

public final class DynamicBootstrap {

  private DynamicBootstrap() {}

  private static final MethodHandle MH_getValueDispatcher;
  private static final MethodHandle MH_dynamicGetValue;
  private static final MethodHandle MH_setValueDispatcher;
  private static final MethodHandle MH_dynamicSetValue;
  private static final MethodHandle MH_mapGet;
  private static final MethodHandle MH_mapPut;

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

      MH_mapGet = lookup.findVirtual(
        Map.class, "get", methodType(Object.class, Object.class));

      MH_mapPut = lookup.findVirtual(
        Map.class, "put", methodType(Object.class, Object.class, Object.class));

    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
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

    if (obj instanceof DynamicDispatcher) {
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
      Member ann = m.getAnnotation(Member.class);
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
      Member ann = m.getAnnotation(Member.class);
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
      MethodHandle mh = throwException(void.class, NullPointerException.class);
      mh = filterArguments(mh, 0,
        dropArguments(MH_newNullPointerException, 0, Object.class));
      return dropArguments(mh, 1, Object.class, EvaluationContext.class);
    }

    if (obj instanceof Map) {
      MethodHandle mh = insertArguments(MH_mapPut, 1, name);
      return permuteReceiverAndValue(mh, value);
    }

    if (obj instanceof DynamicDispatcher) {
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
}
