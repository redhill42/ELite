/*
 * $Id: TypeCoercion.java,v 1.2 2009/05/11 07:29:13 jackyzhang Exp $
 *
 * Copyright (C) 2006 Operamasks Community.
 * Copyright (C) 2000-2006 Apusic Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see http://www.gnu.org/licenses.
 */

package org.operamasks.el.eval;

import javax.el.ELException;
import javax.el.ELContext;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.beans.PropertyEditor;
import java.beans.PropertyEditorManager;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Collection;
import java.util.Iterator;
import java.util.Arrays;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import elite.lang.Range;
import elite.lang.Seq;
import elite.lang.Decimal;
import elite.lang.Rational;
import elite.lang.Closure;
import org.operamasks.el.eval.seq.ListSeq;
import org.operamasks.el.eval.seq.IteratorSeq;
import org.operamasks.el.eval.seq.ArraySeq;
import org.operamasks.el.eval.seq.PArraySeq;
import org.operamasks.el.eval.closure.ClosureObject;
import org.operamasks.el.eval.closure.LiteralClosure;
import static org.operamasks.el.resources.Resources.*;

public final class TypeCoercion
{
    private static Map<Class,Integer> typeMap = new IdentityHashMap<Class,Integer>();
    private static Map<Class,Class> primitives = new IdentityHashMap<Class,Class>();

    public static final int NULL_TYPE              = 0;
    public static final int BOOLEAN_TYPE           = 1;
    public static final int BYTE_TYPE              = 2;
    public static final int CHAR_TYPE              = 3;
    public static final int SHORT_TYPE             = 4;
    public static final int INT_TYPE               = 5;
    public static final int LONG_TYPE              = 6;
    public static final int FLOAT_TYPE             = 7;
    public static final int DOUBLE_TYPE            = 8;
    public static final int DECIMAL_TYPE           = 9;
    public static final int RATIONAL_TYPE          = 10;
    public static final int BIG_INTEGER_TYPE       = 11;
    public static final int BIG_DECIMAL_TYPE       = 12;
    public static final int GENERIC_NUMBER_TYPE    = 13;
    public static final int STRING_TYPE            = 14;
    public static final int OBJECT_TYPE            = 15;
    public static final int UNKNOWN_TYPE           = 16;

    static {
        typeMap.put(Boolean.TYPE, BOOLEAN_TYPE);
        typeMap.put(Boolean.class, BOOLEAN_TYPE);
        typeMap.put(Byte.TYPE, BYTE_TYPE);
        typeMap.put(Byte.class, BYTE_TYPE);
        typeMap.put(Character.TYPE, CHAR_TYPE);
        typeMap.put(Character.class, CHAR_TYPE);
        typeMap.put(Short.TYPE, SHORT_TYPE);
        typeMap.put(Short.class, SHORT_TYPE);
        typeMap.put(Integer.TYPE, INT_TYPE);
        typeMap.put(Integer.class, INT_TYPE);
        typeMap.put(Long.TYPE, LONG_TYPE);
        typeMap.put(Long.class, LONG_TYPE);
        typeMap.put(Float.TYPE, FLOAT_TYPE);
        typeMap.put(Float.class, FLOAT_TYPE);
        typeMap.put(Double.TYPE, DOUBLE_TYPE);
        typeMap.put(Double.class, DOUBLE_TYPE);
        typeMap.put(Decimal.class, DECIMAL_TYPE);
        typeMap.put(Rational.class, RATIONAL_TYPE);
        typeMap.put(BigInteger.class, BIG_INTEGER_TYPE);
        typeMap.put(BigDecimal.class, BIG_DECIMAL_TYPE);
        typeMap.put(Number.class, GENERIC_NUMBER_TYPE);
        typeMap.put(String.class, STRING_TYPE);
        typeMap.put(Object.class, OBJECT_TYPE);

        primitives.put(Void.TYPE, Void.class);
        primitives.put(Boolean.TYPE, Boolean.class);
        primitives.put(Byte.TYPE, Byte.class);
        primitives.put(Character.TYPE, Character.class);
        primitives.put(Short.TYPE, Short.class);
        primitives.put(Integer.TYPE, Integer.class);
        primitives.put(Long.TYPE, Long.class);
        primitives.put(Float.TYPE, Float.class);
        primitives.put(Double.TYPE, Double.class);
    }

    public static int typeof(Class<?> t) {
        Integer type = typeMap.get(t);
        if (type != null)
            return type;
        if (Number.class.isAssignableFrom(t))
            return GENERIC_NUMBER_TYPE;
        return UNKNOWN_TYPE;
    }

    public static int typeof(Object o) {
        if (o == null) {
            return NULL_TYPE;
        }

        Integer type = typeMap.get(o.getClass());
        if (type != null)
            return type;
        if (o instanceof Number)
            return GENERIC_NUMBER_TYPE;
        return UNKNOWN_TYPE;
    }

    public static Class getBoxedType(Class t) {
        if (t.isPrimitive()) {
            return primitives.get(t);
        } else {
            return t;
        }
    }

    public static String coerceToString(Object v) {
        if (v == null) {
            return "";
        } else if (v instanceof String) {
            return (String)v;
        } else if (v instanceof Range) {
            return v.toString();
        } else if (v instanceof Collection) {
            StringBuilder buf = new StringBuilder();
            buf.append("[");
            Iterator i = ((Collection)v).iterator();
            boolean hasNext = i.hasNext();
            while (hasNext) {
                to_s(buf, v, i.next());
                hasNext = i.hasNext();
                if (hasNext) {
                    buf.append(", ");
                }
            }
            buf.append("]");
            return buf.toString();
        } else if (v instanceof Map) {
            StringBuilder buf = new StringBuilder();
            buf.append("{");
            Iterator i = ((Map)v).entrySet().iterator();
            boolean hasNext = i.hasNext();
            while (hasNext) {
                Map.Entry e = (Map.Entry)i.next();
                to_s(buf, v, e.getKey());
                buf.append(":");
                to_s(buf, v, e.getValue());
                hasNext = i.hasNext();
                if (hasNext) {
                    buf.append(", ");
                }
            }
            buf.append("}");
            return buf.toString();
        } else if (v.getClass().isArray()) {
            int length = Array.getLength(v);
            StringBuilder buf = new StringBuilder();
            buf.append("(");
            for (int i = 0; i < length; i++) {
                to_s(buf, v, Array.get(v, i));
                if (i < length-1) {
                    buf.append(", ");
                }
            }
            buf.append(")");
            return buf.toString();
        } else {
            return v.toString();
        }
    }

    private static void to_s(StringBuilder buf, Object v, Object o) {
        if (o == v) {
            buf.append("(this object)");
        } else if (o instanceof String) {
            escape(buf, (String)o);
        } else {
            buf.append(coerceToString(o));
        }
    }

    public static String escape(String s) {
        StringBuilder buf = new StringBuilder();
        escape(buf, s);
        return buf.toString();
    }
    
    public static void escape(StringBuilder buf, String s) {
        boolean escaped = false;
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            String esc = escape(c);
            if (esc != null) {
                if (!escaped) {
                    buf.append('"');
                    buf.append(s.substring(0, i));
                    escaped = true;
                }
                buf.append(esc);
            } else if (escaped) {
                buf.append(c);
            }
        }

        if (escaped) {
            buf.append('"');
        } else {
            buf.append('\'');
            buf.append(s);
            buf.append('\'');
        }
    }

    public static String escape(char c) {
        switch (c) {
        case '\r': return "\\r";
        case '\n': return "\\n";
        case '\f': return "\\f";
        case '\b': return "\\b";
        case '\t': return "\\t";
        case '\\': return "\\\\";
        case '\'': return "'";
        case '"':  return "\\\"";

        case 0: case 1: case 2: case 3: case 4: case 5: case 6: case 7:
            return "\\00" + Integer.toOctalString(c);
        case 11: case 14: case 15:
        case 16: case 17: case 18: case 19: case 20: case 21: case 22: case 23:
        case 24: case 25: case 26: case 27: case 28: case 29: case 30: case 31:
            return "\\0" + Integer.toOctalString(c);

        default:
            return null;
        }
    }

    public static Decimal coerceToDecimal(Object v)
        throws ELException
    {
        if (v == null) {
            return Decimal.ZERO;
        } else if (v instanceof Decimal) {
            return (Decimal)v;
        } else if (v instanceof BigDecimal) {
            return Decimal.valueOf((BigDecimal)v);
        } else if (v instanceof BigInteger) {
            return Decimal.valueOf(new BigDecimal((BigInteger)v));
        } else if (v instanceof Number) {
            return Decimal.valueOf(((Number)v).doubleValue());
        } else if (v instanceof Character) {
            return Decimal.valueOf((short)((Character)v).charValue());
        } else if (v instanceof String) {
            String s = (String)v;
            if (s.length() == 0) {
                return Decimal.ZERO;
            } else {
                return Decimal.valueOf(s);
            }
        } else if (v instanceof Coercible) {
            return selfCoerce((Coercible)v, Decimal.class);
        } else {
            throw coerceError(v, "Decimal");
        }
    }

    public static Rational coerceToRational(Object v) {
        if (v == null) {
            return Rational.ZERO;
        } else if (v instanceof Rational) {
            return (Rational)v;
        } else if (v instanceof BigInteger) {
            return Rational.make((BigInteger)v, BigInteger.ONE);
        } else if (v instanceof BigDecimal) {
            return Rational.valueOf((BigDecimal)v);
        } else if (v instanceof Decimal) {
            return Rational.valueOf((Decimal)v);
        } else if (v instanceof Double || v instanceof Float) {
            return Rational.valueOf(((Number)v).doubleValue());
        } else if (v instanceof Number) {
            return Rational.make(((Number)v).longValue(), 1);
        } else if (v instanceof Character) {
            return Rational.make((short)((Character)v).charValue(), 1);
        } else if (v instanceof String) {
            String s = (String)v;
            if (s.length() == 0) {
                return Rational.ZERO;
            } else {
                return Rational.valueOf(s);
            }
        } else if (v instanceof Coercible) {
            return selfCoerce((Coercible)v, Rational.class);
        } else {
            throw coerceError(v, "Rational");
        }
    }

    public static BigDecimal coerceToBigDecimal(Object v)
        throws ELException
    {
        if (v == null) {
            return BigDecimal.valueOf(0);
        } else if (v instanceof BigDecimal) {
            return (BigDecimal)v;
        } else if (v instanceof BigInteger) {
            return new BigDecimal((BigInteger)v);
        } else if (v instanceof Decimal) {
            return ((Decimal)v).toBigDecimal();
        } else if (v instanceof Rational) {
            return ((Rational)v).toBigDecimal();
        } else if (v instanceof Number) {
            return new BigDecimal(((Number)v).doubleValue());
        } else if (v instanceof Character) {
            return BigDecimal.valueOf((short)((Character)v).charValue());
        } else if (v instanceof String) {
            String s = (String)v;
            if (s.length() == 0) {
                return BigDecimal.valueOf(0);
            } else {
                return new BigDecimal(s);
            }
        } else if (v instanceof Coercible) {
            return selfCoerce((Coercible)v, BigDecimal.class);
        } else {
            throw coerceError(v, "BigDecimal");
        }
    }

    public static BigDecimal coerceToBigDecimal(ELContext ctx, Object v) {
        MathContext mc;
        if (ctx == null || (mc = GlobalScope.getMathContext(ctx)) == null) {
            return coerceToBigDecimal(v);
        }

        if (v == null) {
            return BigDecimal.valueOf(0);
        } else if (v instanceof BigDecimal) {
            return (BigDecimal)v;
        } else if (v instanceof BigInteger) {
            return new BigDecimal((BigInteger)v, mc);
        } else if (v instanceof Decimal) {
            return ((Decimal)v).toBigDecimal();
        } else if (v instanceof Rational) {
            return ((Rational)v).toBigDecimal(mc);
        } else if (v instanceof Number) {
            return new BigDecimal(((Number)v).doubleValue(), mc);
        } else if (v instanceof Character) {
            return BigDecimal.valueOf((short)((Character)v).charValue());
        } else if (v instanceof String) {
            String s = (String)v;
            if (s.length() == 0) {
                return BigDecimal.valueOf(0);
            } else {
                return new BigDecimal(s, mc);
            }
        } else if (v instanceof Coercible) {
            return selfCoerce((Coercible)v, BigDecimal.class);
        } else {
            throw coerceError(v, "BigDecimal");
        }
    }

    public static BigInteger coerceToBigInteger(Object v)
        throws ELException
    {
        if (v == null) {
            return BigInteger.valueOf(0);
        } else if (v instanceof BigInteger) {
            return (BigInteger)v;
        } else if (v instanceof BigDecimal) {
            return ((BigDecimal)v).toBigInteger();
        } else if (v instanceof Decimal) {
            return ((Decimal)v).toBigDecimal().toBigInteger();
        } else if (v instanceof Rational) {
            return ((Rational)v).toBigInteger();
        } else if (v instanceof Number) {
            return BigInteger.valueOf(((Number)v).longValue());
        } else if (v instanceof Character) {
            return BigInteger.valueOf((short)((Character)v).charValue());
        } else if (v instanceof String) {
            String s = (String)v;
            if (s.length() == 0)
                return BigInteger.valueOf(0);
            return new BigInteger(s);
        } else if (v instanceof Coercible) {
            return selfCoerce((Coercible)v, BigInteger.class);
        } else {
            throw coerceError(v, "BigInteger");
        }
    }

    public static Double coerceToDouble(Object v)
        throws ELException
    {
        if (v == null) {
            return 0.0;
        } else if (v instanceof Double) {
            return (Double)v;
        } else if (v instanceof Number) {
            return ((Number)v).doubleValue();
        } else if (v instanceof Character) {
            return (double)(short)((Character)v).charValue();
        } else if (v instanceof String) {
            String s = (String)v;
            if (s.length() == 0)
                return 0.0;
            return Double.valueOf(s);
        } else if (v instanceof Coercible) {
            return selfCoerce((Coercible)v, Double.class);
        } else {
            throw coerceError(v, "Double");
        }
    }

    public static Float coerceToFloat(Object v)
        throws ELException
    {
        if (v == null) {
            return 0.0f;
        } else if (v instanceof Float) {
            return (Float)v;
        } else if (v instanceof Number) {
            return ((Number)v).floatValue();
        } else if (v instanceof Character) {
            return (float)(short)((Character)v).charValue();
        } else if (v instanceof String) {
            String s = (String)v;
            if (s.length() == 0)
                return 0f;
            return Float.valueOf(s);
        } else if (v instanceof Coercible) {
            return selfCoerce((Coercible)v, Float.class);
        } else {
            throw coerceError(v, "Float");
        }
    }

    public static Long coerceToLong(Object v)
        throws ELException
    {
        if (v == null) {
            return 0L;
        } else if (v instanceof Long) {
            return (Long)v;
        } else if (v instanceof Number) {
            return ((Number)v).longValue();
        } else if (v instanceof Character) {
            return (long)(short)((Character)v).charValue();
        } else if (v instanceof String) {
            String s = (String)v;
            if (s.length() == 0)
                return 0L;
            return Long.valueOf(s);
        } else if (v instanceof Coercible) {
            return selfCoerce((Coercible)v, Long.class);
        } else {
            throw coerceError(v, "Long");
        }
    }

    public static int coerceToInt(Object v)
        throws ELException
    {
        if (v == null) {
            return 0;
        } else if (v instanceof Integer) {
            return (Integer)v;
        } else if (v instanceof Number) {
            return ((Number)v).intValue();
        } else if (v instanceof Character) {
            return (Character)v;
        } else if (v instanceof String) {
            String s = (String)v;
            if (s.length() == 0)
                return 0;
            return Integer.valueOf(s);
        } else if (v instanceof Coercible) {
            return selfCoerce((Coercible)v, Integer.class);
        } else {
            throw coerceError(v, "Integer");
        }
    }

    public static Integer coerceToInteger(Object v)
        throws ELException
    {
        return coerceToInt(v);
    }

    public static Short coerceToShort(Object v)
        throws ELException
    {
        if (v == null) {
            return (short)0;
        } else if (v instanceof Short) {
            return (Short)v;
        } else if (v instanceof Number) {
            return ((Number)v).shortValue();
        } else if (v instanceof Character) {
            return (short)((Character)v).charValue();
        } else if (v instanceof String) {
            String s = (String)v;
            if (s.length() == 0)
                return (short)0;
            return Short.valueOf(s);
        } else if (v instanceof Coercible) {
            return selfCoerce((Coercible)v, Short.class);
        } else {
            throw coerceError(v, "Long");
        }
    }

    public static Byte coerceToByte(Object v)
        throws ELException
    {
        if (v == null) {
            return (byte)0;
        } else if (v instanceof Byte) {
            return (Byte)v;
        } else if (v instanceof Number) {
            return ((Number)v).byteValue();
        } else if (v instanceof Character) {
            return (byte)((Character)v).charValue();
        } else if (v instanceof String) {
            String s = (String)v;
            if (s.length() == 0)
                return (byte)0;
            return Byte.valueOf(s);
        } else if (v instanceof Coercible) {
            return selfCoerce((Coercible)v, Byte.class);
        } else {
            throw coerceError(v, "Byte");
        }
    }

    public static Character coerceToCharacter(Object v)
        throws ELException
    {
        if (v == null) {
            return '\0';
        } else if (v instanceof Character) {
            return (Character)v;
        } else if (v instanceof Number) {
            return (char)((Number)v).shortValue();
        } else if (v instanceof String) {
            String s = (String)v;
            if (s.length() == 0)
                return '\0';
            return s.charAt(0);
        } else if (v instanceof Coercible) {
            return selfCoerce((Coercible)v, Character.class);
        } else {
            throw coerceError(v, "Character");
        }
    }

    public static Boolean coerceToBoolean(Object v)
        throws ELException
    {
        if (v == null) {
            return Boolean.FALSE;
        } else if (v instanceof Boolean) {
            return (Boolean)v;
        } else if (v instanceof String) {
            String s = (String)v;
            if (s.length() == 0)
                return Boolean.FALSE;
            assert "true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s);
            return Boolean.valueOf(s);
        } else if (v instanceof Coercible) {
            return selfCoerce((Coercible)v, Boolean.class);
        } else {
            throw coerceError(v, "Boolean");
        }
    }

    @SuppressWarnings("unchecked")
    public static <E extends Enum<E>> Enum<E> coerceToEnum(Object v, Class<E> t) {
        if (v == null) {
            return null;
        } else if (t.isInstance(v)) {
            return (E)v;
        } else if (v instanceof String) {
            String s = (String)v;
            if (s.length() == 0)
                return null;
            return Enum.valueOf(t, s);
        } else if (v instanceof Coercible) {
            return selfCoerce((Coercible)v, t);
        } else {
            throw coerceError(v, t.getName());
        }
    }

    public static Seq coerceToSeq(Object v) {
        if (v == null) {
            return null;
        } else if (v instanceof Seq) {
            return (Seq)v;
        } else if (v instanceof List) {
            return ListSeq.make((List)v);
        } else if (v instanceof Iterable) {
            return IteratorSeq.make(((Iterable)v).iterator());
        } else if (v instanceof Object[]) {
            return ArraySeq.make((Object[])v);
        } else if (v.getClass().isArray()) {
            return PArraySeq.make(v);
        } else {
            throw coerceError(v, "Seq");
        }
    }

    public static boolean canCoerceToSeq(Object v) {
        return (v != null) && canCoerceToSeq(v.getClass());
    }

    public static boolean canCoerceToSeq(Class<?> t) {
        return t.isArray() || Iterable.class.isAssignableFrom(t);
    }

    @SuppressWarnings("unchecked")
    private static Object coerceToAny(ELContext ctx, Object v, Class<?> t)
        throws ELException
    {
        if (v == null) {
            return null;
        }

        if (v instanceof Coercible) {
            return selfCoerce((Coercible)v, t);
        }

        if (t.isEnum()) {
            return coerceToEnum(v, (Class<Enum>)t);
        }

        if (t == Seq.class || t == List.class) {
            return coerceToSeq(v);
        }

        if (v instanceof String) {
            String s = (String)v;
            PropertyEditor ed = PropertyEditorManager.findEditor(t);
            if (ed == null) {
                if (s.length() == 0) return null;
                throw coerceError(v, t.getName());
            } else {
                try {
                    ed.setAsText(s); return ed.getValue();
                } catch (IllegalArgumentException ex) {
                    if (s.length() == 0) return null;
                    throw ex;
                }
            }
        }

        if (v instanceof ClosureObject) {
            Object proxy = ((ClosureObject)v).get_proxy();
            if (t.isInstance(proxy)) {
                return proxy;
            }
        }

        if (t.isInterface() && (v instanceof Closure) && ((Closure)v).isProcedure()) {
            // Implicitly convert a procedure to a proxy object implements
            // the given interface, e.g.
            //    new Thread({=>print('hello')}).start()
            // will create a proxy object that implement Runnable
            // interface and run method invokes the procedure.
            final Closure proc = (Closure)v;
            final ELContext elctx = (ctx != null) ? ctx : ELEngine.getCurrentELContext();
            return Proxy.newProxyInstance(t.getClassLoader(), new Class[]{t},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args)
                        throws Throwable
                    {
                        if (args == null) args = ELUtils.NO_VALUES;
                        if (method.getDeclaringClass() == Object.class) {
                            return method.invoke(proc, args);
                        }

                        EvaluationContext context = proc.getContext(elctx);
                        Class type = method.getReturnType();
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

                        return (type==Void.TYPE) ? null : coerce(elctx, result, type);
                    }
                });
        }

        throw coerceError(v, t.getName());
    }

    public static Object coerce(Object v, Class<?> t) throws ELException {
        return coerce(null, v, t);
    }

    public static Object coerce(ELContext ctx, Object v, Class<?> t)
        throws ELException
    {
        // return null if the type is not primitive, even if the type is a boxed type
        if (v == null && !t.isPrimitive()) {
            return null;
        }

        // quick test
        if (t.isInstance(v)) {
            return v;
        }

        switch (typeof(t)) {
          case STRING_TYPE:
            return coerceToString(v);
          case BIG_DECIMAL_TYPE:
            return coerceToBigDecimal(ctx, v);
          case BIG_INTEGER_TYPE:
            return coerceToBigInteger(v);
          case DECIMAL_TYPE:
            return coerceToDecimal(v);
          case RATIONAL_TYPE:
            return coerceToRational(v);
          case DOUBLE_TYPE:
            return coerceToDouble(v);
          case FLOAT_TYPE:
            return coerceToFloat(v);
          case LONG_TYPE:
            return coerceToLong(v);
          case INT_TYPE:
            return coerceToInteger(v);
          case SHORT_TYPE:
            return coerceToShort(v);
          case BYTE_TYPE:
            return coerceToByte(v);
          case CHAR_TYPE:
            return coerceToCharacter(v);
          case BOOLEAN_TYPE:
            return coerceToBoolean(v);
          default:
            return coerceToAny(ctx, v, t);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T selfCoerce(Coercible value, Class<T> type) {
        if (type.isPrimitive()) {
            type = primitives.get(type);
        }

        Object result = value.coerce(type);
        if (result != null) {
            return type.cast(result);
        } else {
            throw coerceError(value, type.getName());
        }
    }

    /*
     * 根据类型之间的"距离"来选择类型之间的最佳匹配. 类型距离定义如下:
     *   1) 如果两个类型完全一致, 则它们之间的"距离"为0
     *   2) 对于数值类型, 两个类型之间的"距离"是从一个类型隐式升级到另一个类型所
     *      经过的步骤, 例如, 要将short升级为long, 需要先将short升级为int, 再
     *      将int升级为long, 这样short和long之间的"距离"就是2
     *   3) 对于非数值类型, 如果类s是类t的子类, 则s和t之间的距离是s继承t的类层次
     *      深度. 例如, 对于如下类关系:
     *          class A extends B {}
     *          class B extends C {}
     *      则A和B以及B和C之间的距离都是1, 而A和C之间的距离是2
     *
     * 对于重载方法, 总是选择实际参数和形式参数之间距离最小的匹配, 当多个重载方法
     * 只有一个形式参数不同时, 这一算法是准确的, 如果存在多个不同形式参数的重载方
     * 法, 这一算法有可能失败. 例如, 对于如下两个重载方法:
     *         void numericArgs(int i, byte b)
     *         void numericArgs(byte b, int i)
     * 如果实际参数是两个int类型的数值, 则以上两个方法都能匹配.
     */

    public static final int GUESSED_DISTANCE = 20;

    private static final int[][] DISTANCE = new int[UNKNOWN_TYPE+1][UNKNOWN_TYPE+1];

    public static int distanceof(final Class<?> s, final Class<?> t) {
        if (s == t)
            return 0;

        int sk = typeof(s);
        int tk = typeof(t);
        int d = DISTANCE[sk][tk];
        if (d != -1) {
            return d;
        }

        if (t.isAssignableFrom(s)) {
            Class<?> u = s;
            if (t.isInterface()) {
                d = 0;
                do {
                    int f = distanceof_interface(u, t);
                    if (f != -1) return d+f;
                    d++;
                } while((u = u.getSuperclass()) != null);
            } else {
                d = 0;
                while ((u = u.getSuperclass()) != null) {
                    d++;
                    if (u == t) return d;
                }
            }
        }

        if (Coercible.class.isAssignableFrom(s)) {
            return GUESSED_DISTANCE;
        } else if (sk == STRING_TYPE || tk == STRING_TYPE) {
            return GUESSED_DISTANCE + 1;
        } else if (t.isInterface() && Closure.class.isAssignableFrom(s)) {
            return GUESSED_DISTANCE;
        } else if ((t == Seq.class || t == List.class) &&
                   (s.isArray() || Iterable.class.isAssignableFrom(s))) {
            return GUESSED_DISTANCE;
        } else {
            return -1;
        }
    }

    private static int distanceof_interface(Class<?> s, Class<?> t) {
        if (s == t) return 0;
        for (Class<?> ifs : s.getInterfaces()) {
            int d = distanceof_interface(ifs, t);
            if (d != -1) return d+1;
        }
        return -1;
    }

    private static final void put(int t1, int t2, int d) {
        DISTANCE[t1][t2] = d;
    }

    // Initialize type distance table
    static {
        for (int i = 0; i < DISTANCE.length; i++) {
            Arrays.fill(DISTANCE[i], -1);
        }

        put(BYTE_TYPE, BYTE_TYPE, 0);
        put(BYTE_TYPE, SHORT_TYPE, 1);
        put(BYTE_TYPE, CHAR_TYPE, 2);
        put(BYTE_TYPE, INT_TYPE, 3);
        put(BYTE_TYPE, LONG_TYPE, 4);
        put(BYTE_TYPE, FLOAT_TYPE, 5);
        put(BYTE_TYPE, DOUBLE_TYPE, 6);
        put(BYTE_TYPE, DECIMAL_TYPE, 7);
        put(BYTE_TYPE, RATIONAL_TYPE, 8);
        put(BYTE_TYPE, BIG_INTEGER_TYPE, 9);
        put(BYTE_TYPE, BIG_DECIMAL_TYPE, 10);
        put(BYTE_TYPE, GENERIC_NUMBER_TYPE, 11);
        put(BYTE_TYPE, OBJECT_TYPE, 12);
        put(BYTE_TYPE, STRING_TYPE, 13);

        put(SHORT_TYPE, SHORT_TYPE, 0);
        put(SHORT_TYPE, CHAR_TYPE, 1);
        put(SHORT_TYPE, INT_TYPE, 2);
        put(SHORT_TYPE, LONG_TYPE, 3);
        put(SHORT_TYPE, FLOAT_TYPE, 4);
        put(SHORT_TYPE, DOUBLE_TYPE, 5);
        put(SHORT_TYPE, DECIMAL_TYPE, 6);
        put(SHORT_TYPE, RATIONAL_TYPE, 7);
        put(SHORT_TYPE, BIG_INTEGER_TYPE, 8);
        put(SHORT_TYPE, BIG_DECIMAL_TYPE, 9);
        put(SHORT_TYPE, GENERIC_NUMBER_TYPE, 10);
        put(SHORT_TYPE, OBJECT_TYPE, 11);
        put(SHORT_TYPE, STRING_TYPE, 12);
        put(SHORT_TYPE, BYTE_TYPE, 13);

        put(INT_TYPE, INT_TYPE, 0);
        put(INT_TYPE, LONG_TYPE, 1);
        put(INT_TYPE, FLOAT_TYPE, 2);
        put(INT_TYPE, DOUBLE_TYPE, 3);
        put(INT_TYPE, DECIMAL_TYPE, 4);
        put(INT_TYPE, RATIONAL_TYPE, 5);
        put(INT_TYPE, BIG_INTEGER_TYPE, 6);
        put(INT_TYPE, BIG_DECIMAL_TYPE, 7);
        put(INT_TYPE, GENERIC_NUMBER_TYPE, 8);
        put(INT_TYPE, OBJECT_TYPE, 9);
        put(INT_TYPE, STRING_TYPE, 10);
        put(INT_TYPE, SHORT_TYPE, 11);
        put(INT_TYPE, CHAR_TYPE, 12);
        put(INT_TYPE, BYTE_TYPE, 13);

        put(LONG_TYPE, LONG_TYPE, 0);
        put(LONG_TYPE, FLOAT_TYPE, 1);
        put(LONG_TYPE, DOUBLE_TYPE, 2);
        put(LONG_TYPE, DECIMAL_TYPE, 3);
        put(LONG_TYPE, RATIONAL_TYPE, 4);
        put(LONG_TYPE, BIG_INTEGER_TYPE, 5);
        put(LONG_TYPE, BIG_DECIMAL_TYPE, 6);
        put(LONG_TYPE, GENERIC_NUMBER_TYPE, 7);
        put(LONG_TYPE, OBJECT_TYPE, 8);
        put(LONG_TYPE, STRING_TYPE, 9);
        put(LONG_TYPE, INT_TYPE, 10);
        put(LONG_TYPE, SHORT_TYPE, 11);
        put(LONG_TYPE, CHAR_TYPE, 12);
        put(LONG_TYPE, BYTE_TYPE, 13);

        put(FLOAT_TYPE, FLOAT_TYPE, 0);
        put(FLOAT_TYPE, DOUBLE_TYPE, 1);
        put(FLOAT_TYPE, BIG_DECIMAL_TYPE, 2);
        put(FLOAT_TYPE, DECIMAL_TYPE, 3);
        put(FLOAT_TYPE, RATIONAL_TYPE, 4);
        put(FLOAT_TYPE, BIG_INTEGER_TYPE, 5);
        put(FLOAT_TYPE, GENERIC_NUMBER_TYPE, 6);
        put(FLOAT_TYPE, OBJECT_TYPE, 7);
        put(FLOAT_TYPE, STRING_TYPE, 8);
        put(FLOAT_TYPE, LONG_TYPE, 9);
        put(FLOAT_TYPE, INT_TYPE, 10);
        put(FLOAT_TYPE, SHORT_TYPE, 11);
        put(FLOAT_TYPE, CHAR_TYPE, 12);
        put(FLOAT_TYPE, BYTE_TYPE, 13);

        put(DOUBLE_TYPE, DOUBLE_TYPE, 0);
        put(DOUBLE_TYPE, BIG_DECIMAL_TYPE, 1);
        put(DOUBLE_TYPE, DECIMAL_TYPE, 2);
        put(DOUBLE_TYPE, RATIONAL_TYPE, 3);
        put(DOUBLE_TYPE, FLOAT_TYPE, 4);
        put(DOUBLE_TYPE, BIG_INTEGER_TYPE, 5);
        put(DOUBLE_TYPE, GENERIC_NUMBER_TYPE, 6);
        put(DOUBLE_TYPE, OBJECT_TYPE, 7);
        put(DOUBLE_TYPE, STRING_TYPE, 8);
        put(DOUBLE_TYPE, LONG_TYPE, 9);
        put(DOUBLE_TYPE, INT_TYPE, 10);
        put(DOUBLE_TYPE, SHORT_TYPE, 11);
        put(DOUBLE_TYPE, CHAR_TYPE, 12);
        put(DOUBLE_TYPE, BYTE_TYPE, 13);

        put(DECIMAL_TYPE, DECIMAL_TYPE, 0);
        put(DECIMAL_TYPE, BIG_DECIMAL_TYPE, 1);
        put(DECIMAL_TYPE, RATIONAL_TYPE, 2);
        put(DECIMAL_TYPE, DOUBLE_TYPE, 3);
        put(DECIMAL_TYPE, FLOAT_TYPE, 4);
        put(DECIMAL_TYPE, GENERIC_NUMBER_TYPE, 5);
        put(DECIMAL_TYPE, OBJECT_TYPE, 6);
        put(DECIMAL_TYPE, STRING_TYPE, 7);
        put(DECIMAL_TYPE, BIG_INTEGER_TYPE, 8);
        put(DECIMAL_TYPE, LONG_TYPE, 9);
        put(DECIMAL_TYPE, INT_TYPE, 10);
        put(DECIMAL_TYPE, SHORT_TYPE, 11);
        put(DECIMAL_TYPE, CHAR_TYPE, 12);
        put(DECIMAL_TYPE, BYTE_TYPE, 13);

        put(RATIONAL_TYPE, RATIONAL_TYPE, 0);
        put(RATIONAL_TYPE, BIG_DECIMAL_TYPE, 1);
        put(RATIONAL_TYPE, DECIMAL_TYPE, 2);
        put(RATIONAL_TYPE, DOUBLE_TYPE, 3);
        put(RATIONAL_TYPE, FLOAT_TYPE, 4);
        put(RATIONAL_TYPE, GENERIC_NUMBER_TYPE, 5);
        put(RATIONAL_TYPE, OBJECT_TYPE, 6);
        put(RATIONAL_TYPE, STRING_TYPE, 7);
        put(RATIONAL_TYPE, BIG_INTEGER_TYPE, 8);
        put(RATIONAL_TYPE, LONG_TYPE, 9);
        put(RATIONAL_TYPE, INT_TYPE, 10);
        put(RATIONAL_TYPE, SHORT_TYPE, 11);
        put(RATIONAL_TYPE, CHAR_TYPE, 12);
        put(RATIONAL_TYPE, BYTE_TYPE, 13);

        put(BIG_INTEGER_TYPE, BIG_INTEGER_TYPE, 0);
        put(BIG_INTEGER_TYPE, RATIONAL_TYPE, 1);
        put(BIG_INTEGER_TYPE, BIG_DECIMAL_TYPE, 2);
        put(BIG_INTEGER_TYPE, DECIMAL_TYPE, 3);
        put(BIG_INTEGER_TYPE, DOUBLE_TYPE, 4);
        put(BIG_INTEGER_TYPE, FLOAT_TYPE, 5);
        put(BIG_INTEGER_TYPE, GENERIC_NUMBER_TYPE, 6);
        put(BIG_INTEGER_TYPE, OBJECT_TYPE, 7);
        put(BIG_INTEGER_TYPE, STRING_TYPE, 8);
        put(BIG_INTEGER_TYPE, LONG_TYPE, 9);
        put(BIG_INTEGER_TYPE, INT_TYPE, 10);
        put(BIG_INTEGER_TYPE, SHORT_TYPE, 11);
        put(BIG_INTEGER_TYPE, CHAR_TYPE, 12);
        put(BIG_INTEGER_TYPE, BYTE_TYPE, 13);

        put(BIG_DECIMAL_TYPE, BIG_DECIMAL_TYPE, 0);
        put(BIG_DECIMAL_TYPE, DECIMAL_TYPE, 1);
        put(BIG_DECIMAL_TYPE, RATIONAL_TYPE, 2);
        put(BIG_DECIMAL_TYPE, DOUBLE_TYPE, 3);
        put(BIG_DECIMAL_TYPE, FLOAT_TYPE, 4);
        put(BIG_DECIMAL_TYPE, GENERIC_NUMBER_TYPE, 5);
        put(BIG_DECIMAL_TYPE, OBJECT_TYPE, 6);
        put(BIG_DECIMAL_TYPE, STRING_TYPE, 7);
        put(BIG_DECIMAL_TYPE, BIG_INTEGER_TYPE, 8);
        put(BIG_DECIMAL_TYPE, LONG_TYPE, 9);
        put(BIG_DECIMAL_TYPE, INT_TYPE, 10);
        put(BIG_DECIMAL_TYPE, SHORT_TYPE, 11);
        put(BIG_DECIMAL_TYPE, CHAR_TYPE, 12);
        put(BIG_DECIMAL_TYPE, BYTE_TYPE, 13);

        put(GENERIC_NUMBER_TYPE, GENERIC_NUMBER_TYPE, 0);
        put(GENERIC_NUMBER_TYPE, OBJECT_TYPE, 1);
        put(GENERIC_NUMBER_TYPE, BIG_DECIMAL_TYPE, 2);
        put(GENERIC_NUMBER_TYPE, DECIMAL_TYPE, 3);
        put(GENERIC_NUMBER_TYPE, RATIONAL_TYPE, 4);
        put(GENERIC_NUMBER_TYPE, BIG_INTEGER_TYPE, 5);
        put(GENERIC_NUMBER_TYPE, DOUBLE_TYPE, 6);
        put(GENERIC_NUMBER_TYPE, FLOAT_TYPE, 7);
        put(GENERIC_NUMBER_TYPE, LONG_TYPE, 8);
        put(GENERIC_NUMBER_TYPE, INT_TYPE, 9);
        put(GENERIC_NUMBER_TYPE, SHORT_TYPE, 10);
        put(GENERIC_NUMBER_TYPE, CHAR_TYPE, 12);
        put(GENERIC_NUMBER_TYPE, BYTE_TYPE, 13);
        put(GENERIC_NUMBER_TYPE, STRING_TYPE, 14);

        put(STRING_TYPE, STRING_TYPE, 0);
        put(STRING_TYPE, CHAR_TYPE, 1);
        put(STRING_TYPE, OBJECT_TYPE, 2);
        put(STRING_TYPE, BIG_DECIMAL_TYPE, 3);
        put(STRING_TYPE, BIG_INTEGER_TYPE, 4);
        put(STRING_TYPE, DECIMAL_TYPE, 5);
        put(STRING_TYPE, RATIONAL_TYPE, 6);
        put(STRING_TYPE, DOUBLE_TYPE, 7);
        put(STRING_TYPE, FLOAT_TYPE, 8);
        put(STRING_TYPE, LONG_TYPE, 9);
        put(STRING_TYPE, INT_TYPE, 10);
        put(STRING_TYPE, SHORT_TYPE, 11);
        put(STRING_TYPE, BYTE_TYPE, 12);
        put(STRING_TYPE, BOOLEAN_TYPE, 13);

        put(CHAR_TYPE, CHAR_TYPE, 0);
        put(CHAR_TYPE, SHORT_TYPE, 1);
        put(CHAR_TYPE, INT_TYPE, 2);
        put(CHAR_TYPE, LONG_TYPE, 4);
        put(CHAR_TYPE, STRING_TYPE, 5);
        put(CHAR_TYPE, BYTE_TYPE, 6);
        put(CHAR_TYPE, FLOAT_TYPE, 7);
        put(CHAR_TYPE, DOUBLE_TYPE, 8);
        put(CHAR_TYPE, BIG_INTEGER_TYPE, 9);
        put(CHAR_TYPE, DECIMAL_TYPE, 10);
        put(CHAR_TYPE, RATIONAL_TYPE, 11);
        put(CHAR_TYPE, BIG_DECIMAL_TYPE, 12);
        put(CHAR_TYPE, OBJECT_TYPE, 13);

        put(BOOLEAN_TYPE, BOOLEAN_TYPE, 0);
        put(BOOLEAN_TYPE, STRING_TYPE, 1);
        put(BOOLEAN_TYPE, OBJECT_TYPE, 2);

        put(UNKNOWN_TYPE, UNKNOWN_TYPE, -1);
    }

    private static ELException coerceError(Object v, String t) {
        return new ELException(_T(JSPRT_COERCE_ERROR, v.getClass().getName(), t));
    }

    private TypeCoercion() {}
}
