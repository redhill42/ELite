package elite.types;

import java.util.*;

import javax.el.ELContext;

import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Token;
import org.operamasks.el.eval.ELEngine;

/**
 * Bidirectional type inference engine for ELite.
 */
public class TypeInferrer {

    private final ELContext elctx;
    private final Map<String, Type> env;
    private final Deque<Map<String, Type>> scopeStack;
    private final List<String> errors;

    public TypeInferrer(ELContext elctx) {
        this.elctx = elctx;
        this.env = new LinkedHashMap<>();
        this.scopeStack = new ArrayDeque<>();
        this.errors = new ArrayList<>();
    }

    public List<String> getErrors() { return Collections.unmodifiableList(errors); }
    public boolean hasErrors() { return !errors.isEmpty(); }

    public Type infer(ELNode node) {
        if (node == null) return Type.DYNAMIC;

        switch (node.op) {
            case Token.TRUE:
            case Token.FALSE:
                return Type.BOOLEAN;
            case Token.NUMBER:
                return inferNumber(((ELNode.NUMBER) node).value);
            case Token.STRINGVAL:
                return Type.STRING;
            case Token.CHARVAL:
                return Type.CHAR;
            case Token.NULL:
                return Type.DYNAMIC;

            case Token.IDENT:
                return inferIdent((ELNode.IDENT) node);
            case Token.ACCESS:
                return Type.DYNAMIC; // dynamic dispatch

            case Token.ADD: case Token.SUB:
            case Token.MUL: case Token.DIV:
            case Token.REM:
                return inferArithmetic(node);
            case Token.POW:
                return inferPower(node);
            case Token.NEG: case Token.POS:
                return inferUnary(node);

            case Token.EQ: case Token.NE:
            case Token.LT: case Token.LE:
            case Token.GT: case Token.GE:
                return Type.BOOLEAN;
            case Token.AND: case Token.OR: case Token.NOT:
                return Type.BOOLEAN;

            case Token.COND:
                return inferConditional((ELNode.COND) node);
            case Token.CAT:
                return Type.STRING;

            case Token.LAMBDA:
                return inferLambda((ELNode.LAMBDA) node);
            case Token.APPLY:
                return inferApply((ELNode.APPLY) node);

            case Token.LBRACKET:
                return inferBracket(node);

            case Token.DEFINE:
                return inferDefine((ELNode.DEFINE) node);
            case Token.ASSIGN:
                return inferAssign((ELNode.ASSIGN) node);

            case Token.MATCH:
                return inferMatch((ELNode.MATCH) node);

            case Token.NEW:
                return inferNew((ELNode.NEW) node);

            default:
                return Type.DYNAMIC;
        }
    }

    // ---- Literals ----

    private Type inferNumber(Number n) {
        if (n instanceof Integer || n instanceof Short || n instanceof Byte)
            return Type.INTEGER;
        if (n instanceof Long) return Type.LONG;
        if (n instanceof Double) return Type.DOUBLE;
        if (n instanceof Float) return Type.FLOAT;
        return Type.NUMBER;
    }

    // ---- Identifier ----

    private Type inferIdent(ELNode.IDENT node) {
        Type t = env.get(node.id);
        if (t != null) return t;
        try {
            Class<?> cls = ELEngine.resolveJavaClass(elctx, node.id);
            return Type.fromClass(cls);
        } catch (Exception e) {}
        return Type.DYNAMIC;
    }

    // ---- Arithmetic ----

    private Type inferArithmetic(ELNode node) {
        ELNode.Binary bin = (ELNode.Binary) node;
        Type left = infer(bin.left);
        Type right = infer(bin.right);
        if (left instanceof PrimitiveType && right instanceof PrimitiveType) {
            if (left == right) return left;
            if (left.isSubtypeOf(Type.NUMBER) && right.isSubtypeOf(Type.NUMBER))
                return widerNumeric((PrimitiveType) left, (PrimitiveType) right);
        }
        if (left == Type.STRING || right == Type.STRING) return Type.STRING;
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

    // ---- Conditional ----

    private Type inferConditional(ELNode.COND node) {
        ELNode.COND cond = (ELNode.COND) node;
        Type thenType = infer(cond.left);   // left = then branch
        Type elseType = infer(cond.right); // right = else branch
        Type unified = thenType.unify(elseType);
        return unified != null ? unified : Type.DYNAMIC;
    }

    // ---- Lambda ----

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
            bodyType.unify(returnType);
        }

        return new FunctionType(paramTypes, bodyType);
    }

    // ---- Application ----

    private Type inferApply(ELNode.APPLY node) {
        ELNode.APPLY app = (ELNode.APPLY) node;
        Type fnType = infer(app.right); // right = function
        List<Type> argTypes = new ArrayList<>();
        for (ELNode arg : app.args) {
            argTypes.add(infer(arg));
        }

        if (fnType instanceof FunctionType) {
            FunctionType ft = (FunctionType) fnType;
            for (int i = 0; i < Math.min(argTypes.size(), ft.paramTypes.size()); i++) {
                argTypes.get(i).unify(ft.paramTypes.get(i));
            }
            return ft.returnType;
        }

        if (fnType instanceof VarType) {
            Type freshRet = Type.fresh("r");
            List<Type> freshParams = new ArrayList<>();
            for (int i = 0; i < argTypes.size(); i++) {
                freshParams.add(Type.fresh("a"));
            }
            FunctionType freshFn = new FunctionType(freshParams, freshRet);
            fnType.unify(freshFn);
            for (int i = 0; i < argTypes.size(); i++) {
                argTypes.get(i).unify(freshParams.get(i));
            }
            return freshRet instanceof VarType ? ((VarType) freshRet).resolve() : freshRet;
        }

        return Type.DYNAMIC;
    }

    // ---- Bracket (list literal) ----

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
        }
        return new ClassType(java.util.List.class, headType);
    }

    // ---- Define ----

    private Type inferDefine(ELNode.DEFINE node) {
        Type annotatedType = resolveTypeAnnotation(node.type);
        Type t;
        if (annotatedType != null) {
            // Use the annotation as the expected type for inference
            t = annotatedType;
        } else {
            t = infer(node.expr);
        }
        env.put(node.id, t);
        return t;
    }

    // ---- Assign ----

    private Type inferAssign(ELNode.ASSIGN node) {
        ELNode.ASSIGN assign = (ELNode.ASSIGN) node;
        Type rhsType = infer(assign.right);
        if (assign.left instanceof ELNode.IDENT) {
            String name = ((ELNode.IDENT) assign.left).id;
            Type existing = env.get(name);
            if (existing != null) rhsType.unify(existing);
            env.put(name, rhsType);
        }
        return rhsType;
    }

    // ---- Match / case ----

    private Type inferMatch(ELNode.MATCH node) {
        Type result = Type.BOTTOM;
        for (ELNode.CASE caseNode : node.alts) {
            pushScope();
            Type branchType = infer(caseNode.bodies[0]);
            result = result.unify(branchType);
            if (result == null) result = Type.DYNAMIC;
            popScope();
        }
        if (node.deflt != null) {
            Type defType = infer(node.deflt);
            result = result.unify(defType);
            if (result == null) result = Type.DYNAMIC;
        }
        return result;
    }

    // ---- New ----

    private Type inferNew(ELNode.NEW node) {
        try {
            Class<?> cls = ELEngine.resolveJavaClass(elctx, node.base);
            return new ClassType(cls);
        } catch (Exception e) {
            return Type.DYNAMIC;
        }
    }

    // ---- Scope ----

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

    // ---- Type annotation resolution ----

    /**
     * Resolve a type annotation string to a Type.
     * e.g., "Integer" → PrimitiveType.INTEGER
     *       "String" → PrimitiveType.STRING
     *       "List<Integer>" → ClassType(List, INTEGER)
     * Returns null if the annotation is null or cannot be resolved.
     */
    private Type resolveTypeAnnotation(String typeName) {
        if (typeName == null || typeName.isEmpty()) return null;

        // Handle parameterized types: List<Integer>
        int lt = typeName.indexOf('<');
        if (lt > 0) {
            String baseName = typeName.substring(0, lt);
            String argsStr = typeName.substring(lt + 1, typeName.length() - 1);
            Type base = resolveSimpleType(baseName);
            if (base == null) {
                errors.add("Undefined type: '" + baseName + "'");
                return Type.DYNAMIC;
            }
            // Parse type arguments
            String[] argNames = argsStr.split(",");
            Type[] argTypes = new Type[argNames.length];
            for (int i = 0; i < argNames.length; i++) {
                Type argType = resolveSimpleType(argNames[i].trim());
                if (argType == null) {
                    errors.add("Undefined type: '" + argNames[i].trim() + "'");
                    argType = Type.DYNAMIC;
                }
                argTypes[i] = argType;
            }
            try {
                Class<?> cls = ELEngine.resolveJavaClass(elctx, baseName);
                return new ClassType(cls, argTypes);
            } catch (Exception e) {
                errors.add("Undefined type: '" + baseName + "'");
                return Type.DYNAMIC;
            }
        }

        Type resolved = resolveSimpleType(typeName);
        if (resolved == null) {
            errors.add("Undefined type: '" + typeName + "'");
            return Type.DYNAMIC;
        }
        return resolved;
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
                    return null; // Unknown type — caller should report error
                }
        }
    }
}
