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

package elite.lang;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import org.operamasks.el.eval.TypeCoercion;
import elite.lang.annotation.Data;

/**
 * This class represents the ratio of two integer numbers.
 */
@Data({"numerator", "denominator"})
public final class Rational extends Number implements Comparable<Rational>
{
    /**
     * The rational number represents ZERO (0/1).
     */
    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);

    /**
     * The rational number represents ONE (1/1).
     */
    public static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);

    /**
     * The rational number represents positive infinity (1/0).
     */
    public static final Rational POSITIVE_INFINITY = new Rational(BigInteger.ONE, BigInteger.ZERO);

    /**
     * The rational number represents negative infinity (-1/0).
     */
    public static final Rational NEGATIVE_INFINITY = new Rational(BigInteger.ONE.negate(), BigInteger.ZERO);

    // the numerator and denominator component of this rational number.
    private BigInteger numer, denom;

    /**
     * Construct a rational number.
     */
    private Rational(BigInteger numer, BigInteger denom) {
        this.numer = numer;
        this.denom = denom;
    }

    /**
     * Creates a rational number having the specified numerator and denominator
     * components.
     *
     * @param numer the numerator component of this rational number.
     * @param denom the denominator component of this rational number.
     * @return the rational number.
     */
    public static Rational valueOf(Number numer, Number denom) {
        if (numer instanceof BigInteger) {
            if (denom instanceof BigInteger) {
                return make((BigInteger)numer, (BigInteger)denom);
            } else {
                return make((BigInteger)numer, TypeCoercion.coerceToBigInteger(denom));
            }
        } else if (denom instanceof BigInteger) {
            return make(TypeCoercion.coerceToBigInteger(numer), (BigInteger)denom);
        } else {
            return make(numer.longValue(), denom.longValue());
        }
    }

    /**
     * Creat a rational number from the specified numeric value. This method
     * is used for converting other numeric value type into rational number.
     *
     * @param val the numeric value
     * @return the rational number
     */
    public static Rational valueOf(Number val) {
        if (val instanceof BigDecimal) {
            return valueOf((BigDecimal)val);
        } else if (val instanceof Decimal) {
            return valueOf((Decimal)val);
        } else if (val instanceof BigInteger) {
            return new Rational((BigInteger)val, BigInteger.ONE);
        } else if (val instanceof Rational) {
            return (Rational)val;
        } else if (val instanceof Double || val instanceof Float) {
            return valueOf(val.doubleValue());
        } else {
            return make(val.longValue(), 1);
        }
    }

    /**
     * Create a rational number from String representation.
     *
     * @param str the String representation of the rational number.
     * @return the rational number.
     */
    public static Rational valueOf(String str) {
        int sep = str.indexOf('/');
        if (sep >= 0) {
            BigInteger numer = new BigInteger(str.substring(0, sep));
            BigInteger denom = new BigInteger(str.substring(sep+1));
            return new Rational(numer, denom).normalize();
        } else {
            return new Rational(new BigInteger(str), BigInteger.ONE);
        }
    }

    /**
     * Create a rational number from specified numerator and denominator
     * in BigInteger type. Performs necessary normalization.
     */
    public static Rational make(BigInteger numer, BigInteger denom) {
        return new Rational(numer, denom).normalize();
    }

    /**
     * Create a rational number from specified numerator and denominator
     * in long type. Performs necessary normalization.
     */
    public static Rational make(long numer, long denom) {
        if (denom == 0) {
            return (numer >= 0) ? POSITIVE_INFINITY : NEGATIVE_INFINITY;
        } else if (numer == 0) {
            return ZERO;
        } else if (numer == denom) {
            return ONE;
        }

        if (denom < 0) {
            numer = -numer;
            denom = -denom;
        }

        long g = gcd(numer, denom);
        if (g != 1) {
            numer /= g;
            denom /= g;
        }

        return new Rational(BigInteger.valueOf(numer), BigInteger.valueOf(denom));
    }

    private static long gcd(long m, long n) {
        if (m < 0) m = -m;
        if (n < 0) n = -n;
        
        long r;
        while (n > 0) {
            r = m % n;
            m = n;
            n = r;
        }
        return m;
    }

    private static final int CHUNK = 28;

    /**
     * Convert double precision inexact floating number into rational
     * number exactly, without rounding.
     */
    private static Rational valueOf(double x) {
        if (x == 0.0) {
            return new Rational(BigInteger.ZERO, BigInteger.ONE);
        } else if (x == Double.POSITIVE_INFINITY) {
            return POSITIVE_INFINITY;
        } else if (x == Double.NEGATIVE_INFINITY) {
            return NEGATIVE_INFINITY;
        }

        boolean neg = false;
        if (x < 0) {
            x = -x;
            neg = true;
        }

        double[] t = MathLib.frexp(x);
        double f = t[0];
        int e = (int)t[1];

        BigInteger top = BigInteger.ZERO;
        BigInteger bot = BigInteger.ONE;

        // Suck up CHUNK bits at a time; 28 is enough so that we suck
        // up all bits in 2 iterations for all known binary double-
        // precision formats, and small enough to fit in an int.
        while (f != 0) {
            f = MathLib.ldexp(f, CHUNK);
            int digit = (int)f;
            top = top.shiftLeft(CHUNK).add(BigInteger.valueOf(digit));
            f -= digit;
            e -= CHUNK;
        }

        if (e > 0) {
            top = top.shiftLeft(e);
        } else {
            bot = bot.shiftLeft(-e);
        }
        if (neg) {
            top = top.negate();
        }

        return make(top, bot);
    }

    private static Rational valueOf(BigDecimal val) {
        int scale = val.scale();
        BigInteger n, d;

        if (scale == 0) {
            n = val.unscaledValue();
            d = BigInteger.ONE;
        } else if (scale > 0) {
            n = val.unscaledValue();
            d = BigInteger.TEN.pow(scale);
        } else {
            n = val.unscaledValue().multiply(BigInteger.TEN.pow(-scale));
            d = BigInteger.ONE;
        }

        return Rational.make(n, d);
    }

    private static Rational valueOf(Decimal val) {
        int scale = val.scale();
        BigInteger n = BigInteger.valueOf(val.unscaledValue());
        BigInteger d = BigInteger.valueOf(10).pow(scale);
        return Rational.make(n, d);
    }

    /**
     * Returns the numerator of this rational number.
     *
     * @return the numerator of this rational number.
     */
    public BigInteger getNumerator() {
        return numer;
    }

    /**
     * Returns the denominator of this rational number.
     *
     * @return the denominator of this rational number.
     */
    public BigInteger getDenominator() {
        return denom;
    }

    /**
     * Returns the sum of this rational number with the one specified.
     *
     * @param that the rational number to be added.
     * @return <code>this + that</code>
     */
    public Rational add(Rational that) {
        return Rational.make(
            this.numer.multiply(that.denom).add(this.denom.multiply(that.numer)),
            this.denom.multiply(that.denom));
    }

    /**
     * Returns the difference between this rational number and the one specified.
     *
     * @param that the rational number to be subtracted.
     * @return <code>this - that</code>.
     */
    public Rational subtract(Rational that) {
        return Rational.make(
            this.numer.multiply(that.denom).subtract(this.denom.multiply(that.numer)),
            this.denom.multiply(that.denom));
    }

    /**
     * Returns the product of this rational number with the one specified.
     *
     * @param that the rational multiplier.
     * @return <code>this * that</code>.
     */
    public Rational multiply(Rational that) {
        return Rational.make(
            this.numer.multiply(that.numer),
            this.denom.multiply(that.denom));
    }

    /**
     * Returns this ratinal number divided by the one specified.
     *
     * @param that the rational divisor.
     * @return <code>this / that</code>.
     */
    public Rational divide(Rational that) {
        return Rational.make(
            this.numer.multiply(that.denom),
            this.denom.multiply(that.numer));
    }

    /**
     * Returns remainder of this rational number divided by the one specified.
     *
     * @param that the rational divisor.
     * @return <code>this % that</code>.
     */
    public Rational remainder(Rational that) {
        Rational[] divrem = divideAndRemainder(that);
        return divrem[1];
    }

    /**
     * Returns tuple of quotient and remainder of this rational number divided
     * by the one specified.
     *
     * @param that the rational divisor.
     * @return <code>(this / that, this % that)</code>.
     */
    public Rational[] divideAndRemainder(Rational that) {
        // use the identity  x = i * y + r to determine r
        BigInteger n = that.numer, d = that.denom;
        BigInteger i = this.numer.multiply(d).divide(this.denom.multiply(n)); // i=x/y

        Rational[] result = new Rational[2];
        result[0] = Rational.make(i, BigInteger.ONE);
        result[1] = this.subtract(Rational.make(that.numer.multiply(i), that.denom)); // r=x-i*y
        return result;
    }

    /**
     * Returns Greatest Common Divisor of this rational number and the one specified.
     *
     * @param that another rational to compute gcd
     * @return <code>gcd(this, that)</code>.
     */
    public Rational gcd(Rational that) {
        if (that.signum() == 0) {
            return this.abs();
        } else if (this.signum() == 0) {
            return that.abs();
        }

        Rational a = this.abs();
        Rational b = that.abs();
        Rational r;
        while (b.signum() != 0) {
            r = a.remainder(b); a = b; b = r;
        }
        return a;
    }

    /**
     * Returns this rational number raised to the specified power.
     *
     * @param n the exponent.
     * @return <code>this^n</code>.
     */
    public Rational pow(int n) {
        if (n < 0) {
            return Rational.make(denom.pow(-n), numer.pow(-n));
        } else {
            return Rational.make(numer.pow(n), denom.pow(n));
        }
    }

    /**
     * Returns the negation of this rational number.
     *
     * @return the negation of this rational number.
     */
    public Rational negate() {
        return new Rational(numer.negate(), denom);
    }

    /**
     * Returns the signum of this rational number.
     *
     * @return -1, 0, or 1 as the value of this rational number is negative,
     *         zero or positive.
     */
    public int signum() {
        return numer.signum();
    }

    /**
     * Returns the absolute value of this rational number.
     *
     * @return <code>abs(this)</code>
     */
    public Rational abs() {
        return numer.signum() >= 0 ? this : new Rational(numer.negate(), denom);
    }

    /**
     * Converts this rational number to a BigInteger.
     *
     * @return this rational number converted to a BigInteger.
     */
    public BigInteger toBigInteger() {
        return numer.divide(denom);
    }

    /**
     * Converts this rational number to a <code>long</code>. The fraction in the
     * rational number is truncated and if this rational number is too big to fit
     * in a <code>long</code>, only the low-order 64 bits are returned.
     *
     * @return this rational number converted to a <code>long</code>.
     */
    public long longValue() {
        return numer.divide(denom).longValue();
    }

    /**
     * Converts this rational number to an <code>int</code>. The fraction in the
     * rational number is truncated and if this rational number is too big to fit
     * in an <code>int</code>, only the low-order 32 bits are returned.
     *
     * @return this rational number converted to an <code>int</code>.
     */
    public int intValue() {
        return (int)longValue();
    }

    /**
     * Converts this rational number to a <code>short</code>. The fraction in the
     * rational number is truncated and if this rational number is too big to fit
     * in a <code>short</code>, only the low-order 16 bits are returned.
     *
     * @return this rational number converted to a <code>short</code>.
     */
    public short shortValue() {
        return (short)longValue();
    }

    /**
     * Converts this rational number to a <code>byte</code>. The fraction in the
     * rational number is truncated and if this rational number is too big to fit
     * in a <code>byte</code>, only the low-order 8 bits are returned.
     *
     * @return this rational number converted to a <code>byte</code>.
     */
    public byte byteValue() {
        return (byte)longValue();
    }

    /**
     * Converts this rational number to a <code>double</code>. This conversion
     * can lose information about the precision of the rational value.
     *
     * @return this rational number converted to a <code>double</code>.
     */
    public double doubleValue() {
        if (numer.signum() < 0) {
            return -this.abs().doubleValue();
        }

        // Normalize to 63 bit
        int numerBitLength = numer.bitLength();
        int denomBitLength = denom.bitLength();
        if (numerBitLength > denomBitLength) {
            int shift = denomBitLength - 63;
            long divisor = denom.shiftRight(shift).longValue();
            BigInteger dividend = numer.shiftRight(shift);
            return dividend.doubleValue() / divisor;
        } else {
            int shift = numerBitLength - 63;
            long dividend = numer.shiftRight(shift).longValue();
            BigInteger divisor = denom.shiftRight(shift);
            return dividend / divisor.doubleValue();
        }
    }

    /**
     * Converts this rational number to a <code>float</code>. This conversion
     * can lose information about the precision of the rational value.
     *
     * @return this rational number converted to a <code>float</code>.
     */
    public float floatValue() {
        return (float)doubleValue();
    }

    /**
     * Convert this rational number to a <code>BigDecimal</code>. This conversion
     * can lose information about the precision of the rational value.
     *
     * @return this rational number converted to a <code>BigDecimal</code>.
     */
    public BigDecimal toBigDecimal() {
        BigDecimal n = new BigDecimal(numer);
        BigDecimal d = new BigDecimal(denom);
        MathContext mc = new MathContext((int)Math.min(n.precision() +
                                                       (long)Math.ceil(10.0*d.precision()/3.0),
                                                       Integer.MAX_VALUE),
                                         RoundingMode.HALF_EVEN);
        return n.divide(d, mc);
    }

    /**
     * Convert this rational number to a <code>BigDecimal</code>. This conversion
     * can lose information about the precision of the rational value.
     *
     * @param mc the MathContext used for division.
     * @return this rational number converted to a <code>BigDecimal</code>.
     */
    public BigDecimal toBigDecimal(MathContext mc) {
        return new BigDecimal(numer).divide(new BigDecimal(denom), mc);
    }

    /**
     * Compares this rational number with the specified Object for equality.
     *
     * @param obj Object to which this rational number is to be compared.
     * @return <code>true</code> if and only if the specified Object is a
     *         Rational whose value is numerically equal to this Rational.
     */
    public boolean equals(Object obj) {
        if (obj instanceof Rational) {
            Rational that = (Rational)obj;
            return this.numer.equals(that.numer) && this.denom.equals(that.denom);
        } else {
            return false;
        }
    }

    /**
     * Returns the hash code for this rational number.
     *
     * @return hash code for this rational number.
     */
    public int hashCode() {
        return numer.hashCode() ^ denom.hashCode();
    }

    /**
     * Returns the String representation of this rational number.
     *
     * @return String representation of this rational number.
     */
    public String toString() {
        if (denom.signum() == 0) {
            return numer.signum() >= 0 ? "Infinity" : "-Infinity";
        } else if (denom.equals(BigInteger.ONE)) {
            return numer.toString();
        } else {
            return numer.toString().concat("/").concat(denom.toString());
        }
    }

    /**
     * Compares this rational number with the specified one.
     *
     * @param that Rational to which this Rational is to be compared.
     * @return -1, 0 or 1 as this Rational is numerically less than, equal
     *         to, or greater than.
     */
    public int compareTo(Rational that) {
        return this.numer.multiply(that.denom).compareTo(
               that.numer.multiply(this.denom));
    }

    private Rational normalize() {
        if (denom.signum() == 0) {
            return numer.signum() >= 0 ? POSITIVE_INFINITY : NEGATIVE_INFINITY;
            //throw new ArithmeticException("Zero divisor");
        }

        if (denom.signum() < 0) {
            numer = numer.negate();
            denom = denom.negate();
        }

        BigInteger gcd = numer.gcd(denom);
        if (!gcd.equals(BigInteger.ONE)) {
            numer = numer.divide(gcd);
            denom = denom.divide(gcd);
        }

        return this;
    }

    public Number reduce() {
        if (numer.signum() == 0) {
            return 0;
        }

        if (denom.equals(BigInteger.ONE)) {
            return numer.bitLength() < 32 ? numer.intValue() :
                   numer.bitLength() < 64 ? numer.longValue()
                                          : numer;
        }

        return this;
    }
}
