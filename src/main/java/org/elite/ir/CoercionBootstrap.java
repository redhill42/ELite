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
import elite.lang.Range;
import elite.lang.Rational;
import elite.lang.Seq;
import org.elite.eval.Coercible;
import org.elite.eval.ELEngine;
import org.elite.eval.ELUtils;
import org.elite.eval.EvaluationContext;
import org.elite.eval.TypeCoercion;
import org.elite.eval.closure.LiteralClosure;
import org.elite.eval.seq.ArraySeq;
import org.elite.eval.seq.IteratorSeq;
import org.elite.eval.seq.ListSeq;
import org.elite.eval.seq.PArraySeq;
import javax.el.ELContext;
import javax.el.ELException;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.elite.eval.TypeCoercion.getBoxedType;
import static org.elite.eval.TypeCoercion.getUnboxedType;
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
import static org.elite.resources.Resources.*;

public final class CoercionBootstrap {

  private CoercionBootstrap() {}

  private static final MethodHandle MH_coerce;
  private static final MethodHandle MH_selfCoerce;
  private static final MethodHandle MH_classEquals;

  private static final MethodHandle MH_toString;
  private static final MethodHandle MH_collectionToString;
  private static final MethodHandle MH_mapToString;
  private static final MethodHandle MH_arrayToString;

  private static final MethodHandle MH_byteValue;
  private static final MethodHandle MH_shortValue;
  private static final MethodHandle MH_charValue;
  private static final MethodHandle MH_intValue;
  private static final MethodHandle MH_longValue;
  private static final MethodHandle MH_floatValue;
  private static final MethodHandle MH_doubleValue;

  private static final MethodHandle MH_stringToBoolean;
  private static final MethodHandle MH_stringToByte;
  private static final MethodHandle MH_stringToShort;
  private static final MethodHandle MH_stringToChar;
  private static final MethodHandle MH_stringToInteger;
  private static final MethodHandle MH_stringToLong;
  private static final MethodHandle MH_stringToFloat;
  private static final MethodHandle MH_stringToDouble;

  private static final MethodHandle MH_BigDecimalToBigInteger;
  private static final MethodHandle MH_DecimalToBigInteger;
  private static final MethodHandle MH_RationalToBigInteger;
  private static final MethodHandle MH_NumberToBigInteger;
  private static final MethodHandle MH_CharacterToBigInteger;
  private static final MethodHandle MH_StringToBigInteger;

  private static final MethodHandle MH_BigIntegerToBigDecimal;
  private static final MethodHandle MH_DecimalToBigDecimal;
  private static final MethodHandle MH_RationalToBigDecimal;
  private static final MethodHandle MH_NumberToBigDecimal;
  private static final MethodHandle MH_CharacterToBigDecimal;
  private static final MethodHandle MH_StringToBigDecimal;

  private static final MethodHandle MH_BigDecimalToDecimal;
  private static final MethodHandle MH_BigIntegerToDecimal;
  private static final MethodHandle MH_NumberToDecimal;
  private static final MethodHandle MH_CharacterToDecimal;
  private static final MethodHandle MH_StringToDecimal;

  private static final MethodHandle MH_NumberToRational;
  private static final MethodHandle MH_CharacterToRational;
  private static final MethodHandle MH_StringToRational;

  private static final MethodHandle MH_makeListSeq;
  private static final MethodHandle MH_makeIteratorSeq;
  private static final MethodHandle MH_makeArraySeq;
  private static final MethodHandle MH_makePArraySeq;

  private static final MethodHandle MH_createClosureProxy;

  static {
    MethodHandles.Lookup lookup = MethodHandles.lookup();

    try {
      MH_coerce = lookup.findStatic(
        CoercionBootstrap.class, "coerce",
        methodType(Object.class, MutableCallSite.class, ELContext.class,
                   Object.class, Class.class));

      MH_selfCoerce = lookup.findVirtual(
        Coercible.class, "coerce", methodType(Object.class, Class.class));

      MH_classEquals = lookup.findStatic(
        CoercionBootstrap.class, "classEquals",
        methodType(boolean.class, Object.class, Class.class));

      MH_toString = lookup.findVirtual(Object.class, "toString",
                                       methodType(String.class));
      MH_collectionToString = lookup.findStatic(
        CoercionBootstrap.class, "collectionToString",
        methodType(String.class, Collection.class));
      MH_mapToString = lookup.findStatic(
        CoercionBootstrap.class, "mapToString",
        methodType(String.class, Map.class));
      MH_arrayToString = lookup.findStatic(
        CoercionBootstrap.class, "arrayToString",
        methodType(String.class, Object.class));

      MH_byteValue = lookup.findVirtual(
        Number.class, "byteValue", methodType(byte.class));
      MH_shortValue = lookup.findVirtual(
        Number.class, "shortValue", methodType(short.class));
      MH_charValue = lookup.findVirtual(
        Character.class, "charValue", methodType(char.class));
      MH_intValue = lookup.findVirtual(
        Number.class, "intValue", methodType(int.class));
      MH_longValue = lookup.findVirtual(
        Number.class, "longValue", methodType(long.class));
      MH_floatValue = lookup.findVirtual(
        Number.class, "floatValue", methodType(float.class));
      MH_doubleValue = lookup.findVirtual(
        Number.class, "doubleValue", methodType(double.class));

      MH_stringToBoolean = lookup.findStatic(
        Boolean.class, "parseBoolean", methodType(boolean.class, String.class));
      MH_stringToByte = lookup.findStatic(
        CoercionBootstrap.class, "stringToByte",
        methodType(byte.class, String.class));
      MH_stringToShort = lookup.findStatic(
        CoercionBootstrap.class, "stringToShort",
        methodType(short.class, String.class));
      MH_stringToChar = lookup.findStatic(
        CoercionBootstrap.class, "stringToChar",
        methodType(char.class, String.class));
      MH_stringToInteger = lookup.findStatic(
        CoercionBootstrap.class, "stringToInteger",
        methodType(int.class, String.class));
      MH_stringToLong = lookup.findStatic(
        CoercionBootstrap.class, "stringToLong",
        methodType(long.class, String.class));
      MH_stringToFloat = lookup.findStatic(
        CoercionBootstrap.class, "stringToFloat",
        methodType(float.class, String.class));
      MH_stringToDouble = lookup.findStatic(
        CoercionBootstrap.class, "stringToDouble",
        methodType(double.class, String.class));

      MH_BigDecimalToBigInteger = lookup.findVirtual(
        BigDecimal.class, "toBigInteger", methodType(BigInteger.class));
      MH_DecimalToBigInteger = lookup.findVirtual(
        Decimal.class, "toBigInteger", methodType(BigInteger.class));
      MH_RationalToBigInteger = lookup.findVirtual(
        Rational.class, "toBigInteger", methodType(BigInteger.class));
      MH_NumberToBigInteger = lookup.findStatic(
        CoercionBootstrap.class, "numberToBigInteger",
        methodType(BigInteger.class, Number.class));
      MH_CharacterToBigInteger = lookup.findStatic(
        CoercionBootstrap.class, "characterToBigInteger",
        methodType(BigInteger.class, char.class));
      MH_StringToBigInteger = lookup.findStatic(
        CoercionBootstrap.class, "stringToBigInteger",
        methodType(BigInteger.class, String.class));

      MH_BigIntegerToBigDecimal = lookup.findConstructor(
        BigDecimal.class, methodType(void.class, BigInteger.class));
      MH_DecimalToBigDecimal = lookup.findVirtual(
        Decimal.class, "toBigDecimal", methodType(BigDecimal.class));
      MH_RationalToBigDecimal = lookup.findVirtual(
        Rational.class, "toBigDecimal", methodType(BigDecimal.class));
      MH_NumberToBigDecimal = lookup.findStatic(
        CoercionBootstrap.class, "numberToBigDecimal",
        methodType(BigDecimal.class, Number.class));
      MH_CharacterToBigDecimal = lookup.findStatic(
        CoercionBootstrap.class, "characterToBigDecimal",
        methodType(BigDecimal.class, char.class));
      MH_StringToBigDecimal = lookup.findStatic(
        CoercionBootstrap.class, "stringToBigDecimal",
        methodType(BigDecimal.class, String.class));

      MH_BigDecimalToDecimal = lookup.findStatic(
        Decimal.class, "valueOf", methodType(Decimal.class, BigDecimal.class));
      MH_BigIntegerToDecimal = lookup.findStatic(
        Decimal.class, "valueOf", methodType(Decimal.class, BigInteger.class));
      MH_NumberToDecimal = lookup.findStatic(
        CoercionBootstrap.class, "numberToDecimal",
        methodType(Decimal.class, Number.class));
      MH_CharacterToDecimal = lookup.findStatic(
        CoercionBootstrap.class, "characterToDecimal",
        methodType(Decimal.class, char.class));
      MH_StringToDecimal = lookup.findStatic(
        CoercionBootstrap.class, "stringToDecimal",
        methodType(Decimal.class, String.class));

      MH_NumberToRational = lookup.findStatic(
        Rational.class, "valueOf",
        methodType(Rational.class, Number.class));
      MH_CharacterToRational = lookup.findStatic(
        CoercionBootstrap.class, "characterToRational",
        methodType(Rational.class, char.class));
      MH_StringToRational = lookup.findStatic(
        CoercionBootstrap.class, "stringToRational",
        methodType(Rational.class, String.class));

      MH_makeListSeq = lookup.findStatic(
        ListSeq.class, "make", methodType(Seq.class, List.class));
      MH_makeIteratorSeq = lookup.findStatic(
        IteratorSeq.class, "make", methodType(Seq.class, Iterable.class));
      MH_makeArraySeq = lookup.findStatic(
        ArraySeq.class, "make", methodType(Seq.class, Object[].class));
      MH_makePArraySeq = lookup.findStatic(
        PArraySeq.class, "make", methodType(Seq.class, Object.class));

      MH_createClosureProxy = lookup.findStatic(
        CoercionBootstrap.class, "createClosureProxy",
        methodType(Object.class, ELContext.class, Closure.class, Class.class));

    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @SuppressWarnings("unused")
  public static CallSite coerceBootstrap(MethodHandles.Lookup lookup,
                                         String name,
                                         MethodType callsiteType) {
    MutableCallSite cs = new MutableCallSite(callsiteType);
    MethodHandle target = insertArguments(MH_coerce, 0, cs);
    target = insertArguments(target, 2, cs.type().returnType());
    cs.setTarget(target.asType(callsiteType));
    return cs;
  }

  private static boolean classEquals(Object o, Class<?> c) {
    return o == null ? c == null : o.getClass() == c;
  }

  private static Object coerce(MutableCallSite cs, ELContext elctx,
                               Object obj, Class<?> type)
    throws Throwable
  {
    MethodHandle target = dispatchCoerce(obj, type).asType(cs.type());

    // Guard condition: obj != null && obj.getClass() == cachedClass
    Class<?> cachedClass = obj == null ? null : obj.getClass();
    MethodHandle guard = insertArguments(MH_classEquals, 1, cachedClass);

    // Adapt to (ELContext, Object)boolean
    guard = dropArguments(guard, 0, ELContext.class);

    // Create PIC guard.
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly invoke target for current call.
    return target.invoke(elctx, obj);
  }

  static MethodHandle dispatchCoerce(Object obj, Class<?> type) {
    MethodHandle target;

    if (obj == null) {
      Class<?> t = getUnboxedType(type);
      if (t == BigInteger.class)
        target = constant(BigInteger.class, BigInteger.ZERO);
      else if (t == BigDecimal.class)
        target = constant(BigDecimal.class, BigDecimal.ZERO);
      else if (t == Decimal.class)
        target = constant(Decimal.class, Decimal.ZERO);
      else if (t == Rational.class)
        target = constant(Rational.class, Rational.ZERO);
      else
        target = zero(t);
      target = dropArguments(target, 0, Object.class);
    } else if (getBoxedType(type).isInstance(obj))
      target = identity(Object.class);
    else if (obj instanceof Coercible)
      target = insertArguments(MH_selfCoerce, 1, getBoxedType(type));
    else if (type == String.class)
      target = dispatchStringCoerce(obj);
    else if (type == Boolean.class || type == boolean.class)
      target = dispatchBooleanCoerce(obj);
    else if (type == Byte.class || type == byte.class)
      target = dispatchByteCoerce(obj);
    else if (type == Short.class || type == short.class)
      target = dispatchShortCoerce(obj);
    else if (type == Character.class || type == char.class)
      target = dispatchCharacterCoerce(obj);
    else if (type == Integer.class || type == int.class)
      target = dispatchIntegerCoerce(obj);
    else if (type == Long.class || type == long.class)
      target = dispatchLongCoerce(obj);
    else if (type == Float.class || type == float.class)
      target = dispatchFloatCoerce(obj);
    else if (type == Double.class || type == double.class)
      target = dispatchDoubleCoerce(obj);
    else if (type == BigInteger.class)
      target = dispatchBigIntegerCoerce(obj);
    else if (type == BigDecimal.class)
      target = dispatchBigDecimalCoerce(obj);
    else if (type == Decimal.class)
      target = dispatchDecimalCoerce(obj);
    else if (type == Rational.class)
      target = dispatchRationalCoerce(obj);
    else
      target = dispatchAnyCoerce(obj, type);

    if (target == null) {
      ELException ex = new ELException(
        _T(JSPRT_COERCE_ERROR, obj == null ? "null" : obj.getClass().getName(),
           type.getName()));
      target = throwException(void.class, ELException.class);
      target = insertArguments(target, 0, ex);
      target = dropArguments(target, 0, ELContext.class, Object.class);
    }

    if (target.type().parameterCount() == 1)
      target = dropArguments(target, 0, ELContext.class);

    return target;
  }

  private static MethodHandle dispatchStringCoerce(Object obj) {
    if (obj instanceof Range)
      return MH_toString;
    if (obj instanceof Collection)
      return MH_collectionToString;
    if (obj instanceof Map)
      return MH_mapToString;
    if (obj.getClass().isArray())
      return MH_arrayToString;
    return MH_toString;
  }

  private static String coerceToString(Object v) {
    if (v == null)
      return "";
    if (v instanceof String)
      return (String)v;
    if (v instanceof Range)
      return v.toString();
    if (v instanceof Collection<?> c)
      return collectionToString(c);
    if (v instanceof Map<?,?> m)
      return mapToString(m);
    if (v.getClass().isArray())
      return arrayToString(v);
    return v.toString();
  }

  private static String collectionToString(Collection<?> collection) {
    StringBuilder buf = new StringBuilder();
    buf.append("[");
    Iterator<?> it = collection.iterator();
    boolean hasNext = it.hasNext();
    while (hasNext) {
      to_s(buf, collection, it.next());
      hasNext = it.hasNext();
      if (hasNext)
        buf.append(", ");
    }
    buf.append("]");
    return buf.toString();
  }

  private static String mapToString(Map<?, ?> map) {
    StringBuilder buf = new StringBuilder();
    buf.append("{");
    Iterator<?> it = map.entrySet().iterator();
    boolean hasNext = it.hasNext();
    while (hasNext) {
      Map.Entry<?,?> e = (Map.Entry<?,?>)it.next();
      to_s(buf, map, e.getKey());
      buf.append(':');
      to_s(buf, map, e.getValue());
      hasNext = it.hasNext();
      if (hasNext)
        buf.append(", ");
    }
    buf.append("}");
    return buf.toString();
  }

  private static String arrayToString(Object array) {
    int length = Array.getLength(array);
    StringBuilder buf = new StringBuilder();
    buf.append("(");
    for (int i = 0; i < length; i++) {
      to_s(buf, array, Array.get(array, i));
      if (i < length - 1)
        buf.append(", ");
    }
    buf.append(")");
    return buf.toString();
  }

  private static void to_s(StringBuilder buf, Object v, Object o) {
    if (o == v)
      buf.append("(this object)");
    else if (o instanceof String)
      ELUtils.escape(buf, (String)o);
    else
      buf.append(coerceToString(o));
  }

  private static MethodHandle dispatchBooleanCoerce(Object obj) {
    if (obj instanceof String)
      return MH_stringToBoolean;
    return null;
  }

  private static MethodHandle dispatchByteCoerce(Object obj) {
    if (obj instanceof Number)
      return MH_byteValue;
    if (obj instanceof Character)
      return MH_charValue;
    if (obj instanceof String)
      return MH_stringToByte;
    return null;
  }

  private static byte stringToByte(String s) {
    if (s.length() == 0)
      return 0;
    return Byte.parseByte(s);
  }

  private static MethodHandle dispatchShortCoerce(Object obj) {
    if (obj instanceof Number)
      return MH_shortValue;
    if (obj instanceof Character)
      return MH_charValue;
    if (obj instanceof String)
      return MH_stringToShort;
    return null;
  }

  private static short stringToShort(String s) {
    if (s.length() == 0)
      return 0;
    return Short.parseShort(s);
  }

  private static MethodHandle dispatchCharacterCoerce(Object obj) {
    if (obj instanceof Number)
      return MH_shortValue;
    if (obj instanceof String)
      return MH_stringToChar;
    return null;
  }

  private static char stringToChar(String s) {
    if (s.length() == 0)
      return '\0';
    return s.charAt(0);
  }

  private static MethodHandle dispatchIntegerCoerce(Object obj) {
    if (obj instanceof Number)
      return MH_intValue;
    if (obj instanceof Character)
      return MH_charValue;
    if (obj instanceof String)
      return MH_stringToInteger;
    return null;
  }

  private static int stringToInteger(String s) {
    if (s.length() == 0)
      return 0;
    return Integer.parseInt(s);
  }

  private static MethodHandle dispatchLongCoerce(Object obj) {
    if (obj instanceof Number)
      return MH_longValue;
    if (obj instanceof Character)
      return MH_charValue;
    if (obj instanceof String)
      return MH_stringToLong;
    return null;
  }

  private static long stringToLong(String s) {
    if (s.length() == 0)
      return 0;
    return Long.parseLong(s);
  }

  private static MethodHandle dispatchFloatCoerce(Object obj) {
    if (obj instanceof Number)
      return MH_floatValue;
    if (obj instanceof Character)
      return MH_charValue;
    if (obj instanceof String)
      return MH_stringToFloat;
    return null;
  }

  private static float stringToFloat(String s) {
    if (s.length() == 0)
      return 0;
    return Float.parseFloat(s);
  }

  private static MethodHandle dispatchDoubleCoerce(Object obj) {
    if (obj instanceof Number)
      return MH_doubleValue;
    if (obj instanceof Character)
      return MH_charValue;
    if (obj instanceof String)
      return MH_stringToDouble;
    return null;
  }

  private static double stringToDouble(String s) {
    if (s.length() == 0)
      return 0;
    return Double.parseDouble(s);
  }

  private static MethodHandle dispatchBigIntegerCoerce(Object obj) {
    if (obj instanceof BigDecimal)
      return MH_BigDecimalToBigInteger;
    if (obj instanceof Decimal)
      return MH_DecimalToBigInteger;
    if (obj instanceof Rational)
      return MH_RationalToBigInteger;
    if (obj instanceof Number)
      return MH_NumberToBigInteger;
    if (obj instanceof Character)
      return MH_CharacterToBigInteger;
    if (obj instanceof String)
      return MH_StringToBigInteger;
    return null;
  }

  private static BigInteger numberToBigInteger(Number n) {
    return BigInteger.valueOf(n.longValue());
  }

  private static BigInteger characterToBigInteger(char c) {
    return BigInteger.valueOf(c);
  }

  private static BigInteger stringToBigInteger(String s) {
    if (s.length() == 0)
      return BigInteger.ZERO;
    return new BigInteger(s);
  }

  private static MethodHandle dispatchBigDecimalCoerce(Object obj) {
    if (obj instanceof BigInteger)
      return MH_BigIntegerToBigDecimal;
    if (obj instanceof Decimal)
      return MH_DecimalToBigDecimal;
    if (obj instanceof Rational)
      return MH_RationalToBigDecimal;
    if (obj instanceof Number)
      return MH_NumberToBigDecimal;
    if (obj instanceof Character)
      return MH_CharacterToBigDecimal;
    if (obj instanceof String)
      return MH_StringToBigDecimal;
    return null;
  }

  private static BigDecimal numberToBigDecimal(Number n) {
    return BigDecimal.valueOf(n.doubleValue());
  }

  private static BigDecimal characterToBigDecimal(char c) {
    return BigDecimal.valueOf(c);
  }

  private static BigDecimal stringToBigDecimal(String s) {
    if (s.length() == 0)
      return BigDecimal.ZERO;
    return new BigDecimal(s);
  }

  private static MethodHandle dispatchDecimalCoerce(Object obj) {
    if (obj instanceof BigDecimal)
      return MH_BigDecimalToDecimal;
    if (obj instanceof BigInteger)
      return MH_BigIntegerToDecimal;
    if (obj instanceof Number)
      return MH_NumberToDecimal;
    if (obj instanceof Character)
      return MH_CharacterToDecimal;
    if (obj instanceof String)
      return MH_StringToDecimal;
    return null;
  }

  private static Decimal numberToDecimal(Number n) {
    return Decimal.valueOf(n.doubleValue());
  }

  private static Decimal characterToDecimal(char c) {
    return Decimal.valueOf(c);
  }

  private static Decimal stringToDecimal(String s) {
    if (s.length() == 0)
      return Decimal.ZERO;
    return Decimal.valueOf(s);
  }

  private static MethodHandle dispatchRationalCoerce(Object obj) {
    if (obj instanceof Number)
      return MH_NumberToRational;
    if (obj instanceof Character)
      return MH_CharacterToRational;
    if (obj instanceof String)
      return MH_StringToRational;
    return null;
  }

  private static Rational characterToRational(char c) {
    return Rational.valueOf((int)c);
  }

  private static Rational stringToRational(String s) {
    if (s.length() == 0)
      return Rational.ZERO;
    return Rational.valueOf(s);
  }

  @SuppressWarnings("unchecked")
  private static MethodHandle dispatchAnyCoerce(Object obj, Class<?> type) {
    if (type.isEnum()) {
      if (obj instanceof String s) {
        Object value;
        if (s.length() == 0)
          value = null;
        else
          value = Enum.valueOf((Class<Enum>)type, s);
        return dropArguments(constant(type, value), 0, Object.class);
      } else {
        return null;
      }
    }

    if (type == Seq.class || type == List.class) {
      if (obj instanceof List)
        return MH_makeListSeq;
      if (obj instanceof Iterable)
        return MH_makeIteratorSeq;
      if (obj instanceof Object[])
        return MH_makeArraySeq;
      if (obj.getClass().isArray())
        return MH_makePArraySeq;
    }

    if (type.isInterface() && obj instanceof Closure c && c.isProcedure()) {
      return insertArguments(MH_createClosureProxy, 2, type);
    }

    return null;
  }

  private static Object createClosureProxy(ELContext ctx, Closure proc,
                                           Class<?> intf) {
    // Implicitly convert a procedure to a proxy object implements the given
    // interface, e.g.
    //   new Thread({=>print('hello')}).start
    // will create a proxy object that implement Runnable interface and run
    // method invokes the procedure.
    final ELContext elctx = (ctx != null) ? ctx : ELEngine.getCurrentELContext();
    return Proxy.newProxyInstance(intf.getClassLoader(), new Class<?>[]{intf},
      (proxy, method, args) -> {
        if (args == null) args = ELUtils.NO_VALUES;
        if (method.getDeclaringClass() == Object.class)
          return method.invoke(proc, args);

        EvaluationContext context = proc.getContext(elctx);
        Class<?> type = method.getReturnType();
        Object result;

        if (context != null) {
          context.setVariable("$method", new LiteralClosure(method.getName()));
          try {
            result = proc.call(elctx, args);
          } finally {
            context.setVariable("$method", null);
          }
        } else {
          result = proc.call(elctx, args);
        }

        return (type==Void.TYPE) ? null : TypeCoercion.coerce(elctx, result, type);
      });
  }
}
