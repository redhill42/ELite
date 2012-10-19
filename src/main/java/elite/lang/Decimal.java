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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.text.NumberFormat;
import java.text.FieldPosition;

/**
 * Fixed-precision signed decimal numbers.
 */
public class Decimal extends Number implements Comparable<Decimal>
{
    public static final byte MAX_SCALE = initMaxScale();

    public static final Decimal MAX_VALUE = new Decimal(Long.MAX_VALUE,(byte)0);
    public static final Decimal MIN_VALUE = new Decimal(Long.MIN_VALUE,(byte)0);

    public static final Decimal ZERO = new Decimal(0, (byte)0);
    public static final Decimal ONE  = new Decimal(1, (byte)0);

    private long value;
    private byte scale;

    // Constructors
    public Decimal(BigDecimal big) throws ArithmeticException {
	this(big, true);
    }

    public Decimal(String s) throws NumberFormatException, ArithmeticException {
	this(new BigDecimal(s), true);
    }

    public Decimal(double dbl) throws ArithmeticException {
	this(new BigDecimal(dbl), true);
    }

    // Static Factory Methods

    public static Decimal valueOf(long val, int scale)
	throws ArithmeticException
    {
	if (scale < 0 || scale > 19)
	    throw new ArithmeticException("Invalid scale");
	if (scale > MAX_SCALE) {
	    val = round(val, (byte)scale, MAX_SCALE);
	    scale = MAX_SCALE;
	}
	return new Decimal(val, (byte)scale);
    }

    public static Decimal valueOf(long val) {
	return new Decimal(val, (byte)0);
    }

    public static Decimal valueOf(BigDecimal big)
	throws ArithmeticException
    {
	return new Decimal(big);
    }

    public static Decimal valueOf(String s)
	throws NumberFormatException, ArithmeticException
    {
	return new Decimal(s);
    }

    public static Decimal valueOf(double dbl)
	throws ArithmeticException
    {
	return new Decimal(dbl);
    }

    // Arithmetic Operations

    public Decimal add(Decimal val)
	throws ArithmeticException
    {
	return internalAdd(this, val, true);
    }

    public Decimal addAndRound(Decimal val, int scale)
	throws ArithmeticException
    {
	if (scale < 0)
	    throw new ArithmeticException("Negative scale");
	if (scale > MAX_SCALE)
	    scale = MAX_SCALE;
	Decimal res = internalAdd(this, val, false);
	return res.roundInPlace((byte)scale);
    }

    public Decimal addAndRound(Decimal val)
	throws ArithmeticException
    {
	return addAndRound(val, this.scale);
    }

    public Decimal addAndTruncate(Decimal val, int scale)
	throws ArithmeticException
    {
	if (scale < 0)
	    throw new ArithmeticException("Negative scale");
	if (scale > MAX_SCALE)
	    scale = MAX_SCALE;
	Decimal res = internalAdd(this, val, false);
	return res.truncateInPlace((byte)scale);
    }

    public Decimal addAndTruncate(Decimal val)
	throws ArithmeticException
    {
	return addAndTruncate(val, this.scale);
    }

    public Decimal addAndRaise(Decimal val, int scale)
	throws ArithmeticException
    {
	if (scale < 0)
	    throw new ArithmeticException("Negative scale");
	if (scale > MAX_SCALE)
	    scale = MAX_SCALE;
	Decimal res = internalAdd(this, val, false);
	return res.raiseInPlace((byte)scale);
    }

    public Decimal addAndRaise(Decimal val)
	throws ArithmeticException
    {
	return addAndRaise(val, this.scale);
    }

    public Decimal subtract(Decimal val)
	throws ArithmeticException
    {
	return internalSubtract(this, val, true);
    }

    public Decimal subtractAndRound(Decimal val, int scale)
	throws ArithmeticException
    {
	if (scale < 0)
	    throw new ArithmeticException("Negative scale");
	if (scale > MAX_SCALE)
	    scale = MAX_SCALE;
	Decimal res = internalSubtract(this, val, false);
	return res.roundInPlace((byte)scale);
    }

    public Decimal subtractAndRound(Decimal val)
	throws ArithmeticException
    {
	return subtractAndRound(val, this.scale);
    }

    public Decimal subtractAndTruncate(Decimal val, int scale)
	throws ArithmeticException
    {
	if (scale < 0)
	    throw new ArithmeticException("Negative scale");
	if (scale > MAX_SCALE)
	    scale = MAX_SCALE;
	Decimal res = internalSubtract(this, val, false);
	return res.truncateInPlace((byte)scale);
    }

    public Decimal subtractAndTruncate(Decimal val)
	throws ArithmeticException
    {
	return subtractAndTruncate(val, this.scale);
    }

    public Decimal subtractAndRaise(Decimal val, int scale)
	throws ArithmeticException
    {
	if (scale < 0)
	    throw new ArithmeticException("Negative scale");
	if (scale > MAX_SCALE)
	    scale = MAX_SCALE;
	Decimal res = internalSubtract(this, val, false);
	return res.raiseInPlace((byte)scale);
    }

    public Decimal subtractAndRaise(Decimal val)
	throws ArithmeticException
    {
	return subtractAndRaise(val, this.scale);
    }

    public Decimal multiply(Decimal val)
	throws ArithmeticException
    {
	return internalMultiply(this, val, true);
    }

    public Decimal multiplyAndRound(Decimal val, int scale)
	throws ArithmeticException
    {
	if (scale < 0)
	    throw new ArithmeticException("Negative scale");
	if (scale > MAX_SCALE)
	    scale = MAX_SCALE;
	Decimal res = internalMultiply(this, val, false);
	return res.roundInPlace((byte)scale);
    }

    public Decimal multiplyAndRound(Decimal val)
	throws ArithmeticException
    {
	return multiplyAndRound(val, this.scale);
    }

    public Decimal multiplyAndTruncate(Decimal val, int scale)
	throws ArithmeticException
    {
	if (scale < 0)
	    throw new ArithmeticException("Negative scale");
	if (scale > MAX_SCALE)
	    scale = MAX_SCALE;
	Decimal res = internalMultiply(this, val, false);
	return res.truncateInPlace((byte)scale);
    }

    public Decimal multiplyAndTruncate(Decimal val)
	throws ArithmeticException
    {
	return multiplyAndTruncate(val, this.scale);
    }

    public Decimal multiplyAndRaise(Decimal val, int scale)
	throws ArithmeticException
    {
	if (scale < 0)
	    throw new ArithmeticException("Negative scale");
	if (scale > MAX_SCALE)
	    scale = MAX_SCALE;
	Decimal res = internalMultiply(this, val, false);
	return res.raiseInPlace((byte)scale);
    }

    public Decimal multiplyAndRaise(Decimal val)
	throws ArithmeticException
    {
	return multiplyAndRaise(val, this.scale);
    }

    public Decimal divide(Decimal val)
	throws ArithmeticException
    {
	byte maxscale = MAX_SCALE;
	Decimal res = internalDivide(this, val, maxscale+1);
	return res.roundInPlaceWithSoftFail(maxscale);
    }

    public Decimal divideAndRound(Decimal val, int scale)
	throws ArithmeticException
    {
	if (scale < 0)
	    throw new ArithmeticException("Negative scale");
	if (scale > MAX_SCALE)
	    scale = MAX_SCALE;
	Decimal res = internalDivide(this, val, (byte)(scale+1));
	return res.roundInPlace((byte)scale);
    }

    public Decimal divideAndRound(Decimal val)
	throws ArithmeticException
    {
	return divideAndRound(val, this.scale);
    }

    public Decimal divideAndTruncate(Decimal val, int scale)
	throws ArithmeticException
    {
	if (scale < 0)
	    throw new ArithmeticException("Negative scale");
	if (scale > MAX_SCALE)
	    scale = MAX_SCALE;
	Decimal res = internalDivide(this, val, (byte)scale);
	return res.truncateInPlace((byte)scale);
    }

    public Decimal divideAndTruncate(Decimal val)
	throws ArithmeticException
    {
	return divideAndTruncate(val, this.scale);
    }

    public Decimal divideAndRaise(Decimal val, int scale)
	throws ArithmeticException
    {
	if (scale < 0)
	    throw new ArithmeticException("Negative scale");
	if (scale > MAX_SCALE)
	    scale = MAX_SCALE;
	Decimal res = internalDivide(this, val, (byte)(scale+1));
	return res.raiseInPlace((byte)scale);
    }

    public Decimal divideAndRaise(Decimal val)
	throws ArithmeticException
    {
	return divideAndRaise(val, this.scale);
    }

    public Decimal[] divideAndRemainder(Decimal val) {
        Decimal[] result = new Decimal[2];
        result[0] = Decimal.valueOf(this.divide(val).longValue());
        result[1] = this.subtract(result[0].multiply(val));
        return result;
    }

    public Decimal remainder(Decimal val) {
        Decimal i = Decimal.valueOf(this.divide(val).longValue());
        return this.subtract(i.multiply(val));
    }

    public Decimal abs() {
	return value < 0L ? negate() : this;
    }

    public Decimal negate() {
	return new Decimal(-value, scale);
    }

    public int signum() {
	return value > 0 ? 1 : value < 0 ? -1 : 0;
    }

    public int scale() {
	return scale;
    }

    public long unscaledValue() {
        return value;
    }

    // Scaling/Rounding Operations

    public Decimal round(int newscale)
	throws ArithmeticException
    {
	if (newscale < 0)
	    throw new ArithmeticException("Negative scale");
	newscale = Math.min(newscale, MAX_SCALE);
	return new Decimal(value, scale).roundInPlace((byte)newscale);
    }

    public Decimal truncate(int newscale)
	throws ArithmeticException
    {
	if (newscale < 0)
	    throw new ArithmeticException("Negative scale");
	if (newscale > MAX_SCALE)
	    newscale = MAX_SCALE;
	return new Decimal(value, scale).truncateInPlace((byte)newscale);
    }

    public Decimal raise(int newscale)
	throws ArithmeticException
    {
	if (newscale < 0)
	    throw new ArithmeticException("Negative scale");
	if (newscale > MAX_SCALE)
	    newscale = MAX_SCALE;
	return new Decimal(value, scale).raiseInPlace((byte)newscale);
    }

    // Comparison Operations

    public int compareTo(Decimal val) {
	byte as = this.scale, bs = val.scale;
	long av = this.value, bv = val.value;
	long scaled = 0L;
	boolean overflow = false;

	if (av == bv && (av == 0 || as == bs))
	    return 0;
	if ((av ^ bv) < 0)
	    return av >= 0 ? 1 : -1;

	if (as < bs) {
	    scaled = av * scaleFactor[bs - as];
	    if ((scaled ^ av) < 0)
		overflow = true;
	    else
		av = scaled;
	} else if (as > bs) {
	    scaled = bv * scaleFactor[as - bs];
	    if ((bv ^ scaled) < 0)
		overflow = true;
	    else
		bv = scaled;
	}

	if (!overflow) {
	    return av > bv ? 1 : av < bv ? -1 : 0;
	} else {
	    BigDecimal aa = this.toBigDecimal();
	    BigDecimal bb = val.toBigDecimal();
	    return aa.compareTo(bb);
	}
    }

    public boolean greater(Decimal val) {
	return compareTo(val) > 0;
    }

    public boolean greaterEqual(Decimal val) {
	return compareTo(val) >= 0;
    }

    public boolean greaterThanZero() {
	return value > 0L;
    }

    public boolean less(Decimal val) {
	return compareTo(val) < 0;
    }

    public boolean lessEqual(Decimal val) {
	return compareTo(val) <= 0;
    }

    public boolean lessThanZero() {
	return value < 0L;
    }

    public boolean isEqual(Decimal val) {
	return compareTo(val) == 0;
    }

    public boolean isZero() {
	return value == 0L;
    }

    public boolean isOne() {
	return (scaleFactor[scale] == value);
    }

    public boolean equals(Object obj) {
	if (obj == null || !(obj instanceof Decimal))
	    return false;
	Decimal x = (Decimal) obj;
	return value == x.value && scale == x.scale;
    }

    public Decimal min(Decimal val) {
	return compareTo(val) < 0 ? this : val;
    }

    public Decimal max(Decimal val) {
	return compareTo(val) > 0 ? this : val;
    }

    public int hashCode() {
        return (int)(value ^ (value >>> 32));
    }

    // Format Converters

    public String format() {
	return format(Locale.getDefault());
    }

    public String format(Locale locale) {
	return format(NumberFormat.getInstance(locale));
    }

    public String format(NumberFormat nf) {
	long factor = scaleFactor[scale];
	long lhs = value / factor;
	long rhs = value - lhs * factor;
	if (rhs < 0L)
	    rhs = -rhs;

	nf.setMaximumFractionDigits(0);
	nf.setMinimumFractionDigits(0);
	StringBuffer lhsStr = new StringBuffer();
	FieldPosition lhsPos = new FieldPosition(NumberFormat.INTEGER_FIELD);
	lhsStr = nf.format(lhs, lhsStr, lhsPos);

	if (scale != 0) {
	    double rhsd = (double)rhs / factor;
	    nf.setMaximumFractionDigits(scale);
	    nf.setMinimumFractionDigits(scale);
	    StringBuffer rhsStr = new StringBuffer();
	    FieldPosition rhsPos = new FieldPosition(NumberFormat.FRACTION_FIELD);
	    rhsStr = nf.format(rhsd, rhsStr, rhsPos);
	    lhsStr.insert(lhsPos.getEndIndex(),
			  rhsStr.toString().substring(rhsPos.getBeginIndex()-1,
						      rhsPos.getEndIndex()));
	}

	return lhsStr.toString();
    }

    public String toString() {
	long factor = scaleFactor[scale];
	long lhs = value / factor;
	long rhs = value - lhs * factor;

	StringBuffer lhsStr = new StringBuffer();
	if (lhs == 0L && rhs < 0L)
	    lhsStr.append("-0");
	else
	    lhsStr.append(lhs);

	if (scale != 0) {
	    StringBuffer rhsStr = new StringBuffer();
	    if (lhs <= 0L && rhs < 0L)
		rhs = -rhs;
	    rhsStr.append(rhs);
	    int diff = scale - rhsStr.length();
	    while (--diff >= 0)
		rhsStr.insert(0, "0");
	    lhsStr.append(".").append(rhsStr);
	}

	return lhsStr.toString();
    }

    private transient BigDecimal cachedBigDecimal = null;

    public BigDecimal toBigDecimal() {
	if (cachedBigDecimal == null)
	    cachedBigDecimal = BigDecimal.valueOf(value, (int)scale);
	return cachedBigDecimal;
    }

    public int intValue() {
	return (int)(value / scaleFactor[scale]);
    }

    public long longValue() {
	return value / scaleFactor[scale];
    }

    public float floatValue() {
	return (float)value / scaleFactor[scale];
    }

    public double doubleValue() {
	return (double)value / scaleFactor[scale];
    }

    /*------------------------------------------------------------------*/
    // Private Methods

    private Decimal(long value, byte scale) {
	this.value = value;
	this.scale = scale;
    }

    private Decimal(BigDecimal big, boolean checkScale)
	throws ArithmeticException
    {
	if (big.signum() == 0) {
	    this.value = 0;
	    this.scale = 0;
	    return;
	}

	BigInteger bigInt = big.toBigInteger();
	if (bigInt.bitLength() >= 64)
	    throw new ArithmeticException("Overflow");
	long val = bigInt.longValue();
	int scale = big.scale();
	int digits = totalDigits(val);
	int numToMoveRight = 19 - digits;
	if (numToMoveRight > scale)
	    numToMoveRight = scale;
	big = big.movePointRight(numToMoveRight);
	val = big.longValue();
	scale = numToMoveRight;
	if (val < 0 && big.signum() > 0 || val > 0 && big.signum() < 0) {
	    val = big.movePointLeft(1).longValue();
	    scale--;
	}
	if (checkScale) {
	    if (scale > MAX_SCALE) {
		val = round(val, (byte)scale, MAX_SCALE);
		scale = MAX_SCALE;
	    }
	}
	this.value = val;
	this.scale = (byte)scale;
    }

    private static Decimal internalAdd(Decimal a, Decimal b, boolean checkScale)
	throws ArithmeticException
    {
	byte as = a.scale, bs = b.scale;
	long av = a.value, bv = b.value;
	long scaled = 0L, sum = 0L;
	boolean overflow = false;

	if (as < bs) {
	    scaled = av * scaleFactor[bs - as];
	    if ((av ^ scaled) < 0)
		overflow = true;
	    else {
		av = scaled;
		as = bs;
	    }
	} else if (as > bs) {
	    scaled = bv * scaleFactor[as - bs];
	    if ((bv ^ scaled) < 0)
		overflow = true;
	    else
		bv = scaled;
	}

	if (!overflow) {
	    sum = av + bv;
	    if (!((av ^ bv) >= 0 && (av ^ sum) < 0)) {
		if (checkScale) {
		    if (as > MAX_SCALE) {
			sum = round(sum, as, MAX_SCALE);
			as = MAX_SCALE;
		    }
		}
		return new Decimal(sum, as);
	    }
	}

	BigDecimal aa = a.toBigDecimal();
	BigDecimal bb = b.toBigDecimal();
	return new Decimal(aa.add(bb), checkScale);
    }

    private static Decimal internalSubtract(Decimal a, Decimal b, boolean checkScale)
	throws ArithmeticException
    {
	byte as = a.scale, bs = b.scale;
	long av = a.value, bv = b.value;
	long scaled = 0L, diff = 0L;
	boolean overflow = false;

	if (as < bs) {
	    scaled = av * scaleFactor[bs - as];
	    if ((av ^ scaled) < 0)
		overflow = true;
	    else {
		av = scaled;
		as = bs;
	    }
	}
	else if (as > bs) {
	    scaled = bv * scaleFactor[as - bs];
	    if ((bv ^ scaled) < 0)
		overflow = true;
	    else
		bv = scaled;
	}

	if (!overflow) {
	    diff = av - bv;
	    if (!((av ^ bv) < 0 && (av ^ diff) < 0)) {
		if (checkScale) {
		    if (as > MAX_SCALE) {
			diff = round(diff, as, MAX_SCALE);
			as = MAX_SCALE;
		    }
		}
		return new Decimal(diff, as);
	    }
	}

	BigDecimal aa = a.toBigDecimal();
	BigDecimal bb = b.toBigDecimal();
	return new Decimal(aa.subtract(bb), checkScale);
    }

    private static Decimal internalMultiply(Decimal a, Decimal b, boolean checkScale)
	throws ArithmeticException
    {
	if (a.isZero() || b.isZero())
	    return valueOf(0);

	byte as = a.scale, bs = b.scale;
	long av = a.value, bv = b.value;

	boolean done = false;
	while (!done && av != 0L && as > 0) {
	    if (av % 10 == 0L) {
		av /= 10;
		as = (byte)(as - 1);
	    } else {
		done = true;
            }
        }

	done = false;
	while (!done && bv != 0L && bs > 0) {
	    if (bv % 10 == 0L) {
		bv /= 10;
		bs = (byte)(bs - 1);
	    } else {
		done = true;
            }
        }

	if (bitLength(av) + bitLength(bv) > 63) {
	    BigDecimal aa = a.toBigDecimal();
	    BigDecimal bb = b.toBigDecimal();
	    return new Decimal(aa.multiply(bb), checkScale);
	} else {
	    long res = av * bv;
	    byte newscale = (byte)(as + bs);
	    if (checkScale) {
		if (newscale > MAX_SCALE) {
		    res = round(res, newscale, MAX_SCALE);
		    newscale = MAX_SCALE;
		}
	    }
	    return new Decimal(res, newscale);
	}
    }

    private static Decimal internalDivide(Decimal a, Decimal b, int desiredScale)
	throws ArithmeticException
    {
	if (b.isZero())
	    throw new ArithmeticException("Divide by zero");
	if (a.isZero())
	    return valueOf(0);

	byte as = a.scale, bs = b.scale;
	long av = a.value, bv = b.value;

	boolean done = false;
	while (!done && av != 0L && as > 0) {
	    if (av % 10 == 0L) {
		av /= 10;
		as = (byte)(as - 1);
	    } else {
		done = true;
            }
        }

	done = false;
	while (!done && bv != 0L && bs > 0) {
	    if (bv % 10 == 0L) {
		bv /= 10;
		bs = (byte)(bs - 1);
	    } else {
		done = true;
            }
        }

	if (!divResultOverflow(av, as, bv, bs)) {
	    Decimal result = null;
	    if ((result = divideByPowerOfTen(av, as, bv, bs)) != null)
		return result;
	    if ((result = divideByLong(av, as, bv, bs, desiredScale)) != null)
		return result;
	}

	BigDecimal aa = a.toBigDecimal();
	BigDecimal bb = b.toBigDecimal();
	BigDecimal cc = aa.divide(bb, desiredScale, BigDecimal.ROUND_DOWN);
	return new Decimal(cc);
    }

    private static boolean divResultOverflow(long av, byte as, long bv, byte bs) {
	byte avDigits = totalDigits(av);
	byte bvDigits = totalDigits(bv);
	if (bs > as) {
	    avDigits = (byte)(avDigits + (bs - as));
	    if (avDigits > 18)
		return true;
	} else if (as > bs) {
	    bvDigits = (byte)(bvDigits + (as - bs));
	    if (bvDigits > 18)
		return true;
	}
	return false;
    }

    private static Decimal divideByPowerOfTen(long av, byte as, long bv, byte bs) {
	byte i = -1;
	for (i = 0; i < 19 && scaleFactor[i] != bv; i = (byte)(i+1)) {
	    if (scaleFactor[i] > bv)
		return null;
        }
        if (i == 19)
	    return null;
	byte diff = (byte)(i - bs);
	byte newas = (byte)(diff + as);
	if (newas >= 0) {
	    return new Decimal(av, newas);
        } else {
	    byte realas = (byte)Math.abs(newas);
	    return new Decimal(av * scaleFactor[realas], (byte)0);
	}
    }

    private static Decimal divideByLong(long av, byte as, long bv, byte bs, int desiredScale) {
	byte multfactor = (byte)desiredScale;
	if (bs > as) {
	    av *= scaleFactor[bs - as];
	    as = bs;
	} else if (as > bs) {
	    byte diff = (byte)(as - bs);
	    if (multfactor - diff >= 0) {
		multfactor = (byte)(multfactor - diff);
            } else {
		byte newdiff = (byte)(diff - multfactor);
		multfactor = 0;
		bv *= scaleFactor[newdiff];
		bs = (byte)(bs + newdiff);
	    }
	}

	byte digitsConsumedByA = (byte)(totalDigits(av) + multfactor);
	if (digitsConsumedByA > 18) {
	    boolean done = false;
	    while (!done && av != 0L && bv != 0L) {
		if (av % 10 == 0L && bv % 10 == 0L) {
		    av /= 10;
		    bv /= 10;
		    as = (byte)(as - 1);
		    bs = (byte)(bs - 1);
		}
		else {
		    done = true;
                }
            }
	    while (multfactor > 0 && bv != 0L && bv % 10 == 0L) {
		bv /= 10;
		bs = (byte)(bs - 1);
		multfactor = (byte)(multfactor - 1);
	    }
	    digitsConsumedByA = (byte)(totalDigits(av) + multfactor);
	    if (digitsConsumedByA > 18) {
		return null;
            }
        }

	av *= scaleFactor[multfactor];
	return new Decimal(av / bv, (byte)desiredScale);
    }

    private static byte totalDigits(long value) {
	int count = 0;
	for (value = Math.abs(value); value > 0L; value /= 10)
	    count++;
	return (byte)count;
    }

    private static int bitLength(long value) {
	int length = 0;
	for (value = Math.abs(value); value != 0L; value >>>= 1)
	    length++;
	return length;
    }

    private static long truncate(long v, byte cs, byte ds)
	throws ArithmeticException
    {
	if (cs == ds)
	    return v;
	long resultv;
	if (ds < cs)
	    resultv = v / scaleFactor[cs - ds];
	else
	    resultv = v * scaleFactor[ds - cs];
	if ((v ^ resultv) < 0)
	    throw new ArithmeticException("Overflow");
	return resultv;
    }

    private Decimal truncateInPlace(byte ds)
	throws ArithmeticException
    {
	this.value = truncate(this.value, this.scale, ds);
	this.scale = ds;
	return this;
    }

    private static long round(long v, byte cs, byte ds)
	throws ArithmeticException
    {
	if (cs == ds)
	    return v;
	long resultv;
	if (ds < cs) {
	    byte diff = (byte)(cs - ds);
	    long vtemp = v / scaleFactor[diff - 1];
	    int mod10 = (int)Math.abs(vtemp % 10);
	    if (mod10 < 5)
		resultv = v / scaleFactor[diff];
	    else if (vtemp >= 0L)
		resultv = v / scaleFactor[diff] + 1L;
	    else
		resultv = v / scaleFactor[diff] - 1L;
	} else {
	    resultv = v * scaleFactor[ds - cs];
	    if ((v ^ resultv) < 0)
		throw new ArithmeticException("Overflow");
	}
	return resultv;
    }

    private Decimal roundInPlace(byte ds)
	throws ArithmeticException
    {
	this.value = round(this.value, this.scale, ds);
	this.scale = ds;
	return this;
    }

    private Decimal roundInPlaceWithSoftFail(byte ds)
	throws ArithmeticException
    {
	byte cs = this.scale;
	if (cs == ds)
	    return this;
	long v = this.value;
	long resultv = 0L;
	if (ds < cs) {
	    byte diff = (byte)(cs - ds);
	    long vtemp = v / scaleFactor[diff - 1];
	    int mod10 = (int)Math.abs(vtemp % 10);
	    if (mod10 < 5)
		resultv = v / scaleFactor[diff];
	    else if (vtemp >= 0L)
		resultv = v / scaleFactor[diff] + 1L;
	    else
		resultv = v / scaleFactor[diff] - 1L;
	    this.value = resultv;
	    this.scale = ds;
	} else {
	    byte numDigitsCur = totalDigits(v);
	    byte numDigitsShouldAdd = (byte)(ds - cs);
	    if (numDigitsCur + numDigitsShouldAdd > 19)
		numDigitsShouldAdd = (byte)(19 - numDigitsCur);
	    resultv = v * scaleFactor[numDigitsShouldAdd];
	    if ((resultv ^ v) < 0) // overflow
		resultv = v * scaleFactor[--numDigitsShouldAdd];
	    this.value = resultv;
	    this.scale = (byte)(cs + numDigitsShouldAdd);
	}
	return this;
    }

    private static long raise(long v, byte cs, byte ds)
	throws ArithmeticException
    {
	if (cs == ds)
	    return v;
	long resultv;
	if (ds < cs) {
	    byte diff = (byte)(cs - ds);
	    long factor = scaleFactor[diff];
	    long vtemp = v / factor;
	    long remain = Math.abs(v - vtemp * factor);
	    if (remain <= 0L)
		resultv = vtemp;
	    else if (vtemp >= 0L)
		resultv = vtemp + 1L;
	    else
		resultv = vtemp - 1L;
	} else {
	    resultv = v * scaleFactor[ds - cs];
	    if ((resultv ^ v) < 0)
		throw new ArithmeticException("Overflow");
	}
	return resultv;
    }

    private Decimal raiseInPlace(byte ds)
	throws ArithmeticException
    {
	this.value = raise(this.value, this.scale, ds);
	this.scale = ds;
	return this;
    }

    private static final long scaleFactor[] = {
	1L,
	10L,
	100L,
	1000L,
	10000L,
	100000L,
	1000000L,
	10000000L,
	100000000L,
	1000000000L,
	10000000000L,
	100000000000L,
	1000000000000L,
	10000000000000L,
	100000000000000L,
	1000000000000000L,
	10000000000000000L,
	100000000000000000L,
	1000000000000000000L
    };

    static final byte initMaxScale() {
	int s = Integer.getInteger("decimal.max.scale", 8);
	if (s < 0 || s > 18)
	    s = 8;
	return (byte)s;
    }
}
