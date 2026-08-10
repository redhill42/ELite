package org.elite.ir;

import elite.lang.Decimal;
import elite.lang.Rational;
import elite.lang.Seq;
import org.elite.eval.Coercible;
import org.elite.eval.ELEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.el.ELContext;
import java.lang.invoke.MethodHandle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Systematic data-driven tests for {@link CoercionBootstrap#dispatchCoerce}.
 *
 * <p>Each test case specifies:
 * <ul>
 *   <li>A sample object (determines the dispatch path)</li>
 *   <li>The target type</li>
 *   <li>A test value to coerce (same Java type as the sample)</li>
 *   <li>The expected result after coercion</li>
 * </ul>
 *
 * <p>The MethodHandle returned by {@code dispatchCoerce} is invoked directly
 * with {@code (ELContext, testValue)} to verify correctness.
 */
class CoercionBootstrapTest {

    // ── Test case record ──

    record CoerceCase(
        String name,           // human-readable description
        Object sample,         // sample object for dispatch path selection
        Class<?> targetType,   // target type to coerce to
        Object testValue,      // value to actually coerce
        Object expected,       // expected result after coercion
        boolean expectError    // true if coercion should fail
    ) {
        CoerceCase(String name, Object sample, Class<?> targetType,
                   Object testValue, Object expected) {
            this(name, sample, targetType, testValue, expected, false);
        }
    }

    private static ELContext elctx;

    @BeforeAll
    static void createEngine() {
        elctx = ELEngine.createELContext();
    }

    // ── Helper: invoke dispatchCoerce and return the result ──

    private static Object coerceResult(Object sample, Class<?> type, Object value) {
        try {
            MethodHandle mh = CoercionBootstrap.dispatchCoerce(sample, type);
            return mh.invoke(elctx, value);
        } catch (Throwable e) {
            throw new RuntimeException("Coerce failed: sample=" + sample +
                " type=" + type + " value=" + value, e);
        }
    }

    private static void assertCoerce(Object sample, Class<?> type,
                                     Object value, Object expected,
                                     boolean expectError) {
        String context =
          (sample == null ? "null" : sample.getClass().getSimpleName()) +
          "->" + type.getSimpleName() + " value=" + value;

        if (expectError) {
            assertThrows(RuntimeException.class,
                         () -> coerceResult(sample, type, value));
            return;
        }

        Object actual = coerceResult(sample, type, value);

        if (expected == null) {
            assertNull(actual, context);
        } else {
            assertNotNull(actual, context);

            // Compare numerically when both sides represent numbers.
            // Character is coerced to its numeric code point; Short/Byte
            // are numeric wrappers; the coerce may return different boxed types
            // that carry the same numeric value (e.g., char 65 vs Short 65).
            double expectedNum = toNumericValue(expected);
            double actualNum = toNumericValue(actual);
            if (!Double.isNaN(expectedNum) && !Double.isNaN(actualNum)) {
                assertEquals(expectedNum, actualNum, 0.0001,
                             () -> "Expected " + expected + " but got " +
                                   actual + " [" + context + "]");
            } else {
                assertEquals(expected, actual, context);
            }
        }
    }

    /** Convert a value to its numeric representation, or NaN if not numeric. */
    private static double toNumericValue(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Character c) return c.charValue();
        if (v instanceof Boolean b) return b ? 1.0 : 0.0;
        return Double.NaN;
    }

    // ══════════════════════════════════════════════════════════════════
    // To String
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Coerce to String")
    class StringCoerce {
        static Stream<CoerceCase> cases() {
            return Stream.of(
                new CoerceCase("null→String",           null,     String.class, null,     null),
                new CoerceCase("Boolean→String",        true,     String.class, true,     "true"),
                new CoerceCase("Boolean(2)→String",     false,    String.class, false,    "false"),
                new CoerceCase("int→String",            42,       String.class, 42,       "42"),
                new CoerceCase("long→String",           999L,     String.class, 999L,     "999"),
                new CoerceCase("double→String",         3.14,     String.class, 3.14,     "3.14"),
                new CoerceCase("str→String",            "hello",  String.class, "hello",  "hello"),
                new CoerceCase("empty→String",          "",       String.class, "",       ""),
                new CoerceCase("BigInteger→String",     BigInteger.TEN,       String.class, BigInteger.TEN,       "10"),
                new CoerceCase("BigDecimal→String",     BigDecimal.ONE,        String.class, BigDecimal.valueOf(1.5), "1.5"),
                new CoerceCase("Decimal→String",        Decimal.ONE,           String.class, Decimal.ONE,     "1"),
                new CoerceCase("Rational→String",       Rational.ONE,          String.class, Rational.ONE,    "1")
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // To boolean / Boolean
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Coerce to boolean/Boolean")
    class BooleanCoerce {
        static Stream<CoerceCase> cases() {
            return Stream.of(
                new CoerceCase("null→boolean",       null,      boolean.class, false,     false),
                new CoerceCase("null->Boolean",      null,      Boolean.class, null,      false),
                new CoerceCase("String→boolean",     "",        boolean.class, "",        false),
                new CoerceCase("String(true)→bool",  "true",    boolean.class, "true",    true),
                new CoerceCase("String(TRUE)→bool",  "TRUE",    boolean.class, "TRUE",    true),
                new CoerceCase("String(false)→bool", "false",   boolean.class, "false",   false),
                new CoerceCase("String(FALSE)→bool", "FALSE",   boolean.class, "FALSE",   false),
                new CoerceCase("str→Boolean",        "",        Boolean.class, "",        false),
                new CoerceCase("str(T)→Boolean",     "true",    Boolean.class, "true",    true),
                new CoerceCase("str(T)→Boolean",     "TRUE",    Boolean.class, "TRUE",    true),
                new CoerceCase("str(F)→Boolean",     "false",   Boolean.class, "false",   false),
                new CoerceCase("str(F)→Boolean",     "FALSE",   Boolean.class, "FALSE",   false),
                new CoerceCase("Boolean→boolean",    false,     boolean.class, true,      true),
                new CoerceCase("Boolean→Boolean",    false,     Boolean.class, false,     false),

                new CoerceCase("int->boolean",       0,         boolean.class, 0,            false, true),
                new CoerceCase("Object->boolean",    "Object",  boolean.class, new Object(), false, true)
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // To byte / Byte
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Coerce to byte/Byte")
    class ByteCoerce {
        static Stream<CoerceCase> cases() {
            return Stream.of(
                new CoerceCase("int→byte",          (byte)1,   byte.class,   42,  (byte)42),
                new CoerceCase("int(neg)→byte",     (byte)1,   byte.class,  -10, (byte)-10),
                new CoerceCase("Number→byte",       1L,        byte.class,   0L,  (byte)0),
                new CoerceCase("str→byte",          "1",       byte.class,  "99", (byte)99),
                new CoerceCase("empty→byte",        "",        byte.class,   "",  (byte)0),
                new CoerceCase("char→byte",         'A',       byte.class,  'A', (byte)65),
                new CoerceCase("int→Byte",          (byte)1,   Byte.class,   42,  (byte)42),
                new CoerceCase("str→Byte",          "1",       Byte.class,  "0",  (byte)0)
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // To short / Short
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Coerce to short/Short")
    class ShortCoerce {
        static Stream<CoerceCase> cases() {
            return Stream.of(
                new CoerceCase("int→short",      (short)1,    short.class,   100,  (short)100),
                new CoerceCase("str→short",      "1",         short.class,  "200", (short)200),
                new CoerceCase("empty→short",    "",          short.class,   "",   (short)0),
                new CoerceCase("char→short",     'Z',         short.class,  'Z', (short)90),
                new CoerceCase("int→Short",      (short)1,    Short.class,   100,  (short)100),
                new CoerceCase("str→Short",      "1",         Short.class,  "200", (short)200)
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // To char / Character
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Coerce to char/Character")
    class CharCoerce {
        static Stream<CoerceCase> cases() {
            return Stream.of(
                new CoerceCase("Number→char",     (short)65,   char.class,    65,   'A'),
                new CoerceCase("str→char",        " ",         char.class,   "A",   'A'),
                new CoerceCase("empty→char",      "",          char.class,    "",   '\0'),
                new CoerceCase("str(multi)→char", " ",         char.class,  "Hello", 'H'),
                new CoerceCase("int→Character",   (short)66,   Character.class, 66, 'B'),
                new CoerceCase("str→Character",   " ",         Character.class, "X", 'X')
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // To int / Integer
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Coerce to int/Integer")
    class IntCoerce {
        static Stream<CoerceCase> cases() {
            return Stream.of(
                new CoerceCase("null->int",      null,     int.class,      null,       0),
                new CoerceCase("int→int",        1,        int.class,      42,         42),
                new CoerceCase("long→int",       1L,       int.class,      99L,        99),
                new CoerceCase("double→int",     1.0,      int.class,      3.9,        3),
                new CoerceCase("char→int",       'A',      int.class,     'A',         65),
                new CoerceCase("str→int",        "1",      int.class,     "123",       123),
                new CoerceCase("empty→int",      "",       int.class,      "",         0),
                new CoerceCase("neg→int",        "1",      int.class,     "-42",       -42),

                new CoerceCase("null->Integer",  null,     Integer.class, null,        0),
                new CoerceCase("int→Integer",    1,        Integer.class, 42,          42),
                new CoerceCase("long→Integer",   1L,       Integer.class, 99L,         99),
                new CoerceCase("double→Integer", 1.0,      Integer.class, 3.9,         3),
                new CoerceCase("char→Integer",   'A',      Integer.class, 'A',         65),
                new CoerceCase("str→Integer",    "1",      Integer.class, "123",       123),
                new CoerceCase("empty→Integer",  "",       Integer.class, "",          0),
                new CoerceCase("neg→Integer",    "1",      Integer.class, "-42",       -42),

                new CoerceCase("bool->int",   true,     int.class, true,         0, true),
                new CoerceCase("object->int", "object", int.class, new Object(), 0, true)
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // To long / Long
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Coerce to long/Long")
    class LongCoerce {
        static Stream<CoerceCase> cases() {
            return Stream.of(
                new CoerceCase("null->long",       null,     long.class,  null,        0),
                new CoerceCase("int→long",         1,        long.class,  42,          42L),
                new CoerceCase("long→long",        1L,       long.class,  999L,        999L),
                new CoerceCase("char→long",        'A',      long.class,  'A',         65L),
                new CoerceCase("str→long",         "1",      long.class,  "9999",      9999L),
                new CoerceCase("empty→long",       "",       long.class,   "",         0L),
                new CoerceCase("int→Long",         1,        Long.class,   42,         42L),
                new CoerceCase("str(neg)→Long",    "1",      Long.class,  "-9999",     -9999L),
                new CoerceCase("double→Long",      1.0,      Long.class,   5.9,        5L)
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // To float / Float
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Coerce to float/Float")
    class FloatCoerce {
        static Stream<CoerceCase> cases() {
            return Stream.of(
                new CoerceCase("null->float",    null,    float.class,    null,       0.0f),
                new CoerceCase("int→float",      1,        float.class,   42,         42.0f),
                new CoerceCase("double→float",   1.0,      float.class,   3.14,       3.14f),
                new CoerceCase("char→float",     'A',      float.class,   'A',        65.0f),
                new CoerceCase("str→float",      "1.0",    float.class,   "2.5",      2.5f),
                new CoerceCase("empty→float",    "",       float.class,    "",        0.0f),
                new CoerceCase("int→Float",      1,        Float.class,    42,        42.0f),
                new CoerceCase("str(neg)→Float", "1.0",    Float.class,   "-2.5",     -2.5f)
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // To double / Double
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Coerce to double/Double")
    class DoubleCoerce {
        static Stream<CoerceCase> cases() {
            return Stream.of(
                new CoerceCase("null->double",    null,     double.class,  null,       0.0),
                new CoerceCase("int→double",      1,        double.class,  42,         42.0),
                new CoerceCase("long→double",     1L,       double.class,  100L,       100.0),
                new CoerceCase("char→double",     'A',      double.class,  'A',        65.0),
                new CoerceCase("str→double",      "1.0",    double.class,  "3.14159",  3.14159),
                new CoerceCase("empty→double",    "",       double.class,   "",        0.0),
                new CoerceCase("str(neg)→double", "1.0",    double.class,  "-2.718",   -2.718),
                new CoerceCase("int→Double",      1,        Double.class,   42,        42.0),
                new CoerceCase("str→Double",      "1.0",    Double.class,  "99.9",     99.9)
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // To BigInteger
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Coerce to BigInteger")
    class BigIntegerCoerce {
        static Stream<CoerceCase> cases() {
            return Stream.of(
                new CoerceCase("null->BigInteger",      null,              BigInteger.class, null,    BigInteger.ZERO),
                new CoerceCase("int→BigInteger",        1,                 BigInteger.class, 42,      BigInteger.valueOf(42)),
                new CoerceCase("long→BigInteger",       1L,                BigInteger.class, 999L,    BigInteger.valueOf(999)),
                new CoerceCase("char→BigInteger",       'A',               BigInteger.class, 'A',     BigInteger.valueOf(65)),
                new CoerceCase("BigDecimal→BigInteger", BigDecimal.TEN,    BigInteger.class, BigDecimal.valueOf(100), BigInteger.valueOf(100)),
                new CoerceCase("Decimal→BigInteger",    Decimal.ONE,       BigInteger.class, Decimal.valueOf(50), BigInteger.valueOf(50)),
                new CoerceCase("Rational→BigInteger",   Rational.ONE,      BigInteger.class, Rational.valueOf(25), BigInteger.valueOf(25)),
                new CoerceCase("str→BigInteger",        "1",               BigInteger.class, "1234567890", new BigInteger("1234567890")),
                new CoerceCase("empty→BigInteger",      "",                BigInteger.class, "",       BigInteger.ZERO),
                new CoerceCase("str(neg)→BigInteger",   "1",               BigInteger.class, "-999",   new BigInteger("-999"))
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // To BigDecimal
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Coerce to BigDecimal")
    class BigDecimalCoerce {
        static Stream<CoerceCase> cases() {
            return Stream.of(
                new CoerceCase("null->BigDecimal",      null,           BigDecimal.class, null,      BigDecimal.ZERO),
                new CoerceCase("int→BigDecimal",        1,              BigDecimal.class, 42,        BigDecimal.valueOf(42)),
                new CoerceCase("BigInteger→BigDecimal", BigInteger.ONE, BigDecimal.class, BigInteger.valueOf(100), BigDecimal.valueOf(100)),
                new CoerceCase("Decimal→BigDecimal",    Decimal.ONE,    BigDecimal.class, Decimal.valueOf(50), BigDecimal.valueOf(50)),
                new CoerceCase("Rational→BigDecimal",   Rational.ONE,   BigDecimal.class, Rational.valueOf(25), BigDecimal.valueOf(25)),
                new CoerceCase("char→BigDecimal",       'A',            BigDecimal.class, 'A',       BigDecimal.valueOf(65)),
                new CoerceCase("str→BigDecimal",        "1.5",          BigDecimal.class, "3.14159", new BigDecimal("3.14159")),
                new CoerceCase("empty→BigDecimal",      "",             BigDecimal.class, "",        BigDecimal.ZERO)
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // To Decimal (ELite type)
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Coerce to Decimal")
    class DecimalCoerce {
        static Stream<CoerceCase> cases() {
            return Stream.of(
                new CoerceCase("null->Decimal",      null,           Decimal.class, null,      BigDecimal.ZERO),
                new CoerceCase("int→Decimal",        1,              Decimal.class, 42,        Decimal.valueOf(42)),
                new CoerceCase("BigInteger→Decimal", BigInteger.ONE, Decimal.class, BigInteger.TEN, Decimal.valueOf(10)),
                new CoerceCase("BigDecimal→Decimal", BigDecimal.ONE, Decimal.class, BigDecimal.valueOf(100), Decimal.valueOf(100)),
                new CoerceCase("char→Decimal",       'A',            Decimal.class, 'A',       Decimal.valueOf(65)),
                new CoerceCase("str→Decimal",        "1.5",          Decimal.class, "2.718",   Decimal.valueOf("2.718")),
                new CoerceCase("empty→Decimal",      "",             Decimal.class, "",         Decimal.ZERO)
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // To Rational (ELite type)
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Coerce to Rational")
    class RationalCoerce {
        static Stream<CoerceCase> cases() {
            return Stream.of(
                new CoerceCase("null->Rational",     null,   Rational.class, null,      BigDecimal.ZERO),
                new CoerceCase("int→Rational",       1,      Rational.class, 42,        Rational.valueOf(42)),
                new CoerceCase("double→Rational",    1.0,    Rational.class, 2.5,       Rational.valueOf(2.5)),
                new CoerceCase("char→Rational",      'A',    Rational.class, 'A',       Rational.valueOf(65)),
                new CoerceCase("str→Rational",       "1/2",  Rational.class, "3/4",     Rational.valueOf("3/4")),
                new CoerceCase("empty→Rational",     "",     Rational.class, "",         Rational.ZERO)
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // To Enum
    // ══════════════════════════════════════════════════════════════════

    enum Color {
        RED, GREEN, BLUE;
    }

    @Nested
    @DisplayName("Coerce to Enum")
    class EnumCoerce {
        static Stream<CoerceCase> cases() {
            return Stream.of(
              new CoerceCase("null->enum",       null,        Color.class,  null,         null),
              new CoerceCase("enum->enum",       Color.RED,   Color.class,  Color.RED,    Color.RED),
              new CoerceCase("str(BLUE)->enum",  "BLUE",      Color.class,  "BLUE",       Color.BLUE),
              new CoerceCase("str(BLACK)->enum", "BLACK",     Color.class,  "BLACK",      null, true),
              new CoerceCase("int->enum",        0,           Color.class,   0,           null, true),
              new CoerceCase("enum->int",        Color.GREEN, int.class,     Color.GREEN, null, true)
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // To Seq / List
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Coerce to Seq/List")
    class SeqCoerce {
        @Test
        void listToSeq() {
            List<String> list = List.of("a", "b", "c");
            Object result = coerceResult(list, Seq.class, list);
            assertTrue(result instanceof Seq, "List should coerce to Seq but got " + result.getClass());
        }

        @Test
        void arrayToSeq() {
            String[] arr = {"x", "y"};
            Object result = coerceResult(arr, Seq.class, arr);
            assertTrue(result instanceof Seq, "Array should coerce to Seq but got " + result.getClass());
        }

        @Test
        void listIdentity() {
            // List is already an instance of List, identity path returns as-is
            List<Integer> list = List.of(1, 2, 3);
            Object result = coerceResult(list, List.class, list);
            assertSame(list, result, "List→List should pass through via identity");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Self Coerce
    // ══════════════════════════════════════════════════════════════════

    static class SelfCoerceBean implements Coercible {
        @Override
        public Object coerce(Class<?> type) {
            if (type == Integer.class)
                return 42;
            if (type == String.class)
                return "hello";
            return null;
        }
    }

    @Nested
    @DisplayName("Self Coerce")
    class SelfCoerce {
        static Stream<CoerceCase> cases() {
            SelfCoerceBean bean = new SelfCoerceBean();
            return Stream.of(
              new CoerceCase("self->int",     bean,  int.class,     bean, 42),
              new CoerceCase("self->Integer", bean,  Integer.class, bean, 42),
              new CoerceCase("self->String",  bean,  String.class,  bean, "hello"),
              new CoerceCase("self->Long",    bean,  Long.class,    bean, null)
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // Identity / passthrough
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Identity (already correct type)")
    class IdentityCoerce {
        static Stream<CoerceCase> cases() {
            return Stream.of(
                new CoerceCase("String→String", "hello", String.class, "hello", "hello"),
                new CoerceCase("String->Object", "hello", Object.class, "hello", "hello"),
                new CoerceCase("int→int",       42,      int.class,    42,      42),
                new CoerceCase("Long→Long",     99L,     Long.class,   99L,     99L),
                new CoerceCase("Double→Double", 3.14,    Double.class, 3.14,    3.14),
                new CoerceCase("Boolean→Bool",  true,    Boolean.class, true,   true)
            );
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("cases")
        void test(CoerceCase c) { assertCoerce(c.sample, c.targetType, c.testValue, c.expected, c.expectError); }
    }

    // ══════════════════════════════════════════════════════════════════
    // Error cases
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Error / dynamic fallback cases")
    class ErrorCoerce {
        @Test
        void unsupportedTypeRoutesToDynamicCoerce() {
            // Falls through to TypeCoercion.coerce for unrecognized types
            Object obj = new Object();
            MethodHandle mh = CoercionBootstrap.dispatchCoerce(obj, java.awt.Point.class);
            assertNotNull(mh, "Should return a handle (dynamic fallback)");
            // Actually invoking it with an incompatible value will throw
            assertThrows(Exception.class, () -> coerceResult(obj, java.awt.Point.class, obj));
        }

        @Test
        void unknownNumberToEnumRoutesToDynamic() {
            // Number→Enum has no static dispatch path
            MethodHandle mh = CoercionBootstrap.dispatchCoerce(42,
                java.util.concurrent.TimeUnit.class);
            assertNotNull(mh, "Should return dynamic fallback handle");
        }
    }
}
