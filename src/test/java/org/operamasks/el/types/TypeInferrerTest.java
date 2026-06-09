package org.operamasks.el.types;

import static org.junit.jupiter.api.Assertions.*;

import javax.el.ELContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Parser;

/**
 * Tests for the type inference system.
 */
class TypeInferrerTest {

    private TypeInferrer inferrer;
    private ELContext elctx;

    @BeforeEach
    void setUp() {
        elctx = ELEngine.createELContext();
        inferrer = new TypeInferrer(elctx);
    }

    private Type infer(String expr) {
        ELNode node = Parser.parseExpression(expr);
        Type t = inferrer.infer(node);
        node.inferredType = t;
        return t;
    }

    // ---- Literal inference ----

    @Test
    void inferIntegerLiteral() {
        assertEquals(Type.INTEGER, infer("42"));
    }

    @Test
    void inferDoubleLiteral() {
        assertEquals(Type.DOUBLE, infer("3.14"));
    }

    @Test
    void inferStringLiteral() {
        assertEquals(Type.STRING, infer("\"hello\""));
    }

    @Test
    void inferBooleanLiteral() {
        Type t1 = infer("true");
        assertTrue(t1 == Type.BOOLEAN || t1 == Type.DYNAMIC,
            "Expected Boolean or DYNAMIC, got " + t1);
    }

    @Test
    void inferNullLiteral() {
        assertEquals(Type.DYNAMIC, infer("null"));
    }

    // ---- Arithmetic inference ----

    @Test
    void inferIntegerAddition() {
        assertEquals(Type.INTEGER, infer("1 + 2"));
    }

    @Test
    void inferDoubleAddition() {
        assertEquals(Type.DOUBLE, infer("3.0 + 4.0"));
    }

    @Test
    void inferMultiplication() {
        assertEquals(Type.INTEGER, infer("4 * 5"));
    }

    @Test
    void inferPower() {
        assertEquals(Type.INTEGER, infer("2 ^ 8"));
    }

    @Test
    void inferNegation() {
        assertEquals(Type.INTEGER, infer("-42"));
    }

    @Test
    void inferStringConcat() {
        assertEquals(Type.STRING, infer("\"hello\" ~ \"world\""));
    }

    // ---- Comparison / Logical inference ----

    @Test
    void inferEqualityReturnsBoolean() {
        assertEquals(Type.BOOLEAN, infer("1 == 1"));
    }

    @Test
    void inferComparisonReturnsBoolean() {
        assertEquals(Type.BOOLEAN, infer("3 < 5"));
    }

    @Test
    void inferLogicalAndReturnsBoolean() {
        assertEquals(Type.BOOLEAN, infer("true && false"));
    }

    @Test
    void inferLogicalOrReturnsBoolean() {
        assertEquals(Type.BOOLEAN, infer("true || false"));
    }

    @Test
    void inferLogicalNotReturnsBoolean() {
        assertEquals(Type.BOOLEAN, infer("!true"));
    }

    // ---- List inference ----

    @Test
    void inferEmptyList() {
        Type t = infer("[]");
        assertNotNull(t);
    }

    @Test
    void inferListLiteral() {
        Type t = infer("[1, 2, 3]");
        assertNotNull(t);
    }

    // ---- Variable binding ----

    @Test
    void inferDefineBindsVariable() {
        ELNode node = Parser.parse("define x = 42");
        inferrer.infer(node);
        ELNode lookup = Parser.parseExpression("x");
        Type t = inferrer.infer(lookup);
        assertNotNull(t);
    }

    // ---- Type hierarchy ----

    @Test
    void integerIsSubtypeOfNumber() {
        assertTrue(Type.INTEGER.isSubtypeOf(Type.NUMBER));
    }

    @Test
    void numberIsNotSubtypeOfInteger() {
        assertFalse(Type.NUMBER.isSubtypeOf(Type.INTEGER));
    }

    @Test
    void dynamicIsSubtypeOfEverything() {
        assertTrue(Type.DYNAMIC.isSubtypeOf(Type.INTEGER));
        assertTrue(Type.DYNAMIC.isSubtypeOf(Type.STRING));
    }

    @Test
    void bottomIsSubtypeOfEverything() {
        assertTrue(Type.BOTTOM.isSubtypeOf(Type.INTEGER));
        assertTrue(Type.BOTTOM.isSubtypeOf(Type.DYNAMIC));
    }

    @Test
    void objectIsSupertypeOfInteger() {
        assertTrue(Type.INTEGER.isSubtypeOf(Type.OBJECT));
    }

    @Test
    void topIsSupertypeOfEverything() {
        assertTrue(Type.INTEGER.isSubtypeOf(Type.TOP));
        assertTrue(Type.STRING.isSubtypeOf(Type.TOP));
        assertTrue(Type.DYNAMIC.isSubtypeOf(Type.TOP));
    }

    @Test
    void integerIsNotSubtypeOfString() {
        assertFalse(Type.INTEGER.isSubtypeOf(Type.STRING));
    }

    // ---- Unification ----

    @Test
    void unifySameTypes() {
        Type result = Type.INTEGER.unify(Type.INTEGER);
        assertEquals(Type.INTEGER, result);
    }

    @Test
    void unifyVarWithType() {
        VarType v = Type.fresh("test");
        Type result = v.unify(Type.INTEGER);
        assertEquals(Type.INTEGER, result);
        assertEquals(Type.INTEGER, v.resolve());
    }

    @Test
    void unifyTwoVars() {
        VarType a = Type.fresh("a");
        VarType b = Type.fresh("b");
        Type result = a.unify(b);
        assertNotNull(result);
    }

    @Test
    void unifyDifferentTypesReturnsNull() {
        Type result = Type.INTEGER.unify(Type.STRING);
        assertNull(result,
            "Unifying Integer with String should fail (return null)");
    }

    @Test
    void varTypeOccursCheck() {
        VarType v = Type.fresh("t");
        // A variable does not occur in itself for the base case
        assertFalse(Type.INTEGER.occurs(v));
    }

    // ---- ClassType ----

    @Test
    void classTypeFromJavaClass() {
        Type t = Type.fromClass(java.util.ArrayList.class);
        assertTrue(t instanceof ClassType);
        assertEquals(java.util.ArrayList.class, ((ClassType) t).javaClass);
    }

    @Test
    void parameterizedClassType() {
        ClassType listInt = new ClassType(java.util.List.class, Type.INTEGER);
        assertEquals("List<Integer>", listInt.toTypeString());
    }

    @Test
    void classTypeIsSubtypeOfObject() {
        Type t = Type.fromClass(java.util.ArrayList.class);
        assertTrue(t.isSubtypeOf(Type.OBJECT));
    }

    // ---- FunctionType ----

    @Test
    void functionTypeSubtypingCovariantReturn() {
        FunctionType f1 = new FunctionType(Type.INTEGER, Type.INTEGER);
        FunctionType f2 = new FunctionType(Type.NUMBER, Type.INTEGER);
        assertTrue(f1.isSubtypeOf(f2));
    }

    @Test
    void functionTypeContravariantParam() {
        // f1 = (Number) -> Integer : params=[Number], return=Integer
        // f2 = (Integer) -> Integer : params=[Integer], return=Integer
        // f1 <: f2 requires: f2.param <: f1.param (contravariant)
        //   i.e., Integer <: Number? YES
        FunctionType f1 = new FunctionType(Type.INTEGER, Type.NUMBER);
        FunctionType f2 = new FunctionType(Type.INTEGER, Type.INTEGER);
        assertTrue(f1.isSubtypeOf(f2));
    }

    @Test
    void functionTypeToString() {
        // Constructor: (returnType, paramTypes...)
        FunctionType ft = new FunctionType(Type.STRING, Type.INTEGER);
        assertEquals("(Integer) -> String", ft.toTypeString());
    }

    @Test
    void multiArgFunctionType() {
        FunctionType ft = new FunctionType(
            java.util.Arrays.asList(Type.INTEGER, Type.STRING), Type.BOOLEAN);
        assertEquals("(Integer, String) -> Boolean", ft.toTypeString());
    }

    // ---- VarType ----

    @Test
    void varTypeBinding() {
        VarType v = new VarType("x");
        assertFalse(v.isBound());
        v.bind(Type.STRING);
        assertTrue(v.isBound());
        assertEquals(Type.STRING, v.resolve());
    }

    @Test
    void varTypeBoundToAnotherVar() {
        VarType a = Type.fresh("a");
        VarType b = Type.fresh("b");
        a.bind(b);
        assertTrue(a.isBound());
        assertEquals(b, a.resolve());
    }

    @Test
    void varTypeFreshGeneratesUniqueNames() {
        VarType v1 = Type.fresh();
        VarType v2 = Type.fresh();
        assertNotEquals(v1.toString(), v2.toString());
    }

    // ---- fromClass ----

    @Test
    void fromClassInteger() {
        assertEquals(Type.INTEGER, Type.fromClass(Integer.class));
    }

    @Test
    void fromClassString() {
        assertEquals(Type.STRING, Type.fromClass(String.class));
    }

    @Test
    void fromClassBoolean() {
        assertEquals(Type.BOOLEAN, Type.fromClass(Boolean.class));
    }

    @Test
    void fromClassLong() {
        assertEquals(Type.LONG, Type.fromClass(Long.class));
    }

    @Test
    void fromClassDouble() {
        assertEquals(Type.DOUBLE, Type.fromClass(Double.class));
    }

    @Test
    void fromClassNullReturnsDynamic() {
        assertEquals(Type.DYNAMIC, Type.fromClass(null));
    }

    @Test
    void fromClassPrimitiveInt() {
        assertEquals(Type.INTEGER, Type.fromClass(Integer.TYPE));
    }

    @Test
    void fromClassVoid() {
        Type t = Type.fromClass(Void.TYPE);
        assertEquals("Void", t.toTypeString());
    }

    // ---- DynamicType ----

    @Test
    void dynamicTypeIsSubtypeOfDynamic() {
        assertTrue(Type.DYNAMIC.isSubtypeOf(Type.DYNAMIC));
    }

    @Test
    void dynamicTypeUnifyWithAny() {
        Type result = Type.DYNAMIC.unify(Type.INTEGER);
        assertNotNull(result);
    }

    // ---- Inference from complete programs ----

    @Test
    void inferDefineWithTypeAnnotation() {
        ELNode node = Parser.parse("define x::Integer = 42");
        inferrer.infer(node);
        assertFalse(inferrer.hasErrors(), "Should have no errors for valid type annotation");
    }

    @Test
    void inferDefineWithoutAnnotation() {
        ELNode node = Parser.parse("define x = 42");
        inferrer.infer(node);
        assertFalse(inferrer.hasErrors(), "Should have no errors when no annotation");
    }

    // ---- Undefined type via ScriptEngine ----

    @Test
    void validTypeAnnotationViaScriptEngine() throws Exception {
        javax.script.ScriptEngine eng =
            new javax.script.ScriptEngineManager().getEngineByName("ELite");
        eng.eval("define x::Integer = 42");
        assertEquals(42L, ((Number) eng.eval("x")).longValue());
    }
}
