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

package org.operamasks.el.types;

import java.util.*;

import javax.el.ELContext;

import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Token;
import org.operamasks.el.eval.ELEngine;
import static org.operamasks.el.resources.Resources.*;

/**
 * Bidirectional type inference engine for ELite.
 *
 * Supports gradual typing: fully inferred types when possible,
 * falling back to DynamicType when static analysis cannot determine the type.
 */
public class TypeInferrer {

    /** Key for storing type environment in ELContext across evals. */
    private static final class TypeEnvKey {}

    private final ELContext elctx;
    private final Map<String, Type> env;
    private final Deque<Map<String, Type>> scopeStack;
    private final List<String> errors;
    private ELNode currentNode;

    public TypeInferrer(ELContext elctx) {
        this.elctx = elctx;
        this.env = new LinkedHashMap<>();
        this.scopeStack = new ArrayDeque<>();
        this.errors = new ArrayList<>();
        restorePersistedTypes();
    }

    /** Restore type bindings from previous eval runs. */
    @SuppressWarnings("unchecked")
    private void restorePersistedTypes() {
        Object obj = elctx.getContext(TypeEnvKey.class);
        if (obj instanceof Map) {
            env.putAll((Map<String, Type>) obj);
        }
    }

    /** Persist current type bindings for future eval runs. */
    public void persistTypes() {
        Map<String, Type> snapshot = new LinkedHashMap<>(env);
        elctx.putContext(TypeEnvKey.class, snapshot);
    }

    public List<String> getErrors() { return Collections.unmodifiableList(errors); }
    public boolean hasErrors() { return !errors.isEmpty(); }

    public List<String> getPositionErrors() {
        return Collections.unmodifiableList(errors);
    }

    private void addError(String message) {
        if (!errors.contains(message)) {
            errors.add(message);
        }
    }

    private void addErrorAt(ELNode node, String message) {
        int line = org.operamasks.el.parser.Position.line(node.pos);
        int col  = org.operamasks.el.parser.Position.column(node.pos);
        String err = line + ":" + col + ": " + message;
        if (!errors.contains(err)) {
            errors.add(err);
        }
    }

    /**
     * Main entry point: infer the type of an ELNode expression tree.
     */
    public Type infer(ELNode node) {
        if (node == null) return Type.DYNAMIC;
        ELNode prev = currentNode;
        currentNode = node;

        Type result;
        switch (node.op) {
            // Literals
            case Token.TRUE:
            case Token.FALSE:
                result = Type.BOOLEAN; break;
            case Token.NUMBER:
                result = inferNumber(((ELNode.NUMBER) node).value); break;
            case Token.STRINGVAL:
                result = Type.STRING; break;
            case Token.CHARVAL:
                result = Type.CHAR; break;
            case Token.NULL:
                result = Type.DYNAMIC; break;
            case Token.SYMBOL:
                result = Type.DYNAMIC; break;

            // Identifiers & access
            case Token.IDENT:
                result = inferIdent((ELNode.IDENT) node); break;
            case Token.ACCESS:
            case Token.FIELD:
                result = inferAccess((ELNode.ACCESS) node); break;

            // Arithmetic
            case Token.ADD: case Token.SUB:
            case Token.MUL: case Token.DIV: case Token.IDIV:
            case Token.REM:
                result = inferArithmetic(node); break;
            case Token.POW:
                result = inferPower(node); break;
            case Token.NEG: case Token.POS:
                result = inferUnary(node); break;

            // Bitwise operators
            case Token.BITOR: case Token.BITAND: case Token.XOR:
            case Token.SHL: case Token.SHR: case Token.USHR:
            case Token.BITNOT:
                result = inferBitwise(node); break;

            // Comparison & logical
            case Token.EQ: case Token.NE:
            case Token.LT: case Token.LE:
            case Token.GT: case Token.GE:
            case Token.AND: case Token.OR: case Token.NOT:
                result = Type.BOOLEAN; break;

            // Other expression types
            case Token.COND:
                result = inferConditional((ELNode.COND) node); break;
            case Token.COALESCE:
                result = inferCoalesce(node); break;
            case Token.CAT:
                result = Type.STRING; break;
            case Token.INSTANCEOF:
                result = Type.BOOLEAN; break;
            case Token.IN:
                result = Type.BOOLEAN; break;

            // Lambda and application
            case Token.CLASSDEF:
                result = inferClassDef((ELNode.CLASSDEF) node); break;
            case Token.LAMBDA:
                result = inferLambda((ELNode.LAMBDA) node); break;
            case Token.APPLY:
                result = inferApply((ELNode.APPLY) node); break;
            case Token.XFORM:
                result = inferXForm((ELNode.XFORM) node); break;

            // Data structures
            case Token.LBRACKET:
                result = inferBracket(node); break;
            case Token.LBRACE:
                result = inferMapLiteral(node); break;
            case Token.NIL:
                result = new ClassType(java.util.List.class, Type.fresh()); break;
            case Token.TUPLE:
                result = inferTuple((ELNode.TUPLE) node); break;

            // Definitions & assignment
            case Token.DEFINE:
                result = inferDefine((ELNode.DEFINE) node); break;
            case Token.ASSIGN:
                result = inferAssign((ELNode.ASSIGN) node); break;
            case Token.UNDEF:
                result = null; break;

            // Control flow
            case Token.MATCH:
                result = inferMatch((ELNode.MATCH) node); break;
            case Token.THEN:
                result = inferThen(node); break;

            // OOP
            case Token.NEW:
                result = inferNew((ELNode.NEW) node); break;

            // XML literals
            case Token.XML:
                result = inferXml(node); break;

            // User-defined operators
            case Token.PREFIX:
                result = inferUserPrefix((ELNode.PREFIX) node); break;
            case Token.INFIX:
                result = inferUserInfix((ELNode.INFIX) node); break;

            // Parenthesized / embedded expression
            case Token.EXPR:
                if (node instanceof ELNode.EXPR) {
                    result = infer(((ELNode.EXPR) node).right);
                } else {
                    result = Type.DYNAMIC;
                }
                break;

            // Metadata annotation node
            case Token.METADATA:
                result = Type.DYNAMIC; break;

            default:
                result = Type.DYNAMIC; break;
        }

        // Cache inferred type on the node for downstream use
        node.inferredType = result;
        currentNode = prev;
        return result;
    }

    // =========== Class definition ===========

    private Type inferClassDef(ELNode.CLASSDEF node) {
        // Register as a class type using Object.class as placeholder
        // (user-defined classes don't have a Java Class at type-check time)
        ClassType classType = new ClassType(Object.class);
        env.put(node.id, classType);

        if (node.ivars != null) {
            for (ELNode.DEFINE var : node.ivars) {
                infer(var);
            }
        }

        return classType;
    }

    // =========== Literals ===========

    private Type inferNumber(Number n) {
        if (n instanceof Integer || n instanceof Short || n instanceof Byte)
            return Type.INTEGER;
        if (n instanceof Long) return Type.LONG;
        if (n instanceof Double) return Type.DOUBLE;
        if (n instanceof Float) return Type.FLOAT;
        if (n instanceof java.math.BigInteger) return Type.LONG; // closest approximation
        if (n instanceof java.math.BigDecimal) return Type.DOUBLE; // closest approximation
        return Type.NUMBER;
    }

    // =========== Identifier ===========

    private Type inferIdent(ELNode.IDENT node) {
        Type t = env.get(node.id);
        if (t != null) return t;
        try {
            Class<?> cls = ELEngine.resolveJavaClass(elctx, node.id);
            return Type.fromClass(cls);
        } catch (Exception e) {
            // not a known class — may be an unbound variable or DSL construct
        }
        return Type.DYNAMIC;
    }

    // =========== Access (a[b] and a.b) ===========

    private Type inferAccess(ELNode.ACCESS node) {
        Type base = infer(node.right);

        // Field access: a.b → ACCESS(base, STRINGVAL("b"))
        if (node.index instanceof ELNode.STRINGVAL) {
            String fieldName = ((ELNode.STRINGVAL) node.index).value;
            Type resolved = resolveFieldType(base, fieldName);
            if (resolved != null) return resolved;
        }

        // Index access: a[i]
        Type index = infer(node.index);
        if (base instanceof ClassType) {
            ClassType ct = (ClassType) base;
            if (!ct.typeArgs.isEmpty()) {
                // List<T>[i] → T
                return ct.typeArgs.get(0);
            }
            if (ct.javaClass == java.util.Map.class && !ct.typeArgs.isEmpty()) {
                // Map<K,V>[k] → V
                if (ct.typeArgs.size() > 1)
                    return ct.typeArgs.get(1);
            }
        }
        // Java array access: primitiveType[integer] → same primitive
        if (base instanceof PrimitiveType && index instanceof PrimitiveType) {
            return base;
        }
        // Index access with non-numeric key returns unknown type.
        // Example: 25[CELSIUS] → Measure (not Integer).
        return Type.DYNAMIC;
    }

    private Type resolveFieldType(Type baseType, String fieldName) {
        // Known standard Java class fields
        if (baseType == Type.STRING) {
            switch (fieldName) {
                case "length": return Type.INTEGER;
                case "class": return new ClassType(Class.class);
                case "empty": return Type.BOOLEAN;
                default: return Type.DYNAMIC; // string methods return various types
            }
        }
        if (baseType == Type.INTEGER || baseType == Type.LONG
            || baseType == Type.DOUBLE || baseType == Type.FLOAT
            || baseType instanceof PrimitiveType) {
            switch (fieldName) {
                case "class": return new ClassType(Class.class);
                default: return Type.DYNAMIC;
            }
        }
        if (baseType instanceof ClassType) {
            ClassType ct = (ClassType) baseType;
            // Collection/List fields
            if (java.util.Collection.class.isAssignableFrom(ct.javaClass)
                || java.util.List.class.isAssignableFrom(ct.javaClass)) {
                switch (fieldName) {
                    case "length": case "size": return Type.INTEGER;
                    case "first": case "last":
                        return ct.typeArgs.isEmpty() ? Type.DYNAMIC : ct.typeArgs.get(0);
                    case "tail": case "init": return baseType;
                    case "class": return new ClassType(Class.class);
                    case "empty": return Type.BOOLEAN;
                    default: return Type.DYNAMIC;
                }
            }
            // Try to look up actual Java field type
            try {
                java.lang.reflect.Field field = ct.javaClass.getField(fieldName);
                return Type.fromClass(field.getType());
            } catch (NoSuchFieldException e) {
                // not a public field — could be a method or property
            }
            // Try getXxx() getter
            String capName = Character.toUpperCase(fieldName.charAt(0))
                + fieldName.substring(1);
            try {
                java.lang.reflect.Method m = ct.javaClass.getMethod("get" + capName);
                return Type.fromClass(m.getReturnType());
            } catch (NoSuchMethodException e) { /* not a getter */ }
            // Try isXxx() boolean getter
            try {
                java.lang.reflect.Method m = ct.javaClass.getMethod("is" + capName);
                return Type.fromClass(m.getReturnType());
            } catch (NoSuchMethodException e) { /* not a boolean getter */ }
            // Try JavaBeans Introspector as last resort
            try {
                java.beans.BeanInfo info = java.beans.Introspector.getBeanInfo(ct.javaClass);
                for (java.beans.PropertyDescriptor pd : info.getPropertyDescriptors()) {
                    if (pd.getName().equals(fieldName) && pd.getReadMethod() != null) {
                        return Type.fromClass(pd.getReadMethod().getReturnType());
                    }
                }
            } catch (Exception e) { /* Introspector failed */ }
        }
        return null;
    }

    // =========== Arithmetic ===========

    private Type inferArithmetic(ELNode node) {
        ELNode.Binary bin = (ELNode.Binary) node;
        Type left = infer(bin.left);
        Type right = infer(bin.right);
        if (left == Type.STRING || right == Type.STRING) {
            if (node.op == Token.ADD) {
                addErrorAt(currentNode,
                    _T(JSPRT_UNSUPPORTED_EVAL_TYPE, "String in arithmetic: use ~ for concatenation"));
            }
            return Type.DYNAMIC;
        }
        if (left instanceof PrimitiveType && right instanceof PrimitiveType) {
            if (left == right) return left;
            if (left.isSubtypeOf(Type.NUMBER) && right.isSubtypeOf(Type.NUMBER))
                return widerNumeric((PrimitiveType) left, (PrimitiveType) right);
        }
        return Type.DYNAMIC;
    }

    private Type widerNumeric(PrimitiveType a, PrimitiveType b) {
        if (a == Type.DOUBLE || b == Type.DOUBLE) return Type.DOUBLE;
        if (a == Type.FLOAT || b == Type.FLOAT) return Type.FLOAT;
        if (a == Type.LONG || b == Type.LONG) return Type.LONG;
        return Type.INTEGER;
    }

    private Type inferPower(ELNode node) {
        ELNode.Binary bin = (ELNode.Binary) node;
        Type left = infer(bin.left);
        if (left == Type.INTEGER || left == Type.LONG) return Type.INTEGER;
        if (left == Type.FLOAT || left == Type.DOUBLE) return Type.DOUBLE;
        return Type.NUMBER;
    }

    private Type inferUnary(ELNode node) {
        ELNode.Unary un = (ELNode.Unary) node;
        Type t = infer(un.right);
        if (t instanceof PrimitiveType) return t;
        return Type.DYNAMIC;
    }

    // =========== Bitwise ===========

    private Type inferBitwise(ELNode node) {
        if (node instanceof ELNode.Binary) {
            ELNode.Binary bin = (ELNode.Binary) node;
            Type left = infer(bin.left);
            Type right = infer(bin.right);
            if (left == Type.INTEGER || left == Type.LONG) return left;
            if (right == Type.INTEGER || right == Type.LONG) return right;
            return Type.INTEGER;
        }
        // Unary bitwise (BITNOT)
        if (node instanceof ELNode.Unary) {
            Type t = infer(((ELNode.Unary) node).right);
            if (t == Type.INTEGER || t == Type.LONG) return t;
            return Type.INTEGER;
        }
        return Type.INTEGER;
    }

    // =========== Conditional / Coalesce ===========

    private Type inferConditional(ELNode.COND node) {
        Type thenType = infer(node.left);
        Type elseType = infer(node.right);
        Type unified = thenType.unify(elseType);
        return unified != null ? unified : Type.DYNAMIC;
    }

    private Type inferCoalesce(ELNode node) {
        ELNode.Binary bin = (ELNode.Binary) node;
        Type left = infer(bin.left);
        Type right = infer(bin.right);
        Type unified = left.unify(right);
        return unified != null ? unified : left;
    }

    // =========== Sequential (THEN) ===========

    private Type inferThen(ELNode node) {
        ELNode.THEN then = (ELNode.THEN) node;
        infer(then.left); // side-effecting expression
        return infer(then.right);
    }

    // =========== Lambda ===========

    private Type inferLambda(ELNode.LAMBDA node) {
        pushScope();
        List<Type> paramTypes = new ArrayList<>();
        for (ELNode.DEFINE param : node.vars) {
            Type pt = resolveTypeAnnotation(param.type);
            if (pt == null) {
                pt = Type.fresh("p");
            }
            env.put(param.id, pt);
            paramTypes.add(pt);
        }
        Type bodyType = infer(node.body);
        popScope();

        // Use annotated return type if available
        Type returnType = resolveTypeAnnotation(node.rtype);
        if (returnType != null) {
            // void functions can return any value (it's discarded)
            if (!isVoidType(returnType)) {
                Type unified = bodyType.unify(returnType);
                if (unified == null && bodyType != Type.DYNAMIC && bodyType != Type.BOTTOM) {
                    addErrorAt(currentNode,
                        _T(EL_RETURN_TYPE_MISMATCH, returnType.toTypeString(), bodyType.toTypeString()));
                }
            }
            bodyType = returnType;
        }

        return new FunctionType(paramTypes, bodyType);
    }

    // =========== Application (function call) ===========

    private Type inferApply(ELNode.APPLY node) {
        Type fnType = infer(node.right);
        List<Type> argTypes = new ArrayList<>();
        for (ELNode arg : node.args) {
            argTypes.add(infer(arg));
        }

        // Case 1: Known function type
        if (fnType instanceof FunctionType) {
            return inferKnownFunctionCall((FunctionType) fnType, argTypes);
        }

        // Case 2: Unresolved type variable — try to constrain it
        if (fnType instanceof VarType) {
            return inferVarTypeFunctionCall((VarType) fnType, argTypes);
        }

        // Case 3: ClassType with constructor call semantics
        if (fnType instanceof ClassType) {
            ClassType ct = (ClassType) fnType;
            return ct; // e.g., Point(3,4) returns Point
        }

        // Case 4: Dynamic or unknown — just return DYNAMIC
        return Type.DYNAMIC;
    }

    private Type inferKnownFunctionCall(FunctionType ft, List<Type> argTypes) {
        // Check argument types against declared parameter types
        for (int i = 0; i < Math.min(argTypes.size(), ft.paramTypes.size()); i++) {
            Type argType = argTypes.get(i);
            Type paramType = ft.paramTypes.get(i);
            if (!(paramType instanceof VarType) && paramType != Type.DYNAMIC) {
                Type resolved = argType instanceof VarType ? ((VarType)argType).resolve() : argType;
                if (resolved != Type.DYNAMIC && !resolved.isSubtypeOf(paramType)) {
                    addErrorAt(currentNode,
                        _T(EL_ARG_TYPE_MISMATCH, i+1, paramType.toTypeString(), resolved.toTypeString()));
                }
            }
            argTypes.get(i).unify(paramType);
        }
        return ft.returnType;
    }

    private Type inferVarTypeFunctionCall(VarType fnVar, List<Type> argTypes) {
        Type freshRet = Type.fresh("r");
        List<Type> freshParams = new ArrayList<>();
        for (int i = 0; i < argTypes.size(); i++) {
            freshParams.add(Type.fresh("a"));
        }
        FunctionType freshFn = new FunctionType(freshParams, freshRet);
        fnVar.unify(freshFn);
        for (int i = 0; i < argTypes.size(); i++) {
            argTypes.get(i).unify(freshParams.get(i));
        }
        return freshRet instanceof VarType ? ((VarType) freshRet).resolve() : freshRet;
    }

    // =========== Pipe / Transform (x -> f) ===========

    private Type inferXForm(ELNode.XFORM node) {
        Type argType = node.left instanceof ELNode.TUPLE
            ? inferTupleArgs(node.left)
            : infer(node.left);
        Type fnType = infer(node.right);

        if (fnType instanceof FunctionType) {
            FunctionType ft = (FunctionType) fnType;
            // The first argument type should unify with the left-hand value
            if (!ft.paramTypes.isEmpty() && argType != Type.DYNAMIC) {
                argType.unify(ft.paramTypes.get(0));
            }
            return ft.returnType;
        }

        if (fnType instanceof VarType) {
            return inferVarTypeFunctionCall((VarType) fnType, Collections.singletonList(argType));
        }

        return Type.DYNAMIC;
    }

    private Type inferTupleArgs(ELNode node) {
        if (node instanceof ELNode.TUPLE) {
            ELNode.TUPLE tup = (ELNode.TUPLE) node;
            List<Type> types = new ArrayList<>();
            for (ELNode e : tup.elems) {
                types.add(infer(e));
            }
            return new FunctionType(types, Type.DYNAMIC);
        }
        return infer(node);
    }

    // =========== Data structures ===========

    private Type inferBracket(ELNode node) {
        if (node instanceof ELNode.CONS) {
            return inferCons((ELNode.CONS) node);
        }
        if (node instanceof ELNode.NIL) {
            return new ClassType(java.util.List.class, Type.fresh());
        }
        return Type.DYNAMIC;
    }

    private Type inferCons(ELNode.CONS node) {
        Type headType = infer(node.head);
        Type tailType = infer(node.tail);
        if (tailType instanceof ClassType) {
            ClassType ct = (ClassType) tailType;
            if (!ct.typeArgs.isEmpty())
                headType.unify(ct.typeArgs.get(0));
        } else if (tailType instanceof VarType) {
            // Constrain VarType to be List<X> where X = headType
            ClassType listT = new ClassType(java.util.List.class, headType);
            tailType.unify(listT);
        }
        return new ClassType(java.util.List.class, headType);
    }

    /** Infer type for Map/Record literal: { key: value, ... } */
    private Type inferMapLiteral(ELNode node) {
        if (node instanceof ELNode.MAP) {
            ELNode.MAP map = (ELNode.MAP) node;
            if (map.keys != null && map.keys.length > 0) {
                Type keyType = infer(map.keys[0]);
                Type valType = infer(map.values[0]);
                // Unify all key/value pairs
                for (int i = 1; i < map.keys.length; i++) {
                    infer(map.keys[i]).unify(keyType);
                    infer(map.values[i]).unify(valType);
                }
                return new ClassType(java.util.Map.class, keyType, valType);
            }
            return new ClassType(java.util.Map.class, Type.DYNAMIC, Type.DYNAMIC);
        }
        return Type.DYNAMIC;
    }

    /** Infer type for Tuple literal: (a, b, c) */
    private Type inferTuple(ELNode.TUPLE node) {
        // Infer element types and construct a composite type
        if (node.elems != null && node.elems.length > 0) {
            Type[] elemTypes = new Type[node.elems.length];
            for (int i = 0; i < node.elems.length; i++) {
                elemTypes[i] = infer(node.elems[i]);
            }
            return new ClassType(Object[].class, elemTypes);
        }
        return Type.OBJECT;
    }

    // =========== XML ===========

    private Type inferXml(ELNode node) {
        // XML literals produce XmlNode / org.w3c.dom.Document nodes
        try {
            Class<?> xmlNodeClass = Class.forName("elite.xml.XmlNode");
            return new ClassType(xmlNodeClass);
        } catch (ClassNotFoundException e) {
            // Try W3C DOM
            return new ClassType(org.w3c.dom.Node.class);
        }
    }

    // =========== User-defined operators ===========

    private Type inferUserPrefix(ELNode.PREFIX node) {
        // Resolve the operator as a function and infer as application
        Type fnType = inferIdent(new ELNode.IDENT(node.pos, node.name));
        Type argType = infer(node.right);
        if (fnType instanceof FunctionType) {
            return ((FunctionType) fnType).returnType;
        }
        // Try VarType resolution
        if (fnType instanceof VarType) {
            Type ret = Type.fresh("r");
            fnType.unify(new FunctionType(Collections.singletonList(argType), ret));
            return ret instanceof VarType ? ((VarType) ret).resolve() : ret;
        }
        return Type.DYNAMIC;
    }

    private Type inferUserInfix(ELNode.INFIX node) {
        Type fnType = inferIdent(new ELNode.IDENT(node.pos, node.name));
        Type arg1 = infer(node.left);
        Type arg2 = infer(node.right);
        if (fnType instanceof FunctionType) {
            return ((FunctionType) fnType).returnType;
        }
        if (fnType instanceof VarType) {
            Type ret = Type.fresh("r");
            fnType.unify(new FunctionType(Arrays.asList(arg1, arg2), ret));
            return ret instanceof VarType ? ((VarType) ret).resolve() : ret;
        }
        return Type.DYNAMIC;
    }

    // =========== Define / Assign ===========

    private Type inferDefine(ELNode.DEFINE node) {
        Type annotatedType = resolveTypeAnnotation(node.type);
        Type inferredType = Type.DYNAMIC;

        if (node.expr != null) {
            inferredType = infer(node.expr);
        }

        Type t;
        if (annotatedType != null) {
            t = annotatedType;
            // If no expression (e.g., function parameter), annotated type is definitive
            if (node.expr != null && inferredType != Type.DYNAMIC) {
                Type unified = inferredType.unify(annotatedType);
                if (unified == null && !inferredType.isSubtypeOf(annotatedType)) {
                    addErrorAt(currentNode,
                        _T(EL_RETURN_TYPE_MISMATCH,
                            annotatedType.toTypeString(), inferredType.toTypeString()));
                }
            }
        } else {
            t = inferredType;
        }
        env.put(node.id, t);
        return t;
    }

    private Type inferAssign(ELNode.ASSIGN node) {
        Type rhsType = infer(node.right);
        // Simple variable assignment: x = value
        if (node.left instanceof ELNode.IDENT) {
            String name = ((ELNode.IDENT) node.left).id;
            Type existing = env.get(name);
            if (existing != null) {
                Type unified = rhsType.unify(existing);
                if (unified == null && existing != Type.DYNAMIC
                    && !rhsType.isSubtypeOf(existing)) {
                    addErrorAt(currentNode,
                        _T(EL_RETURN_TYPE_MISMATCH,
                            existing.toTypeString(), rhsType.toTypeString()));
                }
            }
            env.put(name, rhsType);
        }
        // Tuple destructuring: (a, b) = expr
        if (node.left instanceof ELNode.TUPLE) {
            ELNode.TUPLE tup = (ELNode.TUPLE) node.left;
            for (ELNode elem : tup.elems) {
                if (elem instanceof ELNode.IDENT) {
                    String name = ((ELNode.IDENT) elem).id;
                    env.put(name, Type.DYNAMIC); // individual tuple element types unknown
                }
            }
        }
        return rhsType;
    }

    // =========== Match ===========

    private Type inferMatch(ELNode.MATCH node) {
        Type result = Type.BOTTOM;
        if (node.alts != null) {
            for (ELNode.CASE caseNode : node.alts) {
                pushScope();
                if (caseNode.bodies != null && caseNode.bodies.length > 0) {
                    Type branchType = infer(caseNode.bodies[0]);
                    Type unified = result.unify(branchType);
                    result = (unified != null) ? unified : Type.DYNAMIC;
                }
                popScope();
            }
        }
        if (node.deflt != null) {
            Type defType = infer(node.deflt);
            Type unified = result.unify(defType);
            result = (unified != null) ? unified : Type.DYNAMIC;
        }
        return result == Type.BOTTOM ? Type.DYNAMIC : result;
    }

    // =========== New ===========

    private Type inferNew(ELNode.NEW node) {
        // Check if it's a user-defined class registered in the environment
        Type envType = env.get(node.base);
        if (envType != null) return envType;

        // Try to resolve as a Java class
        try {
            Class<?> cls = ELEngine.resolveJavaClass(elctx, node.base);
            return new ClassType(cls);
        } catch (Exception e) {
            return Type.DYNAMIC;
        }
    }

    private static boolean isVoidType(Type t) {
        if (t instanceof PrimitiveType pt) {
            return pt.toTypeString().equals("Void") || pt.toTypeString().equals("void");
        }
        return false;
    }

    // =========== Scope management ===========

    private void pushScope() {
        scopeStack.push(new LinkedHashMap<>(env));
    }

    private void popScope() {
        if (!scopeStack.isEmpty()) {
            Map<String, Type> prev = scopeStack.pop();
            env.clear();
            env.putAll(prev);
        }
    }

    public Map<String, Type> getEnvironment() {
        return Collections.unmodifiableMap(env);
    }

    // =========== Type annotation resolution ===========

    /**
     * Resolve a type annotation string to a Type.
     * Examples:
     *   "Integer" → PrimitiveType.INTEGER
     *   "String" → PrimitiveType.STRING
     *   "List<Integer>" → ClassType(List, INTEGER)
     *   "Map<String,Integer>" → ClassType(Map, STRING, INTEGER)
     *   "void" → PrimitiveType("Void")
     * Returns null if the annotation is null or empty.
     */
    Type resolveTypeAnnotation(String typeName) {
        return resolveTypeAnnotation(typeName, currentNode);
    }

    private Type resolveTypeAnnotation(String typeName, ELNode errorNode) {
        if (typeName == null || typeName.isEmpty()) return null;

        // Handle parameterized types: Foo<A, B>
        int lt = typeName.indexOf('<');
        if (lt > 0 && typeName.endsWith(">")) {
            String baseName = typeName.substring(0, lt);
            String argsStr = typeName.substring(lt + 1, typeName.length() - 1);
            Type base = resolveSimpleType(baseName);
            if (base == null) {
                return Type.DYNAMIC;
            }
            // Parse type arguments (handle nested generics with balanced brackets)
            List<String> argNames = splitTypeArgs(argsStr);
            Type[] argTypes = new Type[argNames.size()];
            for (int i = 0; i < argNames.size(); i++) {
                // Recursively resolve with correct error node
                Type argType = resolveTypeAnnotation(argNames.get(i).trim(), errorNode);
                if (argType == null) argType = Type.DYNAMIC;
                argTypes[i] = argType;
            }
            try {
                Class<?> cls = ELEngine.resolveJavaClass(elctx, baseName);
                return new ClassType(cls, argTypes);
            } catch (Exception e) {
                return Type.DYNAMIC;
            }
        }

        Type resolved = resolveSimpleType(typeName);
        if (resolved == null) {
            return Type.DYNAMIC; // Return DYNAMIC even on error for gradual typing
        }
        return resolved;
    }

    /** Split "A, B<C, D>, E" into ["A", "B<C,D>", "E"]. */
    private static List<String> splitTypeArgs(String argsStr) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < argsStr.length(); i++) {
            char c = argsStr.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(argsStr.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(argsStr.substring(start));
        return parts;
    }

    private Type resolveSimpleType(String name) {
        if (name == null) return null;
        switch (name) {
            case "Integer": case "int": return Type.INTEGER;
            case "Long": case "long": return Type.LONG;
            case "Double": case "double": return Type.DOUBLE;
            case "Float": case "float": return Type.FLOAT;
            case "Boolean": case "boolean": return Type.BOOLEAN;
            case "String": return Type.STRING;
            case "Char": case "char": return Type.CHAR;
            case "Number": return Type.NUMBER;
            case "Object": return Type.OBJECT;
            case "Void": case "void": return new PrimitiveType("Void", Void.TYPE);
            default:
                try {
                    Class<?> cls = ELEngine.resolveJavaClass(elctx, name);
                    return new ClassType(cls);
                } catch (Exception e) {
                    return null;
                }
        }
    }
}
