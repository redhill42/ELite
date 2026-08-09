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
import org.elite.eval.DynamicDispatcher;
import org.elite.eval.EvaluationContext;
import org.elite.eval.EvaluationException;
import org.elite.eval.SystemScope;
import org.elite.eval.TypeCoercion;
import org.elite.eval.closure.MethodClosure;
import org.elite.eval.closure.TargetMethodClosure;
import org.elite.resolver.MethodResolver;
import org.elite.util.BeanUtils;
import javax.el.ELContext;
import javax.el.ELException;
import javax.el.PropertyNotFoundException;
import javax.el.PropertyNotWritableException;
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

import static java.lang.invoke.MethodType.*;
import static org.elite.eval.ELUtils.*;
import static org.elite.resources.Resources.*;

public final class DynamicBootstrap {

  private DynamicBootstrap() {}

  public record IndyDescriptor(String name, String bootstrap, Class<?> rtype,
                               Class<?>... ptypes) {}

  private static final MethodHandle MH_getValue;
  private static final MethodHandle MH_dynamicGetValue;
  private static final MethodHandle MH_setValue;
  private static final MethodHandle MH_dynamicSetValue;
  private static final MethodHandle MH_packArguments;
  private static final MethodHandle MH_mapGet;
  private static final MethodHandle MH_mapPut;
  private static final MethodHandle MH_classEquals;
  private static final MethodHandle MH_coerce;

  static {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      MH_getValue = lookup.findStatic(
        DynamicBootstrap.class, "getValue",
        methodType(Object.class, MethodHandles.Lookup.class,
                   MutableCallSite.class, String.class,
                   EvaluationContext.class, Object.class));

      MH_dynamicGetValue = lookup.findStatic(
        DynamicBootstrap.class, "dynamicGetValue",
        methodType(Object.class, EvaluationContext.class, Object.class,
                   String.class));

      MH_setValue = lookup.findStatic(
        DynamicBootstrap.class, "setValue",
        methodType(void.class, MethodHandles.Lookup.class,
                   MutableCallSite.class, String.class, Object.class,
                   Object.class, EvaluationContext.class));

      MH_dynamicSetValue = lookup.findStatic(
        DynamicBootstrap.class, "dynamicSetValue",
        methodType(void.class, Object.class, Object.class, String.class,
                   EvaluationContext.class));

      MH_packArguments = lookup.findStatic(
        DynamicBootstrap.class, "packArguments",
        methodType(Object[].class, Object.class));

      MH_mapGet = lookup.findVirtual(
        Map.class, "get", methodType(Object.class, Object.class));

      MH_mapPut = lookup.findVirtual(
        Map.class, "put", methodType(Object.class, Object.class, Object.class));

      MH_classEquals = lookup.findStatic(
        DynamicBootstrap.class, "classEquals",
        methodType(boolean.class, Object.class, Class.class));

      MH_coerce = lookup.findStatic(
        TypeCoercion.class, "coerce",
        methodType(Object.class, Object.class, Class.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @SuppressWarnings("unused")
  public static CallSite getValueBootstrap(MethodHandles.Lookup lookup,
                                           String name,
                                           MethodType callSiteType) {
    MutableCallSite cs = new MutableCallSite(callSiteType);
    MethodHandle target = MethodHandles.insertArguments(
      MH_getValue, 0, lookup, cs, name).asType(callSiteType);
    cs.setTarget(target);
    return cs;
  }

  private static boolean classEquals(Object o, Class<?> c) {
    return o == null ? c == null : o.getClass() == c;
  }

  private static Object getValue(MethodHandles.Lookup lookup,
                                 MutableCallSite cs, String name,
                                 EvaluationContext env, Object obj)
      throws Throwable
  {
    MethodHandle target = dispatchGetValue(lookup, obj, name);
    target = target.asType(cs.type());

    // Guard condition: obj != null && obj.getClass() == cachedClass
    Class<?> cachedClass = obj == null ? null : obj.getClass();
    MethodHandle guard = MethodHandles.insertArguments(
      MH_classEquals, 1, cachedClass);

    // Adapt to (EvaluationContext, Object)boolean, ignore env.
    guard = MethodHandles.dropArguments(guard, 0, EvaluationContext.class);

    // Create PIC guard.
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = MethodHandles.guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly invoke target for current call.
    return target.invokeExact(env, obj);
  }

  private static MethodHandle dispatchGetValue(MethodHandles.Lookup lookup,
                                               Object obj, String name) {
    if (obj == null) {
      MethodHandle mh = MethodHandles.constant(Object.class, null);
      return MethodHandles.dropArguments(mh, 0, EvaluationContext.class,
                                         Object.class);
    }

    if (obj instanceof Map) {
      MethodHandle mh = MethodHandles.insertArguments(MH_mapGet, 1, name);
      return MethodHandles.dropArguments(mh, 0, EvaluationContext.class);
    }

    if (obj instanceof DynamicDispatcher) {
      if (lookup.lookupClass() == obj.getClass()) {
        try {
          // First try declared fields to bypass access control.
          Field f = getField(obj.getClass(), name);
          if (f != null) {
            MethodHandle mh = lookup.unreflectGetter(f);
            return MethodHandles.dropArguments(mh, 0, EvaluationContext.class);
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
          return MethodHandles.dropArguments(mh, 0, EvaluationContext.class,
                                             Object.class);
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
        return MethodHandles.dropArguments(mh, 0, EvaluationContext.class);
      }
    } catch (IntrospectionException | IllegalAccessException ex) {
      // fallthrough
    }

    // Get the field with given name.
    try {
      Field f = getField(obj.getClass(), name);
      if (f != null) {
        MethodHandle mh = lookup.unreflectGetter(f);
        return MethodHandles.dropArguments(mh, 0, EvaluationContext.class);
      }
    } catch (IllegalAccessException ex) {
      // fallthrough
    }

    // Fallback to dynamic getValue.
    return MethodHandles.insertArguments(MH_dynamicGetValue, 2, name);
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
        mh = MethodHandles.insertArguments(mh, 2, (Object)new Object[0]);

        MethodType permuteType = methodType(
          mh.type().returnType(), mh.type().parameterType(1),
          mh.type().parameterType(0));
        return MethodHandles.permuteArguments(mh, permuteType, 1, 0);
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
    while (c != Object.class) {
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
    while (c != Object.class) {
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

  private static Object dynamicGetValue(EvaluationContext env, Object obj,
                                        String name) {
    ELContext elctx = env.getELContext();

    try {
      elctx.setPropertyResolved(false);
      Object value = elctx.getELResolver().getValue(elctx, obj, name);
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

    Closure method = resolveMethod(elctx, obj, name);
    if (method != null)
      return method;

    throw new EvaluationException(elctx, _T(EL_PROPERTY_NOT_FOUND,
                                            obj.getClass().getName(), name));
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

  @SuppressWarnings("unused")
  public static CallSite setValueBootstrap(MethodHandles.Lookup lookup,
                                           String name,
                                           MethodType callSiteType) {
    MutableCallSite cs = new MutableCallSite(callSiteType);
    MethodHandle target = MethodHandles.insertArguments(
      MH_setValue, 0, lookup, cs, name).asType(callSiteType);
    cs.setTarget(target);
    return cs;
  }

  private static void setValue(MethodHandles.Lookup lookup, MutableCallSite cs,
                               String name, Object value, Object obj,
                               EvaluationContext env)
    throws Throwable
  {
    MethodHandle target = dispatchSetValue(lookup, name, obj);
    target = target.asType(cs.type());

    // Guard condition: obj != null && obj.getClass() == cachedClass.
    Class<?> cachedClass = obj == null ? null : obj.getClass();
    MethodHandle guard = MethodHandles.insertArguments(
      MH_classEquals, 1, cachedClass);

    // Adapt to (Object, Object, EvaluationContext)boolean, ignore env and value.
    guard = MethodHandles.dropArguments(guard, 0, Object.class);
    guard = MethodHandles.dropArguments(guard, 2, EvaluationContext.class);

    // Create PIC guard.
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = MethodHandles.guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly invoke target for current call.
    target.invokeExact(value, obj, env);
  }

  private static MethodHandle dispatchSetValue(MethodHandles.Lookup lookup,
                                               String name, Object obj) {
    if (obj == null) {
      MethodHandle mh = MethodHandles.throwException(
        void.class, NullPointerException.class);
      mh = MethodHandles.insertArguments(
        mh, 0, new NullPointerException());
      return MethodHandles.dropArguments(
        mh, 0, Object.class, Object.class, EvaluationContext.class);
    }

    if (obj instanceof Map) {
      MethodHandle mh = MethodHandles.insertArguments(MH_mapPut, 1, name);
      return permuteReceiverAndValue(mh);
    }

    if (obj instanceof DynamicDispatcher) {
      if (lookup.lookupClass() == obj.getClass()) {
        // First try declared fields to bypass access control.
        try {
          Field f = getField(obj.getClass(), name);
          if (f != null && !Modifier.isFinal(f.getModifiers()))
            return permuteReceiverAndValue(lookup.unreflectSetter(f));
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
          mh = MethodHandles.filterArguments(mh, 2, MH_packArguments);
          return MethodHandles.permuteArguments(mh, methodType(
            mh.type().returnType(),
            mh.type().parameterType(2),
            mh.type().parameterType(0),
            mh.type().parameterType(1)), 1, 2, 0);
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
          mh = MethodHandles.dropArguments(mh, 0, Object.class);
          return permuteReceiverAndValue(mh);
        }
      } catch (IllegalAccessException e) {
        // fallthrough
      }
    }

    // Invoke write method of a property.
    try {
      Method m = BeanUtils.getWriteMethod(obj.getClass(), name);
      if (m != null)
        return permuteReceiverAndValue(lookup.unreflect(m));
    } catch (IntrospectionException | IllegalAccessException e) {
      // fallthrough
    }

    // Set the field with given name.
    try {
      Field f = getField(obj.getClass(), name);
      if (f != null && !Modifier.isFinal(f.getModifiers()))
        return permuteReceiverAndValue(lookup.unreflectSetter(f));
    } catch (IllegalAccessException ex) {
      // fallthrough
    }

    // Fallback to dynamic setValue.
    return MethodHandles.insertArguments(MH_dynamicSetValue, 2, name);
  }

  private static Object[] packArguments(Object value) {
    return new Object[]{value};
  }

  private static MethodHandle permuteReceiverAndValue(MethodHandle mh) {
    mh = MethodHandles.permuteArguments(mh, methodType(
      mh.type().returnType(),
      mh.type().parameterType(1),
      mh.type().parameterType(0)), 1, 0);

    // Add coercion filter on the value argument (position 0).
    // TypeCoercion.coerce(Object, Class) handles conversions that asType can't
    // (String->int, int->String, etc.). explicitCastArguments bridges the
    // filter's Object output to the target's possibly-primitive parameter type.
    Class<?> targetType = mh.type().parameterType(0);
    if (targetType != Object.class) {
      MethodHandle filter = MethodHandles.insertArguments(
        MH_coerce, 1, targetType);
      filter = MethodHandles.explicitCastArguments(
        filter, methodType(targetType, Object.class));
      mh = MethodHandles.filterArguments(mh, 0, filter);
    }

    return MethodHandles.dropArguments(mh, 2, EvaluationContext.class);
  }

  private static void dynamicSetValue(Object value, Object obj, String name,
                                      EvaluationContext env) {
    ELContext elctx = env.getELContext();

    try {
      elctx.setPropertyResolved(false);
      elctx.getELResolver().setValue(elctx, obj, name, value);
      if (elctx.isPropertyResolved())
        return;
    } catch (PropertyNotFoundException ex) {
      // fallthrough
    } catch (PropertyNotWritableException ex) {
      throw new EvaluationException(elctx, _T(EL_PROPERTY_NOT_WRITABLE,
                                              obj.getClass().getName(), name));
    } catch (EvaluationException ex) {
      throw ex;
    } catch (ELException ex) {
      throw new EvaluationException(elctx, ex.getMessage(), ex.getCause());
    } catch (RuntimeException ex) {
      throw new EvaluationException(elctx, ex);
    }

    throw new EvaluationException(
      elctx, _T(EL_PROPERTY_NOT_FOUND, obj.getClass().getName(), name));
  }
}
