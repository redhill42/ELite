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

package org.operamasks.el.ir;

import org.operamasks.el.eval.seq.Cons;
import org.operamasks.el.parser.DefaultVisitor;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Position;
import org.operamasks.el.parser.Token;

import java.util.*;

import static org.operamasks.el.ir.IRFormat.*;
import static org.operamasks.el.ir.Opcode.*;

/**
 * Converts an ELNode expression tree into IR form with explicit jump-based
 * control flow.
 *
 * <p>Design rule: every basic block MUST end with a terminator (JUMP,
 * RETURN, or
 * conditional JUMP followed by unconditional JUMP). No block falls through to
 * the next block in memory. This ensures correctness regardless of block ID
 * order.
 */
public class IRBuilder {

    // ── Block management (stored by ID, output in ID order) ──
    final Map<Integer, int[]> blockMap = new LinkedHashMap<>();
    IREmitter current;
    int currentBlockId = 0;
    int nextBlockId = 1;  // 0 is the initial block

    // ── Symbol table ──
    private final Map<String, Integer> varIndex = new LinkedHashMap<>();
    private final List<String> varNames = new ArrayList<>();
    private final List<Integer> paramFlags = new ArrayList<>(); // per-var flags

    // ── Closure capture ──
    private final IRBuilder parent; // enclosing scope (null for top-level)
    private final Map<String, Integer> capturedVars = new LinkedHashMap<>();
    // free vars → capture index

    // ── Constant pool (maybe shared with parent builder) ──
    private Map<Object, Integer> constIndex = new HashMap<>();
    private List<Object> constants = new ArrayList<>();

    // ── Compile-time class resolution ──
    // Mirrors ClassResolver: alias = simpleName→fqName (import foo.Bar);
    // packages = "foo.bar" prefixes (import foo.bar.*).
    private final ClassLoader classLoader;
    private final Map<String, String> importAliases;   // simpleName → fully
    // .qualified.Name
    private final List<String> importPackages;   // package prefixes for
    // wildcard imports

    // ── Loop stack ──
    private record LoopTargets(int continueBlock, int breakBlock) {
    }

    private final Deque<LoopTargets> loopStack = new ArrayDeque<>();

    // ── Debug info ──
    private final boolean debug;
    private String currentFile;       // source file name
    private int currentLine;          // line number of last built ELNode
    private final List<Integer> pcLineTable = new ArrayList<>(); // [pc,
    // line, ...]

    /**
     * Set the source file name for debug info (called before compilation).
     */
    void setFile(String file) {
        this.currentFile = file;
    }

    // ── Scope analysis (from pre-pass) ──
    private ScopeAnalyzer.ScopeAnalysis scopeAnalysis;

    /**
     * Variables in this scope that are captured by inner closures → use
     * STORE_GLOBAL/PUSH_GLOBAL.
     */
    private final Set<String> isCaptured = new HashSet<>();

    /**
     * Slot indices whose variable was stored via STORE_GLOBAL during define.
     * Assignments (x = expr) need STORE_GLOBAL only for these slots — slot-only
     * variables (function locals, control-flow shadows) don't have global
     * bindings and would fail with PropertyNotFoundException.
     */
    private final Set<Integer> globalSlots = new HashSet<>();

    // ── Control-flow scope tracking (compile-time slot allocation only, no
    // runtime ops) ──
    // When entering a control-flow scope ({...} block in if/while/for), we
    // save the current varIndex bindings for names that will be shadowed,
    // allocate new slots for the inner variables, and restore on scope exit.
    private final Deque<Map<String, Integer>> savedVarBindings =
            new ArrayDeque<>();

    private void enterControlScope() {
        savedVarBindings.push(new LinkedHashMap<>());
    }

    private void leaveControlScope() {
        Map<String, Integer> saved = savedVarBindings.pop();
        for (Map.Entry<String, Integer> e : saved.entrySet()) {
            if (e.getValue() == null) {
                varIndex.remove(e.getKey());
            } else {
                varIndex.put(e.getKey(), e.getValue());
            }
        }
    }

    // ── Tail-call optimization ──
    String lambdaName = null;
    boolean inTailPosition = false;

    IRBuilder() {
        this(null, null, null, false);
    }

    /**
     * Create a top-level builder with optional import context for CLASS
     * resolution.
     */
    IRBuilder(ClassLoader loader, List<String> imps) {
        this(loader, imps, null, false);
    }

    /**
     * Create a top-level builder with import context and scope analysis.
     */
    IRBuilder(ClassLoader loader, List<String> imps,
              ScopeAnalyzer.ScopeAnalysis analysis) {
        this(loader, imps, analysis, false);
    }

    /**
     * Create a top-level builder with import context, scope analysis, and
     * debug flag.
     */
    IRBuilder(ClassLoader loader, List<String> imps,
              ScopeAnalyzer.ScopeAnalysis analysis, boolean debug) {
        this.parent = null;
        this.classLoader = loader;
        this.importAliases = new LinkedHashMap<>();
        this.importPackages = new ArrayList<>();
        seedImports(imps);
        this.currentBlockId = 0;
        this.current = new IREmitter();
        this.debug = debug;
        this.scopeAnalysis = analysis;
        if (analysis != null) {
            this.isCaptured.addAll(analysis.capturedByInner);
        }
    }

    /**
     * Create a nested builder sharing the parent's constant pool and import
     * context.
     */
    private IRBuilder(IRBuilder parent) {
        this(parent, null);
    }

    /**
     * Create a nested builder with optional scope analysis for this lambda.
     */
    private IRBuilder(IRBuilder parent, ScopeAnalyzer.ScopeAnalysis analysis) {
        this.parent = parent;
        this.classLoader = parent != null ? parent.classLoader : null;
        this.importAliases = parent != null ? parent.importAliases :
                             new LinkedHashMap<>();
        this.importPackages = parent != null ? parent.importPackages :
                              new ArrayList<>();
        this.currentBlockId = 0;
        this.current = new IREmitter();
        this.debug = parent != null && parent.debug;
        this.currentFile = parent != null ? parent.currentFile : null;
        this.scopeAnalysis = analysis;
        if (analysis != null) {
            this.isCaptured.addAll(analysis.capturedByInner);
        }
        if (parent != null) {
            this.lambdaName = parent.lambdaName;
            // Share constants with parent so pool indices are consistent
            this.constants = parent.constants;
            this.constIndex = parent.constIndex;
        }
    }

    /**
     * Seed import aliases and package prefixes from the program's import list.
     * Built-in defaults mirror ClassResolver's defaults.
     */
    private void seedImports(List<String> imps) {
        // Built-in imports (mirrors ClassResolver constructor)
        importPackages.add("elite.lang");
        importPackages.add("java.lang");
        importPackages.add("java.util");
        addImportAlias("java.lang.reflect.Array");
        addImportAlias("java.math.BigInteger");
        addImportAlias("java.math.BigDecimal");

        if (imps != null) {
            for (String imp : imps) {
                if (imp.endsWith(".*")) {
                    String pkg = imp.substring(0, imp.length() - 2);
                    if (!importPackages.contains(pkg))
                        importPackages.add(pkg);
                } else {
                    addImportAlias(imp);
                }
            }
        }
    }

    private void addImportAlias(String fqName) {
        String simpleName = fqName.substring(fqName.lastIndexOf('.') + 1);
        importAliases.putIfAbsent(simpleName, fqName);
    }

    /**
     * Resolve a class name at compile time using the builder's import context.
     * Returns null if resolution fails (caller should fall back to trampoline).
     */
    Class<?> resolveClassAtCompileTime(String name) {
        if (classLoader == null)
            return null;

        // Fully-qualified name: try directly
        if (name.indexOf('.') != -1) {
            try {
                return Class.forName(name, false, classLoader);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }

        // Check simple-name alias (from import foo.bar.Baz)
        String fqName = importAliases.get(name);
        if (fqName != null) {
            try {
                return Class.forName(fqName, false, classLoader);
            } catch (ClassNotFoundException e) {
                // fall through to package search
            }
        }

        // Search wildcard-imported packages (import foo.bar.*)
        for (String pkg : importPackages) {
            try {
                return Class.forName(pkg + "." + name, false, classLoader);
            } catch (ClassNotFoundException e) { /* continue */ }
        }

        return null;
    }

    // ============ MAIN DISPATCH ============

    void build(ELNode node) {
        if (node == null) {
            emitPushNull();
            return;
        }

        if (debug) {
            int line = Position.line(node.pos);
            if (line > 0) {
                currentLine = line;
                // Record the first line for PC 0
                if (pcLineTable.isEmpty()) {
                    pcLineTable.add(0);
                    pcLineTable.add(currentLine);
                }
            }
        }

        if (node instanceof ELNode.COMPOUND) {
            buildCompound((ELNode.COMPOUND)node);
            return;
        }
        if (node instanceof ELNode.Composite) {
            buildComposite((ELNode.Composite)node);
            return;
        }
        if (node instanceof ELNode.FOREACH) {
            buildForEach((ELNode.FOREACH)node);
            return;
        }
        if (node instanceof ELNode.CONST_MATCH) {
            buildTrampoline(node);
            return;
        }
        if (node instanceof ELNode.MATCH) {
            buildTrampoline(node);
            return;
        }

        switch (node.op) {
        case Token.NUMBER:
            buildNumber((ELNode.NUMBER)node);
            break;
        case Token.STRINGVAL:
            buildString((ELNode.STRINGVAL)node);
            break;
        case Token.CHARVAL:
            buildConst(((ELNode.CHARVAL)node).value);
            break;
        case Token.TRUE:
            emitPushTrue();
            break;
        case Token.FALSE:
            emitPushFalse();
            break;
        case Token.BOOLEANVAL:
            if (((ELNode.BOOLEANVAL)node).value)
                emitPushTrue();
            else
                emitPushFalse();
            break;
        case Token.NULL:
            emitPushNull();
            break;
        case Token.SYMBOL:
            buildConst(((ELNode.SYMBOL)node).value);
            break;

        case Token.IDENT:
            buildIdent((ELNode.IDENT)node);
            break;
        case Token.ACCESS:
            buildAccess((ELNode.ACCESS)node);
            break;
        case Token.APPLY:
            buildApply((ELNode.APPLY)node);
            break;
        case Token.XFORM:
            buildXform((ELNode.XFORM)node);
            break;

        case Token.ADD:
        case Token.SUB:
        case Token.MUL:
        case Token.DIV:
        case Token.REM:
        case Token.POW:
            buildBinaryOp(node);
            break;
        case Token.NEG:
        case Token.POS:
            buildUnaryOp(node);
            break;
        case Token.CAT:
            buildCat(node);
            break;

        case Token.BITOR:
        case Token.BITAND:
        case Token.XOR:
        case Token.SHL:
        case Token.SHR:
        case Token.USHR:
        case Token.BITNOT:
            buildBinaryOp(node);
            break;

        case Token.EQ:
        case Token.NE:
        case Token.LT:
        case Token.LE:
        case Token.GT:
        case Token.GE:
            buildComparison(node);
            break;
        case Token.IDEQ:
        case Token.IDNE:
            buildIdentityCmp((ELNode.Binary)node);
            break;
        case Token.AND:
        case Token.OR:
        case Token.NOT:
            buildLogical(node);
            break;

        case Token.COND:
            buildConditional((ELNode.COND)node);
            break;
        case Token.COALESCE:
            buildCoalesce(node);
            break;

        case Token.ASSIGN:
            if (node instanceof ELNode.ASSIGNOP)
                buildAssignOp((ELNode.ASSIGNOP)node);
            else
                buildAssign((ELNode.ASSIGN)node);
            break;
        case Token.DEFINE:
            buildDefine((ELNode.DEFINE)node);
            break;

        case Token.THEN:
            buildThen((ELNode.THEN)node);
            break;
        case Token.EXPR:
            if (node instanceof ELNode.EXPR)
                buildExpr((ELNode.EXPR)node);
            else
                buildTrampoline(node);
            break;

        case Token.WHILE:
            buildWhile((ELNode.WHILE)node);
            break;
        case Token.FOR:
            if (node instanceof ELNode.FOR)
                buildFor((ELNode.FOR)node);
            else
                buildTrampoline(node);
            break;

        case Token.BREAK:
            buildBreak();
            break;
        case Token.CONTINUE:
            buildContinue();
            break;
        case Token.THROW:
            // ELNode.RETURN uses Token.THROW as its op (for Control.Return
            // extends Control.Throw), but must be handled by buildReturn,
            // not buildThrow.
            if (node instanceof ELNode.RETURN)
                buildReturn((ELNode.RETURN)node);
            else
                buildThrow((ELNode.THROW)node);
            break;
        case Token.TRY:
            buildTry((ELNode.TRY)node);
            break;
        case Token.LAMBDA:
            buildLambda((ELNode.LAMBDA)node);
            break;

        case Token.CONS:
            buildCons((ELNode.CONS)node);
            break;
        case Token.MAP:
            buildMap((ELNode.MAP)node);
            break;
        case Token.TUPLE:
            buildTuple((ELNode.TUPLE)node);
            break;
        case Token.RANGE:
            buildRange((ELNode.RANGE)node);
            break;
        case Token.IN:
            buildContains(node);
            break;
        case Token.INSTANCEOF:
            buildInstanceOf((ELNode.INSTANCEOF)node);
            break;
        case Token.NIL:
            current.emitNewList(0);
            break;  // [] = empty list
        case Token.ARRAY:
            buildTrampoline(node);
            break;  // complex, rare
        case Token.INC:
            buildIncDec((ELNode.INC)node);
            break;
        case Token.DEC:
            buildIncDec((ELNode.DEC)node);
            break;

        default:
            buildTrampoline(node);
        }
    }

    // ── Literals ──

    private void buildNumber(ELNode.NUMBER node) {
        Number n = node.value;
        if (n instanceof Integer || n instanceof Short || n instanceof Byte) {
            emitPushConst(T_INT, n.intValue());
        } else if (n instanceof Long) {
            long v = n.longValue();
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE)
                emitPushConst(T_INT, (int)v);
            else
                emitPushConst(T_LONG, v);
        } else if (n instanceof Double || n instanceof Float) {
            emitPushConst(T_DOUBLE, n.doubleValue());
        } else {
            emitPushConst(K_NONE, n);
        }
    }

    private void buildString(ELNode.STRINGVAL node) {
        emitPushConst(T_STRING, node.value);
    }

    private void buildConst(Object value) {
        emitPushConst(K_NONE, value);
    }

    private void buildAccess(ELNode.ACCESS node) {
        // For simple keys (identifiers, numbers, strings), use native
        // LOAD_PROPERTY
        if (isSimpleKey(node.index)) {
            // Try to resolve field/getter access at compile time for known
            // Java types
            String fieldName = getKeyName(node.index);
            org.operamasks.el.types.Type baseType = node.right != null ?
                                                    node.right.inferredType :
                                                    null;
            java.lang.Class<?> javaClass = resolveJavaClass(baseType);

            if (javaClass != null && fieldName != null) {
                // 1) Check for JavaBean getter: getXxx() or isXxx() (primary
                // Java interface)
                java.lang.reflect.Method getter = resolveGetter(javaClass,
                        fieldName);
                if (getter != null) {
                    build(node.right); // push base
                    int methodIdx = putConstant(getter);
                    current.emit2(INVOKE_GETTER, K_FN, methodIdx, 0);
                    return;
                }

                // 2) Check for public field (fallback)
                try {
                    java.lang.reflect.Field field =
                            javaClass.getField(fieldName);
                    if (java.lang.reflect.Modifier.isPublic(field.getModifiers())) {
                        build(node.right); // base
                        int nameIdx = putConstant(fieldName);
                        current.emitLoadField(nameIdx);
                        return;
                    }
                } catch (NoSuchFieldException e) { /* fall through */ }

                // 3) Neither getter nor field — fall back to ELResolver
                // (could be a method reference, static member, or nested class)
            }

            build(node.right);   // base object
            build(node.index);   // key
            current.emitLoadProperty();
        } else {
            buildTrampoline(node);
        }
    }

    /**
     * Resolve a JavaBean getter method (getXxx or isXxx) for the given
     * property name.
     */
    static java.lang.reflect.Method resolveGetter(Class<?> cls,
                                                  String propName) {
        String suffix =
                Character.toUpperCase(propName.charAt(0)) + propName.substring(1);
        // Try getXxx()
        try {
            return cls.getMethod("get" + suffix);
        } catch (NoSuchMethodException e) {
        }
        // Try isXxx() (for booleans)
        try {
            return cls.getMethod("is" + suffix);
        } catch (NoSuchMethodException e) {
        }
        return null;
    }

    /**
     * Resolve a public method by name and argument count. Returns null if
     * ambiguous.
     */
    static java.lang.reflect.Method resolveMethod(Class<?> cls, String name,
                                                  int argCount) {
        java.lang.reflect.Method found = null;
        for (java.lang.reflect.Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argCount && java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                if (found != null)
                    return null; // ambiguous overload
                found = m;
            }
        }
        return found;
    }

    /**
     * Resolve a JavaBean setter method (setXxx) for the given property name.
     */
    static java.lang.reflect.Method resolveSetter(Class<?> cls,
                                                  String propName) {
        String suffix =
                Character.toUpperCase(propName.charAt(0)) + propName.substring(1);
        try {
            return cls.getMethod("set" + suffix, getGetterReturnType(cls,
                    propName));
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Class<?> getGetterReturnType(Class<?> cls, String propName) {
        java.lang.reflect.Method getter = resolveGetter(cls, propName);
        return getter != null ? getter.getReturnType() : Object.class;
    }

    /**
     * Resolve a Type to a concrete Java Class, or null if unknown.
     */
    private static java.lang.Class<?> resolveJavaClass(org.operamasks.el.types.Type type) {
        if (type instanceof org.operamasks.el.types.ClassType ct) {
            return ct.javaClass;
        }
        if (type instanceof org.operamasks.el.types.PrimitiveType pt) {
            return pt.javaClass;
        }
        return null;
    }

    /**
     * Extract the key name from a simple key node.
     */
    private static String getKeyName(ELNode key) {
        if (key instanceof ELNode.IDENT ident)
            return ident.id;
        if (key instanceof ELNode.STRINGVAL s)
            return s.value;
        return null;
    }

    private static boolean isSimpleKey(ELNode key) {
        return key instanceof ELNode.IDENT ||
               key instanceof ELNode.NUMBER ||
               key instanceof ELNode.STRINGVAL ||
               key instanceof ELNode.CHARVAL;
    }

    // ── Identifiers ──
    private void buildIdent(ELNode.IDENT node) {
        // 1) Captured variable: must go through evaluation context
        //    (both in the enclosing scope and inside closures).
        if (isCaptured.contains(node.id)) {
            int nameIdx = putConstant(node.id);
            current.emitPushGlobal(nameIdx);
            return;
        }

        // 2) Check local varIndex (non-captured locals + params)
        Integer idx = varIndex.get(node.id);
        if (idx != null) {
            current.emitPushVar(idx);
            return;
        }

        // 3) Free variable from enclosing scope, NOT captured by inner closures
        //    (capture by value — push from enclosing local slot at closure
        //    creation)
        if (parent != null && parent.varIndex.containsKey(node.id) &&
            !parent.isCaptured.contains(node.id)) {
            Integer captureIdx = capturedVars.get(node.id);
            if (captureIdx == null) {
                capturedVars.put(node.id, capturedVars.size());
                captureIdx = ensureVar(node.id, 0);
            }
            idx = varIndex.get(node.id);
            current.emitPushVar(idx);
            return;
        }

        // 4) Global fallback — resolve by name in evaluation context
        int nameIdx = putConstant(node.id);
        current.emitPushGlobal(nameIdx);
    }

    // ── Apply ──
    private void buildApply(ELNode.APPLY node) {
        // List comprehensions [expr | x <- list] and XFORM — fall back to AST
        if (node.right instanceof ELNode.FOREACH ||
            node.right instanceof ELNode.FOR ||
            node.right instanceof ELNode.XFORM) {
            buildTrampoline(node);
            return;
        }

        // @data constructors have lazy fields (&tail) — AST must evaluate
        // the call to wrap deferred arguments in EvalClosure. IR eagerly
        // builds all arguments before INVOKE_DYN, causing infinite recursion.
        if (node.right instanceof ELNode.IDENT &&
            dataConstructorNames.contains(((ELNode.IDENT)node.right).id)) {
            buildTrampoline(node);
            return;
        }

        // Determine if this will use direct call or TCO (avoids pushing target)
        boolean isTail =
                inTailPosition && lambdaName != null &&
                node.right instanceof ELNode.IDENT &&
                lambdaName.equals(((ELNode.IDENT)node.right).id);
        boolean isDirect =
                !isTail && node.right instanceof ELNode.IDENT &&
                resolveKnownFunction(((ELNode.IDENT)node.right).id) != null;

        if (isTail) {
            // TCO: build args (never in tail position), emit INVOKE_TAIL
            boolean prev = inTailPosition;
            inTailPosition = false;
            for (ELNode arg : node.args)
                build(arg);
            inTailPosition = prev;
            current.emitInvokeTail(node.args.length);
            return;
        }

        if (isDirect) {
            // Direct call: build args, emit INVOKE_DIRECT
            boolean prev = inTailPosition;
            inTailPosition = false;
            for (ELNode arg : node.args)
                build(arg);
            inTailPosition = prev;
            Integer funcIdx =
                    resolveKnownFunction(((ELNode.IDENT)node.right).id);
            current.emitInvokeDirect(funcIdx, node.args.length);
            return;
        }

        // Try to resolve direct method call for known Java types
        if (node.right instanceof ELNode.ACCESS access && isSimpleKey(access.index)) {
            String methodName = getKeyName(access.index);
            org.operamasks.el.types.Type baseType = access.right != null ?
                                                    access.right.inferredType : null;
            java.lang.Class<?> javaClass = resolveJavaClass(baseType);
            if (javaClass != null && methodName != null) {
                java.lang.reflect.Method method = resolveMethod(javaClass,
                        methodName, node.args.length);
                if (method != null) {
                    // Direct method call: push base, push args, INVOKE_METHOD
                    boolean prev2 = inTailPosition;
                    inTailPosition = false;
                    build(access.right); // base
                    for (ELNode arg : node.args)
                        build(arg); // args
                    inTailPosition = prev2;
                    int methodIdx = putConstant(method);
                    current.emitInvokeMethod(methodIdx, node.args.length);
                    return;
                }
                // Method not uniquely resolvable (ambiguous overloads) —
                // trampoline
                buildTrampoline(node);
                return;
            }
        }

        // 0-arg ACCESS: use INVOKE_DYN_METHOD which resolves the method
        // by name at runtime and calls MethodClosure.invoke(elctx, base, args).
        // This handles ELContext injection (e.g. tree.eval() → eval(ELContext))
        // and preserves `this` for instance methods.
        if (node.args.length == 0 && node.right instanceof ELNode.ACCESS access &&
            isSimpleKey(access.index)) {
            String methodName = getKeyName(access.index);
            int keyIdx = putConstant(methodName);
            boolean prev = inTailPosition;
            inTailPosition = false;
            build(access.right); // base
            inTailPosition = prev;
            current.emitInvokeDynMethod(keyIdx, 0);
        } else if (node.right instanceof ELNode.ACCESS && node.args.length > 0) {
            // args > 0 ACCESS: trampoline to AST which handles closure
            // creation correctly (IR closures lose ELContext when lazy
            // sequences are forced outside eval).
            buildTrampoline(node);
        } else {
            boolean prev = inTailPosition;
            inTailPosition = false;
            build(node.right);
            for (ELNode arg : node.args)
                build(arg);
            inTailPosition = prev;
            current.emitInvokeDyn(node.args.length);
        }
    }

    private void buildXform(ELNode.XFORM node) {
        if (node.right instanceof ELNode.IDENT) {
            String id = ((ELNode.IDENT)node.right).id;
            if (dataConstructorNames.contains(id)) {
                buildTrampoline(node);
                return;
            }

            boolean isTail =
                    inTailPosition && lambdaName != null && lambdaName.equals(id);
            boolean isDirect = !isTail && resolveKnownFunction(id) != null;

            if (isTail) {
                inTailPosition = false;
                build(node.left);
                inTailPosition = true;
                current.emitInvokeTail(1);
                return;
            }

            if (isDirect) {
                boolean prev = inTailPosition;
                inTailPosition = false;
                build(node.left);
                inTailPosition = prev;
                Integer funcIdx = resolveKnownFunction(id);
                current.emitInvokeDirect(funcIdx, 1);
                return;
            }
        }

        buildTrampoline(node);
    }

    // ── Literals: list, map, tuple, range ──

    private void buildCons(ELNode.CONS node) {
        // For delayed (lazy) sequences or dotted-pair tails, fall back to AST.
        // The AST evaluator handles DelayCons and proper Cons cell
        // construction.
        if (hasDelayOrDottedTail(node)) {
            buildTrampoline(node);
            return;
        }
        // Simple list cons: [a, b, c] → NEW_LIST
        int count = countCons(node);
        emitConsElements(node);
        current.emitNewList(count);
    }

    /**
     * Check if any CONS in the chain has delay=true or a non-CONS/non-NIL tail.
     */
    private static boolean hasDelayOrDottedTail(ELNode.CONS node) {
        ELNode cur = node;
        while (cur instanceof ELNode.CONS c) {
            if (c.delay)
                return true;
            cur = c.tail;
        }
        return cur != null && cur.op != Token.NIL;
    }

    private static int countCons(ELNode.CONS node) {
        int n = 0;
        ELNode cur = node;
        while (cur instanceof ELNode.CONS c) {
            n++;
            cur = c.tail;
        }
        return cur != null && cur.op != Token.NIL ? n + 1 : n;
    }

    private void emitConsElements(ELNode.CONS node) {
        ELNode cur = node;
        while (cur instanceof ELNode.CONS c) {
            build(c.head);
            cur = c.tail;
        }
        if (cur != null && cur.op != Token.NIL) {
            build(cur);  // dotted tail
        }
    }

    private void buildMap(ELNode.MAP node) {
        // Emit key-value pairs: key1, val1, key2, val2, ...
        for (int i = 0; i < node.keys.length; i++) {
            build(node.keys[i]);
            build(node.values[i]);
        }
        current.emitNewMap(node.keys.length);
    }

    private void buildTuple(ELNode.TUPLE node) {
        for (ELNode e : node.elems)
            build(e);
        current.emitNewTuple(node.elems.length);
    }

    private void buildRange(ELNode.RANGE node) {
        build(node.begin);
        if (node.exclude) {
            // Exclusive range [begin..<end): push end-1 for inclusive range end
            build(node.end);
            emitPushConst(T_INT, 1L);
            current.emitDynSub();
        } else {
            build(node.end);
        }
        current.emitNewRange();
    }

    private void buildInstanceOf(ELNode.INSTANCEOF node) {
        build(node.right);  // evaluate the expression
        // Put type name in constant pool, emit INSTANCEOF trampoline
        int typeIdx = putConstant(node.type);
        current.emit2(TRAMPOLINE, K_DYN, typeIdx, node.negative ? 1 : 0);
    }

    private void buildContains(ELNode node) {
        if (node instanceof ELNode.IN in) {
            build(in.right);  // container
            build(in.left);   // element
            current.emitDynIn();
            if (in.negative) {
                current.emitNot();
            }
        }
    }

    // ── Binary arithmetic ──
    private void buildBinaryOp(ELNode node) {
        if (!(node instanceof ELNode.Binary bin)) {
            buildTrampoline(node);
            return;
        }

        // User-defined class instances may have custom operators —
        // trampoline to AST
        if (isNonNumericClassType(bin.left) || isNonNumericClassType(bin.right)) {
            buildTrampoline(node);
            return;
        }

        int l = typeIdFromNode(bin.left), r = typeIdFromNode(bin.right);
        // Shift operators are overloaded for stream I/O vs bit shift.
        // When types are unknown, trampoline to AST which handles
        // overloading correctly.
        if ((l < 0 || r < 0) && (node.op == Token.SHL || node.op == Token.SHR ||
                                 node.op == Token.USHR)) {
            buildTrampoline(node);
            return;
        }

        boolean prev = inTailPosition;
        inTailPosition = false; // sub-expressions of binary ops are NOT in
        // tail position
        build(bin.left);
        build(bin.right);
        inTailPosition = prev;
        if (l >= 0 && r >= 0)
            emitTypedOp(node.op, widerType(l, r));
        else
            emitDynamicOp(node.op);
    }

    private static boolean isNonNumericClassType(ELNode node) {
        if (node == null || node.inferredType == null)
            return false;
        return node.inferredType instanceof org.operamasks.el.types.ClassType ct &&
               !Number.class.isAssignableFrom(ct.javaClass) &&
               !String.class.isAssignableFrom(ct.javaClass);
    }

    /**
     * Expand ++x / x++ / --x / x-- for local variables.
     */
    private void buildIncDec(ELNode node) {
        boolean isInc = node.op == Token.INC;
        boolean isPre = node instanceof ELNode.INC inc ? inc.is_preincrement
                                                       :
                        ((ELNode.DEC)node).is_preincrement;
        ELNode target = ((ELNode.Unary)node).right;

        if (target instanceof ELNode.IDENT ident) {
            Integer idx = varIndex.get(ident.id);
            if (idx != null && !isCaptured.contains(ident.id)) {
                // Local variable (not captured): emit INC/DEC opcode
                if (isPre) {
                    current.emit1(isInc ? INC : DEC, K_PRIM, idx);
                } else {
                    current.emitPushVar(idx);
                    current.emit1(isInc ? INC : DEC, K_PRIM, idx);
                    current.emitPop(); // discard new value, leave old value
                }
                return;
            }
            if (isCaptured.contains(ident.id)) {
                // Captured variable: read from evalContext, mutate, store back
                // via STORE_GLOBAL so the enclosing scope sees the change.
                int nameIdx = putConstant(ident.id);
                int oneIdx = putConstant(1L);
                if (isPre) {
                    current.emitPushGlobal(nameIdx);
                    current.emitPushConst(oneIdx);
                    emitDynamicOp(isInc ? Token.ADD : Token.SUB);
                    current.emitDup(); // keep new value on stack for return
                    current.emitStoreGlobal(nameIdx);
                } else {
                    current.emitPushGlobal(nameIdx); // old value
                    current.emitDup();               // dup for return
                    current.emitPushConst(oneIdx);
                    emitDynamicOp(isInc ? Token.ADD : Token.SUB);
                    current.emitStoreGlobal(nameIdx);
                    current.emitPop(); // discard new value, keep old value
                }
                return;
            }
        }

        // Non-local or complex target → trampoline
        buildTrampoline(node);
    }

    private void buildUnaryOp(ELNode node) {
        if (node instanceof ELNode.Unary un) {
            boolean prev = inTailPosition;
            inTailPosition = false;
            build(un.right);
            inTailPosition = prev;
            emitDynamicOp(node.op);
        } else
            buildTrampoline(node);
    }

    private void buildCat(ELNode node) {
        if (node instanceof ELNode.Binary bin) {
            // If both sides are strings, use native CAT; otherwise trampoline
            int lt = typeIdFromNode(bin.left), rt = typeIdFromNode(bin.right);
            if (lt == T_STRING && rt == T_STRING) {
                boolean prev = inTailPosition;
                inTailPosition = false;
                build(bin.left);
                build(bin.right);
                inTailPosition = prev;
                current.emitDynCat();
            } else {
                buildTrampoline(node);
            }
        } else
            buildTrampoline(node);
    }

    private void emitTypedOp(int op, int t) {
        switch (op) {
        case Token.ADD -> {
            if (t == T_INT)
                current.emitIAdd();
            else if (t == T_LONG)
                current.emitLAdd();
            else if (t == T_DOUBLE)
                current.emitDAdd();
            else
                current.emitDynAdd();
        }
        case Token.SUB -> {
            if (t == T_INT)
                current.emitISub();
            else if (t == T_LONG)
                current.emitLSub();
            else if (t == T_DOUBLE)
                current.emitDSub();
            else
                current.emitDynSub();
        }
        case Token.MUL -> {
            if (t == T_INT)
                current.emitIMul();
            else if (t == T_LONG)
                current.emitLMul();
            else if (t == T_DOUBLE)
                current.emitDMul();
            else
                current.emitDynMul();
        }
        case Token.DIV ->
            current.emitDynDiv();  // use dynamic path for correct ELite semantics
        case Token.REM -> {
            if (t == T_INT)
                current.emitIRem();
            else
                current.emitDynRem();
        }
        case Token.NEG -> {
            if (t == T_INT)
                current.emitINeg();
            else if (t == T_LONG)
                current.emitLNeg();
            else if (t == T_DOUBLE)
                current.emitDNeg();
            else
                current.emitDynNeg();
        }
        default -> emitDynamicOp(op);
        }
    }

    private void emitDynamicOp(int op) {
        switch (op) {
        case Token.ADD -> current.emitDynAdd();
        case Token.SUB -> current.emitDynSub();
        case Token.MUL -> current.emitDynMul();
        case Token.DIV -> current.emitDynDiv();
        case Token.REM -> current.emitDynRem();
        case Token.NEG -> current.emitDynNeg();
        case Token.POW -> current.emitDynPow();
        case Token.POS -> {
            // unary plus is a no-op: value already on stack
        }
        // Bitwise: emit typed (int) by default for dynamic path
        case Token.BITOR ->
                current.emit1(Opcode.IOR, IRFormat.K_PRIM, IRFormat.T_INT);
        case Token.BITAND ->
                current.emit1(Opcode.IAND, IRFormat.K_PRIM, IRFormat.T_INT);
        case Token.XOR ->
                current.emit1(Opcode.IXOR, IRFormat.K_PRIM, IRFormat.T_INT);
        case Token.SHL ->
                current.emit1(Opcode.ISHL, IRFormat.K_PRIM, IRFormat.T_INT);
        case Token.SHR ->
                current.emit1(Opcode.ISHR, IRFormat.K_PRIM, IRFormat.T_INT);
        case Token.USHR ->
                current.emit1(Opcode.IUSHR, IRFormat.K_PRIM, IRFormat.T_INT);
        case Token.BITNOT ->
                current.emit1(Opcode.IBITNOT, IRFormat.K_PRIM, IRFormat.T_INT);
        default ->
                throw new UnsupportedOperationException("Unsupported " +
                                                        "dynamic op: " + op);
        }
    }

    // Shortcut for 'current' in emitter methods

    // ── Comparisons ──
    private void buildComparison(ELNode node) {
        if (!(node instanceof ELNode.Binary bin)) {
            buildTrampoline(node);
            return;
        }

        int l = typeIdFromNode(bin.left), r = typeIdFromNode(bin.right);
        boolean prev = inTailPosition;
        inTailPosition = false;
        build(bin.left);
        build(bin.right);
        inTailPosition = prev;
        if (l >= 0 && r >= 0)
            emitTypedCmp(node.op, widerType(l, r));
        else
            emitDynamicCmp(node.op);
    }

    private void emitTypedCmp(int op, int t) {
        switch (op) {
        case Token.EQ -> {
            if (t == T_INT)
                current.emitIEq();
            else if (t == T_LONG)
                current.emitLEq();
            else if (t == T_DOUBLE)
                current.emitDEq();
            else
                current.emitDynEq();
        }
        case Token.NE -> {
            if (t == T_INT)
                current.emitINe();
            else if (t == T_LONG)
                current.emitLNe();
            else if (t == T_DOUBLE)
                current.emitDNe();
            else {
                current.emitDynEq();
                current.emitNot();
            }
        }
        case Token.LT -> {
            if (t == T_INT)
                current.emitILt();
            else if (t == T_LONG)
                current.emitLLt();
            else if (t == T_DOUBLE)
                current.emitDLt();
            else
                current.emitDynLt();
        }
        case Token.LE -> {
            if (t == T_INT)
                current.emitILe();
            else if (t == T_LONG)
                current.emitLLe();
            else if (t == T_DOUBLE)
                current.emitDLe();
            else
                current.emitDynLe();
        }
        case Token.GT -> {
            if (t == T_INT)
                current.emitIGt();
            else if (t == T_LONG)
                current.emitLGt();
            else if (t == T_DOUBLE)
                current.emitDGt();
            else {
                current.emitDynLe();
                current.emitNot();
            }
        }
        case Token.GE -> {
            if (t == T_INT)
                current.emitIGe();
            else if (t == T_LONG)
                current.emitLGe();
            else if (t == T_DOUBLE)
                current.emitDGe();
            else {
                current.emitDynLt();
                current.emitNot();
            }
        }
        default -> current.emitDynEq();
        }
    }

    private void emitDynamicCmp(int op) {
        switch (op) {
        case Token.EQ -> current.emitDynEq();
        case Token.NE -> {
            current.emitDynEq();
            current.emitNot();
        }
        case Token.LT -> current.emitDynLt();
        case Token.LE -> current.emitDynLe();
        case Token.GT -> {
            current.emitDynLe();
            current.emitNot();
        }
        case Token.GE -> {
            current.emitDynLt();
            current.emitNot();
        }
        default -> current.emitDynEq();
        }
    }

    // ── Logical AND/OR/NOT ──
    private void buildLogical(ELNode node) {
        if (node.op == Token.NOT) {
            build(((ELNode.Unary)node).right);
            current.emitNot();
            return;
        }

        ELNode.Binary bin = (ELNode.Binary)node;
        int contB = allocBlockId(), endB = allocBlockId();
        if (node.op == Token.AND) {
            build(bin.left);
            current.emitDup();
            current.emitJumpIfFalse(endB);
            current.emitPop();
            build(bin.right);
            current.emitJump(contB);
            startBlock(endB);
            current.emitPop();
            emitPushFalse();
            current.emitJump(contB);
        } else {
            build(bin.left);
            current.emitDup();
            current.emitJumpIfTrue(endB);
            current.emitPop();
            build(bin.right);
            current.emitJump(contB);
            startBlock(endB);
            current.emitPop();
            emitPushTrue();
            current.emitJump(contB);
        }
        startBlock(contB);
    }

    // ── Conditional (if/else / ?:) ──
    private void buildConditional(ELNode.COND node) {
        int thenB = allocBlockId();
        int elseB = allocBlockId();
        int mergeB = allocBlockId();

        build(node.cond);
        // Ensure jumps are in the same block as condition
        current.emitJumpIfTrue(thenB);
        current.emitJump(elseB);
        sealAndStart(thenB);
        enterControlScope();
        buildTail(node.left);
        leaveControlScope();
        current.emitJump(mergeB);
        sealAndStart(elseB);
        enterControlScope();
        buildTail(node.right);
        leaveControlScope();
        current.emitJump(mergeB);
        sealAndStart(mergeB);
    }

    /**
     * Seal current block into blockMap and start a new block with the given ID.
     */
    private void sealAndStart(int blockId) {
        int[] code = current.toArray();
        blockMap.put(currentBlockId, code);
        runningPc += code.length;
        if (debug && currentLine > 0) {
            recordDebugLine(runningPc);
        }
        current.clear();
        currentBlockId = blockId;
    }

    // Running PC counter for debug info
    private int runningPc;

    // ── Coalesce ──
    private void buildCoalesce(ELNode node) {
        if (!(node instanceof ELNode.Binary bin)) {
            buildTrampoline(node);
            return;
        }

        build(bin.left);
        int keepB = allocBlockId();
        int nullB = allocBlockId();
        int mergeB = allocBlockId();
        current.emitDup();
        current.emitJumpIfNonNull(keepB);
        current.emitJump(nullB);
        startBlock(nullB);
        current.emitPop();
        build(bin.right);
        current.emitJump(mergeB);
        startBlock(keepB);
        current.emitJump(mergeB);
        startBlock(mergeB);
    }

    // ── Identity comparison (=== / !==) ──
    private void buildIdentityCmp(ELNode.Binary node) {
        boolean prev = inTailPosition;
        inTailPosition = false;
        build(node.left);
        build(node.right);
        inTailPosition = prev;
        if (node.op == Token.IDNE)
            current.emitRefNe();
        else
            current.emitRefEq();
    }

    // ── Compound assignment (+=, -=, etc.) ──
    // 在 IR 层面展开为 x = x op y，不依赖 AST 节点结构
    private void buildAssignOp(ELNode.ASSIGNOP node) {
        if (node.left instanceof ELNode.IDENT ident) {
            // Build: left-value op right-value, then store back
            build(node.left);        // push current value of x
            build(node.right);       // push delta
            int leftT = typeIdFromNode(node.left);
            int rightT = typeIdFromNode(node.right);
            if (leftT >= 0 && rightT >= 0) {
                emitTypedOp(node.binary.op, widerType(leftT, rightT));
            } else {
                emitDynamicOp(node.binary.op);
            }

            // Store result back — assign must find existing binding in full
            // chain.
            // Only emit STORE_GLOBAL when the variable was stored via STORE_GLOBAL
            // at define time (top-level, captured). Slot-only variables
            // (function locals, control-flow shadows) don't have global
            // bindings and STORE_GLOBAL would throw PropertyNotFoundException.
            current.emitDup();
            if (isCaptured.contains(ident.id)) {
                int nameIdx = putConstant(ident.id);
                current.emitStoreGlobal(nameIdx);
            } else {
                int idx = varIndex.getOrDefault(ident.id, -1);
                if (idx >= 0) {
                    current.emitStoreVar(idx);
                    if (globalSlots.contains(idx)) {
                        int nameIdx = putConstant(ident.id);
                        current.emitStoreGlobal(nameIdx);
                    }
                } else {
                    // Variable from previous eval — only global binding exists
                    int nameIdx = putConstant(ident.id);
                    current.emitStoreGlobal(nameIdx);
                }
            }
        } else {
            buildTrampoline(node);
        }
    }

    // ── Assign/Define ──
    private void buildAssign(ELNode.ASSIGN node) {
        build(node.right); // value to assign
        if (node.left instanceof ELNode.IDENT ident) {
            current.emitDup();
            if (isCaptured.contains(ident.id)) {
                // Captured by inner closure: search full eval context chain
                int nameIdx = putConstant(ident.id);
                current.emitStoreGlobal(nameIdx);
            } else {
                int idx = varIndex.getOrDefault(ident.id, -1);
                if (idx >= 0) {
                    current.emitStoreVar(idx);
                    if (globalSlots.contains(idx)) {
                        int nameIdx = putConstant(ident.id);
                        current.emitStoreGlobal(nameIdx);
                    }
                } else {
                    // Variable from previous eval — only global binding exists
                    int nameIdx = putConstant(ident.id);
                    current.emitStoreGlobal(nameIdx);
                }
            }
        } else if (node.left instanceof ELNode.ACCESS access &&
                   isSimpleKey(access.index)) {
            // obj.prop = value — try direct field store for known Java types
            String fieldName = getKeyName(access.index);
            org.operamasks.el.types.Type baseType = access.right != null ?
                                                    access.right.inferredType : null;
            java.lang.Class<?> javaClass = resolveJavaClass(baseType);
            if (javaClass != null && fieldName != null) {
                // 1) Check for JavaBean setter: setXxx(type) (primary Java
                // interface)
                java.lang.reflect.Method setter = resolveSetter(javaClass,
                        fieldName);
                if (setter != null) {
                    build(access.right); // base below value: [value, base]
                    int methodIdx = putConstant(setter);
                    current.emit2(INVOKE_SETTER, K_FN, methodIdx, 0);
                    return;
                }

                // 2) Check for public field (fallback)
                try {
                    java.lang.reflect.Field field =
                            javaClass.getField(fieldName);
                    if (java.lang.reflect.Modifier.isPublic(field.getModifiers())) {
                        build(access.right); // base below value: [value, base]
                        int nameIdx = putConstant(fieldName);
                        current.emitStoreField(nameIdx);
                        return;
                    }
                } catch (NoSuchFieldException e) { /* fall through */ }
            }
            build(access.right); // base
            build(access.index); // key
            current.emitStoreProperty();
        } else
            buildTrampoline(node);
    }

    private void buildDefine(ELNode.DEFINE node) {
        if (node.expr != null) {
            // @data constructors (CLASSDEF) have lazy fields (&tail)
            // that must be wrapped in EvalClosure by AST.
            if (node.expr instanceof ELNode.CLASSDEF) {
                dataConstructorNames.add(node.id);
                buildTrampoline(node);
                return;
            }

            // CLASS nodes (from import) produce DataClass wrapping a
            // Java Class. Resolve at compile time using the import context
            // and push the raw java.lang.Class directly. A raw Class is
            // compatible with ACCESS.invoke() which checks `base instanceof
            // Class` for static method resolution. DataClass wrapping is only
            // needed for @data (CLASSDEF) or pattern matching — import-based
            // CLASS nodes don't need it.
            if (node.expr instanceof ELNode.CLASS clsNode) {
                Class<?> cls = resolveClassAtCompileTime(clsNode.name);
                if (cls != null) {
                    emitPushConst(K_NONE, cls);
                    // CLASS define inside a control-flow scope: save old
                    // binding,
                    // allocate new slot with the same name
                    if (!savedVarBindings.isEmpty()) {
                        Map<String, Integer> saved = savedVarBindings.peek();
                        if (!saved.containsKey(node.id)) {
                            // Save old binding and remove from varIndex so
                            // ensureVar allocates a fresh slot for the shadow
                            saved.put(node.id, varIndex.remove(node.id));
                        }
                        int idx = ensureVar(node.id);
                        current.emitDup();
                        current.emitStoreVar(idx);
                    } else if (isCaptured.contains(node.id)) {
                        // Captured by inner closure: STORE_GLOBAL only
                        current.emitDup();
                        int nameIdx = putConstant(node.id);
                        current.emitDefineGlobal(nameIdx);
                    } else if (parent != null) {
                        // In a function, not captured: STORE_VAR only
                        int idx = ensureVar(node.id);
                        current.emitDup();
                        current.emitStoreVar(idx);
                    } else {
                        // Top-level: STORE_VAR + STORE_GLOBAL (persistence)
                        int idx = ensureVar(node.id);
                        current.emitDup();
                        current.emitStoreVar(idx);
                        int nameIdx = putConstant(node.id);
                        current.emitDefineGlobal(nameIdx);
                        globalSlots.add(idx);
                    }
                } else {
                    buildTrampoline(node);
                }
                return;
            }

            build(node.expr);

            // Three-tier variable storage strategy:
            // 1. Captured by inner closure → STORE_GLOBAL only (eval context)
            // 2. Function-local, not captured → STORE_VAR only (locals[])
            // 3. Top-level, not captured → STORE_VAR + STORE_GLOBAL
            // (persistence)

            if (!savedVarBindings.isEmpty()) {
                // Inside a control-flow scope ({...} in if/while/for):
                // save the old slot binding, allocate a new slot for the shadow
                Map<String, Integer> saved = savedVarBindings.peek();
                if (!saved.containsKey(node.id)) {
                    // Save old binding and remove from varIndex so ensureVar
                    // allocates a fresh slot for the shadow. Null → didn't exist.
                    saved.put(node.id, varIndex.remove(node.id));
                }
                int idx = ensureVar(node.id);
                current.emitDup();
                current.emitStoreVar(idx);
            } else if (isCaptured.contains(node.id)) {
                // This variable is captured by an inner closure.
                // All accesses must go through the EvaluationContext chain
                // so that closures see the same binding.
                current.emitDup();
                int nameIdx = putConstant(node.id);
                current.emitDefineGlobal(nameIdx);
            } else if (parent != null) {
                // Function-level define, not captured by any inner closure.
                // Local slot only — dies with the IRInterpreter invocation.
                int idx = ensureVar(node.id);
                current.emitDup();
                current.emitStoreVar(idx);
            } else {
                // Top-level define: store locally (for fast access) AND
                // globally (for cross-eval persistence).
                int idx = ensureVar(node.id);
                current.emitDup();
                current.emitStoreVar(idx);
                int nameIdx = putConstant(node.id);
                current.emitDefineGlobal(nameIdx);
                globalSlots.add(idx);
            }
        }
    }

    // ── Sequential ──
    private void buildThen(ELNode.THEN node) {
        build(node.left);
        current.emitPop();
        buildTail(node.right);
    }

    private void buildExpr(ELNode.EXPR node) {
        build(node.right);
    }

    /**
     * Compile string interpolation (Composite) without trampoline.
     * Equivalent to AST: StringBuilder → append(coerceToString(elem)) →
     * toString().
     * Uses DYNCAT chain to concatenate elements with type coercion.
     */
    private void buildComposite(ELNode.Composite node) {
        if (node.elems.length == 0) {
            emitPushConst(T_STRING, "");
            return;
        }
        build(node.elems[0]);
        for (int i = 1; i < node.elems.length; i++) {
            build(node.elems[i]);
            current.emitDynCat();
        }
    }

    private void buildCompound(ELNode.COMPOUND node) {
        for (int i = 0; i < node.exps.length - 1; i++) {
            build(node.exps[i]);
            current.emitPop();
        }
        if (node.exps.length > 0) {
            buildTail(node.exps[node.exps.length - 1]);
        } else {
            emitPushNull();
        }
    }

    /**
     * Build a node in tail position (preserves current tail status).
     */
    private void buildTail(ELNode node) {
        boolean prev = inTailPosition;
        inTailPosition = true;
        build(node);
        inTailPosition = prev;
    }

    // ── While ──
    private void buildWhile(ELNode.WHILE node) {
        int header = allocBlockId();
        int body = allocBlockId();
        int exit = allocBlockId();

        loopStack.push(new LoopTargets(header, exit));
        current.emitJump(header);
        startBlock(header);
        build(node.cond);
        current.emitJumpIfTrue(body);
        current.emitJump(exit);
        startBlock(body);
        enterControlScope();
        build(node.body);
        leaveControlScope();
        current.emitPop();
        current.emitJump(header);
        startBlock(exit);
        emitPushNull();
        // Exit block falls through to next — add RETURN at toplevel by caller
        loopStack.pop();
    }

    // ── For ──
    private void buildFor(ELNode.FOR node) {
        if (node.init != null)
            for (ELNode e : node.init) {
                build(e);
                current.emitPop();
            }

        int header = allocBlockId();
        int body = allocBlockId();
        int exit = allocBlockId();

        loopStack.push(new LoopTargets(header, exit));
        current.emitJump(header);

        startBlock(header);
        if (node.cond != null) {
            build(node.cond);
            current.emitJumpIfTrue(body);
        } else
            current.emitJump(body);
        current.emitJump(exit);

        startBlock(body);
        if (node.body != null) {
            enterControlScope();
            build(node.body);
            leaveControlScope();
            current.emitPop();
        }
        if (node.step != null)
            for (ELNode e : node.step) {
                build(e);
                current.emitPop();
            }
        current.emitJump(header);

        startBlock(exit);
        emitPushNull();
        loopStack.pop();
    }

    private void buildForEach(ELNode.FOREACH node) {
        // Optimize: simple integer ranges use indexed loop instead of iterator
        if (canOptimizeRange(node)) {
            buildOptimizedRangeFor(node);
            return;
        }

        // General iterator-based for-each: trampoline to AST.
        // The IR compilation has a known bug with ITER_DONE/STORE_VAR
        // stack ordering that causes ClassCastException on 2nd iteration.
        // AST handles for-in correctly.
        buildTrampoline(node);
    }

    /**
     * Check if the for-each iterates over a simple integer range [start.
     * .end] or [start..<end).
     */
    private static boolean canOptimizeRange(ELNode.FOREACH node) {
        if (node.var == null || node.index != null)
            return false;
        if (!(node.range instanceof ELNode.RANGE r))
            return false;
        if (r.next != null)
            return false; // custom step not supported
        return isSimple(r.begin) && isSimple(r.end);
    }

    private static boolean isSimple(ELNode n) {
        return n instanceof ELNode.IDENT || n instanceof ELNode.NUMBER ||
               n instanceof ELNode.POS || n instanceof ELNode.NEG;
    }

    /**
     * Emit indexed loop: i = start; while (i <= end) { body; i = i + 1 }
     */
    private void buildOptimizedRangeFor(ELNode.FOREACH node) {
        ELNode.RANGE r = (ELNode.RANGE)node.range;
        String loopVar = node.var.id;
        int varIdx = ensureVar(loopVar);
        boolean exclusive = r.exclude;

        // Emit constant 1 for increment
        int oneIdx = putConstant(1L);

        // Initialize loop var: i = start (discard the expression result)
        build(r.begin);
        current.emitStoreVar(varIdx);
        current.emitPop();  // STORE_VAR pushes back, pop it

        int header = allocBlockId();
        int body = allocBlockId();
        int step = allocBlockId();
        int exit = allocBlockId();
        // continue → step (increment, then re-check condition)
        // break → exit
        loopStack.push(new LoopTargets(step, exit));

        // Jump to header
        current.emitJump(header);

        // Header: push i, push end, compare, branch
        startBlock(header);
        current.emitPushVar(varIdx);
        build(r.end);
        if (exclusive)
            current.emitILt();
        else
            current.emitILe();  // int comparison, not dynamic
        current.emitJumpIfFalse(exit);
        current.emitJump(body);

        // Body: execute
        startBlock(body);
        enterControlScope();
        build(node.body);
        leaveControlScope();
        current.emitPop();                // discard body result
        current.emitJump(step);           // → increment step

        // Step: increment loop var, then re-check condition
        startBlock(step);
        current.emitPushVar(varIdx);
        current.emitPushConst(oneIdx);    // push constant 1
        current.emitIAdd();
        current.emitStoreVar(varIdx);     // stores to i, pushes result back
        current.emitPop();                // discard
        current.emitJump(header);

        // Exit
        startBlock(exit);
        emitPushNull();

        loopStack.pop();
    }

    // ── Break / Continue / Return ──
    private void buildBreak() {
        current.emitJump(loopStack.peek().breakBlock());
    }

    private void buildContinue() {
        current.emitJump(loopStack.peek().continueBlock());
    }

    private void buildReturn(ELNode.RETURN node) {
        if (node.right != null) {
            build(node.right);
            int t = typeIdFromNode(node.right);
            current.emitReturn(t >= 0 ? t : T_INT);
        } else
            current.emitReturnVoid();
    }

    private void buildThrow(ELNode.THROW node) {
        build(node.cause);
        current.emitThrow();
    }

    private void buildTry(ELNode.TRY node) {
        // Compile try body, catch handlers, and finally block as nested IR
        // functions.
        // Bytecode compiler uses these to generate JVM exception tables.
        // IR interpreter falls back to AST trampoline (via TRAMPOLINE).

        IRFunction tryBody = compileSubtree(node.body, null);
        int handlerCount = node.handlers != null ? node.handlers.length : 0;
        String[] catchTypes = new String[handlerCount];
        String[] catchVars = new String[handlerCount];
        IRFunction[] catchBodies = new IRFunction[handlerCount];
        for (int i = 0; i < handlerCount; i++) {
            catchTypes[i] = node.handlers[i].type;  // null = any exception
            catchVars[i] = node.handlers[i].id;
            // Register catch variable so handler body can PUSH_VAR it
            catchBodies[i] = compileSubtree(node.handlers[i].expr,
                    catchVars[i]);
        }
        IRFunction finallyBlock = node.finalizer != null ?
                                  compileSubtree(node.finalizer, null) : null;

        TryDescriptor td = new TryDescriptor(node, tryBody, catchTypes,
                catchVars, catchBodies, finallyBlock);
        int poolIdx = putConstant(td);
        current.emit2(TRAMPOLINE, K_DYN, poolIdx, 0);
    }

    /**
     * Compile a single ELNode subtree into a standalone IRFunction.
     * Does NOT capture variables from the enclosing scope — all external
     * variable references use PUSH_GLOBAL/STORE_GLOBAL.  This is correct for
     * try/catch bodies and finally blocks (they are not closures).
     */
    private IRFunction compileSubtree(ELNode node, String varToBind) {
        // No parent → no variable capture from enclosing scope.
        // External variables fall through to PUSH_GLOBAL/STORE_GLOBAL.
        IRBuilder nested = new IRBuilder(null);
        // Still share the constant pool so pool indices are consistent.
        nested.constants = this.constants;
        nested.constIndex = this.constIndex;
        nested.inTailPosition = true;
        if (varToBind != null) {
            nested.ensureVar(varToBind);  // locals[0] = caught exception
        }
        nested.build(node);
        if (!endsWithReturn(nested)) {
            nested.current.emitReturnVoid();
        }
        return nested.finish("<try_block>", varToBind != null ? 1 : 0);
    }

    // ── Lambda ──
    private void buildLambda(ELNode.LAMBDA node) {
        // Compute scope analysis for this lambda: determine which variables
        // it captures from the enclosing scope and whether they are mutated.
        Set<String> lambdaFreeVars = new HashSet<>();
        Set<String> lambdaMutableFree = new HashSet<>();
        computeLambdaCaptures(node, lambdaFreeVars, lambdaMutableFree);

        IRBuilder nested = new IRBuilder(this);
        nested.lambdaName = node.name;

        // Propagate source file from the AST node
        if (node.file != null)
            nested.currentFile = node.file;
        for (ELNode.DEFINE var : node.vars) {
            int flags = var.type != null ? IRFunction.PARAM_EXPLICIT_TYPE : 0;
            nested.ensureVar(var.id, flags);
        }

        // Pre-scan the body for free variable references that the normal
        // buildIdent path may miss (e.g. identifiers inside trampolined
        // sub-expressions like CONST_MATCH or list comprehensions).
        // Without this scan, those identifiers are never added to
        // capturedVars and are invisible to the trampoline at runtime.
        captureFreeVariables(nested, node);

        // Run scope analysis to identify which local variables are captured
        // by inner closures. These must use STORE_GLOBAL (eval context chain)
        // so inner closures can read and modify them via PUSH_GLOBAL/STORE_GLOBAL.
        ScopeAnalyzer.ScopeAnalysis lamAnaly = ScopeAnalyzer.analyzeLambda(
            node, Set.of(), new HashSet<>());
        nested.isCaptured.addAll(lamAnaly.capturedByInner);

        // Mark captured parameters so the interpreter can sync them to
        // evalContext at function entry (params are slot-only by default).
        for (ELNode.DEFINE var : node.vars) {
            if (lamAnaly.capturedByInner.contains(var.id)) {
                Integer idx = nested.varIndex.get(var.id);
                if (idx != null) {
                    int flags = nested.paramFlags.get(idx);
                    nested.paramFlags.set(idx, flags | IRFunction.PARAM_CAPTURED);
                }
            }
        }

        // Build the lambda body in its own function scope so that
        // functions defined inside are registered locally and don't
        // leak into the enclosing scope's knownFunctions.
        nested.inTailPosition = true;
        nested.pushFunctionScope();
        try {
            nested.build(node.body);
        } finally {
            nested.popFunctionScope();
        }
        if (!endsWithReturn(nested)) {
            int t = nested.typeIdFromNode(node.body);
            nested.current.emitReturn(t >= 0 ? t : T_INT);
        }

        IRFunction rawFn = nested.finish(node.name != null ? node.name :
                                         "lambda", node.vars.length,
                nested.capturedVars);
        IRFunction fn = rawFn.withDefaults(extractDefaults(node.vars));
        int poolIdx = putConstant(fn);
        // Register this function in the enclosing scope for direct calls.
        // Capturing functions (closures) must go through INVOKE_DYN so
        // captured values are passed via IRClosure.
        if (fn.captureCount() == 0) {
            registerFunction(node.name, poolIdx);
        }

        // Emit CLOSURE opcode. For captured variables:
        // - If the captured var is in the enclosing scope's isCaptured set
        //   (i.e., it's stored in eval context), push via PUSH_GLOBAL.
        // - Otherwise, push from the enclosing scope's local slot via PUSH_VAR.
        if (!nested.capturedVars.isEmpty()) {
            for (Map.Entry<String, Integer> e : nested.capturedVars.entrySet()) {
                String varName = e.getKey();
                if (isCaptured.contains(varName)) {
                    // Captured var lives in eval context — read from there
                    int nameIdx = putConstant(varName);
                    current.emitPushGlobal(nameIdx);
                } else {
                    // Free var from enclosing scope's local slot
                    Integer outerIdx = varIndex.get(varName);
                    if (outerIdx != null) {
                        current.emitPushVar(outerIdx);
                    }
                    // else: variable not found — will be undefined at runtime
                }
            }
        }
        current.emitClosure(poolIdx, nested.capturedVars.size());

        // If the lambda has a name, store it so recursive calls from
        // trampolined bodies can find the function by name.
        // Suppress inside control-flow scopes (if/while/for blocks) to
        // prevent the function from leaking out of the block.
        if (node.name != null && !node.name.isEmpty() &&
            savedVarBindings.isEmpty()) {
            current.emitDup();
            int nameIdx = putConstant(node.name);
            current.emitDefineGlobal(nameIdx);
        }
    }

    /**
     * Compute which variables this lambda captures from the enclosing scope
     * and whether they are mutated. Uses ScopeAnalysis when available,
     * falling back to the pre-scan approach.
     */
    private void computeLambdaCaptures(ELNode.LAMBDA node,
                                       Set<String> freeVarsOut,
                                       Set<String> mutableFreeOut) {
        // Collect parameter names to exclude them
        Set<String> paramNames = new HashSet<>();
        for (ELNode.DEFINE v : node.vars) {
            if (!"_".equals(v.id))
                paramNames.add(v.id);
        }

        // Use ScopeAnalysis if available (from pre-pass)
        if (scopeAnalysis != null) {
            // The pre-pass already computed captures for all lambdas.
            // We use the enclosing scope's capturedByInner to know which
            // of OUR variables are captured. For the lambda itself, we
            // need to compute which enclosing variables IT captures.
            // This is done by walking the lambda body and checking against
            // varIndex (enclosing scope's locals).
            Set<String> bodyRefs = new HashSet<>();
            Set<String> bodyMutations = new HashSet<>();
            collectVarRefs(node.body, paramNames, bodyRefs, bodyMutations);

            for (String ref : bodyRefs) {
                if (!paramNames.contains(ref) && (varIndex.containsKey(ref) || isCaptured.contains(ref))) {
                    freeVarsOut.add(ref);
                    if (bodyMutations.contains(ref)) {
                        mutableFreeOut.add(ref);
                    }
                }
            }
            return;
        }

        // Fallback: scan the body for free variable references
        // (same logic as captureFreeVariables but without allocating slots)
        if (parent == null)
            return; // top-level lambda, no outer scope

        Set<String> bodyRefs = new HashSet<>();
        Set<String> bodyMutations = new HashSet<>();
        collectVarRefs(node.body, paramNames, bodyRefs, bodyMutations);

        for (String ref : bodyRefs) {
            if (!paramNames.contains(ref) &&
                (parent.varIndex.containsKey(ref) ||
                 parent.isCaptured.contains(ref))) {
                freeVarsOut.add(ref);
                if (bodyMutations.contains(ref)) {
                    mutableFreeOut.add(ref);
                }
            }
        }
    }

    /**
     * Collect all variable references and mutations in a subtree.
     */
    private void collectVarRefs(ELNode body, Set<String> excludeNames,
                                Set<String> refsOut, Set<String> mutationsOut) {
        body.accept(new DefaultVisitor() {
            public void visit(ELNode.IDENT e) {
                if (!excludeNames.contains(e.id)) {
                    refsOut.add(e.id);
                }
            }

            public void visit(ELNode.ASSIGN e) {
                if (e.left instanceof ELNode.IDENT ident && !excludeNames.contains(ident.id)) {
                    mutationsOut.add(ident.id);
                    refsOut.add(ident.id);
                }
                scan(e.left);
                scan(e.right);
            }

            public void visit(ELNode.INC e) {
                if (e.right instanceof ELNode.IDENT ident && !excludeNames.contains(ident.id)) {
                    mutationsOut.add(ident.id);
                    refsOut.add(ident.id);
                }
                scan(e.right);
            }

            public void visit(ELNode.DEC e) {
                if (e.right instanceof ELNode.IDENT ident && !excludeNames.contains(ident.id)) {
                    mutationsOut.add(ident.id);
                    refsOut.add(ident.id);
                }
                scan(e.right);
            }

            // Skip nested lambdas — their body references are their own
            // captures, not captures of THIS lambda.
            public void visit(ELNode.LAMBDA e) {
                // Don't recurse into nested lambdas for THIS lambda's
                // capture analysis
            }

            public void visit(ELNode.BLOCK e) {
                // Don't recurse into nested blocks either
            }
        });
    }

    // ── Trampoline ──
    private void buildTrampoline(ELNode node) {
        int poolIdx = putConstant(node);
        current.emit2(TRAMPOLINE, K_DYN, poolIdx, 0);
    }

    // ── Block management ──
    private int allocBlockId() {
        return nextBlockId++;
    }

    private void startBlock(int blockId) {
        if (current != null && !current.isEmpty()) {
            int[] code = current.toArray();
            blockMap.put(currentBlockId, code);
            runningPc += code.length;
            if (debug && currentLine > 0) {
                recordDebugLine(runningPc);
            }
            current.clear();
        }
        currentBlockId = blockId;
        current = new IREmitter();
    }

    // ── Symbol/type helpers ──
    int ensureVar(String name) {
        return ensureVar(name, 0);
    }

    int ensureVar(String name, int flags) {
        Integer idx = varIndex.get(name);
        if (idx != null)
            return idx;
        idx = varNames.size();
        varNames.add(name);
        paramFlags.add(flags);
        varIndex.put(name, idx);
        return idx;
    }

    private int putConstant(Object value) {
        return constIndex.computeIfAbsent(value, k -> {
            constants.add(k);
            return constants.size() - 1;
        });
    }

    private int typeIdFromNode(ELNode node) {
        if (node == null)
            return -1;
        if (node.inferredType != null)
            return typeIdFromEliteType(node.inferredType);
        return switch (node.op) {
            case Token.NUMBER -> {
                Number n = ((ELNode.NUMBER)node).value;
                if (n instanceof Integer || n instanceof Short || n instanceof Byte)
                    yield T_INT;
                if (n instanceof Long) {
                    long v = n.longValue();
                    yield (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE)
                          ? T_INT : T_LONG;
                }
                if (n instanceof Double || n instanceof Float)
                    yield T_DOUBLE;
                yield -1;
            }
            case Token.STRINGVAL -> T_STRING;
            case Token.CHARVAL -> T_CHAR;
            case Token.TRUE, Token.FALSE -> T_BOOL;
            default -> -1;
        };
    }

    private static int typeIdFromEliteType(org.operamasks.el.types.Type t) {
        if (t == org.operamasks.el.types.Type.INTEGER)
            return T_INT;
        if (t == org.operamasks.el.types.Type.LONG)
            return T_LONG;
        if (t == org.operamasks.el.types.Type.DOUBLE)
            return T_DOUBLE;
        if (t == org.operamasks.el.types.Type.FLOAT)
            return T_DOUBLE;
        if (t == org.operamasks.el.types.Type.BOOLEAN)
            return T_BOOL;
        if (t == org.operamasks.el.types.Type.STRING)
            return T_STRING;
        if (t == org.operamasks.el.types.Type.CHAR)
            return T_CHAR;
        return -1;
    }

    private static int widerType(int a, int b) {
        if (a == T_DOUBLE || b == T_DOUBLE)
            return T_DOUBLE;
        if (a == T_LONG || b == T_LONG)
            return T_LONG;
        return a >= 0 ? a : (b >= 0 ? b : T_INT);
    }

    /**
     * Pre-scan the lambda body for free variable references that may
     * be missed during normal compilation because they are inside
     * trampolined sub-expressions (CONST_MATCH, list comprehensions).
     */
    private void captureFreeVariables(IRBuilder nested, ELNode.LAMBDA node) {
        // Use nested.parent — the immediate enclosing scope that contains
        // variables visible to the nested lambda. Using this.parent would
        // skip one level when called from buildLambda (which runs in the
        // parent builder's context, not the nested builder's).
        IRBuilder enclosing = nested.parent;
        if (enclosing == null)
            return; // top-level lambda, no outer scope
        java.util.Set<String> seen = new java.util.HashSet<>();
        // Collect lambda parameter names to exclude them
        for (ELNode.DEFINE v : node.vars) {
            if (!"_".equals(v.id))
                seen.add(v.id);
        }
        node.body.accept(new org.operamasks.el.parser.DefaultVisitor() {
            public void visit(ELNode.IDENT e) {
                if (seen.contains(e.id) || nested.varIndex.get(e.id) != null || !enclosing.varIndex.containsKey(e.id))
                    return;
                // Skip self-referencing name — handled by STORE_GLOBAL
                if (e.id.equals(node.name))
                    return;
                nested.capturedVars.put(e.id, nested.capturedVars.size());
                nested.ensureVar(e.id, 0);
                seen.add(e.id);
            }
        });
    }

    // ── Finalization ──
    static boolean endsWithReturn(IRBuilder b) {
        if (b.current != null && !b.current.isEmpty()) {
            InstructionView v = new InstructionView(b.current.toArray(), 0);
            int lastOp = -1;
            while (v.inBounds()) {
                lastOp = v.opcode();
                v.advance();
            }
            if (lastOp == RETURN || lastOp == RETURN_VOID)
                return true;
        }
        int maxId =
                b.blockMap.keySet().stream().max(Integer::compare).orElse(-1);
        if (maxId < 0)
            return false;
        int[] lb = b.blockMap.get(maxId);
        if (lb == null || lb.length == 0)
            return false;
        InstructionView v = new InstructionView(lb, 0);
        int lastOp = -1;
        while (v.inBounds()) {
            lastOp = v.opcode();
            v.advance();
        }
        return lastOp == RETURN || lastOp == RETURN_VOID;
    }

    IRFunction finish(String name, int paramCount) {
        return finish(name, paramCount, null);
    }

    /**
     * Record the current line for the given PC (used by debug info).
     */
    private void recordDebugLine(int pc) {
        if (debug && currentLine > 0 && pc >= 0) {
            int n = pcLineTable.size();
            // Deduplicate consecutive same-line entries
            if (n >= 2 && pcLineTable.get(n - 1) == currentLine)
                return;
            pcLineTable.add(pc);
            pcLineTable.add(currentLine);
        }
    }

    /**
     * Build DebugInfo from collected data.
     */
    private DebugInfo buildDebugInfo(String name, int blockCount,
                                     int[] offsets) {
        if (!debug || pcLineTable.isEmpty())
            return DebugInfo.EMPTY;
        // Compute block start positions: for each block, find the first
        // pcLineTable entry whose PC is >= the block's start offset.
        int[] blockPos = new int[blockCount];
        for (int i = 0; i < blockCount; i++) {
            int blockStart = offsets[i];
            int line = 0;
            // Get the line from the earliest PC entry at or after block start
            for (int j = 0; j < pcLineTable.size(); j += 2) {
                if (pcLineTable.get(j) >= blockStart) {
                    line = pcLineTable.get(j + 1);
                    break;
                }
            }
            blockPos[i] = line > 0 ?
                          org.operamasks.el.parser.Position.make(line, 1) : 0;
        }
        int n = pcLineTable.size();
        int[] pcLines = new int[n];
        for (int i = 0; i < n; i++)
            pcLines[i] = pcLineTable.get(i);
        return new DebugInfo(currentFile, name, blockPos, pcLines, n / 2);
    }

    IRFunction finish(String name, int paramCount,
                      Map<String, Integer> captures) {
        // Seal current block and record its debug line
        if (current != null) {
            if (!current.isEmpty()) {
                int[] code = current.toArray();
                blockMap.put(currentBlockId, code);
                if (debug && currentLine > 0) {
                    runningPc += code.length;
                    recordDebugLine(runningPc);
                }
            } else {
                current.emitReturnVoid();
                int[] code = current.toArray();
                blockMap.put(currentBlockId, code);
                if (debug && currentLine > 0) {
                    runningPc += code.length;
                    recordDebugLine(runningPc);
                }
            }
        }

        int count = Math.max(nextBlockId,
                blockMap.keySet().stream().max(Integer::compare).orElse(0) + 1);
        int[][] ordered = new int[count][];
        for (int i = 0; i < count; i++) {
            int[] code = blockMap.get(i);
            ordered[i] = code != null ? code :
                         new IREmitter().emitNop().toArray();
        }
        IntList merged = new IntList();
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = merged.size();
            merged.addAll(ordered[i]);
        }

        // Build debug info using the recorded pc→line table

        // Build paramFlags: trim to paramCount
        int[] pf = null;
        if (!paramFlags.isEmpty()) {
            pf = new int[paramCount];
            for (int i = 0; i < paramCount && i < paramFlags.size(); i++)
                pf[i] = paramFlags.get(i);
        }

        return new IRFunction(name, paramCount, captures != null ?
                                                captures.size() : 0,
                merged.toArray(), offsets, constants.toArray(new Object[0]),
                varNames.toArray(new String[0]), buildDebugInfo(name, count,
                offsets), pf);
    }

    // ── Convenience emits ──
    private void emitPushConst(int typeId, Object value) {
        int idx = putConstant(value);
        int kind = (typeId >= 0) ? K_PRIM : K_NONE;
        int payload = idx & 0xFFFF;
        if (idx < 0x10000)
            current.emit1(PUSH_CONST, kind, payload);
        else
            current.emit2(PUSH_CONST, kind, idx >>> 16, idx & 0xFFFF);
    }

    private void emitPushConst(int typeId, long value) {
        emitPushConst(typeId, (Object)Long.valueOf(value));
    }

    private void emitPushConst(int typeId, double value) {
        emitPushConst(typeId, (Object)Double.valueOf(value));
    }

    private void emitPushTrue() {
        current.emitPushTrue();
    }

    private void emitPushFalse() {
        current.emitPushFalse();
    }

    private void emitPushNull() {
        current.emitPushNull();
    }

    /**
     * Extract default parameter values from lambda definitions.
     */
    private static Object[] extractDefaults(ELNode.DEFINE[] vars) {
        boolean hasDefault = false;
        for (ELNode.DEFINE v : vars)
            if (v.expr != null) {
                hasDefault = true;
                break;
            }
        if (!hasDefault)
            return null;

        Object[] defs = new Object[vars.length];
        for (int i = 0; i < vars.length; i++) {
            defs[i] = vars[i].expr != null ? literalValue(vars[i].expr) : null;
        }
        return defs;
    }

    /**
     * Extract a literal value from an ELNode, or null if non-literal.
     */
    private static Object literalValue(ELNode node) {
        if (node instanceof ELNode.NUMBER n)
            return n.value;
        if (node instanceof ELNode.STRINGVAL s)
            return s.value;
        if (node instanceof ELNode.CHARVAL c)
            return c.value;
        if (node.op == Token.NULL)
            return null;
        if (node.op == Token.TRUE)
            return Boolean.TRUE;
        if (node.op == Token.FALSE)
            return Boolean.FALSE;
        if (node.op == Token.SYMBOL && node instanceof ELNode.SYMBOL s)
            return s.value;
        // Negative number: (- literal)
        if (node.op == Token.NEG && node instanceof ELNode.Unary u && u.right instanceof ELNode.NUMBER n) {
            Number v = n.value;
            if (v instanceof Integer)
                return -v.intValue();
            if (v instanceof Long)
                return -v.longValue();
            if (v instanceof Double)
                return -v.doubleValue();
            return v;
        }
        return null; // complex expression
    }

    // ── Function registry for direct calls ──
    // Scope-aware: each function scope has its own map. Push on scope entry,
    // pop on scope exit. Lookups search from innermost outward.
    private static final ThreadLocal<Deque<Map<String, Integer>>> knownFunctions = ThreadLocal.withInitial(ArrayDeque::new);
    /**
     * @data constructor names whose calls need AST trampoline (lazy args).
     */
    private static final Set<String> dataConstructorNames = new HashSet<>();

    /**
     * Push a new function scope (called at the start of a lambda body).
     */
    private void pushFunctionScope() {
        knownFunctions.get().push(new LinkedHashMap<>());
    }

    /**
     * Pop the current function scope (called at the end of a lambda body).
     */
    private void popFunctionScope() {
        Deque<Map<String, Integer>> stack = knownFunctions.get();
        if (!stack.isEmpty())
            stack.pop();
    }

    /**
     * Register a function name in the current scope.
     */
    private void registerFunction(String name, int irFunctionPoolIdx) {
        if (name == null)
            return;
        Deque<Map<String, Integer>> stack = knownFunctions.get();
        if (stack.isEmpty())
            stack.push(new LinkedHashMap<>());
        stack.peek().put(name, irFunctionPoolIdx);
    }

    /**
     * Resolve a function name from innermost scope outward.
     */
    private Integer resolveKnownFunction(String name) {
        for (Map<String, Integer> scope : knownFunctions.get()) {
            Integer idx = scope.get(name);
            if (idx != null)
                return idx;
        }
        return null;
    }

    // ── TCO compilation API (for testing and direct use) ──

    /**
     * Compile a lambda body with the given name (for TCO detection) and
     * parameter names.
     */
    static IRFunction compileLambda(String name, String[] paramNames,
                                    ELNode body) {
        IRBuilder b = new IRBuilder();
        b.lambdaName = name;
        b.inTailPosition = true;
        for (String p : paramNames)
            b.ensureVar(p);
        b.build(body);
        if (!endsWithReturn(b)) {
            int typeId = b.typeIdFromNode(body);
            b.current.emitReturn(typeId >= 0 ? typeId : T_INT);
        }
        return b.finish(name != null ? name : "lambda", paramNames.length);
    }

    // ── Static API ──

    private static final ConstantFolder FOLDER = new ConstantFolder();

    /**
     * Clear the function registry before compiling a new program.
     */
    private static void clearKnownFunctions() {
        knownFunctions.get().clear();
        knownFunctions.remove();  // also remove ThreadLocal to prevent
        // cross-test pollution
        dataConstructorNames.clear();
    }

    public static IRFunction compile(ELNode node) {
        return compile(node, true);
    }

    /**
     * Compile a single expression, optionally applying optimization passes.
     */
    public static IRFunction compile(ELNode node, boolean optimize) {
        return compile(node, optimize, false);
    }

    public static IRFunction compile(ELNode node, boolean optimize,
                                     boolean debug) {
        clearKnownFunctions();
        IRBytecodeCompiler.resetState();
        ScopeAnalyzer.ScopeAnalysis analysis = ScopeAnalyzer.analyze(null,
                java.util.List.of(node), null);
        IRBuilder b = new IRBuilder(null, null, analysis, debug);
        b.build(node);
        if (!endsWithReturn(b)) {
            int typeId = b.typeIdFromNode(node);
            b.current.emitReturn(typeId >= 0 ? typeId : T_INT);
        }
        return finishIR(b.finish("<expr>", 0), 0, optimize, false);
    }

    public static IRFunction compile(List<ELNode> expressions) {
        return compileWithDefs(null, expressions, null, null, true);
    }

    /**
     * Compile expressions with prior function definitions for direct call
     * optimization.
     */
    public static IRFunction compileWithDefs(List<ELNode> defs,
                                             List<ELNode> expressions) {
        return compileWithDefs(defs, expressions, true);
    }

    /**
     * Compile expressions with optional optimization passes and import context.
     * <p>
     * When {@code optimize} is false, constant folding and type specialization
     * (GUARD_TYPE, DEOPT splitting) are skipped. This is used by opt level 1
     * to produce conservative IR for comparison with optimized IR.
     * <p>
     * {@code imps} and {@code loader} enable compile-time resolution of CLASS
     * nodes (from {@code import} statements) without falling back to
     * TRAMPOLINE.
     */
    public static IRFunction compileWithDefs(List<ELNode> defs,
                                             List<ELNode> expressions,
                                             boolean optimize) {
        return compileWithDefs(defs, expressions, null, null, optimize);
    }

    /**
     * Compile with import context for compile-time class resolution.
     *
     * @param defs     function/class definition nodes
     * @param exps     expression nodes
     * @param imps     import list (e.g. ["java.util.*", "dsl.UnitFormat"])
     * @param loader   ClassLoader for resolving class names
     * @param optimize whether to run optimization passes
     */
    public static IRFunction compileWithDefs(List<ELNode> defs,
                                             List<ELNode> exps,
                                             List<String> imps,
                                             ClassLoader loader,
                                             boolean optimize) {
        return compileWithDefs(defs, exps, imps, loader, optimize, false, null);
    }

    public static IRFunction compileWithDefs(List<ELNode> defs,
                                             List<ELNode> exps,
                                             List<String> imps,
                                             ClassLoader loader,
                                             boolean optimize, boolean debug) {
        return compileWithDefs(defs, exps, imps, loader, optimize, debug, null);
    }

    public static IRFunction compileWithDefs(List<ELNode> defs,
                                             List<ELNode> exps,
                                             List<String> imps,
                                             ClassLoader loader,
                                             boolean optimize, boolean debug,
                                             String file) {
        clearKnownFunctions();
        IRBytecodeCompiler.resetState();  // fresh ELContext + funcRegistry
        // per compilation

        // Run scope analysis before building IR to determine which variables
        // are captured by closures and need to go through the evaluation
        // context.
        ScopeAnalyzer.ScopeAnalysis analysis = ScopeAnalyzer.analyze(defs,
                exps, null);
        IRBuilder b = new IRBuilder(loader, imps, analysis, debug);
        if (file != null)
            b.setFile(file);

        // Pre-register function definitions for direct call optimization
        if (defs != null) {
            for (ELNode def : defs) {
                registerDef(b, def, optimize);
            }
        }

        // Compile expressions
        for (int i = 0; i < exps.size() - 1; i++) {
            b.build(exps.get(i));
            b.current.emitPop();
        }
        if (!exps.isEmpty()) {
            ELNode last = exps.get(exps.size() - 1);
            b.build(last);
            if (!endsWithReturn(b)) {
                int t = b.typeIdFromNode(last);
                b.current.emitReturn(t >= 0 ? t : T_INT);
            }
        }
        return finishIR(b.finish("<program>", 0), 0, optimize, false);
    }

    /**
     * Apply (or skip) optimization passes to a finished IR function.
     */
    private static IRFunction finishIR(IRFunction fn, int paramCount,
                                       boolean optimize, boolean isLambda) {
        if (optimize) {
            fn = FOLDER.transform(fn);
            fn = IRSpecializer.specialize(fn, new int[paramCount]);
            if (isLambda)
                fn = FOLDER.transform(fn);  // fold constants in specialized
            // code
        }
        return fn;
    }

    /**
     * Pre-compile a function definition and register it for direct calls.
     */
    private static void registerDef(IRBuilder b, ELNode def, boolean optimize) {
        if (def instanceof ELNode.DEFINE d && d.expr instanceof ELNode.LAMBDA lam) {
            String name = lam.name != null ? lam.name : d.id;
            IRBuilder nested = new IRBuilder(b);  // share parent pool
            nested.lambdaName = lam.name;
            if (lam.file != null)
                nested.currentFile = lam.file;
            for (ELNode.DEFINE var : lam.vars) {
                int flags = var.type != null ?
                            IRFunction.PARAM_EXPLICIT_TYPE : 0;
                nested.ensureVar(var.id, flags);
            }
            // Run scope analysis to identify variables captured by inner
            // closures. These must use STORE_GLOBAL (eval context chain)
            // so inner closures can read and modify them.
            ScopeAnalyzer.ScopeAnalysis lamAnaly = ScopeAnalyzer.analyzeLambda(
                lam, Set.of(), new HashSet<>());
            nested.isCaptured.addAll(lamAnaly.capturedByInner);

            // Mark captured parameters for evalContext sync at function entry
            for (ELNode.DEFINE var : lam.vars) {
                if (lamAnaly.capturedByInner.contains(var.id)) {
                    Integer idx = nested.varIndex.get(var.id);
                    if (idx != null) {
                        int flags = nested.paramFlags.get(idx);
                        nested.paramFlags.set(idx, flags | IRFunction.PARAM_CAPTURED);
                    }
                }
            }

            nested.inTailPosition = true;
            // Build the body in its own scope — functions defined inside
            // are registered locally and won't leak to the outer scope.
            nested.pushFunctionScope();
            try {
                nested.build(lam.body);
            } finally {
                nested.popFunctionScope();
            }
            if (!endsWithReturn(nested)) {
                int t = nested.typeIdFromNode(lam.body);
                nested.current.emitReturn(t >= 0 ? t : T_INT);
            }
            IRFunction rawFn = nested.finish(name, lam.vars.length);
            rawFn = rawFn.withDefaults(extractDefaults(lam.vars));
            IRFunction fn = finishIR(rawFn, lam.vars.length, optimize, true);
            int poolIdx = b.putConstant(fn);
            b.registerFunction(name, poolIdx);
        }
    }
}
