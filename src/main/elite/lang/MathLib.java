/*
 * $Id: MathLib.java,v 1.7 2009/05/07 16:57:04 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package elite.lang;

import java.math.BigInteger;
import java.math.BigDecimal;
import javax.el.ELException;
import javax.el.ELContext;

import org.operamasks.el.eval.closure.ClosureObject;
import static org.operamasks.el.eval.TypeCoercion.*;
import static org.operamasks.el.eval.ELUtils.*;
import static elite.lang.Builtin.*;

/**
 * Numeric functions.
 */
public final class MathLib
{
    private MathLib() {}

    /**
     * 将数值转换成十六进制格式的字符串
     *
     * 用法: hex(number)
     *      number.hex()
     *      number->hex
     */
    public static String hex(Number x) {
        if (x instanceof BigInteger) {
            return ((BigInteger)x).toString(16);
        } else if (x instanceof BigDecimal) {
            return ((BigDecimal)x).toBigInteger().toString(16);
        } else {
            return Long.toHexString(x.longValue());
        }
    }

    /**
     * 将数值转换成八进制格式的字符串
     *
     * 用法: oct(number)
     *      number.oct()
     *      number->oct
     */
    public static String oct(Number x) {
        if (x instanceof BigInteger) {
            return ((BigInteger)x).toString(8);
        } else if (x instanceof BigDecimal) {
            return ((BigDecimal)x).toBigInteger().toString(8);
        } else {
            return Long.toOctalString(x.longValue());
        }
    }

    /**
     * 将参数值连续相加.
     */
    public static Object sum(ELContext elctx, Object... args) {
        if (args.length == 0) {
            throw new ELException("sum: expect at least 1 argument, given 0");
        }

        Object res = args[0];
        for (int i = 1; i < args.length; i++) {
            res = __add__(elctx, res, args[i]);
        }
        return res;
    }

    /**
     * 将参数值连续相减.
     */
    public static Object difference(ELContext elctx, Object... args) {
        if (args.length == 0) {
            throw new ELException("difference: expect at least 1 argument, given 0");
        }

        Object res = args[0];
        for (int i = 1; i < args.length; i++) {
            res = __sub__(elctx, res, args[i]);
        }
        return res;
    }

    /**
     * 将参数值连续相乘.
     */
    public static Object product(ELContext elctx, Object... args) {
        if (args.length == 0) {
            throw new ELException("product: expect at least 1 argument, given 0");
        }

        Object res = args[0];
        for (int i = 1; i < args.length; i++) {
            res = __mul__(elctx, res, args[i]);
        }
        return res;
    }

    /**
     * 将参数值连续相除.
     */
    public static Object divide(ELContext elctx, Object... args) {
        if (args.length == 0) {
            throw new ELException("divide: expect at least 1 argument, given 0");
        }

        Object res = args[0];
        for (int i = 1; i < args.length; i++) {
            res = __div__(elctx, res, args[i]);
        }
        return res;
    }

    /**
     * 计算两个数值的余数.
     */
    public static Object remainder(ELContext elctx, Object x, Object y) {
        return __rem__(elctx, x, y);
    }

    /**
     * 计算两个数值的商和余数.
     */
    public static Number[] divmod(Number x, Number y) {
        if ((x instanceof Float || y instanceof Float) ||
            (x instanceof Double || y instanceof Double)) {
            double xx = coerceToDouble(x);
            double yy = coerceToDouble(y);
            return new Number[] {Math.rint(xx / yy), xx % yy};
        } else if (x instanceof BigDecimal || y instanceof BigDecimal) {
            BigDecimal xx = coerceToBigDecimal(x);
            BigDecimal yy = coerceToBigDecimal(y);
            return xx.divideAndRemainder(yy);
        } else if (x instanceof Decimal || y instanceof Decimal) {
            Decimal xx = coerceToDecimal(x);
            Decimal yy = coerceToDecimal(y);
            return xx.divideAndRemainder(yy);
        } else if (x instanceof Rational || y instanceof Rational) {
            Rational xx = coerceToRational(x);
            Rational yy = coerceToRational(y);
            return xx.divideAndRemainder(yy);
        } else if (x instanceof BigInteger || y instanceof BigInteger) {
            BigInteger xx = coerceToBigInteger(x);
            BigInteger yy = coerceToBigInteger(y);
            return xx.divideAndRemainder(yy);
        } else {
            long xx = coerceToLong(x);
            long yy = coerceToLong(y);
            return new Number[] {xx / yy, xx % yy};
        }
    }

    /**
     * 找出给定参数中的最大值.
     */
    public static Object max(ELContext elctx, Object... args) {
        if (args.length == 0) {
            throw new ELException("max: expect at least 1 argument, given 0");
        }

        Object res = args[0];
        for (int i = 1; i < args.length; i++) {
            Object x = args[i];
            if (__gt__(elctx, x, res))
                res = x;
        }
        return res;
    }

    /**
     * 找出给定参数中的最小值.
     */
    public static Object min(ELContext elctx, Object... args) {
        if (args.length == 0) {
            throw new ELException("min: expect at least 1 argument, given 0");
        }

        Object res = args[0];
        for (int i = 1; i < args.length; i++) {
            Object x = args[i];
            if (__lt__(elctx, x, res))
                res = x;
        }
        return res;
    }

    /**
     * 比较两个数a和b的大小, 当a>b时返回1, 当a<b时返回-1, 当a==b时返回0.
     */
    @SuppressWarnings("unchecked")
    public static int compare(ELContext elctx, Object x, Object y) {
        if (x instanceof Comparable && x.getClass().isInstance(y)) {
            return ((Comparable)x).compareTo(y);
        }

        if (__eq__(elctx, x, y)) {
            return 0;
        } else if (__lt__(elctx, x, y)) {
            return -1;
        } else {
            return 1;
        }
    }

    /**
     * 计算绝对值.
     */
    public static Object abs(ELContext elctx, Number x) {
        if (x instanceof BigDecimal) {
            return ((BigDecimal)x).abs();
        } else if (x instanceof BigInteger) {
            return ((BigInteger)x).abs();
        } else if (x instanceof Decimal) {
            return ((Decimal)x).abs();
        } else if (x instanceof Rational) {
            return ((Rational)x).abs();
        } else if (x instanceof Double) {
            return Math.abs(x.doubleValue());
        } else if (x instanceof Float) {
            return Math.abs(x.floatValue());
        } else if (x instanceof Long) {
            return Math.abs(x.longValue());
        } else if (x instanceof Integer) {
            return Math.abs(x.intValue());
        } else if (x instanceof Short) {
            return Math.abs(x.intValue());
        } else if (x instanceof Byte) {
            return Math.abs(x.intValue());
        } else {
            if (x instanceof ClosureObject) {
                Object result = ((ClosureObject)x).invokeSpecial(elctx, "abs", NO_PARAMS);
                if (result != NO_RESULT) return result;
            }
            return Math.abs(x.doubleValue());
        }
    }

    /**
     * 计算数值的符号, 1 表示正数, -1 表示负数, 0 表示零.
     */
    public static int signum(ELContext elctx, Object x) {
        if (x instanceof BigDecimal) {
            return ((BigDecimal)x).signum();
        } else if (x instanceof BigInteger) {
            return ((BigInteger)x).signum();
        } else if (x instanceof Decimal) {
            return ((Decimal)x).signum();
        } else if (x instanceof Rational) {
            return ((Rational)x).signum();
        } else if (x instanceof Double) {
            return Double.compare((Double)x, 0.0);
        } else if (x instanceof Float) {
            return Float.compare((Float)x, 0.0f);
        } else if (x instanceof Long) {
            long l = (Long)x;
            return (l > 0) ? 1 : (l < 0) ? -1 : 0;
        } else if (x instanceof Integer) {
            int i = (Integer)x;
            return (i > 0) ? 1 : (i < 0) ? -1 : 0;
        } else if (x instanceof Short) {
            short i = (Short)x;
            return (i > 0) ? 1 : (i < 0) ? -1 : 0;
        } else if (x instanceof Byte) {
            short i = (Byte)x;
            return (i > 0) ? 1 : (i < 0) ? -1 : 0;
        } else {
            if (__eq__(elctx, x, 0)) {
                return 0;
            } else if (__lt__(elctx, x, 0)) {
                return -1;
            } else {
                return 1;
            }
        }
    }

    /**
     * 计算最大公约数.
     */
    public static Object gcd(ELContext elctx, Object... args) {
        if (args.length < 2) {
            throw new ELException("gcd: expect at least 2 argument, given " + args.length);
        }

        Object res = args[0];
        for (int i = 1; i < args.length; i++) {
            res = gcd2(elctx, res, args[i]);
            if (res.equals(1)) {
                break;
            }
        }
        return res;
    }

    /**
     * 计算最小公倍数.
     */
    public static Object lcm(ELContext elctx, Object... args) {
        if (args.length < 2) {
            throw new ELException("lcm: expect at least 2 argument, given " + args.length);
        }

        Object a = args[0];
        for (int i = 1; i < args.length; i++) {
            Object b = args[i];
            a = __mul__(elctx, __div__(elctx, a, gcd2(elctx, a, b)), b);
        }
        return a;
    }

    static Object gcd2(ELContext elctx, Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            if (a instanceof Rational || b instanceof Rational) {
                Rational g = coerceToRational(a).gcd(coerceToRational(b));
                return g.equals(Rational.ONE) ? 1 : g;
            } else if (a instanceof BigInteger || b instanceof BigInteger) {
                BigInteger g = coerceToBigInteger(a).gcd(coerceToBigInteger(b));
                return g.equals(BigInteger.ONE) ? 1 : g;
            } else if (a instanceof BigDecimal || b instanceof BigDecimal) {
                BigInteger g = coerceToBigInteger(a).gcd(coerceToBigInteger(b));
                return g.equals(BigInteger.ONE) ? 1 : g;
            } else {
                long m = Math.abs(((Number)a).longValue());
                long n = Math.abs(((Number)b).longValue());
                long r;
                while (n != 0) {
                    r = m % n; m = n; n = r;
                }
                return m;
            }
        } else {
            Object r;
            while (!__eq__(elctx, b, 0)) {
                r = __rem__(elctx, a, b);
                a = b;
                b = r;
            }
            return a;
        }
    }

    public static double floor(double x) { return Math.floor(x); }
    public static double ceiling(double x) { return Math.ceil(x); }
    public static double truncate(double x) { return (double)(int)x; }
    public static long   round(double x) { return Math.round(x); }

    public static Object exp(ELContext elctx, Number x) {
        if (x instanceof ClosureObject) {
            Object result = ((ClosureObject)x).invokeSpecial(elctx, "exp", NO_PARAMS);
            if (result != NO_RESULT) return result;
        }
        return Math.exp(x.doubleValue());
    }

    public static Object log(ELContext elctx, Number x) {
        if (x instanceof ClosureObject) {
            Object result = ((ClosureObject)x).invokeSpecial(elctx, "log", NO_PARAMS);
            if (result != NO_RESULT) return result;
        }
        return Math.log(x.doubleValue());
    }

    public static Object log10(ELContext elctx, Number x) {
        if (x instanceof ClosureObject) {
            Object result = ((ClosureObject)x).invokeSpecial(elctx, "log10", NO_PARAMS);
            if (result != NO_RESULT) return result;
        }
        return Math.log10(x.doubleValue());
    }

    public static Object sin(ELContext elctx, Number x) {
        if (x instanceof ClosureObject) {
            Object result = ((ClosureObject)x).invokeSpecial(elctx, "sin", NO_PARAMS);
            if (result != NO_RESULT) return result;
        }
        return Math.sin(x.doubleValue());
    }

    public static Object cos(ELContext elctx, Number x) {
        if (x instanceof ClosureObject) {
            Object result = ((ClosureObject)x).invokeSpecial(elctx, "cos", NO_PARAMS);
            if (result != NO_RESULT) return result;
        }
        return Math.cos(x.doubleValue());
    }
    
    public static Object tan(ELContext elctx, Number x) {
        if (x instanceof ClosureObject) {
            Object result = ((ClosureObject)x).invokeSpecial(elctx, "tan", NO_PARAMS);
            if (result != NO_RESULT) return result;
        }
        return Math.tan(x.doubleValue());
    }

    public static Object asin(ELContext elctx, Number x) {
        if (x instanceof ClosureObject) {
            Object result = ((ClosureObject)x).invokeSpecial(elctx, "asin", NO_PARAMS);
            if (result != NO_RESULT) return result;
        }
        return Math.asin(x.doubleValue());
    }

    public static Object acos(ELContext elctx, Number x) {
        if (x instanceof ClosureObject) {
            Object result = ((ClosureObject)x).invokeSpecial(elctx, "acos", NO_PARAMS);
            if (result != NO_RESULT) return result;
        }
        return Math.acos(x.doubleValue());
    }

    public static Object atan(ELContext elctx, Number x) {
        if (x instanceof ClosureObject) {
            Object result = ((ClosureObject)x).invokeSpecial(elctx, "atan", NO_PARAMS);
            if (result != NO_RESULT) return result;
        }
        return Math.atan(x.doubleValue());
    }

    public static double atan2(double y, double x) {
        return Math.atan2(y, x);
    }

    public static Object sinh(ELContext elctx, Number x) {
        if (x instanceof ClosureObject) {
            Object result = ((ClosureObject)x).invokeSpecial(elctx, "sinh", NO_PARAMS);
            if (result != NO_RESULT) return result;
        }
        return Math.sinh(x.doubleValue());
    }

    public static Object cosh(ELContext elctx, Number x) {
        if (x instanceof ClosureObject) {
            Object result = ((ClosureObject)x).invokeSpecial(elctx, "cosh", NO_PARAMS);
            if (result != NO_RESULT) return result;
        }
        return Math.cosh(x.doubleValue());
    }

    public static Object tanh(ELContext elctx, Number x) {
        if (x instanceof ClosureObject) {
            Object result = ((ClosureObject)x).invokeSpecial(elctx, "tanh", NO_PARAMS);
            if (result != NO_RESULT) return result;
        }
        return Math.tanh(x.doubleValue());
    }

    public static Object sqrt(ELContext elctx, Number x) {
        if (x instanceof ClosureObject) {
            Object result = ((ClosureObject)x).invokeSpecial(elctx, "sqrt", NO_PARAMS);
            if (result != NO_RESULT) return result;
        }
        return Math.sqrt(x.doubleValue());
    }

    public static Object pow(ELContext elctx, Object x, Object y) {
        return Builtin.__pow__(elctx, x, y);
    }

    private static final long D_MASK  = 0x7ffL;
    private static final long D_BIAS  = 0x3FEL;
    private static final int  D_SHIFT = (64-11-1);

    /**
     * 将浮点数x分解成尾数和指数: x=m*2^e
     * m为规范化小数
     * 返回元组(m,e)
     */
    public static double[] frexp(double d) {
        if (d == 0.0) {
            return new double[]{0.0, 0.0};
        }

        long x = Double.doubleToLongBits(d);
        int e = (int)(((x >>> D_SHIFT) & D_MASK) - D_BIAS);
        x &= ~(D_MASK << D_SHIFT);
        x |= D_BIAS << D_SHIFT;
        d = Double.longBitsToDouble(x);
        return new double[]{d, e};
    }

    /**
     * 以指定尾数和指数装载浮点数.
     */
    public static double ldexp(double d, int e) {
        if (d == 0.0) {
            return 0.0;
        }

        long x = Double.doubleToLongBits(d);
        e += (int)((x >>> D_SHIFT) & D_MASK);
        if (e <= 0)
            return 0.0;   // underflow
        if (e >= D_MASK)  // overflow
            return (d < 0) ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        x &= ~(D_MASK << D_SHIFT);
        x |= (long)e << D_SHIFT;
        return Double.longBitsToDouble(x);
    }
}
