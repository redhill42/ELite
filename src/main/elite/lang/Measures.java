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
import java.text.DecimalFormatSymbols;
import java.text.DecimalFormat;
import java.text.ParsePosition;
import java.text.ParseException;
import java.util.Locale;
import javax.measure.quantity.Quantity;
import javax.measure.unit.Unit;
import javax.measure.unit.UnitFormat;
import javax.measure.Measure;
import javax.measure.MeasureFormat;
import javax.measure.converter.UnitConverter;
import javax.measure.converter.RationalConverter;
import javax.measure.converter.AddConverter;
import javax.el.ELContext;

import elite.lang.annotation.Expando;
import org.operamasks.util.SimpleCache;
import org.operamasks.el.eval.ELEngine;
import static elite.lang.Builtin.*;

@SuppressWarnings("unchecked")
public final class Measures
{
    private Measures() {}

    @Expando(name="+")
    public static Unit __unit__plus__(Unit unit, Unit that) {
        return unit.compound(that);
    }

    @Expando(name="+")
    public static Unit __unit__plus__(Unit unit, Number offset) {
        return unit.plus(offset.doubleValue());
    }

    @Expando(name="*")
    public static Unit __unit_times__(Unit unit, Unit that) {
        return unit.times(that);
    }

    @Expando(name="*")
    public static Unit __unit_times__(Unit unit, double factor) {
        return unit.times(factor);
    }

    @Expando(name="*")
    public static Unit __unit_times__(Unit unit, long factor) {
        return unit.times(factor);
    }

    @Expando(name="*")
    public static Unit __unit_times__(Unit unit, Rational r) {
        long dividend = r.getNumerator().longValue();
        long divisor = r.getDenominator().longValue();
        return unit.transform(new RationalConverter(dividend, divisor));
    }

    @Expando(name="/")
    public static Unit __unit_divide__(Unit unit, Unit that) {
        return unit.divide(that);
    }

    @Expando(name="/")
    public static Unit __unit_divide__(Unit unit, double divisor) {
        return unit.divide(divisor);
    }

    @Expando(name="/")
    public static Unit __unit_divide__(Unit unit, long divisor) {
        return unit.divide(divisor);
    }

    @Expando(name="/")
    public static Unit __unit_divide__(Unit unit, Rational r) {
        long dividend = r.getDenominator().longValue();
        long divisor = r.getNumerator().longValue();
        return unit.transform(new RationalConverter(dividend, divisor));
    }

    @Expando(name="^")
    public static Unit __unit_pow__(Unit unit, Number n) {
        if (n instanceof Rational) {
            int numer = ((Rational)n).getNumerator().intValue();
            int denom = ((Rational)n).getDenominator().intValue();

            if (numer == 0) {
                return Unit.ONE;
            } else if (numer == denom) {
                return unit;
            } else if (denom == 1) {
                return unit.pow(numer);
            } else if (numer == 1) {
                return unit.root(denom);
            } else {
                return unit.pow(numer).root(denom);
            }
        } else {
            return unit.pow(n.intValue());
        }
    }

    public static Unit sqrt(Unit unit) {
        return unit.root(2);
    }

    @Expando(name="+")
    public static Measure __measure_plus__(ELContext elctx, Measure self, Measure that) {
        Object value = self.getValue();
        Unit unit = self.getUnit();
        if (unit.equals(that.getUnit())) {
            return getMeasure(__add__(elctx, value, that.getValue()), unit);
        } else {
            return getMeasure(__add__(elctx, value, that.to(unit).getValue()), unit);
        }
    }

    @Expando(name="+")
    public static Measure __measure_plus__(ELContext elctx, Measure self, Object that) {
        return getMeasure(__add__(elctx, self.getValue(), that), self.getUnit());
    }

    @Expando(name="?+")
    public static Measure __rev_measure_plus__(ELContext elctx, Measure self, Object that) {
        return getMeasure(__add__(elctx, that, self.getValue()), self.getUnit());
    }

    @Expando(name="-")
    public static Measure __measure_minus__(ELContext elctx, Measure self, Measure that) {
        Object value = self.getValue();
        Unit unit = self.getUnit();
        if (unit.equals(that.getUnit())) {
            return getMeasure(__sub__(elctx, value, that.getValue()), unit);
        } else {
            return getMeasure(__sub__(elctx, value, that.to(unit).getValue()), unit);
        }
    }

    @Expando(name="-")
    public static Measure __measure_minus__(ELContext elctx, Measure self, Object that) {
        return getMeasure(__sub__(elctx, self.getValue(), that), self.getUnit());
    }

    @Expando(name="?-")
    public static Measure __rev_measure_minus__(ELContext elctx, Measure self, Object that) {
        return getMeasure(__sub__(elctx, that, self.getValue()), self.getUnit());
    }

    @Expando(name="*")
    public static Measure __measure_times__(ELContext elctx, Measure self, Measure that) {
        return getMeasure(__mul__(elctx, self.getValue(), that.getValue()),
                          self.getUnit().times(that.getUnit()));
    }

    @Expando(name="*")
    public static Measure __measure_times__(ELContext elctx, Measure self, Object that) {
        return getMeasure(__mul__(elctx, self.getValue(), that), self.getUnit());
    }

    @Expando(name="?*")
    public static Measure __rev_measure_times(ELContext elctx, Measure self, Object that) {
        return getMeasure(__mul__(elctx, that, self.getValue()), self.getUnit());
    }

    @Expando(name="/")
    public static Measure __measure_divide__(ELContext elctx, Measure self, Measure that) {
        return getMeasure(__div__(elctx, self.getValue(), that.getValue()),
                          self.getUnit().divide(that.getUnit()));

    }

    @Expando(name="/")
    public static Measure __measure_divide__(ELContext elctx, Measure self, Object that) {
        return getMeasure(__div__(elctx, self.getValue(), that), self.getUnit());
    }

    @Expando(name="?/")
    public static Measure __rev_measure_divide__(ELContext elctx, Measure self, Object that) {
        return getMeasure(__div__(elctx, that, self.getValue()), self.getUnit());
    }

    @Expando(name="^")
    public static Measure __measure_pow__(ELContext elctx, Measure self, Number n) {
        Object value = __pow__(elctx, self.getValue(), n);
        Unit unit = __unit_pow__(self.getUnit(), n);
        return getMeasure(value, unit);
    }

    public static Measure sqrt(ELContext elctx, Measure measure) {
        return __measure_pow__(elctx, measure, Rational.make(1, 2));
    }

    @Expando(name="__call__")
    public static Object __measure_convert__(Unit unit, Measure measure) {
        // convert measure to another unit
        // usage: 15.km->mi
        return measure.to(unit);
    }

    @Expando(name="__call__")
    public static Object __measure_convert__(Unit unit, Unit that) {
        // define a converter
        // usage: define cvt = km->mi; cvt(15);
        return that.getConverterTo(unit);
    }

    @Expando(name="__call__")
    public static Object __unit_convert__(UnitConverter cvt, Number value) {
        return cvt.convert(value.doubleValue());
    }

    @Expando
    public static String format(Measure value) {
        return MeasureFormat.getInstance().format(value);
    }

    @Expando
    public static String format(ELContext elctx, Measure value, String pattern) {
        Locale locale = getLocale(elctx);

        DecimalFormatSymbols symbols = new DecimalFormatSymbols(locale);
        DecimalFormat numberFormat = new DecimalFormat(pattern, symbols);
        UnitFormat unitFormat = UnitFormat.getInstance(locale);
        return MeasureFormat.getInstance(numberFormat, unitFormat).format(value);
    }

    @Expando
    public static void label(ELContext elctx, Unit unit, String name) {
        UnitFormat.getInstance(getLocale(elctx)).label(unit, name);
    }

    @Expando
    public static void alias(ELContext elctx, Unit unit, String name) {
        UnitFormat.getInstance(getLocale(elctx)).alias(unit, name);
    }

    private static SimpleCache<String,Unit> cache = new SimpleCache<String,Unit>(200);

    public static Unit getUnit(ELContext elctx, String symbol) {
        Unit unit = cache.get(symbol);
        if (unit != null) {
            return unit;
        }

        try {
            unit = UnitFormat.getInstance(getLocale(elctx))
                    .parseProductUnit(symbol, new ParsePosition(0));
        } catch (ParseException ex) {
            return null;
        }

        cache.put(symbol, unit);
        return unit;
    }

    public static Measure getMeasure(Object v, Unit u) {
        if (v instanceof Number) {
            if (v instanceof BigDecimal) {
                return javax.measure.DecimalMeasure.valueOf((BigDecimal)v, u);
            } else if (v instanceof BigInteger) {
                return javax.measure.DecimalMeasure.valueOf(new BigDecimal((BigInteger)v), u);
            } else if (v instanceof Decimal) {
                return new DecimalMeasure((Decimal)v, u);
            } else if (v instanceof Rational) {
                return new RationalMeasure((Rational)v, u);
            } else if (v instanceof Double || v instanceof Float ||
                       v instanceof Long || v instanceof Integer ||
                       v instanceof Short || v instanceof Byte) {
                return Measure.valueOf(((Number)v).doubleValue(), u);
            } else {
                return new NumberMeasure((Number)v, u);
            }
        } else {
            return new ObjectMeasure(v, u);
        }
    }

    /**
     * Represents a measure whose value is an 64-bit precision
     * decimal number.
     */
    private static class DecimalMeasure<Q extends Quantity> extends Measure<Decimal,Q> {
        private final Decimal value;
        private final Unit<Q> unit;

        /**
         * Create a decimal measure for the specified number stated in the
         * specified unit.
         *
         * @param value the measurement value.
         * @param unit the measurement unit.
         */
        public DecimalMeasure(Decimal value, Unit<Q> unit) {
            this.value = value;
            this.unit = unit;
        }

        @Override
        public Decimal getValue() {
            return value;
        }

        @Override
        public Unit<Q> getUnit() {
            return unit;
        }

        /**
         * Returns the rational measure equivalent to this measure but
         * stated in the specified unit.
         */
        @Override
        public Measure<Decimal,Q> to(Unit<Q> unit) {
            if (unit == this.unit || unit.equals(this.unit)) {
                return this;
            }

            UnitConverter cvtr = this.unit.getConverterTo(unit);
            if (cvtr instanceof RationalConverter) {
                RationalConverter factor = (RationalConverter)cvtr;
                Decimal dividend = Decimal.valueOf(factor.getDividend());
                Decimal divisor = Decimal.valueOf(factor.getDivisor());
                Decimal result = value.multiply(dividend).divide(divisor);
                return new DecimalMeasure<Q>(result, unit);
            } else if (cvtr.isLinear()) {
                Decimal factor = Decimal.valueOf(cvtr.convert(1.0));
                Decimal result = value.multiply(factor);
                return new DecimalMeasure<Q>(result, unit);
            } else if (cvtr instanceof AddConverter) {
                Decimal offset = Decimal.valueOf(((AddConverter)cvtr).getOffset());
                Decimal result = value.add(offset);
                return new DecimalMeasure<Q>(result, unit);
            } else {
                Decimal result = Decimal.valueOf(cvtr.convert(value.doubleValue()));
                return new DecimalMeasure(result, unit);
            }
        }

        public double doubleValue(Unit<Q> unit) {
            if (unit == this.unit || unit.equals(this.unit))
                return value.doubleValue();
            return this.unit.getConverterTo(unit).convert(value.doubleValue());
        }
    }

    /**
     * Represents a measure whose value is an arbitrary-precision
     * rational number.
     */
    private static class RationalMeasure<Q extends Quantity> extends Measure<Rational,Q> {
        private final Rational value;
        private final Unit<Q> unit;

        /**
         * Create a rational measure for the specified number stated in the
         * specified unit.
         *
         * @param value the measurement value.
         * @param unit the measurement unit.
         */
        public RationalMeasure(Rational value, Unit<Q> unit) {
            this.value = value;
            this.unit = unit;
        }

        @Override
        public Rational getValue() {
            return value;
        }

        @Override
        public Unit<Q> getUnit() {
            return unit;
        }

        /**
         * Returns the rational measure equivalent to this measure but
         * stated in the specified unit.
         */
        @Override
        public Measure<Rational,Q> to(Unit<Q> unit) {
            if (unit == this.unit || unit.equals(this.unit)) {
                return this;
            }

            UnitConverter cvtr = this.unit.getConverterTo(unit);
            if (cvtr instanceof RationalConverter) {
                RationalConverter factor = (RationalConverter)cvtr;
                long dividend = factor.getDividend();
                long divisor = factor.getDivisor();
                Rational result = value.multiply(Rational.make(dividend, divisor));
                return new RationalMeasure<Q>(result, unit);
            } else if (cvtr.isLinear()) {
                Rational factor = Rational.valueOf(cvtr.convert(1.0));
                Rational result = value.multiply(factor);
                return new RationalMeasure<Q>(result, unit);
            } else if (cvtr instanceof AddConverter) {
                Rational offset = Rational.valueOf(((AddConverter)cvtr).getOffset());
                Rational result = value.add(offset);
                return new RationalMeasure<Q>(result, unit);
            } else {
                Rational result = Rational.valueOf(cvtr.convert(value.doubleValue()));
                return new RationalMeasure<Q>(result, unit);
            }
        }

        public double doubleValue(Unit<Q> unit) {
            if (unit == this.unit || unit.equals(this.unit))
                return value.doubleValue();
            return this.unit.getConverterTo(unit).convert(value.doubleValue());
        }
    }

    /**
     * Represents a measure whose value is any number.
     */
    private static class NumberMeasure<Q extends Quantity> extends Measure<Number,Q> {
        private final Number value;
        private final Unit<Q> unit;

        /**
         * Create a measure for the specified object stated in the specified unit.
         *
         * @param value the measurement value.
         * @param unit the measurement unit.
         */
        public NumberMeasure(Number value, Unit<Q> unit) {
            this.value = value;
            this.unit = unit;
        }

        public Number getValue() {
            return value;
        }

        public Unit<Q> getUnit() {
            return unit;
        }

        /**
         * Returns the measure equivalent to this measure but
         * stated in the specified unit.
         */
        public Measure<Number,Q> to(Unit<Q> unit) {
            if (unit == this.unit || unit.equals(this.unit)) {
                return this;
            }

            ELContext elctx = ELEngine.getCurrentELContext();
            UnitConverter cvtr = this.unit.getConverterTo(unit);
            if (cvtr instanceof RationalConverter) {
                RationalConverter factor = (RationalConverter)cvtr;
                long dividend = factor.getDividend();
                long divisor = factor.getDivisor();
                Object result = __div__(elctx, __mul__(elctx, value, dividend), divisor);
                return new NumberMeasure<Q>((Number)result, unit);
            } else if (cvtr.isLinear()) {
                double factor = cvtr.convert(1.0);
                Object result = __mul__(elctx, value, factor);
                return new NumberMeasure<Q>((Number)result, unit);
            } else if (cvtr instanceof AddConverter) {
                double offset = ((AddConverter)cvtr).getOffset();
                Object result = __add__(elctx, value, offset);
                return new NumberMeasure<Q>((Number)result, unit);
            } else {
                return new NumberMeasure<Q>(cvtr.convert(value.doubleValue()), unit);
            }
        }

        public double doubleValue(Unit<Q> unit) {
            if (unit == this.unit || unit.equals(this.unit))
                return value.doubleValue();
            return this.unit.getConverterTo(unit).convert(value.doubleValue());
        }
    }

    /**
     * Represents a measure whose value is any object.
     */
    private static class ObjectMeasure<Q extends Quantity> extends Measure<Object,Q> {
        private final Object value;
        private final Unit<Q> unit;

        /**
         * Create a measure for the specified object stated in the specified unit.
         *
         * @param value the measurement value.
         * @param unit the measurement unit.
         */
        public ObjectMeasure(Object value, Unit<Q> unit) {
            this.value = value;
            this.unit = unit;
        }

        public Object getValue() {
            return value;
        }

        public Unit<Q> getUnit() {
            return unit;
        }

        /**
         * Returns the measure equivalent to this measure but
         * stated in the specified unit.
         */
        public Measure<Object,Q> to(Unit<Q> unit) {
            if (unit == this.unit || unit.equals(this.unit)) {
                return this;
            }

            ELContext elctx = ELEngine.getCurrentELContext();
            UnitConverter cvtr = this.unit.getConverterTo(unit);
            if (cvtr instanceof RationalConverter) {
                RationalConverter factor = (RationalConverter)cvtr;
                long dividend = factor.getDividend();
                long divisor = factor.getDivisor();
                Object result = __div__(elctx, __mul__(elctx, value, dividend), divisor);
                return new ObjectMeasure<Q>(result, unit);
            } else if (cvtr.isLinear()) {
                double factor = cvtr.convert(1.0);
                Object result = __mul__(elctx, value, factor);
                return new ObjectMeasure<Q>(result, unit);
            } else if (cvtr instanceof AddConverter) {
                double offset = ((AddConverter)cvtr).getOffset();
                Object result = __add__(elctx, value, offset);
                return new ObjectMeasure<Q>(result, unit);
            } else {
                throw new UnsupportedOperationException();
            }
        }

        public double doubleValue(Unit<Q> unit) {
            throw new UnsupportedOperationException();
        }
    }
}
