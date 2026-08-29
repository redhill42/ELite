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
import org.elite.eval.EvaluationContext;
import org.elite.eval.EvaluationException;
import org.elite.eval.TypeCoercion;
import org.elite.eval.closure.CallableClosure;
import javax.el.ELContext;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodHandles.filterArguments;
import static java.lang.invoke.MethodType.methodType;

final class BootstrapCommon {
  private BootstrapCommon() {}

  static final MethodHandle MH_classEquals;
  static final MethodHandle MH_classesEqual;
  static final MethodHandle MH_getELContext;
  static final MethodHandle MH_newNullPointerException;
  static final MethodHandle MH_newEvaluationException;

  static final MethodHandle MH_callClosure;
  static final MethodHandle MH_callClosureWith;

  static {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();

      MH_classEquals = lookup.findStatic(
        BootstrapCommon.class, "classEquals",
        methodType(boolean.class, Object.class, Class.class));

      MH_classesEqual = lookup.findStatic(
        BootstrapCommon.class, "classesEqual",
        methodType(boolean.class, Object.class, Object.class, Class.class,
                   Class.class));

      MH_getELContext = lookup.findVirtual(
        EvaluationContext.class, "getELContext", methodType(ELContext.class));

      MH_newNullPointerException = lookup.findConstructor(
        NullPointerException.class, methodType(void.class));

      MH_newEvaluationException = lookup.findConstructor(
        EvaluationException.class,
        methodType(void.class, ELContext.class, String.class));

      MH_callClosure = lookup.findVirtual(
        Closure.class, "call",
        methodType(Object.class, ELContext.class, String[].class,
                   Object[].class));

      MH_callClosureWith = lookup.findVirtual(
        CallableClosure.class, "call_with",
        methodType(Object.class, ELContext.class, Object.class, Object[].class));

    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static MethodHandle makeCoerce(Object obj, Class<?> type, Class<?> rtype) {
    MethodHandle coerce = CoercionBootstrap.dispatchCoerce(obj, type);
    coerce = insertArguments(coerce, 0, (ELContext)null);
    return coerce.asType(methodType(rtype, Object.class));
  }

  static MethodHandle makeCoerce(Object obj, Class<?> type) {
    return makeCoerce(obj, type, type);
  }

  static MethodHandle makeCoerce(MethodHandle mh, int pos, Object... args) {
    for (int i = 0; i < args.length; i++) {
      Class<?> type = mh.type().parameterType(pos + i);
      if (!TypeCoercion.getBoxedType(type).isInstance(args[i]))
        mh = filterArguments(mh, pos + i, makeCoerce(args[i], type));
    }
    return mh;
  }

  static MethodHandle permuteArguments(MethodHandle mh, int... order) {
    MethodType oldType = mh.type();
    Class<?>[] ptypes = new Class<?>[order.length];
    for (int i = 0; i < order.length; ++i) {
      ptypes[i] = oldType.parameterType(order[i]);
    }

    MethodType newType = methodType(oldType.returnType(), ptypes);
    int[] permOrder = new int[order.length];
    for (int i = 0; i < order.length; ++i) {
      permOrder[order[i]] = i;
    }

    return MethodHandles.permuteArguments(mh, newType, permOrder);
  }

  static boolean classEquals(Object o, Class<?> c) {
    return o == null ? c == null : o.getClass() == c;
  }

  static boolean classesEqual(Object o1, Object o2, Class<?> c1, Class<?> c2) {
    return classEquals(o1, c1) && classEquals(o2, c2);
  }

  static MethodHandle throwNullPointerException() {
    MethodHandle mh = throwException(void.class, NullPointerException.class);
    return filterArguments(mh, 0,
                           dropArguments(MH_newNullPointerException, 0, EvaluationContext.class));
  }

  static MethodHandle throwEvaluationException(String message) {
    MethodHandle ex = filterArguments(
      MH_newEvaluationException, 0, MH_getELContext);
    ex = insertArguments(ex, 1, message);

    MethodHandle thrower = throwException(void.class, EvaluationException.class);
    return filterArguments(thrower, 0, ex);
  }

  static boolean isELiteClass(Class<?> c) {
    return c.isAnnotationPresent(MetaClass.class);
  }

  static boolean isELiteObject(Object obj) {
    return obj != null && obj.getClass().isAnnotationPresent(MetaClass.class);
  }
}
