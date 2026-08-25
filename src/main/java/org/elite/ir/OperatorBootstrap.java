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
import elite.lang.Rational;
import elite.lang.Seq;
import org.elite.eval.EvaluationContext;
import org.elite.eval.GlobalScope;
import org.elite.eval.TypeCoercion;
import org.elite.eval.closure.MethodClosure;
import org.elite.eval.seq.Cons;
import org.elite.resolver.MethodResolver;
import javax.el.ELContext;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static org.elite.ir.BootstrapCommon.*;
import static java.lang.invoke.MethodType.*;
import static java.lang.invoke.MethodHandles.*;
import static org.elite.eval.ELUtils.*;
import static org.elite.resources.Resources.*;

public final class OperatorBootstrap {
  
  private OperatorBootstrap() {}

  private static final long LONG_SIG_BIT = (1L << 63);
  private static final int INT_SIG_BIT = (1 << 31);

  private static final MethodHandle MH_binaryDispatcher;
  private static final MethodHandle MH_unaryDispatcher;
  private static final MethodHandle MH_assignOpDispatcher;

  private static final MethodHandle MH_looksLikeFloats;

  private static final MethodHandle MH_intAdd,
                                    MH_longAdd,
                                    MH_longAddPromote,
                                    MH_floatAdd,
                                    MH_doubleAdd,
                                    MH_bigIntegerAdd,
                                    MH_bigDecimalAdd,
                                    MH_decimalAdd,
                                    MH_rationalAdd;

  private static final MethodHandle MH_intSub,
                                    MH_longSub,
                                    MH_longSubPromote,
                                    MH_floatSub,
                                    MH_doubleSub,
                                    MH_bigIntegerSub,
                                    MH_bigDecimalSub,
                                    MH_decimalSub,
                                    MH_rationalSub;

  private static final MethodHandle MH_intMul,
                                    MH_longMul,
                                    MH_longMulPromote,
                                    MH_floatMul,
                                    MH_doubleMul,
                                    MH_bigIntegerMul,
                                    MH_bigDecimalMul,
                                    MH_decimalMul,
                                    MH_rationalMul;

  private static final MethodHandle MH_intDiv,
                                    MH_longDiv,
                                    MH_floatDiv,
                                    MH_doubleDiv,
                                    MH_bigIntegerDiv,
                                    MH_bigDecimalDiv,
                                    MH_decimalDiv,
                                    MH_rationalDiv;

  private static final MethodHandle MH_intIDiv,
                                    MH_longIDiv,
                                    MH_floatIDiv,
                                    MH_doubleIDiv,
                                    MH_bigIntegerIDiv,
                                    MH_bigDecimalIDiv,
                                    MH_decimalIDiv,
                                    MH_rationalIDiv;

  private static final MethodHandle MH_intRem,
                                    MH_longRem,
                                    MH_floatRem,
                                    MH_doubleRem,
                                    MH_bigIntegerRem,
                                    MH_bigDecimalRem,
                                    MH_decimalRem,
                                    MH_rationalRem;

  private static final MethodHandle MH_longPow,
                                    MH_doublePow,
                                    MH_bigIntegerPow,
                                    MH_bigDecimalPow,
                                    MH_decimalPow,
                                    MH_rationalPow;

  private static final MethodHandle MH_booleanEq,
                                    MH_intEq,
                                    MH_longEq,
                                    MH_floatEq,
                                    MH_doubleEq,
                                    MH_arrayEq,
                                    MH_objectEq;

  private static final MethodHandle MH_booleanNe,
                                    MH_intNe,
                                    MH_longNe,
                                    MH_floatNe,
                                    MH_doubleNe,
                                    MH_arrayNe,
                                    MH_objectNe;

  private static final MethodHandle MH_intLt,
                                    MH_longLt,
                                    MH_floatLt,
                                    MH_doubleLt,
                                    MH_compareLt;

  private static final MethodHandle MH_intLe,
                                    MH_longLe,
                                    MH_floatLe,
                                    MH_doubleLe,
                                    MH_compareLe;

  private static final MethodHandle MH_intGt,
                                    MH_longGt,
                                    MH_floatGt,
                                    MH_doubleGt,
                                    MH_compareGt;

  private static final MethodHandle MH_intGe,
                                    MH_longGe,
                                    MH_floatGe,
                                    MH_doubleGe,
                                    MH_compareGe;

  private static final MethodHandle MH_compareTo;

  private static final MethodHandle MH_booleanBitAnd,
                                    MH_intBitAnd,
                                    MH_longBitAnd,
                                    MH_bigIntegerBitAnd;

  private static final MethodHandle MH_booleanBitOr,
                                    MH_intBitOr,
                                    MH_longBitOr,
                                    MH_bigIntegerBitOr;

  private static final MethodHandle MH_booleanXor,
                                    MH_intXor,
                                    MH_longXor,
                                    MH_bigIntegerXor;

  private static final MethodHandle MH_intShl,
                                    MH_longShl,
                                    MH_bigIntegerShl;

  private static final MethodHandle MH_intShr,
                                    MH_longShr,
                                    MH_bigIntegerShr;

  private static final MethodHandle MH_intUShr,
                                    MH_longUShr,
                                    MH_bigIntegerUShr;

  private static final MethodHandle MH_intNeg,
                                    MH_longNeg,
                                    MH_floatNeg,
                                    MH_doubleNeg,
                                    MH_bigIntegerNeg,
                                    MH_bigDecimalNeg,
                                    MH_decimalNeg,
                                    MH_rationalNeg,
                                    MH_stringNeg;

  private static final MethodHandle MH_intBitNot,
                                    MH_longBitNot,
                                    MH_bigIntegerBitNot,
                                    MH_bigDecimalBitNot;

  private static final MethodHandle MH_arrayEmpty;

  private static final MethodHandle MH_Closure_compose;
  private static final MethodHandle MH_Seq_append;
  private static final MethodHandle MH_Cons_make;
  private static final MethodHandle MH_newCons;
  private static final MethodHandle MH_stringCat;
  private static final MethodHandle MH_collectionAppend;
  private static final MethodHandle MH_collectionPrepend;
  private static final MethodHandle MH_arrayAppend;
  private static final MethodHandle MH_arrayPrepend;

  private static final MethodHandle MH_arrayContains;
  private static final MethodHandle MH_primitiveArrayContains;

  private static final MethodHandle MH_EitherLeft;
  private static final MethodHandle MH_EitherRight;

  static {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();

      MethodHandle mh_longValue = lookup.findVirtual(
        Number.class, "longValue", MethodType.methodType(long.class));
      MethodHandle mh_Rational_reduce = lookup.findVirtual(
        Rational.class, "reduce", MethodType.methodType(Number.class));

      MH_binaryDispatcher = lookup.findStatic(
        OperatorBootstrap.class, "binaryDispatcher",
        methodType(Object.class, MethodHandles.Lookup.class,
                   MutableCallSite.class, String.class, Object.class,
                   Object.class, EvaluationContext.class));

      MH_unaryDispatcher = lookup.findStatic(
        OperatorBootstrap.class, "unaryDispatcher",
        methodType(Object.class, MethodHandles.Lookup.class,
                   MutableCallSite.class, String.class, Object.class,
                   EvaluationContext.class));

      MH_assignOpDispatcher = lookup.findStatic(
        OperatorBootstrap.class, "assignOpDispatcher",
        methodType(Either.class, MethodHandles.Lookup.class,
                   MutableCallSite.class, String.class, Object.class,
                   Object.class, EvaluationContext.class));

      MH_looksLikeFloats = lookup.findStatic(
        OperatorBootstrap.class, "looksLikeFloats",
        methodType(boolean.class, Object.class, Object.class));

      MH_intAdd = lookup.findStatic(
        OperatorBootstrap.class, "intAdd",
        MethodType.methodType(Number.class, int.class, int.class));
      MH_longAdd = lookup.findStatic(
        OperatorBootstrap.class, "longAdd",
        MethodType.methodType(long.class, long.class, long.class));
      MH_longAddPromote = lookup.findStatic(
        OperatorBootstrap.class, "longAddPromote",
        MethodType.methodType(Number.class, long.class, long.class));
      MH_floatAdd = lookup.findStatic(
        OperatorBootstrap.class, "floatAdd",
        MethodType.methodType(float.class, float.class, float.class));
      MH_doubleAdd = lookup.findStatic(
        OperatorBootstrap.class, "doubleAdd",
        MethodType.methodType(double.class, double.class, double.class));
      MH_bigIntegerAdd = lookup.findVirtual(
        BigInteger.class, "add",
        MethodType.methodType(BigInteger.class, BigInteger.class));
      MH_bigDecimalAdd = lookup.findVirtual(
        BigDecimal.class, "add",
        MethodType.methodType(BigDecimal.class, BigDecimal.class));
      MH_decimalAdd = lookup.findVirtual(
        Decimal.class, "add",
        MethodType.methodType(Decimal.class, Decimal.class));
      MH_rationalAdd = filterReturnValue(
        lookup.findVirtual(
          Rational.class, "add",
          methodType(Rational.class, Rational.class)),
        mh_Rational_reduce);

      MH_intSub = lookup.findStatic(
        OperatorBootstrap.class, "intSub",
        MethodType.methodType(Number.class, int.class, int.class));
      MH_longSub = lookup.findStatic(
        OperatorBootstrap.class, "longSub",
        MethodType.methodType(long.class, long.class, long.class));
      MH_longSubPromote = lookup.findStatic(
        OperatorBootstrap.class, "longSubPromote",
        MethodType.methodType(Number.class, long.class, long.class));
      MH_floatSub = lookup.findStatic(
        OperatorBootstrap.class, "floatSub",
        MethodType.methodType(float.class, float.class, float.class));
      MH_doubleSub = lookup.findStatic(
        OperatorBootstrap.class, "doubleSub",
        MethodType.methodType(double.class, double.class, double.class));
      MH_bigIntegerSub = lookup.findVirtual(
        BigInteger.class, "subtract",
        MethodType.methodType(BigInteger.class, BigInteger.class));
      MH_bigDecimalSub = lookup.findVirtual(
        BigDecimal.class, "subtract",
        MethodType.methodType(BigDecimal.class, BigDecimal.class));
      MH_decimalSub = lookup.findVirtual(
        Decimal.class, "subtract",
        MethodType.methodType(Decimal.class, Decimal.class));
      MH_rationalSub = filterReturnValue(
        lookup.findVirtual(
          Rational.class, "subtract",
          methodType(Rational.class, Rational.class)),
        mh_Rational_reduce);

      MH_intMul = lookup.findStatic(
        OperatorBootstrap.class, "intMul",
        MethodType.methodType(Number.class, int.class, int.class));
      MH_longMul = lookup.findStatic(
        OperatorBootstrap.class, "longMul",
        MethodType.methodType(long.class, long.class, long.class));
      MH_longMulPromote = lookup.findStatic(
        OperatorBootstrap.class, "longMulPromote",
        MethodType.methodType(Number.class, long.class, long.class));
      MH_floatMul = lookup.findStatic(
        OperatorBootstrap.class, "floatMul",
        MethodType.methodType(float.class, float.class, float.class));
      MH_doubleMul = lookup.findStatic(
        OperatorBootstrap.class, "doubleMul",
        MethodType.methodType(double.class, double.class, double.class));
      MH_bigIntegerMul = lookup.findVirtual(
        BigInteger.class, "multiply",
        MethodType.methodType(BigInteger.class, BigInteger.class));
      MH_bigDecimalMul = lookup.findVirtual(
        BigDecimal.class, "multiply",
        MethodType.methodType(BigDecimal.class, BigDecimal.class));
      MH_decimalMul = lookup.findVirtual(
        Decimal.class, "multiply",
        MethodType.methodType(Decimal.class, Decimal.class));
      MH_rationalMul = filterReturnValue(
        lookup.findVirtual(
          Rational.class, "multiply",
          methodType(Rational.class, Rational.class)),
        mh_Rational_reduce);

      MH_intDiv =
        permuteArguments(
          filterArguments(
            lookup.findStatic(
              OperatorBootstrap.class, "intDiv",
              MethodType.methodType(Number.class, ELContext.class,
                                    int.class, int.class)),
            0, MH_getELContext),
          1, 2, 0);
      MH_longDiv =
        permuteArguments(
          filterArguments(
            lookup.findStatic(
              OperatorBootstrap.class, "longDiv",
              MethodType.methodType(Number.class, ELContext.class,
                                    long.class, long.class)),
            0, MH_getELContext),
          1, 2, 0);
      MH_bigIntegerDiv =
        permuteArguments(
          filterArguments(
            lookup.findStatic(
              OperatorBootstrap.class, "bigIntegerDiv",
              MethodType.methodType(Number.class, ELContext.class,
                                    BigInteger.class, BigInteger.class)),
            0, MH_getELContext),
          1, 2, 0);
      MH_bigDecimalDiv =
        permuteArguments(
          filterArguments(
            lookup.findStatic(
              OperatorBootstrap.class, "bigDecimalDiv",
              MethodType.methodType(Number.class, ELContext.class,
                                    BigDecimal.class, BigDecimal.class)),
            0, MH_getELContext),
          1, 2, 0);
      MH_floatDiv = lookup.findStatic(
        OperatorBootstrap.class, "floatDiv",
        MethodType.methodType(float.class, float.class, float.class));
      MH_doubleDiv = lookup.findStatic(
        OperatorBootstrap.class, "doubleDiv",
        MethodType.methodType(double.class, double.class, double.class));
      MH_decimalDiv = lookup.findVirtual(
        Decimal.class, "divide",
        MethodType.methodType(Decimal.class, Decimal.class));
      MH_rationalDiv = filterReturnValue(
        lookup.findVirtual(
          Rational.class, "divide",
          methodType(Rational.class, Rational.class)),
        mh_Rational_reduce);

      MH_intIDiv = lookup.findStatic(
        OperatorBootstrap.class, "intIDiv",
        MethodType.methodType(int.class, int.class, int.class));
      MH_longIDiv = lookup.findStatic(
        OperatorBootstrap.class, "longIDiv",
        MethodType.methodType(long.class, long.class, long.class));
      MH_floatIDiv = lookup.findStatic(
        OperatorBootstrap.class, "floatIDiv",
        MethodType.methodType(long.class, float.class, float.class));
      MH_doubleIDiv = lookup.findStatic(
        OperatorBootstrap.class, "doubleIDiv",
        MethodType.methodType(long.class, double.class, double.class));
      MH_bigIntegerIDiv = lookup.findVirtual(
        BigInteger.class, "divide",
        MethodType.methodType(BigInteger.class, BigInteger.class));
      MH_bigDecimalIDiv = lookup.findStatic(
        OperatorBootstrap.class, "bigDecimalIDiv",
        methodType(BigInteger.class, BigDecimal.class, BigDecimal.class));
      MH_decimalIDiv =
        filterReturnValue(
          lookup.findVirtual(
            Decimal.class, "divide",
            MethodType.methodType(Decimal.class, Decimal.class))
            .asType(methodType(Number.class, Decimal.class, Decimal.class)),
          mh_longValue);
      MH_rationalIDiv = filterReturnValue(
        lookup.findVirtual(
          Rational.class, "divide",
          methodType(Rational.class, Rational.class))
          .asType(methodType(Number.class, Rational.class, Rational.class)),
        mh_longValue);

      MH_intRem = lookup.findStatic(
        OperatorBootstrap.class, "intRem",
        MethodType.methodType(int.class, int.class, int.class));
      MH_longRem = lookup.findStatic(
        OperatorBootstrap.class, "longRem",
        MethodType.methodType(long.class, long.class, long.class));
      MH_floatRem = lookup.findStatic(
        OperatorBootstrap.class, "floatRem",
        MethodType.methodType(float.class, float.class, float.class));
      MH_doubleRem = lookup.findStatic(
        OperatorBootstrap.class, "doubleRem",
        MethodType.methodType(double.class, double.class, double.class));
      MH_bigIntegerRem = lookup.findVirtual(
        BigInteger.class, "remainder",
        MethodType.methodType(BigInteger.class, BigInteger.class));
      MH_bigDecimalRem = lookup.findVirtual(
        BigDecimal.class, "remainder",
        MethodType.methodType(BigDecimal.class, BigDecimal.class));
      MH_decimalRem = lookup.findVirtual(
        Decimal.class, "remainder",
        MethodType.methodType(Decimal.class, Decimal.class));
      MH_rationalRem = filterReturnValue(
        lookup.findVirtual(
          Rational.class, "remainder",
          methodType(Rational.class, Rational.class)),
        mh_Rational_reduce);

      MH_doublePow = lookup.findStatic(
        Math.class, "pow", methodType(double.class, double.class, double.class));
      MH_longPow =
        permuteArguments(
          filterArguments(
            lookup.findStatic(
              OperatorBootstrap.class, "longPow",
              methodType(Number.class, ELContext.class, long.class, int.class)),
            0, MH_getELContext),
          1, 2, 0);
      MH_bigIntegerPow =
        permuteArguments(
          filterArguments(
            lookup.findStatic(
              OperatorBootstrap.class, "bigIntegerPow",
              methodType(Number.class, ELContext.class, BigInteger.class,
                         int.class)),
            0, MH_getELContext),
          1, 2, 0);
      MH_bigDecimalPow =
        permuteArguments(
          filterArguments(
            lookup.findStatic(
              OperatorBootstrap.class, "bigDecimalPow",
              methodType(Number.class, ELContext.class, BigDecimal.class, int.class)),
            0, MH_getELContext),
          1, 2, 0);
      MH_decimalPow = lookup.findStatic(
        OperatorBootstrap.class, "decimalPow",
        methodType(Number.class, Decimal.class, int.class));
      MH_rationalPow =
        filterReturnValue(
          lookup.findVirtual(
            Rational.class, "pow",
            methodType(Rational.class, int.class)),
          mh_Rational_reduce);

      MH_booleanEq = lookup.findStatic(
        OperatorBootstrap.class, "booleanEq",
        methodType(boolean.class, boolean.class, boolean.class));
      MH_intEq = lookup.findStatic(
        OperatorBootstrap.class, "intEq",
        methodType(boolean.class, int.class, int.class));
      MH_longEq = lookup.findStatic(
        OperatorBootstrap.class, "longEq",
        methodType(boolean.class, long.class, long.class));
      MH_floatEq = lookup.findStatic(
        OperatorBootstrap.class, "floatEq",
        methodType(boolean.class, float.class, float.class));
      MH_doubleEq = lookup.findStatic(
        OperatorBootstrap.class, "doubleEq",
        methodType(boolean.class, double.class, double.class));
      MH_arrayEq = lookup.findStatic(
        Arrays.class, "equals",
        methodType(boolean.class, Object[].class, Object[].class));
      MH_objectEq = lookup.findStatic(
        Objects.class, "equals",
        methodType(boolean.class, Object.class, Object.class));

      MH_booleanNe = lookup.findStatic(
        OperatorBootstrap.class, "booleanNe",
        methodType(boolean.class, boolean.class, boolean.class));
      MH_intNe = lookup.findStatic(
        OperatorBootstrap.class, "intNe",
        methodType(boolean.class, int.class, int.class));
      MH_longNe = lookup.findStatic(
        OperatorBootstrap.class, "longNe",
        methodType(boolean.class, long.class, long.class));
      MH_floatNe = lookup.findStatic(
        OperatorBootstrap.class, "floatNe",
        methodType(boolean.class, float.class, float.class));
      MH_doubleNe = lookup.findStatic(
        OperatorBootstrap.class, "doubleNe",
        methodType(boolean.class, double.class, double.class));
      MH_arrayNe = lookup.findStatic(
        OperatorBootstrap.class, "arrayNe",
        methodType(boolean.class, Object[].class, Object[].class));
      MH_objectNe = lookup.findStatic(
        OperatorBootstrap.class, "objectNe",
        methodType(boolean.class, Object.class, Object.class));

      MH_intLt = lookup.findStatic(
        OperatorBootstrap.class, "intLt",
        methodType(boolean.class, int.class, int.class));
      MH_longLt = lookup.findStatic(
        OperatorBootstrap.class, "longLt",
        methodType(boolean.class, long.class, long.class));
      MH_floatLt = lookup.findStatic(
        OperatorBootstrap.class, "floatLt",
        methodType(boolean.class, float.class, float.class));
      MH_doubleLt = lookup.findStatic(
        OperatorBootstrap.class, "doubleLt",
        methodType(boolean.class, double.class, double.class));
      MH_compareLt = lookup.findStatic(
        OperatorBootstrap.class, "compareLt",
        methodType(boolean.class, Comparable.class, Comparable.class));

      MH_intLe = lookup.findStatic(
        OperatorBootstrap.class, "intLe",
        methodType(boolean.class, int.class, int.class));
      MH_longLe = lookup.findStatic(
        OperatorBootstrap.class, "longLe",
        methodType(boolean.class, long.class, long.class));
      MH_floatLe = lookup.findStatic(
        OperatorBootstrap.class, "floatLe",
        methodType(boolean.class, float.class, float.class));
      MH_doubleLe = lookup.findStatic(
        OperatorBootstrap.class, "doubleLe",
        methodType(boolean.class, double.class, double.class));
      MH_compareLe = lookup.findStatic(
        OperatorBootstrap.class, "compareLe",
        methodType(boolean.class, Comparable.class, Comparable.class));

      MH_intGt = lookup.findStatic(
        OperatorBootstrap.class, "intGt",
        methodType(boolean.class, int.class, int.class));
      MH_longGt = lookup.findStatic(
        OperatorBootstrap.class, "longGt",
        methodType(boolean.class, long.class, long.class));
      MH_floatGt = lookup.findStatic(
        OperatorBootstrap.class, "floatGt",
        methodType(boolean.class, float.class, float.class));
      MH_doubleGt = lookup.findStatic(
        OperatorBootstrap.class, "doubleGt",
        methodType(boolean.class, double.class, double.class));
      MH_compareGt = lookup.findStatic(
        OperatorBootstrap.class, "compareGt",
        methodType(boolean.class, Comparable.class, Comparable.class));

      MH_intGe = lookup.findStatic(
        OperatorBootstrap.class, "intGe",
        methodType(boolean.class, int.class, int.class));
      MH_longGe = lookup.findStatic(
        OperatorBootstrap.class, "longGe",
        methodType(boolean.class, long.class, long.class));
      MH_floatGe = lookup.findStatic(
        OperatorBootstrap.class, "floatGe",
        methodType(boolean.class, float.class, float.class));
      MH_doubleGe = lookup.findStatic(
        OperatorBootstrap.class, "doubleGe",
        methodType(boolean.class, double.class, double.class));
      MH_compareGe = lookup.findStatic(
        OperatorBootstrap.class, "compareGe",
        methodType(boolean.class, Comparable.class, Comparable.class));

      MH_compareTo = lookup.findVirtual(
        Comparable.class, "compareTo", methodType(int.class, Object.class));

      MH_booleanBitAnd = lookup.findStatic(
        OperatorBootstrap.class, "booleanBitAnd",
        methodType(boolean.class, boolean.class, boolean.class));
      MH_intBitAnd = lookup.findStatic(
        OperatorBootstrap.class, "intBitAnd",
        methodType(int.class, int.class, int.class));
      MH_longBitAnd = lookup.findStatic(
        OperatorBootstrap.class, "longBitAnd",
        methodType(long.class, long.class, long.class));
      MH_bigIntegerBitAnd = lookup.findVirtual(
        BigInteger.class, "and", methodType(BigInteger.class, BigInteger.class));

      MH_booleanBitOr = lookup.findStatic(
        OperatorBootstrap.class, "booleanBitOr",
        methodType(boolean.class, boolean.class, boolean.class));
      MH_intBitOr = lookup.findStatic(
        OperatorBootstrap.class, "intBitOr",
        methodType(int.class, int.class, int.class));
      MH_longBitOr = lookup.findStatic(
        OperatorBootstrap.class, "longBitOr",
        methodType(long.class, long.class, long.class));
      MH_bigIntegerBitOr = lookup.findVirtual(
        BigInteger.class, "or", methodType(BigInteger.class, BigInteger.class));

      MH_booleanXor = lookup.findStatic(
        OperatorBootstrap.class, "booleanXor",
        methodType(boolean.class, boolean.class, boolean.class));
      MH_intXor = lookup.findStatic(
        OperatorBootstrap.class, "intXor",
        methodType(int.class, int.class, int.class));
      MH_longXor = lookup.findStatic(
        OperatorBootstrap.class, "longXor",
        methodType(long.class, long.class, long.class));
      MH_bigIntegerXor = lookup.findVirtual(
        BigInteger.class, "xor", methodType(BigInteger.class, BigInteger.class));

      MH_intShl = lookup.findStatic(
        OperatorBootstrap.class, "intShl",
        methodType(int.class, int.class, int.class));
      MH_longShl = lookup.findStatic(
        OperatorBootstrap.class, "longShl",
        methodType(long.class, long.class, int.class));
      MH_bigIntegerShl = lookup.findVirtual(
        BigInteger.class, "shiftLeft",
        methodType(BigInteger.class, int.class));

      MH_intShr = lookup.findStatic(
        OperatorBootstrap.class, "intShr",
        methodType(int.class, int.class, int.class));
      MH_longShr = lookup.findStatic(
        OperatorBootstrap.class, "longShr",
        methodType(long.class, long.class, int.class));
      MH_bigIntegerShr = lookup.findVirtual(
        BigInteger.class, "shiftRight",
        methodType(BigInteger.class, int.class));

      MH_intUShr = lookup.findStatic(
        OperatorBootstrap.class, "intUShr",
        methodType(int.class, int.class, int.class));
      MH_longUShr = lookup.findStatic(
        OperatorBootstrap.class, "longUShr",
        methodType(Number.class, long.class, int.class));
      MH_bigIntegerUShr = lookup.findVirtual(
        BigInteger.class, "shiftRight",
        methodType(BigInteger.class, int.class));

      MH_intNeg = lookup.findStatic(
        OperatorBootstrap.class, "intNeg", methodType(int.class, int.class));
      MH_longNeg = lookup.findStatic(
        OperatorBootstrap.class, "longNeg", methodType(long.class, long.class));
      MH_floatNeg = lookup.findStatic(
        OperatorBootstrap.class, "floatNeg", methodType(float.class, float.class));
      MH_doubleNeg = lookup.findStatic(
        OperatorBootstrap.class, "doubleNeg", methodType(double.class, double.class));
      MH_bigIntegerNeg = lookup.findVirtual(
        BigInteger.class, "negate", methodType(BigInteger.class));
      MH_bigDecimalNeg = lookup.findVirtual(
        BigDecimal.class, "negate", methodType(BigDecimal.class));
      MH_decimalNeg = lookup.findVirtual(
        Decimal.class, "negate", methodType(Decimal.class));
      MH_rationalNeg = lookup.findVirtual(
        Rational.class, "negate", methodType(Rational.class));
      MH_stringNeg = lookup.findStatic(
        OperatorBootstrap.class, "stringNeg",
        methodType(Object.class, String.class));

      MH_intBitNot = lookup.findStatic(
        OperatorBootstrap.class, "intBitNot",
        methodType(int.class, int.class));
      MH_longBitNot = lookup.findStatic(
        OperatorBootstrap.class, "longBitNot",
        methodType(long.class, long.class));
      MH_bigIntegerBitNot = lookup.findVirtual(
        BigInteger.class, "not", methodType(BigInteger.class));
      MH_bigDecimalBitNot =
        filterReturnValue(
          lookup.findVirtual(BigDecimal.class, "toBigInteger",
                             methodType(BigInteger.class)),
          MH_bigIntegerBitNot);

      MH_arrayEmpty = lookup.findStatic(
        OperatorBootstrap.class, "arrayEmpty",
        methodType(boolean.class, Object.class));

      MH_Closure_compose = lookup.findVirtual(
        Closure.class, "compose", methodType(Closure.class, Closure.class));
      MH_Seq_append = lookup.findVirtual(
        Seq.class, "append", methodType(Seq.class, Seq.class));
      MH_Cons_make = lookup.findStatic(
          Cons.class, "make", methodType(Cons.class, Object.class))
        .asType(methodType(Seq.class, Object.class));
      MH_newCons = lookup.findConstructor(
        Cons.class, methodType(void.class, Object.class, Seq.class));
      MH_collectionAppend = lookup.findStatic(
        OperatorBootstrap.class, "collectionAppend",
        methodType(Object.class, Collection.class, Object.class));
      MH_collectionPrepend = lookup.findStatic(
        OperatorBootstrap.class, "collectionPrepend",
        methodType(Object.class, Object.class, Collection.class));
      MH_arrayAppend =
        permuteArguments(
          filterArguments(
            lookup.findStatic(
              OperatorBootstrap.class, "arrayAppend",
              methodType(Object.class, ELContext.class, Object.class, Object.class)),
            0, MH_getELContext),
          1, 2, 0);
      MH_arrayPrepend =
        permuteArguments(
          filterArguments(
            lookup.findStatic(
              OperatorBootstrap.class, "arrayPrepend",
              methodType(Object.class, ELContext.class, Object.class, Object.class)),
            0, MH_getELContext),
          1, 2, 0);
      MH_stringCat = lookup.findStatic(
        OperatorBootstrap.class, "stringCat",
        methodType(String.class, Object.class, Object.class));

      MH_arrayContains = lookup.findStatic(
        OperatorBootstrap.class, "arrayContains",
        methodType(boolean.class, Object.class, Object[].class));
      MH_primitiveArrayContains = lookup.findStatic(
        OperatorBootstrap.class, "primitiveArrayContains",
        methodType(boolean.class, Object.class, Object.class));

      MH_EitherLeft = lookup.findStatic(
        Either.class, "left", methodType(Either.class, Object.class));
      MH_EitherRight = lookup.findStatic(
        Either.class, "right", methodType(Either.class, Object.class));

    } catch (NoSuchMethodException | IllegalAccessException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private static MethodHandle commonCoerce(MethodHandle target, Class<?> type,
                                           Object lhs, Object rhs) {
    if (!type.isInstance(lhs))
      target = filterArguments(target, 0, makeCoerce(lhs, type));
    if (!type.isInstance(rhs))
      target = filterArguments(target, 1, makeCoerce(rhs, type));
    return target;
  }

  //=------------------------------------------------------------------------=//

  @SuppressWarnings("unused")
  public static CallSite binaryBootstrap(MethodHandles.Lookup lookup,
                                         String name,
                                         MethodType callsiteType) {
    MutableCallSite cs = new MutableCallSite(callsiteType);
    MethodHandle target = insertArguments(MH_binaryDispatcher, 0,
                                          lookup, cs, name);
    target = target.asType(callsiteType);
    cs.setTarget(target);
    return cs;
  }

  private static Object binaryDispatcher(MethodHandles.Lookup lookup,
                                         MutableCallSite cs, String name,
                                         Object lhs, Object rhs,
                                         EvaluationContext env)
    throws Throwable
  {
    MethodHandle target = dispatchBinary(lookup, env, name, lhs, rhs);
    target = target.asType(cs.type());

    Class<?> lhsType = lhs == null ? null : lhs.getClass();
    Class<?> rhsType = rhs == null ? null : rhs.getClass();
    MethodHandle guard = insertArguments(MH_classesEqual, 2, lhsType, rhsType);

    // Adapt to (Object, Object, EvaluationContext)boolean.
    guard = dropArguments(guard, 2, EvaluationContext.class);

    // Create PIC guard.
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly invoke target for current call.
    return target.invoke(lhs, rhs, env);
  }

  private static MethodHandle dispatchBinary(MethodHandles.Lookup lookup,
                                             EvaluationContext env,
                                             String name, Object lhs,
                                             Object rhs) {
    MethodHandle target = switch (demangle(name)) {
      case "+"        -> dispatchAdd    (lookup, env, lhs, rhs);
      case "-"        -> dispatchSub    (lookup, env, lhs, rhs);
      case "*"        -> dispatchMul    (lookup, env, lhs, rhs);
      case "/"        -> dispatchDiv    (lookup, env, lhs, rhs);
      case "__div__"  -> dispatchIDiv   (lookup, env, lhs, rhs);
      case "%"        -> dispatchRem    (lookup, env, lhs, rhs);
      case "^"        -> dispatchPow    (lookup, env, lhs, rhs);
      case "=="       -> dispatchEq     (lookup, env, lhs, rhs);
      case "!="       -> dispatchNe     (lookup, env, lhs, rhs);
      case "<"        -> dispatchLt     (lookup, env, lhs, rhs);
      case "<="       -> dispatchLe     (lookup, env, lhs, rhs);
      case ">"        -> dispatchGt     (lookup, env, lhs, rhs);
      case ">="       -> dispatchGe     (lookup, env, lhs, rhs);
      case "<=>"      -> dispatchCmp    (lookup, env, lhs, rhs);
      case "`&"       -> dispatchBitAnd (lookup, env, lhs, rhs);
      case "`|"       -> dispatchBitOr  (lookup, env, lhs, rhs);
      case "`^"       -> dispatchXor    (lookup, env, lhs, rhs);
      case "<<"       -> dispatchShl    (lookup, env, lhs, rhs);
      case ">>"       -> dispatchShr    (lookup, env, lhs, rhs);
      case ">>>"      -> dispatchUshr   (lookup, env, lhs, rhs);
      case "~"        -> dispatchCat    (lookup, env, lhs, rhs);
      case "<-"       -> dispatchIn     (lookup, env, lhs, rhs);
      default -> throw new AssertionError("Unknown operator: " + name);
    };

    if (target.type().parameterCount() == 2)
      target = dropArguments(target, 2, EvaluationContext.class);
    return target;
  }

  private static MethodHandle dispatchBinaryOperator(MethodHandles.Lookup lookup,
                                                     EvaluationContext env,
                                                     String opname, Object lhs,
                                                     Object rhs) {
    if (isELiteObject(lhs)) {
      try {
        // Find the operator method in ELite class.
        //   [static] Object +(EvaluationContext, Object[])
        Method m = lhs.getClass().getMethod(
          mangle(opname), EvaluationContext.class, Object[].class);
        MetaMethod ann = m.getAnnotation(MetaMethod.class);
        if (Modifier.isStatic(m.getModifiers())) {
          if (ann != null && ann.arity() == 2 && !ann.varargs()) {
            // (env, [lhs, rhs]) -> (lhs, rhs, env)
            MethodHandle mh = lookup.unreflect(m).asCollector(Object[].class, 2);
            return permuteArguments(mh, 1, 2, 0);
          }
        } else {
          if (ann != null && ann.arity() == 1 && !ann.varargs()) {
            // (lhs, env, [rhs]) -> (lhs, rhs, env)
            MethodHandle mh = lookup.unreflect(m).asCollector(Object[].class, 1);
            return permuteArguments(mh, 0, 2, 1);
          }
        }
      } catch (NoSuchMethodException | IllegalAccessException e) {
        // fallthrough
      }
    } else if (lhs != null) {
      ELContext elctx = env.getELContext();
      MethodResolver resolver = MethodResolver.getInstance(elctx);
      MethodClosure mc;

      // Find static operator procedure.
      mc = resolver.resolveStaticMethod(lhs.getClass(), opname);
      if (mc != null) {
        Method m = mc.getJavaMethod(elctx, null, lhs, rhs);
        if (m == null) {
          return dropArguments(
            throwEvaluationException(_T(EL_FN_NO_SUCH_METHOD, opname,
                                        opname, lhs.getClass().getName())),
            0, Object.class, Object.class);
        }

        try {
          MethodHandle mh = lookup.unreflect(m);
          if (m.getParameterTypes()[0] == ELContext.class) {
            // (elctx, lhs, rhs) -> (lhs, rhs, env)
            mh = filterArguments(mh, 0, MH_getELContext);
            mh = permuteArguments(mh, 1, 2, 0);
          } else {
            mh = dropArguments(mh, 2, EvaluationContext.class);
          }
          return makeCoerce(mh, 0, lhs, rhs);
        } catch (IllegalAccessException e) {
          // fallthrough
        }
      }

      // Find instance operator method.
      mc = resolver.resolveMethod(lhs.getClass(), opname);
      if (mc != null) {
        Method m = mc.getJavaMethod(elctx, lhs, rhs);
        if (m == null) {
          return dropArguments(
            throwEvaluationException(_T(EL_FN_NO_SUCH_METHOD, opname,
                                        opname, lhs.getClass().getName())),
            0, Object.class, Object.class);
        }

        try {
          MethodHandle mh = lookup.unreflect(m);
          if (m.getParameterTypes()[0] == ELContext.class) {
            if (Modifier.isStatic(m.getModifiers())) {
              // For expando method, (elctx, lhs, rhs) -> (lhs, rhs, env)
              mh = permuteArguments(mh, 1, 2, 0);
            } else {
              // For instance method, (lhs, elctx, rhs) -> (lhs, rhs, env)
              mh = permuteArguments(mh, 0, 2, 1);
            }
            mh = filterArguments(mh, 2, MH_getELContext);
          } else {
            // (lhs, rhs) -> (lhs, rhs, env)
            mh = dropArguments(mh, 2, EvaluationContext.class);
          }
          return makeCoerce(mh, 0, lhs, rhs);
        } catch (IllegalAccessException e) {
          // fallthrough
        }
      }
    }

    if (isELiteObject(rhs)) {
      try {
        // Find the static operator method in ELite class.
        //   static Object +(EvaluationContext, Object[])
        Method m = rhs.getClass().getMethod(
          mangle(opname), EvaluationContext.class, Object[].class);
        MetaMethod ann = m.getAnnotation(MetaMethod.class);
        if (Modifier.isStatic(m.getModifiers()) &&
            ann != null && ann.arity() == 2 && !ann.varargs()) {
          // (env, [lhs, rhs]) -> (lhs, rhs, env)
          MethodHandle mh = lookup.unreflect(m).asCollector(Object[].class, 2);
          return permuteArguments(mh, 1, 2, 0);
        }
      } catch (NoSuchMethodException | IllegalAccessException e) {
        // fallthrough
      }

      try {
        // Find the instance reverse operator procedure.
        //   Object ?+(EvaluationContext, Object[])
        Method m = rhs.getClass().getMethod(
          mangle("?".concat(opname)), EvaluationContext.class, Object[].class);
        MetaMethod ann = m.getAnnotation(MetaMethod.class);
        if (!Modifier.isStatic(m.getModifiers()) &&
            ann != null && ann.arity() == 1 && !ann.varargs()) {
          // (rhs, env, [lhs]) -> (lhs, rhs, env)
          MethodHandle mh = lookup.unreflect(m).asCollector(Object[].class, 1);
          return permuteArguments(mh, 2, 0, 1);
        }
      } catch (NoSuchMethodException | IllegalAccessException e) {
        // fallthrough
      }
    } else if (rhs != null) {
      ELContext elctx = env.getELContext();
      MethodResolver resolver = MethodResolver.getInstance(elctx);
      MethodClosure mc;

      // Find static operator procedure.
      mc = resolver.resolveStaticMethod(rhs.getClass(), opname);
      if (mc != null) {
        Method m = mc.getJavaMethod(elctx, null, lhs, rhs);
        if (m == null) {
          return dropArguments(
            throwEvaluationException(_T(EL_FN_NO_SUCH_METHOD, opname,
                                        opname, rhs.getClass().getName())),
            0, Object.class, Object.class);
        }

        try {
          MethodHandle mh = lookup.unreflect(m);
          if (m.getParameterTypes()[0] == ELContext.class) {
            // (elctx, lhs, rhs) -> (lhs, rhs, env)
            mh = filterArguments(mh, 0, MH_getELContext);
            mh = permuteArguments(mh, 1, 2, 0);
          } else {
            mh = dropArguments(mh, 2, EvaluationContext.class);
          }
          return makeCoerce(mh, 0, lhs, rhs);
        } catch (IllegalAccessException e) {
          // fallthrough
        }
      }

      // Invoke expando reverse operator procedure.
      mc = resolver.resolveMethod(rhs.getClass(), "?".concat(opname));
      if (mc != null) {
        Method m = mc.getJavaMethod(elctx, rhs, lhs);
        if (m == null) {
          return dropArguments(
            throwEvaluationException(_T(EL_FN_NO_SUCH_METHOD, opname,
                                        opname, rhs.getClass().getName())),
            0, Object.class, Object.class);
        }

        try {
          MethodHandle mh = lookup.unreflect(m);
          if (m.getParameterTypes()[0] == ELContext.class) {
            if (Modifier.isStatic(m.getModifiers())) {
              // For expando method, (elctx, rhs, lhs) -> (lhs, rhs, env)
              mh = filterArguments(mh, 0, MH_getELContext);
              mh = permuteArguments(mh, 2, 1, 0);
            } else {
              // For instance method, (rhs, elctx, lhs) -> (lhs, rhs, env)
              mh = permuteArguments(mh, 2, 0, 1);
            }
          } else {
            // (rhs, lhs) -> (rhs, lhs, env) -> (lhs, rhs, env)
            mh = dropArguments(mh, 2, EvaluationContext.class);
            mh = permuteArguments(mh, 1, 0, 2);
          }
          return makeCoerce(mh, 0, lhs, rhs);
        } catch (IllegalAccessException e) {
          // fallthrough
        }
      }
    }

    return null;
  }

  //=------------------------------------------------------------------------=//

  private static MethodHandle dispatchArithmetic(MethodHandles.Lookup lookup,
                                                 EvaluationContext env,
                                                 String opname,
                                                 Object lhs, Object rhs,
                                                 MethodHandle intOp,
                                                 MethodHandle longOp,
                                                 MethodHandle floatOp,
                                                 MethodHandle doubleOp,
                                                 MethodHandle bigIntegerOp,
                                                 MethodHandle bigDecimalOp,
                                                 MethodHandle decimalOp,
                                                 MethodHandle rationalOp) {
    if (lhs == null || rhs == null)
      return dropArguments(throwNullPointerException(), 0, Object.class,
                           Object.class);

    MethodHandle operator = dispatchBinaryOperator(lookup, env, opname, lhs, rhs);
    if (operator != null)
      return operator;

    if (lhs.getClass() == rhs.getClass()) {
      if (lhs instanceof Integer || lhs instanceof Short || lhs instanceof Byte)
        return intOp;
      if (lhs instanceof Long)
        return longOp;
      if (lhs instanceof Float)
        return floatOp;
      if (lhs instanceof Double)
        return doubleOp;
      if (lhs instanceof BigInteger)
        return bigIntegerOp;
      if (lhs instanceof BigDecimal)
        return bigDecimalOp;
      if (lhs instanceof Decimal)
        return decimalOp;
      if (lhs instanceof Rational)
        return rationalOp;
    }

    if (lhs instanceof BigDecimal || rhs instanceof BigDecimal)
      return commonCoerce(bigDecimalOp, BigDecimal.class, lhs, rhs);
    if (lhs instanceof Decimal || rhs instanceof Decimal)
      return commonCoerce(decimalOp, Decimal.class, lhs, rhs);
    if (lhs instanceof Rational || rhs instanceof Rational)
      return commonCoerce(rationalOp, Rational.class, lhs, rhs);
    if (lhs instanceof BigInteger || rhs instanceof BigInteger)
      return commonCoerce(bigIntegerOp, BigInteger.class, lhs, rhs);

    if (lhs instanceof Double || rhs instanceof Double)
      return commonCoerce(doubleOp, double.class, lhs, rhs);
    if (lhs instanceof Float || rhs instanceof Float)
      return commonCoerce(floatOp, float.class, lhs, rhs);

    if (lhs instanceof CharSequence || rhs instanceof CharSequence) {
      return guardWithTest(
        MH_looksLikeFloats,
        commonCoerce(doubleOp, double.class, lhs, rhs)
          .asType(methodType(Object.class, Object.class, Object.class)),
        commonCoerce(intOp, int.class, lhs, rhs)
          .asType(methodType(Object.class, Object.class, Object.class)));
    }

    if (lhs instanceof Long || rhs instanceof Long)
      return commonCoerce(longOp, long.class, lhs, rhs);
    return commonCoerce(intOp, int.class, lhs, rhs);
  }

  private static boolean looksLikeFloats(Object lhs, Object rhs) {
    return looksLikeFloat(lhs) || looksLikeFloat(rhs);
  }

  private static MethodHandle dispatchAdd(MethodHandles.Lookup lookup,
                                          EvaluationContext env,
                                          Object lhs, Object rhs) {
    if (GlobalScope.isBigIntegerEnabled(env.getELContext())) {
      return dispatchArithmetic(lookup, env, "+", lhs, rhs, MH_intAdd,
                                MH_longAddPromote, MH_floatAdd, MH_doubleAdd,
                                MH_bigIntegerAdd, MH_bigDecimalAdd,
                                MH_decimalAdd, MH_rationalAdd);
    } else {
      return dispatchArithmetic(lookup, env, "+", lhs, rhs, MH_intAdd,
                                MH_longAdd, MH_floatAdd, MH_doubleAdd,
                                MH_bigIntegerAdd, MH_bigDecimalAdd,
                                MH_decimalAdd, MH_rationalAdd);
    }
  }

  private static Number intAdd(int x, int y) {
    int z = x + y;
    if ((~(x ^ y) & (x ^ z) & INT_SIG_BIT) != 0)
      return (long)x + (long)y;
    else
      return z;
  }

  private static long longAdd(long x, long y) {
    return x + y;
  }

  private static Number longAddPromote(long x, long y) {
    long z = x + y;
    if ((~(x ^ y) & (x ^ z) & LONG_SIG_BIT) != 0)
      return BigInteger.valueOf(x).add(BigInteger.valueOf(y));
    else
      return z;
  }

  private static float floatAdd(float x, float y) {
    return x + y;
  }

  private static double doubleAdd(double x, double y) {
    return x + y;
  }

  private static MethodHandle dispatchSub(MethodHandles.Lookup lookup,
                                          EvaluationContext env,
                                          Object lhs, Object rhs) {

    if (GlobalScope.isBigIntegerEnabled(env.getELContext())) {
      return dispatchArithmetic(lookup, env, "-", lhs, rhs, MH_intSub,
                                MH_longSubPromote, MH_floatSub, MH_doubleSub,
                                MH_bigIntegerSub, MH_bigDecimalSub,
                                MH_decimalSub, MH_rationalSub);
    } else {
      return dispatchArithmetic(lookup, env, "-", lhs, rhs, MH_intSub,
                                MH_longSub, MH_floatSub, MH_doubleSub,
                                MH_bigIntegerSub, MH_bigDecimalSub,
                                MH_decimalSub, MH_rationalSub);

    }
  }

  private static Number intSub(int x, int y) {
    int z = x - y;
    if ((~(x ^ -y) & (x ^ z) & INT_SIG_BIT) != 0)
      return (long)x - (long)y;
    else
      return z;
  }

  private static long longSub(long x, long y) {
    return x - y;
  }

  private static Number longSubPromote(long x, long y) {
    long z = x - y;
    if ((~(x ^ -y) & (x ^ z) & LONG_SIG_BIT) != 0)
      return BigInteger.valueOf(x).subtract(BigInteger.valueOf(y));
    else
      return z;
  }

  private static float floatSub(float x, float y) {
    return x - y;
  }

  private static double doubleSub(double x, double y) {
    return x - y;
  }

  private static MethodHandle dispatchMul(MethodHandles.Lookup lookup,
                                          EvaluationContext env,
                                          Object lhs, Object rhs) {
    if (GlobalScope.isBigIntegerEnabled(env.getELContext())) {
      return dispatchArithmetic(lookup, env, "*", lhs, rhs, MH_intMul,
                                MH_longMulPromote, MH_floatMul, MH_doubleMul,
                                MH_bigIntegerMul, MH_bigDecimalMul,
                                MH_decimalMul, MH_rationalMul);
    } else {
      return dispatchArithmetic(lookup, env, "*", lhs, rhs, MH_intMul,
                                MH_longMul, MH_floatMul, MH_doubleMul,
                                MH_bigIntegerMul, MH_bigDecimalMul,
                                MH_decimalMul, MH_rationalMul);
    }
  }

  private static Number intMul(int x, int y) {
    int z = x * y;
    if (y != 0 && z / y != x)  // overflow
      return (long)x * (long)y;
    return z;
  }

  private static long longMul(long x, long y) {
    return x * y;
  }

  private static Number longMulPromote(long x, long y) {
    long z = x * y;
    if (y != 0L && z / y != x)  // overflow
      return BigInteger.valueOf(x).multiply(BigInteger.valueOf(y));
    return z;
  }

  private static float floatMul(float x, float y) {
    return x * y;
  }

  private static double doubleMul(double x, double y) {
    return x * y;
  }

  private static MethodHandle dispatchDiv(MethodHandles.Lookup lookup,
                                          EvaluationContext env,
                                          Object lhs, Object rhs) {
    return dispatchArithmetic(lookup, env, "/", lhs, rhs,
                              MH_intDiv, MH_longDiv, MH_floatDiv, MH_doubleDiv,
                              MH_bigIntegerDiv, MH_bigDecimalDiv, MH_decimalDiv,
                              MH_rationalDiv);
  }

  private static Number intDiv(ELContext elctx, int x, int y) {
    if (x % y == 0)
      return x / y;
    if (GlobalScope.isRationalEnabled(elctx))
      return Rational.make(x, y);
    return (double)x / (double)y;
  }

  private static Number longDiv(ELContext elctx, long x, long y) {
    if (x % y == 0)
      return x / y;
    if (GlobalScope.isRationalEnabled(elctx))
      return Rational.make(x, y);
    return (double)x / (double)y;
  }

  private static Number bigIntegerDiv(ELContext elctx, BigInteger x, BigInteger y) {
    BigInteger[] r = x.divideAndRemainder(y);
    if (r[1].equals(BigInteger.ZERO))
      return r[0];

    if (GlobalScope.isRationalEnabled(elctx))
      return Rational.make(x, y);

    MathContext mc = GlobalScope.getMathContext(elctx);
    if (mc != null)
      return new BigDecimal(x).divide(new BigDecimal(y), mc);
    else
      return new BigDecimal(x).divide(new BigDecimal(y), RoundingMode.HALF_UP);
  }

  private static Number bigDecimalDiv(ELContext elctx, BigDecimal x, BigDecimal y) {
    MathContext mc = GlobalScope.getMathContext(elctx);
    if (mc == null) {
      mc = new MathContext((int)Math.min(
        x.precision() + (long)Math.ceil(10.0 * y.precision() / 3.0),
        Integer.MAX_VALUE), RoundingMode.HALF_UP);
    }
    return x.divide(y, mc);
  }

  private static float floatDiv(float x, float y) {
    return x / y;
  }

  private static double doubleDiv(double x, double y) {
    return x / y;
  }

  private static MethodHandle dispatchIDiv(MethodHandles.Lookup lookup,
                                          EvaluationContext env,
                                          Object lhs, Object rhs) {
    return dispatchArithmetic(lookup, env, "__div__", lhs, rhs,
                              MH_intIDiv, MH_longIDiv, MH_floatIDiv, MH_doubleIDiv,
                              MH_bigIntegerIDiv, MH_bigDecimalIDiv, MH_decimalIDiv,
                              MH_rationalIDiv);
  }

  private static int intIDiv(int x, int y) {
    return x / y;
  }

  private static long longIDiv(long x, long y) {
    return x / y;
  }

  private static BigInteger bigDecimalIDiv(BigDecimal x, BigDecimal y) {
    return x.toBigInteger().divide(y.toBigInteger());
  }

  private static long floatIDiv(float x, float y) {
    return (long)x / (long)y;
  }

  private static long doubleIDiv(double x, double y) {
    return (long)x / (long)y;
  }

  private static MethodHandle dispatchRem(MethodHandles.Lookup lookup,
                                          EvaluationContext env,
                                          Object lhs, Object rhs) {
    return dispatchArithmetic(lookup, env, "%", lhs, rhs,
                              MH_intRem, MH_longRem, MH_floatRem, MH_doubleRem,
                              MH_bigIntegerRem, MH_bigDecimalRem, MH_decimalRem,
                              MH_rationalRem);
  }

  private static int intRem(int x, int y) {
    return x % y;
  }

  private static long longRem(long x, long y) {
    return x % y;
  }

  private static float floatRem(float x, float y) {
    return x % y;
  }

  private static double doubleRem(double x, double y) {
    return x % y;
  }

  private static MethodHandle dispatchPow(MethodHandles.Lookup lookup,
                                          EvaluationContext env,
                                          Object lhs, Object rhs) {
    if (lhs == null || rhs == null)
      return dropArguments(throwNullPointerException(), 0, Object.class,
                           Object.class);

    MethodHandle operator = dispatchBinaryOperator(lookup, env, "^", lhs, rhs);
    if (operator != null)
      return operator;

    if (rhs instanceof Integer || rhs instanceof Short || rhs instanceof Byte) {
      if (lhs instanceof Long || lhs instanceof Integer ||
          lhs instanceof Short || lhs instanceof Byte)
        return MH_longPow;
      if (lhs instanceof BigInteger)
        return MH_bigIntegerPow;
      if (lhs instanceof BigDecimal)
        return MH_bigDecimalPow;
      if (lhs instanceof Decimal)
        return MH_decimalPow;
      if (lhs instanceof Rational)
        return MH_rationalPow;
    }

    return commonCoerce(MH_doublePow, double.class, lhs, rhs);
  }

  private static Number longPow(ELContext elctx, long m, int n) {
    if (n == 0)
      return 1;
    if (n == 1)
      return m;

    if (n > 0 && GlobalScope.isBigIntegerEnabled(elctx)) {
      BigInteger z = BigInteger.valueOf(m).pow(n);
      return z.bitLength() < 32 ? z.intValue() :
             z.bitLength() < 64 ? z.longValue()
                                : z;
    }

    if (GlobalScope.isRationalEnabled(elctx))
      return Rational.make(BigInteger.ONE, BigInteger.valueOf(m).pow(-n));

    return Math.pow(m, n);
  }

  private static Number bigIntegerPow(ELContext elctx, BigInteger x, int n) {
    if (n == 0)
      return 1;
    if (n == 1)
      return x;
    if (n > 0)
      return x.pow(n);
    if (GlobalScope.isRationalEnabled(elctx))
      return Rational.make(BigInteger.ONE, x.pow(-n));
    return Math.pow(x.doubleValue(), n);
  }

  private static Number bigDecimalPow(ELContext elctx, BigDecimal x, int n) {
    MathContext mc = (MathContext)elctx.getContext(MathContext.class);
    return (mc == null) ? x.pow(n) : x.pow(n, mc);
  }

  private static Number decimalPow(Decimal x, int n) {
    return Decimal.valueOf(x.toBigDecimal().pow(n));
  }

  //=------------------------------------------------------------------------=//

  private static MethodHandle dispatchEquality(MethodHandles.Lookup lookup,
                                               EvaluationContext env,
                                               String opname,
                                               Object lhs, Object rhs,
                                               MethodHandle booleanOp,
                                               MethodHandle intOp,
                                               MethodHandle longOp,
                                               MethodHandle floatOp,
                                               MethodHandle doubleOp,
                                               MethodHandle arrayOp,
                                               MethodHandle objectOp) {
    MethodHandle operator = dispatchBinaryOperator(lookup, env, opname, lhs, rhs);
    if (operator != null)
      return operator;

    if (lhs != null && rhs != null && lhs.getClass() == rhs.getClass())
      return objectOp;

    if (lhs instanceof Number && rhs instanceof Number) {
      if (isELiteObject(lhs) || isELiteObject(rhs))
        return objectOp;
      if (lhs instanceof BigDecimal || rhs instanceof BigDecimal)
        return commonCoerce(objectOp, BigDecimal.class, lhs, rhs);
      if (lhs instanceof Decimal || rhs instanceof Decimal)
        return commonCoerce(objectOp, Decimal.class, lhs, rhs);
      if (lhs instanceof Rational || rhs instanceof Rational)
        return commonCoerce(objectOp, Rational.class, lhs, rhs);
      if (lhs instanceof BigInteger || rhs instanceof BigInteger)
        return commonCoerce(objectOp, BigInteger.class, lhs, rhs);
      if (lhs instanceof Double || rhs instanceof Double)
        return commonCoerce(doubleOp, double.class, lhs, rhs);
      if (lhs instanceof Float || rhs instanceof Float)
        return commonCoerce(floatOp, float.class, lhs, rhs);
      if (lhs instanceof Long || rhs instanceof Long)
        return commonCoerce(longOp, long.class, lhs, rhs);
      return commonCoerce(intOp, int.class, lhs, rhs);
    }

    if (lhs instanceof Boolean || rhs instanceof Boolean)
      return commonCoerce(booleanOp, boolean.class, lhs, rhs);

    if (lhs instanceof Enum || rhs instanceof Enum) {
      Class<?> t = lhs instanceof Enum ? lhs.getClass() : rhs.getClass();
      return commonCoerce(objectOp, t, lhs, rhs);
    }

    if (lhs instanceof Object[] || rhs instanceof Object[])
      return arrayOp;

    if ((lhs instanceof Character && rhs instanceof Number) ||
        (lhs instanceof Number && rhs instanceof Character))
      return commonCoerce(intOp, char.class, lhs, rhs);

    return objectOp;
  }

  private static MethodHandle dispatchEq(MethodHandles.Lookup lookup,
                                         EvaluationContext env,
                                         Object lhs, Object rhs) {
    return dispatchEquality(lookup, env, "==", lhs, rhs,
                            MH_booleanEq, MH_intEq, MH_longEq, MH_floatEq,
                            MH_doubleEq, MH_arrayEq, MH_objectEq);
  }

  private static boolean booleanEq(boolean x, boolean y) {
    return x == y;
  }

  private static boolean intEq(int x, int y) {
    return x == y;
  }

  private static boolean longEq(long x, long y) {
    return x == y;
  }

  private static boolean floatEq(float x, float y) {
    return x == y;
  }

  private static boolean doubleEq(double x, double y) {
    return x == y;
  }

  private static MethodHandle dispatchNe(MethodHandles.Lookup lookup,
                                         EvaluationContext env,
                                         Object lhs, Object rhs) {
    return dispatchEquality(lookup, env, "!=", lhs, rhs,
                            MH_booleanNe, MH_intNe, MH_longNe, MH_floatNe,
                            MH_doubleNe, MH_arrayNe, MH_objectNe);
  }

  private static boolean booleanNe(boolean x, boolean y) {
    return x != y;
  }

  private static boolean intNe(int x, int y) {
    return x != y;
  }

  private static boolean longNe(long x, long y) {
    return x != y;
  }

  private static boolean floatNe(float x, float y) {
    return x != y;
  }

  private static boolean doubleNe(double x, double y) {
    return x != y;
  }

  private static boolean arrayNe(Object[] x, Object[] y) {
    return !Arrays.equals(x, y);
  }

  private static boolean objectNe(Object x, Object y) {
    return !Objects.equals(x, y);
  }

  //=------------------------------------------------------------------------=//

  private static MethodHandle dispatchComparison(MethodHandles.Lookup lookup,
                                                 EvaluationContext env,
                                                 String opname,
                                                 Object lhs, Object rhs,
                                                 MethodHandle intOp,
                                                 MethodHandle longOp,
                                                 MethodHandle floatOp,
                                                 MethodHandle doubleOp,
                                                 MethodHandle compareOp) {
    if (lhs == null || rhs == null) {
      MethodHandle cst = constant(boolean.class, false);
      return dropArguments(cst, 0, Object.class, Object.class);
    }

    MethodHandle operator = dispatchBinaryOperator(lookup, env, opname, lhs, rhs);
    if (operator != null)
      return operator;

    if (lhs instanceof BigDecimal || rhs instanceof BigDecimal)
      return commonCoerce(compareOp, BigDecimal.class, lhs, rhs);
    if (lhs instanceof Decimal || rhs instanceof Decimal)
      return commonCoerce(compareOp, Decimal.class, lhs, rhs);
    if (lhs instanceof Rational || rhs instanceof Rational)
      return commonCoerce(compareOp, Rational.class, lhs, rhs);
    if (lhs instanceof BigInteger || rhs instanceof BigInteger)
      return commonCoerce(compareOp, BigInteger.class, lhs, rhs);
    if (lhs instanceof Double || rhs instanceof Double)
      return commonCoerce(doubleOp, double.class, lhs, rhs);
    if (lhs instanceof Float || rhs instanceof Float)
      return commonCoerce(floatOp, float.class, lhs, rhs);
    if (lhs instanceof Long || rhs instanceof Long)
      return commonCoerce(longOp, long.class, lhs, rhs);
    if ((lhs instanceof Integer || lhs instanceof Short || lhs instanceof Byte) ||
        (rhs instanceof Integer || rhs instanceof Short || rhs instanceof Byte))
      return commonCoerce(intOp, int.class, lhs, rhs);
    if (lhs instanceof Character || rhs instanceof Character)
      return commonCoerce(intOp, char.class, lhs, rhs);
    if (lhs instanceof Comparable && rhs instanceof Comparable)
      return compareOp;

    return dropArguments(
      throwEvaluationException(_T(JSPRT_UNSUPPORTED_EVAL_TYPE,
                                  lhs.getClass().getName())),
      0, Object.class, Object.class);
  }

  private static MethodHandle dispatchLt(MethodHandles.Lookup lookup,
                                         EvaluationContext env,
                                         Object lhs, Object rhs) {
    return dispatchComparison(lookup, env, "<", lhs, rhs,
                              MH_intLt, MH_longLt, MH_floatLt, MH_doubleLt,
                              MH_compareLt);
  }

  private static boolean intLt(int x, int y) {
    return x < y;
  }

  private static boolean longLt(long x, long y) {
    return x < y;
  }

  private static boolean floatLt(float x, float y) {
    return x < y;
  }

  private static boolean doubleLt(double x, double y) {
    return x < y;
  }

  private static <T extends Comparable<T>> boolean compareLt(T x, T y) {
    return x.compareTo(y) < 0;
  }

  private static MethodHandle dispatchLe(MethodHandles.Lookup lookup,
                                         EvaluationContext env,
                                         Object lhs, Object rhs) {
    return dispatchComparison(lookup, env, "<=", lhs, rhs,
                              MH_intLe, MH_longLe, MH_floatLe, MH_doubleLe,
                              MH_compareLe);
  }

  private static boolean intLe(int x, int y) {
    return x <= y;
  }

  private static boolean longLe(long x, long y) {
    return x <= y;
  }

  private static boolean floatLe(float x, float y) {
    return x <= y;
  }

  private static boolean doubleLe(double x, double y) {
    return x <= y;
  }

  private static <T extends Comparable<T>> boolean compareLe(T x, T y) {
    return x.compareTo(y) <= 0;
  }

  private static MethodHandle dispatchGt(MethodHandles.Lookup lookup,
                                         EvaluationContext env,
                                         Object lhs, Object rhs) {
    return dispatchComparison(lookup, env, ">", lhs, rhs,
                              MH_intGt, MH_longGt, MH_floatGt, MH_doubleGt,
                              MH_compareGt);
  }

  private static boolean intGt(int x, int y) {
    return x > y;
  }

  private static boolean longGt(long x, long y) {
    return x > y;
  }

  private static boolean floatGt(float x, float y) {
    return x > y;
  }

  private static boolean doubleGt(double x, double y) {
    return x > y;
  }

  private static <T extends Comparable<T>> boolean compareGt(T x, T y) {
    return x.compareTo(y) > 0;
  }

  private static MethodHandle dispatchGe(MethodHandles.Lookup lookup,
                                         EvaluationContext env,
                                         Object lhs, Object rhs) {
    return dispatchComparison(lookup, env, ">=", lhs, rhs,
                              MH_intGe, MH_longGe, MH_floatGe, MH_doubleGe,
                              MH_compareGe);
  }

  private static boolean intGe(int x, int y) {
    return x >= y;
  }

  private static boolean longGe(long x, long y) {
    return x >= y;
  }

  private static boolean floatGe(float x, float y) {
    return x >= y;
  }

  private static boolean doubleGe(double x, double y) {
    return x >= y;
  }

  private static <T extends Comparable<T>> boolean compareGe(T x, T y) {
    return x.compareTo(y) >= 0;
  }

  private static MethodHandle dispatchCmp(MethodHandles.Lookup lookup,
                                          EvaluationContext env,
                                          Object lhs, Object rhs) {
    MethodHandle operator = dispatchBinaryOperator(lookup, env, "<=>", lhs, rhs);
    if (operator != null)
      return operator;

    if (lhs instanceof Comparable<?>) {
      MethodHandle coerce = makeCoerce(rhs, lhs.getClass());
      coerce = coerce.asType(methodType(Object.class, Object.class));
      return filterArguments(MH_compareTo, 1, coerce);
    }

    if (rhs instanceof Comparable<?>) {
      MethodHandle coerce = makeCoerce(lhs, rhs.getClass());
      coerce = coerce.asType(methodType(Object.class, Comparable.class));
      return filterArguments(MH_compareTo, 0, coerce);
    }

    return dropArguments(
      throwEvaluationException(_T(EL_NOT_COMPARABLE)),
      0, Object.class, Object.class);
  }

  //=------------------------------------------------------------------------=//

  private static MethodHandle dispatchBitwise(MethodHandles.Lookup lookup,
                                              EvaluationContext env,
                                              String opname,
                                              Object lhs, Object rhs,
                                              MethodHandle booleanOp,
                                              MethodHandle intOp,
                                              MethodHandle longOp,
                                              MethodHandle bigIntegerOp) {
    if (lhs == null || rhs == null)
      return dropArguments(throwNullPointerException(), 0, Object.class,
                           Object.class);

    MethodHandle operator = dispatchBinaryOperator(lookup, env, opname, lhs, rhs);
    if (operator != null)
      return operator;

    if (lhs instanceof Boolean || rhs instanceof Boolean)
      return commonCoerce(booleanOp, boolean.class, lhs, rhs);
    if (lhs instanceof BigInteger || rhs instanceof BigInteger)
      return commonCoerce(bigIntegerOp, BigInteger.class, lhs, rhs);
    if (lhs instanceof Long || rhs instanceof Long)
      return commonCoerce(longOp, long.class, lhs, rhs);
    return commonCoerce(intOp, int.class, lhs, rhs);
  }

  private static MethodHandle dispatchBitAnd(MethodHandles.Lookup lookup,
                                             EvaluationContext env,
                                             Object lhs, Object rhs) {
    return dispatchBitwise(lookup, env, "`&", lhs, rhs,
                           MH_booleanBitAnd, MH_intBitAnd, MH_longBitAnd,
                           MH_bigIntegerBitAnd);
  }

  private static boolean booleanBitAnd(boolean x, boolean y) {
    return x & y;
  }

  private static int intBitAnd(int x, int y) {
    return x & y;
  }

  private static long longBitAnd(long x, long y) {
    return x & y;
  }

  private static MethodHandle dispatchBitOr(MethodHandles.Lookup lookup,
                                            EvaluationContext env,
                                            Object lhs, Object rhs) {
    return dispatchBitwise(lookup, env, "`|", lhs, rhs,
                           MH_booleanBitOr, MH_intBitOr, MH_longBitOr,
                           MH_bigIntegerBitOr);
  }

  private static boolean booleanBitOr(boolean x, boolean y) {
    return x | y;
  }

  private static int intBitOr(int x, int y) {
    return x | y;
  }

  private static long longBitOr(long x, long y) {
    return x | y;
  }

  private static MethodHandle dispatchXor(MethodHandles.Lookup lookup,
                                          EvaluationContext env,
                                          Object lhs, Object rhs) {
    return dispatchBitwise(lookup, env, "`^", lhs, rhs,
                           MH_booleanXor, MH_intXor, MH_longXor,
                           MH_bigIntegerXor);
  }

  private static boolean booleanXor(boolean x, boolean y) {
    return x ^ y;
  }

  private static int intXor(int x, int y) {
    return x ^ y;
  }

  private static long longXor(long x, long y) {
    return x ^ y;
  }

  //=------------------------------------------------------------------------=//

  private static MethodHandle dispatchShift(MethodHandles.Lookup lookup,
                                            EvaluationContext env,
                                            String opname,
                                            Object lhs, Object rhs,
                                            MethodHandle intOp,
                                            MethodHandle longOp,
                                            MethodHandle bigIntegerOp) {
    if (lhs == null || rhs == null)
      return dropArguments(throwNullPointerException(), 0, Object.class,
                           Object.class);

    MethodHandle operator = dispatchBinaryOperator(lookup, env, opname, lhs, rhs);
    if (operator != null)
      return operator;

    if (lhs instanceof BigInteger)
      return makeCoerce(bigIntegerOp, 0, lhs, rhs);
    if (lhs instanceof Long)
      return makeCoerce(longOp, 0, lhs, rhs);
    return makeCoerce(intOp, 0, lhs, rhs);
  }

  private static MethodHandle dispatchShl(MethodHandles.Lookup lookup,
                                          EvaluationContext env,
                                          Object lhs, Object rhs) {
    return dispatchShift(lookup, env, "<<", lhs, rhs,
                         MH_intShl, MH_longShl, MH_bigIntegerShl);
  }

  private static int intShl(int x, int n) {
    return x << n;
  }

  private static long longShl(long x, int n) {
    return x << n;
  }

  private static MethodHandle dispatchShr(MethodHandles.Lookup lookup,
                                          EvaluationContext env,
                                          Object lhs, Object rhs) {
    return dispatchShift(lookup, env, ">>", lhs, rhs,
                         MH_intShr, MH_longShr, MH_bigIntegerShr);
  }

  private static int intShr(int x, int n) {
    return x >> n;
  }

  private static long longShr(long x, int n) {
    return x >> n;
  }

  private static MethodHandle dispatchUshr(MethodHandles.Lookup lookup,
                                           EvaluationContext env,
                                           Object lhs, Object rhs) {
    return dispatchShift(lookup, env, ">>>", lhs, rhs,
                         MH_intUShr, MH_longUShr, MH_bigIntegerUShr);
  }

  private static int intUShr(int x, int n) {
    return x >>> n;
  }

  private static Number longUShr(long x, int n) {
    return x >>> n;
  }

  //=------------------------------------------------------------------------=//

  private static MethodHandle dispatchCat(MethodHandles.Lookup lookup,
                                          EvaluationContext env,
                                          Object lhs, Object rhs) {
    MethodHandle operator = dispatchBinaryOperator(lookup, env, "~", lhs, rhs);
    if (operator != null)
      return operator;

    if (lhs instanceof Closure && rhs instanceof Closure)
      return MH_Closure_compose;

    if (lhs instanceof Seq) {
      if (rhs instanceof Collection) {
        return filterArguments(MH_Seq_append, 1, makeCoerce(rhs, Seq.class));
      } else {
        return filterArguments(MH_Seq_append, 1, MH_Cons_make);
      }
    }

    if (lhs instanceof Collection<?>)
      return MH_collectionAppend;

    if (lhs != null && lhs.getClass().isArray() && rhs != null)
      return MH_arrayAppend;

    if (rhs instanceof Seq)
      return MH_newCons;

    if (rhs instanceof Collection<?>)
      return MH_collectionPrepend;

    if (rhs != null && rhs.getClass().isArray())
      return MH_arrayPrepend;

    return MH_stringCat;
  }

  private static Object collectionAppend(Collection<?> c, Object o) {
    List<Object> result = new ArrayList<>(c);
    if (o instanceof Collection<?>)
      result.addAll((Collection<?>)o);
    else
      result.add(o);
    return result;
  }

  private static Object collectionPrepend(Object o, Collection<?> c) {
    List<Object> result = new ArrayList<>();
    result.add(o);
    result.addAll(c);
    return result;
  }

  private static Object arrayAppend(ELContext elctx, Object x, Object y) {
    Class<?> t = x.getClass();
    Class<?> c = t.getComponentType();
    int xlen = Array.getLength(x);

    if (y.getClass() == t) {
      // concatenate array with same type
      int ylen = Array.getLength(y);
      Object a = Array.newInstance(c, xlen + ylen);
      System.arraycopy(x, 0, a, 0, xlen);
      System.arraycopy(y, 0, a, xlen, ylen);
      return a;
    } else if (y.getClass().isArray()) {
      // concatenate array with different type
      int ylen = Array.getLength(y);
      Object[] a = new Object[xlen + ylen];
      for (int i = 0; i < xlen; i++)
        a[i] = Array.get(x, i);
      for (int i = 0; i < ylen; i++)
        a[xlen + i] = Array.get(y, i);
      return a;
    } else {
      // concatenate an array with an element
      Object a = Array.newInstance(c, xlen + 1);
      System.arraycopy(x, 0, a, 0, xlen);
      Array.set(a, xlen, TypeCoercion.coerce(elctx, y, c));
      return a;
    }
  }

  private static Object arrayPrepend(ELContext elctx, Object x, Object y) {
    Class<?> c = y.getClass().getComponentType();
    int len = Array.getLength(y);
    Object a = Array.newInstance(c, len + 1);
    Array.set(a, 0, TypeCoercion.coerce(elctx, x, c));
    System.arraycopy(y, 0, a, 1, len);
    return a;
  }

  private static String stringCat(Object lhs, Object rhs) {
    return String.valueOf(lhs) + rhs;
  }

  //=------------------------------------------------------------------------=//

  private static MethodHandle dispatchIn(MethodHandles.Lookup lookup,
                                         EvaluationContext env,
                                         Object lhs, Object rhs) {
    if (lhs == null || rhs == null)
      return dropArguments(constant(boolean.class, false), 0,
                           Object.class, Object.class, EvaluationContext.class);

    if (rhs.getClass().isArray()) {
      if (rhs.getClass().getComponentType().isPrimitive()) {
        MethodHandle coerce = makeCoerce(lhs, rhs.getClass().getComponentType());
        coerce = coerce.asType(methodType(Object.class, Object.class));
        return filterArguments(MH_primitiveArrayContains, 0, coerce);
      } else {
        return MH_arrayContains;
      }
    }

    MethodHandle mh = DynamicBootstrap.dispatchInvoke(
      lookup, env, "contains", false, new String[0], rhs, new Object[]{lhs});
    mh = mh.asCollector(Object[].class, 1);
    return permuteArguments(mh, 2, 1, 0);
  }

  private static boolean arrayContains(Object x, Object[] a) {
    return Arrays.asList(a).contains(x);
  }

  private static boolean primitiveArrayContains(Object x, Object a) {
    int length = Array.getLength(a);
    for (int i = 0; i < length; i++) {
      if (Objects.equals(x, Array.get(a, i)))
        return true;
    }
    return false;
  }

  //=------------------------------------------------------------------------=//

  @SuppressWarnings("unused")
  public static CallSite unaryBootstrap(MethodHandles.Lookup lookup, String name,
                                        MethodType callsiteType) {
    MutableCallSite cs = new MutableCallSite(callsiteType);
    MethodHandle target = insertArguments(MH_unaryDispatcher, 0, lookup, cs, name);
    target = target.asType(callsiteType);
    cs.setTarget(target);
    return cs;
  }

  private static Object unaryDispatcher(MethodHandles.Lookup lookup,
                                        MutableCallSite cs, String name,
                                        Object rhs, EvaluationContext env)
    throws Throwable
  {
    MethodHandle target = dispatchUnary(lookup, env, name, rhs);
    target = target.asType(cs.type());

    Class<?> rhsType = rhs == null ? null : rhs.getClass();
    MethodHandle guard = insertArguments(MH_classEquals, 1, rhsType);

    // Adapt to (Object, EvaluationContext)boolean.
    guard = dropArguments(guard, 1, EvaluationContext.class);

    // Create PIC guard.
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly invoke target for current call.
    return target.invoke(rhs, env);
  }

  private static MethodHandle dispatchUnary(MethodHandles.Lookup lookup,
                                            EvaluationContext env,
                                            String name, Object rhs) {
    MethodHandle target = switch (demangle(name)) {
      case "__neg__"    -> dispatchNeg(lookup, env, rhs);
      case "`!"         -> dispatchBitNot(lookup, env, rhs);
      case "__empty__"  -> dispatchEmpty(lookup, env, rhs);
      default           -> throw new AssertionError("Unknown operator: " + name);
    };

    if (target.type().parameterCount() == 1)
      target = dropArguments(target, 1, EvaluationContext.class);
    return target;
  }

  private static MethodHandle dispatchUnaryOperator(MethodHandles.Lookup lookup,
                                                    EvaluationContext env,
                                                    String name, String opname,
                                                    Object rhs) {
    if (isELiteObject(rhs)) {
      try {
        // Find the operator method in ELite class.
        //   [static] Object __neg__(EvaluationContext, Object[])
        Method m = rhs.getClass().getMethod(name, EvaluationContext.class,
                                            Object[].class);
        MetaMethod ann = m.getAnnotation(MetaMethod.class);
        if (Modifier.isStatic(m.getModifiers())) {
          if (ann != null && ann.arity() == 1 && !ann.varargs()) {
            // (env, rhs) -> (rhs, env)
            MethodHandle mh = lookup.unreflect(m).asCollector(Object[].class, 1);
            return permuteArguments(mh, 1, 0);
          }
        } else {
          if (ann != null && ann.arity() == 0 && !ann.varargs()) {
            // (rhs, env) -> already in order.
            return lookup.unreflect(m).asCollector(Object[].class, 0);
          }
        }
      } catch (NoSuchMethodException | IllegalAccessException e) {
        // fallthrough
      }
    } else {
      ELContext elctx = env.getELContext();
      MethodResolver resolver = MethodResolver.getInstance(elctx);
      MethodClosure mc;

      // Find static operator procedure.
      mc = resolver.resolveStaticMethod(rhs.getClass(), opname);
      if (mc != null) {
        Method m = mc.getJavaMethod(elctx, null, rhs);
        if (m == null) {
          return dropArguments(
            throwEvaluationException(_T(EL_FN_NO_SUCH_METHOD, opname,
                                        opname, rhs.getClass().getName())),
            0, Object.class);
        }

        try {
          MethodHandle mh = lookup.unreflect(m);
          if (m.getParameterTypes()[0] == ELContext.class) {
            // (elctx, rhs) -> (rhs, env)
            mh = filterArguments(mh, 0, MH_getELContext);
            mh = permuteArguments(mh, 1, 0);
          } else {
            mh = dropArguments(mh, 1, EvaluationContext.class);
          }
          return makeCoerce(mh, 0, rhs);
        } catch (IllegalAccessException e) {
          // fallthrough
        }
      }

      // Find instance operator method.
      mc = resolver.resolveMethod(rhs.getClass(), opname);
      if (mc != null) {
        Method m = mc.getJavaMethod(elctx, rhs);
        if (m == null) {
          return dropArguments(
            throwEvaluationException(_T(EL_FN_NO_SUCH_METHOD, opname,
                                        opname, rhs.getClass().getName())),
            0, Object.class);
        }

        try {
          MethodHandle mh = lookup.unreflect(m);
          if (m.getParameterTypes()[0] == ELContext.class) {
            if (Modifier.isStatic(m.getModifiers())) {
              // For expand method, (elctx, rhs)
              mh = permuteArguments(mh, 1, 0);
            } else {
              // For instance method, (rhs, elctx), already in order.
            }
            mh = filterArguments(mh, 1, MH_getELContext);
          } else {
            mh = dropArguments(mh, 1, EvaluationContext.class);
          }
          return makeCoerce(mh, 0, rhs);
        } catch (IllegalAccessException e) {
          // fallthrough
        }
      }
    }

    return null;
  }

  //=------------------------------------------------------------------------=//

  private static MethodHandle dispatchNeg(MethodHandles.Lookup lookup,
                                          EvaluationContext env, Object rhs) {
    if (rhs == null)
      return dropArguments(throwNullPointerException(), 0, Object.class);

    MethodHandle operator = dispatchUnaryOperator(
      lookup, env, "__neg__", "__neg__", rhs);
    if (operator != null)
      return operator;

    if (rhs instanceof Integer || rhs instanceof Short || rhs instanceof Byte ||
        rhs instanceof Character)
      return makeCoerce(MH_intNeg, 0, rhs);
    if (rhs instanceof Long)
      return MH_longNeg;
    if (rhs instanceof Float)
      return MH_floatNeg;
    if (rhs instanceof Double)
      return MH_doubleNeg;
    if (rhs instanceof BigInteger)
      return MH_bigIntegerNeg;
    if (rhs instanceof BigDecimal)
      return MH_bigDecimalNeg;
    if (rhs instanceof Decimal)
      return MH_decimalNeg;
    if (rhs instanceof Rational)
      return MH_rationalNeg;
    if (rhs instanceof String)
      return MH_stringNeg;

    return dropArguments(
      throwEvaluationException(_T(JSPRT_UNSUPPORTED_EVAL_TYPE,
                                  rhs.getClass().getName())),
      0, Object.class);
  }

  private static int intNeg(int x) {
    return -x;
  }

  private static long longNeg(long x) {
    return -x;
  }

  private static float floatNeg(float x) {
    return -x;
  }

  private static double doubleNeg(double x) {
    return -x;
  }

  private static Object stringNeg(String x) {
    if (looksLikeFloat(x))
      return -Double.parseDouble(x);
    else
      return -Long.parseLong(x);
  }

  //=------------------------------------------------------------------------=//

  private static MethodHandle dispatchBitNot(MethodHandles.Lookup lookup,
                                             EvaluationContext env, Object rhs) {
    if (rhs == null)
      return dropArguments(throwNullPointerException(), 0, Object.class);

    MethodHandle operator = dispatchUnaryOperator(
      lookup, env, "__bitnot__", "`!`", rhs);
    if (operator != null)
      return operator;

    if (rhs instanceof Integer || rhs instanceof Short || rhs instanceof Byte ||
        rhs instanceof Character)
      return makeCoerce(MH_intBitNot, 0, rhs);
    if (rhs instanceof Long)
      return MH_longBitNot;
    if (rhs instanceof BigInteger)
      return MH_bigIntegerBitNot;
    if (rhs instanceof BigDecimal)
      return MH_bigDecimalBitNot;
    return makeCoerce(MH_longBitNot, 0, rhs);
  }

  private static int intBitNot(int x) {
    return ~x;
  }

  private static long longBitNot(long x) {
    return ~x;
  }

  //=------------------------------------------------------------------------=//

  private static MethodHandle dispatchEmpty(MethodHandles.Lookup lookup,
                                            EvaluationContext env, Object rhs) {
    if (rhs == null) {
      MethodHandle cst = constant(boolean.class, false);
      return dropArguments(cst, 0, Object.class);
    }

    if (rhs.getClass().isArray())
      return MH_arrayEmpty;

    MethodHandle mh = DynamicBootstrap.dispatchInvoke(
      lookup, env, "isEmpty", false, new String[0], rhs, new Object[0]);
    mh = insertArguments(mh, 2, (Object)null);
    return permuteArguments(mh, 1, 0);
  }

  private static boolean arrayEmpty(Object x) {
    return Array.getLength(x) == 0;
  }

  //=------------------------------------------------------------------------=//

  @SuppressWarnings("unused")
  public static CallSite assignOpBootstrap(MethodHandles.Lookup lookup,
                                           String name,
                                           MethodType callsiteType) {
    MutableCallSite cs = new MutableCallSite(callsiteType);
    MethodHandle target = insertArguments(MH_assignOpDispatcher,
                                          0, lookup, cs, name);
    target = target.asType(callsiteType);
    cs.setTarget(target);
    return cs;
  }

  private static Either assignOpDispatcher(MethodHandles.Lookup lookup,
                                           MutableCallSite cs, String name,
                                           Object lhs, Object rhs,
                                           EvaluationContext env)
    throws Throwable
  {
    MethodHandle target = dispatchAssignOp(lookup, env, name, lhs, rhs);
    target = target.asType(cs.type());

    Class<?> lhsType = lhs == null ? null : lhs.getClass();
    Class<?> rhsType = rhs == null ? null : rhs.getClass();
    MethodHandle guard = insertArguments(MH_classesEqual, 2, lhsType, rhsType);
    guard = dropArguments(guard, 2, EvaluationContext.class);

    // Create PIC guard
    MethodHandle fallback = cs.getTarget();
    MethodHandle guarded = guardWithTest(guard, target, fallback);
    cs.setTarget(guarded);

    // Directly invoke target for current call.
    return (Either)target.invokeExact(lhs, rhs, env);
  }

  private static MethodHandle dispatchAssignOp(MethodHandles.Lookup lookup,
                                               EvaluationContext env,
                                               String name, Object lhs,
                                               Object rhs) {
    if (lhs == null || rhs == null)
      return dropArguments(throwNullPointerException(), 0, Object.class,
                           Object.class);

    String opname = name.concat("=");

    // Invoke assignment operator procedure
    if (isELiteObject(lhs)) {
      try {
        Method m = lhs.getClass()
          .getMethod(mangle(opname), EvaluationContext.class, Object[].class);
        MetaMethod ann = m.getAnnotation(MetaMethod.class);
        if (!Modifier.isStatic(m.getModifiers()) && ann != null &&
            ann.arity() == 1 && !ann.varargs()) {
          // (lhs, env, [rhs]) -> (lhs, rhs, env)
          MethodHandle mh = lookup.unreflect(m).asCollector(Object[].class, 1);
          mh = permuteArguments(mh, 0, 2, 1);
          mh = mh.asType(mh.type().changeReturnType(Object.class));
          return filterReturnValue(mh, MH_EitherLeft);
        }
      } catch (NoSuchMethodException | IllegalAccessException e) {
        // fallthrough
      }
    } else if (!(lhs instanceof Number)) {
      ELContext elctx = env.getELContext();
      MethodResolver resolver = MethodResolver.getInstance(elctx);
      MethodClosure mc = resolver.resolveMethod(lhs.getClass(), opname);

      if (mc != null) {
        Method m = mc.getJavaMethod(elctx, lhs, rhs);
        if (m == null) {
          return dropArguments(
            throwEvaluationException(_T(EL_FN_NO_SUCH_METHOD, opname,
                                        opname, lhs.getClass().getName())),
            0, Object.class, Object.class);
        }

        try {
          MethodHandle mh = lookup.unreflect(m);
          if (m.getParameterTypes()[0] == ELContext.class) {
            if (Modifier.isStatic(m.getModifiers())) {
              // For expando method, (elctx, lhs, rhs) -> (lhs, rhs, env)
              mh = permuteArguments(mh, 1, 2, 0);
            } else {
              // For instance method, (lhs, elctx, rhs) -> (lhs, rhs, env)
              mh = permuteArguments(mh, 0, 2, 1);
            }
            mh = filterArguments(mh, 2, MH_getELContext);
          } else {
            mh = dropArguments(mh, 2, EvaluationContext.class);
          }
          mh = makeCoerce(mh, 0, lhs, rhs);
          mh = mh.asType(mh.type().changeReturnType(Object.class));
          return filterReturnValue(mh, MH_EitherLeft);
        } catch (IllegalAccessException e) {
          // fallthrough
        }
      }
    }

    // Do standard evaluation.
    MethodHandle mh = dispatchBinary(lookup, env, name, lhs, rhs);
    mh = mh.asType(mh.type().changeReturnType(Object.class));
    return filterReturnValue(mh, MH_EitherRight);
  }
}
