/*
 * Copyright (c) 2006-2011 Daniel Yuan.
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
import java.lang.reflect.Array;

import elite.lang.Range;
import elite.lang.Seq;
import elite.lang.Decimal;
import elite.lang.Rational;
import org.operamasks.el.eval.seq.ListSeq;
import org.operamasks.el.eval.seq.IteratorSeq;
import org.operamasks.el.eval.seq.ArraySeq;
import org.operamasks.el.eval.seq.PArraySeq;
import org.operamasks.el.eval.closure.ClosureObject;
import static org.operamasks.el.resources.Resources.*;

/**
 * @deprecated replaced by {@link TypeCoercion}
 */
@Deprecated
public final class Coercion
{
    @SuppressWarnings("unchecked")
    private static Map<Class,Integer> typeMap = new IdentityHashMap<Class,Integer>();

    public static final int NULL_TYPE              = 0;
    public static final int BOOLEAN_PRIMITIVE_TYPE = 1;
    public static final int BOOLEAN_BOXED_TYPE     = 2;
    public static final int BYTE_PRIMITIVE_TYPE    = 3;
    public static final int BYTE_BOXED_TYPE        = 4;
    public static final int CHAR_PRIMITIVE_TYPE    = 5;
    public static final int CHAR_BOXED_TYPE        = 6;
    public static final int SHORT_PRIMITIVE_TYPE   = 7;
    public static final int SHORT_BOXED_TYPE       = 8;
    public static final int INT_PRIMITIVE_TYPE     = 9;
    public static final int INT_BOXED_TYPE         = 10;
    public static final int LONG_PRIMITIVE_TYPE    = 11;
    public static final int LONG_BOXED_TYPE        = 12;
    public static final int FLOAT_PRIMITIVE_TYPE   = 13;
    public static final int FLOAT_BOXED_TYPE       = 14;
    public static final int DOUBLE_PRIMITIVE_TYPE  = 15;
    public static final int DOUBLE_BOXED_TYPE      = 16;
    public static final int STRING_TYPE            = 17;
    public static final int BIG_DECIMAL_TYPE       = 18;
    public static final int BIG_INTEGER_TYPE       = 19;
    public static final int ENUM_TYPE              = 20;
    public static final int OBJECT_TYPE            = 21;
    public static final int DECIMAL_TYPE           = 22;
    public static final int RATIONAL_TYPE          = 23;
    public static final int UNKNOWN_TYPE           = 24;

    static {
        typeMap.put(Boolean.TYPE, BOOLEAN_PRIMITIVE_TYPE);
        typeMap.put(Boolean.class, BOOLEAN_BOXED_TYPE);
        typeMap.put(Byte.TYPE, BYTE_PRIMITIVE_TYPE);
        typeMap.put(Byte.class, BYTE_BOXED_TYPE);
        typeMap.put(Character.TYPE, CHAR_PRIMITIVE_TYPE);
        typeMap.put(Character.class, CHAR_BOXED_TYPE);
        typeMap.put(Short.TYPE, SHORT_PRIMITIVE_TYPE);
        typeMap.put(Short.class, SHORT_BOXED_TYPE);
        typeMap.put(Integer.TYPE, INT_PRIMITIVE_TYPE);
        typeMap.put(Integer.class, INT_BOXED_TYPE);
        typeMap.put(Long.TYPE, LONG_PRIMITIVE_TYPE);
        typeMap.put(Long.class, LONG_BOXED_TYPE);
        typeMap.put(Float.TYPE, FLOAT_PRIMITIVE_TYPE);
        typeMap.put(Float.class, FLOAT_BOXED_TYPE);
        typeMap.put(Double.TYPE, DOUBLE_PRIMITIVE_TYPE);
        typeMap.put(Double.class, DOUBLE_BOXED_TYPE);
        typeMap.put(BigDecimal.class, BIG_DECIMAL_TYPE);
        typeMap.put(BigInteger.class, BIG_INTEGER_TYPE);
        typeMap.put(String.class, STRING_TYPE);
        typeMap.put(Object.class, OBJECT_TYPE);
        typeMap.put(Decimal.class, DECIMAL_TYPE);
        typeMap.put(Rational.class, RATIONAL_TYPE);
    }

    public static int typeof(Class<?> t) {
        if (t == null) {
            return NULL_TYPE;
        } else {
            Integer type = typeMap.get(t);
            if (type != null)
                return type;
            if (t.isEnum())
                return ENUM_TYPE;
            return UNKNOWN_TYPE;
        }
    }

    public static int typeof(Object o) {
        if (o == null) {
            return NULL_TYPE;
        } else {
            Integer type = typeMap.get(o.getClass());
            if (type != null)
                return type;
            if (Enum.class.isInstance(o))
                return ENUM_TYPE;
            return UNKNOWN_TYPE;
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

    public static void escape(StringBuilder buf, String s) {
        boolean escaped = false;
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            String esc = null;
            switch (c) {
            case '\r': esc = "\\r";  break;
            case '\n': esc = "\\n";  break;
            case '\f': esc = "\\f";  break;
            case '\b': esc = "\\b";  break;
            case '\t': esc = "\\t";  break;
            case '\\': esc = "\\\\"; break;
            case '\'': esc = "'";    break;
            case '"':  esc = "\\\""; break;
            
            case 0: case 1: case 2: case 3: case 4: case 5: case 6: case 7:
                esc = "\\00" + Integer.toOctalString(c);
                break;
            case 11: case 14: case 15:
            case 16: case 17: case 18: case 19: case 20: case 21: case 22: case 23:
            case 24: case 25: case 26: case 27: case 28: case 29: case 30: case 31:
                esc = "\\0" + Integer.toOctalString(c);
                break;
            }

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
        } else {
            throw coerceError(v, "BigDecimal");
        }
    }

    public static BigDecimal coerceToBigDecimal(Object v, MathContext mc) {
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
            return Boolean.valueOf(s);
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
    
    public static Object coerceToAny(Object v, Class<?> t)
        throws ELException
    {
	if (v == null || t.isInstance(v)) {
	    return v;
        } else if (t == Seq.class || t == List.class) {
            return coerceToSeq(v);
        } else if (v instanceof String) {
	    String s = (String)v;
	    PropertyEditor editor = PropertyEditorManager.findEditor(t);
	    if (editor == null) {
		if (s.length() == 0) return null;
		throw coerceError(v, t.getName());
	    } else {
		try {
		    editor.setAsText(s);
		    return editor.getValue();
		} catch (IllegalArgumentException ex) {
		    if (s.length() == 0) return null;
		    throw ex;
		}
	    }
	} else if (v instanceof ClosureObject) {
            Object proxy = ((ClosureObject)v).get_proxy();
            if (t.isInstance(proxy)) {
                return proxy;
            }
        }

        throw coerceError(v, t.getName());
    }

    @SuppressWarnings("unchecked")
    public static Object coerce(Object v, Class<?> t)
        throws ELException
    {
        // quick test
        if (t.isInstance(v)) {
            return v;
        }

        switch (typeof(t)) {
          case STRING_TYPE:
	    return coerceToString(v);
          case BIG_DECIMAL_TYPE:
	    return coerceToBigDecimal(v);
          case BIG_INTEGER_TYPE:
	    return coerceToBigInteger(v);
          case DECIMAL_TYPE:
            return coerceToDecimal(v);
          case RATIONAL_TYPE:
            return coerceToRational(v);
          case DOUBLE_PRIMITIVE_TYPE:
          case DOUBLE_BOXED_TYPE:
	    return coerceToDouble(v);
          case FLOAT_PRIMITIVE_TYPE:
          case FLOAT_BOXED_TYPE:
	    return coerceToFloat(v);
          case LONG_PRIMITIVE_TYPE:
          case LONG_BOXED_TYPE:
	    return coerceToLong(v);
          case INT_PRIMITIVE_TYPE:
          case INT_BOXED_TYPE:
	    return coerceToInteger(v);
          case SHORT_PRIMITIVE_TYPE:
          case SHORT_BOXED_TYPE:
	    return coerceToShort(v);
          case BYTE_PRIMITIVE_TYPE:
          case BYTE_BOXED_TYPE:
	    return coerceToByte(v);
          case CHAR_PRIMITIVE_TYPE:
          case CHAR_BOXED_TYPE:
	    return coerceToCharacter(v);
          case BOOLEAN_PRIMITIVE_TYPE:
          case BOOLEAN_BOXED_TYPE:
	    return coerceToBoolean(v);
          case ENUM_TYPE:
            return coerceToEnum(v, (Class<Enum>)t);
          default:
	    return coerceToAny(v, t);
	}
    }

    private static ELException coerceError(Object v, String t) {
        return new ELException(_T(JSPRT_COERCE_ERROR, v.getClass().getName(), t));
    }

    private Coercion() {}
}
