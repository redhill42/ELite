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

import elite.lang.Builtin;
import elite.lang.Closure;
import elite.lang.MathLib;
import elite.lang.annotation.Expando;
import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.ELProgram;
import org.operamasks.el.parser.DefaultVisitor;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Position;
import org.operamasks.el.parser.Token;
import org.operamasks.el.resolver.ClassResolver;
import org.operamasks.el.resolver.MethodResolver;
import org.operamasks.util.BeanUtils;

import javax.el.ELContext;
import javax.el.ValueExpression;
import java.beans.IntrospectionException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
public class IRBuilder extends ELNode.Visitor {

    // Context used to resolve global method and Java class.
    private final ELContext elctx;

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
    List<Object> constants = new ArrayList<>();

    // ── Loop stack ──
    private record LoopTargets(int continueBlock, int breakBlock) {
    }

    private final Deque<LoopTargets> loopStack = new ArrayDeque<>();

    // ── Debug info ──
    private String currentFile;       // source file name
    private int currentLine;          // line number of last built ELNode
    private final List<Integer> pcLineTable = new ArrayList<>(); // [pc, line, ...]

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
    private final Deque<Map<String, Integer>> savedVarBindings = new ArrayDeque<>();

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
        this(ELEngine.createELContext());
    }

    /**
     * Create a top-level builder with import context, scope analysis, and
     * debug flag.
     */
    IRBuilder(ELContext elctx) {
        this.elctx = elctx;
        this.parent = null;
        this.currentBlockId = 0;
        this.current = new IREmitter();
    }

    /**
     * Create a nested builder sharing the parent's constant pool and import
     * context.
     */
    private IRBuilder(IRBuilder parent) {
        assert(parent != null);
        this.parent = parent;
        this.elctx = parent.elctx;
        this.currentBlockId = 0;
        this.current = new IREmitter();
        this.currentFile = parent.currentFile;
        this.lambdaName = parent.lambdaName;

        // Share constants with parent so pool indices are consistent
        this.constants = parent.constants;
        this.constIndex = parent.constIndex;
    }

    /**
     * Resolve a class name at compile time using the builder's import context.
     * Returns null if resolution fails (caller should fall back to trampoline).
     */
    Class<?> resolveClassAtCompileTime(String name) {
        try {
            return ClassResolver.getInstance(elctx).resolveClass(name);
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }

    // ============ MAIN DISPATCH ============

    public void analyze(ScopeAnalyzer.ScopeAnalysis analysis) {
        this.scopeAnalysis = analysis;
        isCaptured.addAll(analysis.capturedByInner);
    }

    void build(ELNode node) {
        if (node == null) {
            emitPushNull();
            return;
        }

        if (ELProgram.DEBUG) {
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

        node.accept(this);
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

    // ── Literals ──

    public void visit(ELNode.NUMBER node) {
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

    public void visit(ELNode.REGEXP node) {
        emitPushConst(K_NONE, node.value);
    }

    public void visit(ELNode.STRINGVAL node) {
        emitPushConst(T_STRING, node.value);
    }

    public void visit(ELNode.LITERAL node) {
        emitPushConst(T_STRING, node.value);
    }

    public void visit(ELNode.CHARVAL node) {
        buildConst(node.value);
    }

    public void visit(ELNode.BOOLEANVAL node) {
        if (node.value)
            emitPushTrue();
        else
            emitPushFalse();
    }

    public void visit(ELNode.NULL node) {
        emitPushNull();
    }

    public void visit(ELNode.SYMBOL node) {
        buildConst(node.value);
    }

    private void buildConst(Object value) {
        emitPushConst(K_NONE, value);
    }

    public void visit(ELNode.ACCESS node) {
        // For simple string keys, use native LOAD_PROPERTY
        if (node.index instanceof ELNode.STRINGVAL) {
            // Try to resolve field/getter access at compile time for known
            // Java types
            String fieldName = ((ELNode.STRINGVAL)node.index).value;
            org.operamasks.el.types.Type baseType = node.right != null ?
                                                    node.right.inferredType :
                                                    null;
            java.lang.Class<?> javaClass = resolveJavaClass(baseType);

            if (javaClass != null) {
                try {
                    Method getter = BeanUtils.getReadMethod(javaClass, fieldName);
                    if (getter != null) {
                        build(node.right); // push base
                        int methodIdx = putConstant(getter);
                        current.emit2(INVOKE_GETTER, K_FN, methodIdx, 0);
                        return;
                    }
                } catch (IntrospectionException ex) { /* fallthrough */ }

                // Check for public field (fallback)
                try {
                    Field field = javaClass.getField(fieldName);
                    if (Modifier.isPublic(field.getModifiers())) {
                        build(node.right); // base
                        int nameIdx = putConstant(fieldName);
                        current.emitLoadField(nameIdx);
                        return;
                    }
                } catch (NoSuchFieldException e) { /* fall through */ }
            }
        }

        // Neither getter nor field — fall back to ELResolver
        // (could be a method reference, static member, or nested class)
        build(node.right);   // base object
        build(node.index);   // key
        current.emitLoadProperty();
    }

    /**
     * Resolve a public method by name and argument count. Returns null if
     * ambiguous.
     */
    static Method resolveMethod(Class<?> cls, String name, int argCount) {
        Method found = null;
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argCount &&
                Modifier.isPublic(m.getModifiers())) {
                if (found != null)
                    return null; // ambiguous overload
                found = m;
            }
        }
        return found;
    }

    /**
     * Resolve a Type to a concrete Java Class, or null if unknown.
     */
    private static Class<?> resolveJavaClass(org.operamasks.el.types.Type type) {
        if (type instanceof org.operamasks.el.types.ClassType ct) {
            return ct.javaClass;
        }
        if (type instanceof org.operamasks.el.types.PrimitiveType pt) {
            return pt.javaClass;
        }
        return null;
    }

    // ── Identifiers ──
    public void visit(ELNode.IDENT node) {
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
            if (!capturedVars.containsKey(node.id)) {
                capturedVars.put(node.id, capturedVars.size());
                ensureVar(node.id, 0);
            }
            idx = varIndex.get(node.id);
            current.emitPushVar(idx);
            return;
        }

        // 4) Global fallback — resolve by name in evaluation context
        int nameIdx = putConstant(node.id);
        current.emitPushGlobal(nameIdx);
    }

    private boolean isLocalVar(String id) {
        if (isCaptured.contains(id))
            return false;

        if (varIndex.containsKey(id))
            return true;

        if (parent != null && parent.varIndex.containsKey(id) &&
            !parent.isCaptured.contains(id))
            return true;

        return false;
    }

    // ── Apply ──
    public void visit(ELNode.APPLY node) {
        if (node.right instanceof ELNode.IDENT) {
            String id = ((ELNode.IDENT)node.right).id;

            if (inTailPosition && id.equals(lambdaName)) {
                // TCO: build args (never in tail position), emit INVOKE_TAIL
                inTailPosition = false;
                for (ELNode arg : node.args)
                    build(arg);
                inTailPosition = true;
                current.emitInvokeTail(node.args.length);
                return;
            }

            Integer funcIdx = resolveKnownFunction(id);
            if (funcIdx != null) {
                // Direct call: check paramFlags for lazy (&) params.
                // Lazy params get compiled as thunks (DELAY), eager as normal.
                IRFunction targetFn = (IRFunction) constants.get(funcIdx);
                int[] pFlags = targetFn.paramFlags();
                boolean prev = inTailPosition;
                inTailPosition = false;
                for (int i = 0; i < node.args.length; i++) {
                    if (pFlags != null && i < pFlags.length
                        && (pFlags[i] & IRFunction.PARAM_LAZY) != 0) {
                        buildThunk(node.args[i]);  // lazy → thunk
                    } else {
                        build(node.args[i]);       // eager → normal
                    }
                }
                inTailPosition = prev;
                current.emitInvokeDirect(funcIdx, node.args.length);
                return;
            }

            // Resolve builtin function.
            if (tryBuildGlobalMethodCall(id, node.args))
                return;

            // FIXME: @data constructors have lazy fields (&tail) — AST must evaluate
            // the call to wrap deferred arguments in EvalClosure. IR eagerly
            // builds all arguments before INVOKE_DYN, causing infinite recursion.
            if (node.right instanceof ELNode.IDENT &&
                dataConstructorNames.contains(((ELNode.IDENT)node.right).id)) {
                buildTrampoline(node);
                return;
            }

            // resolve target at runtime if the given id is not a local var
            if (!isLocalVar(id)) {
                int nameIdx = putConstant(id);
                boolean prev = inTailPosition;
                inTailPosition = false;
                for (ELNode arg : node.args)
                    build(arg);
                inTailPosition = prev;
                current.emitInvokeTarget(nameIdx, node.args.length);
                return;
            }
        }

        if (node.right instanceof ELNode.ACCESS acc) {
            // Try to resolve direct method for known Java types.
            if (acc.index instanceof ELNode.STRINGVAL key) {
                if (tryBuildDirectMethodCall(acc.right, key.value, node.args))
                    return;
            }

            // resolve method at runtime
            boolean prev = inTailPosition;
            inTailPosition = false;
            build(acc.right);
            build(acc.index);
            for (ELNode arg : node.args)
                build(arg);
            inTailPosition = prev;
            current.emitInvokeDynMethod(node.args.length);
            return;
        }

        // evaluate base and generate dynamic call
        boolean prev = inTailPosition;
        inTailPosition = false;
        build(node.right);
        for (ELNode arg : node.args)
            build(arg);
        inTailPosition = prev;
        current.emitInvokeDyn(node.args.length);
    }

    private boolean tryBuildGlobalMethodCall(String name, ELNode[] args) {
        var mc = MethodResolver.getInstance(elctx).resolveGlobalMethod(name);
        if (mc == null)
            return false;

        Method method = mc.getJavaMethod();
        if (method == null)
            return false;

        return buildMethodCall(method, null, args);
    }

    private boolean tryBuildDirectMethodCall(ELNode base, String name, ELNode[] args) {
        Class<?> baseClass = null;
        if (base.inferredType != null)
            baseClass = resolveJavaClass(base.inferredType);
        if (baseClass == null)
            baseClass = Object.class;

        var mc = MethodResolver.getInstance(elctx).resolveMethod(baseClass, name);
        if (mc == null)
            return false;

        Method method = mc.getJavaMethod();
        if (method == null)
            return false;

        return buildMethodCall(method, base, args);
    }

    private boolean buildMethodCall(Method method, ELNode base, ELNode[] args) {
        Class<?>[] types = method.getParameterTypes();
        int nargs = types.length;
        int iarg = 0;
        boolean vargs = method.isVarArgs();
        boolean expando = base != null && Modifier.isStatic(method.getModifiers()) &&
                          method.getAnnotation(Expando.class) != null;

        if (nargs > 0 && types[0] == ELContext.class)
            iarg++;
        if (expando)
            iarg++;

        if (vargs) {
            if (args.length < nargs - iarg - 1)
                return false;
            nargs--;
        } else if (args.length != nargs - iarg) {
            return false;
        }

        if (buildBuiltin(method, base, args))
            return true;

        build(base);

        // Build fixed arguments in the extra values.
        int i = 0;
        for (; iarg < nargs; iarg++, i++) {
            if (delayed(types[iarg], args[i]))
                buildThunk(args[i]);
            else
                build(args[i]);
        }

        // Copy variable arguments in the extra values
        if (vargs) {
            assert types[nargs].isArray();
            Class<?> argtype = types[nargs].getComponentType();
            for (; i < args.length; i++) {
                if (delayed(argtype, args[i]))
                    buildThunk(args[i]);
                else
                    build(args[i]);
            }
        }

        int methodIdx = putConstant(method);
        if (expando)
            current.emitInvokeExpando(methodIdx, args.length);
        else
            current.emitInvokeMethod(methodIdx, args.length);
        return true;
    }

    private static boolean delayed(Class<?> type, ELNode arg) {
        return (type == ValueExpression.class || type == Closure.class) &&
               !(arg instanceof ELNode.LAMBDA);
    }

    /**
     * Build direct IR for well known builtin functions.
     */
    private boolean buildBuiltin(Method method, ELNode base, ELNode[] args) {
        if (method.getDeclaringClass() == Builtin.class) {
            switch (method.getName()) {
            case "begin":
                if (args.length == 0) {
                    emitPushNull();
                    return true;
                }
                for (int i = 0; i < args.length - 1; i++) {
                    build(args[i]);
                    current.emitPop();
                }
                build(args[args.length - 1]);
                return true;

            case "delay":
                assert args.length == 1;
                buildThunk(args[0]);
                return true;

            case "coalesce": {
                if (args.length == 0) {
                    current.emitPushNull();
                    return true;
                }
                if (args.length == 1) {
                    build(args[0]);
                    return true;
                }

                // Create a chained coalesce expression and build it.
                ELNode exp = args[args.length - 1];
                for (int i = args.length - 2; i >= 0; i--) {
                    exp = new ELNode.COALESCE(args[i].pos, args[i], exp);
                }
                exp.accept(this);
                return true;
            }

            case "list":
                for (ELNode arg : args)
                    build(arg);
                current.emitNil();
                for (int i = 0; i < args.length; i++)
                    current.emitNewCons();
                return true;

            case "cons":
                assert args.length == 2;
                buildThunk(args[0]);
                buildThunk(args[1]);
                current.emitNewDelayCons();
                return true;

            case "range":
                assert args.length == 3;
                build(args[0]);
                current.emitDup();
                build(args[2]);
                current.emitDynAdd();
                build(args[1]);
                current.emitNewRange();
                return true;

            case "upto":
                return buildStepBuiltin(base, args[0], args[1], 1, DYNLE);
            case "downto":
                return buildStepBuiltin(base, args[0], args[1], -1, DYNGE);
            case "step":
                assert args.length == 3;
                if (args[1] instanceof ELNode.NUMBER n) {
                    int step = n.value.intValue();
                    if (step != 0)
                        return buildStepBuiltin(base, args[0], args[2], step,
                                                step > 0 ? DYNLE : DYNGE);
                }
                return false;
            case "times":
                return buildStepBuiltin(new ELNode.NUMBER(-1, 0), base, args[0], 1, DYNLT);
            }
        }

        if (method.getDeclaringClass() == MathLib.class) {
            switch (method.getName()) {
            case "sum":
                return buildMathReduce(args, DYNADD);
            case "difference":
                return buildMathReduce(args, DYNSUB);
            case "product":
                return buildMathReduce(args, DYNMUL);
            case "divide":
                return buildMathReduce(args, DYNDIV);

            case "remainder":
                build(args[0]);
                build(args[1]);
                current.emitDynRem();
                return true;

            case "pow":
                build(args[0]);
                build(args[1]);
                current.emitDynPow();
                return true;
            }
        }

        return false;
    }

    private boolean buildStepBuiltin(ELNode begin, ELNode end, ELNode body,
                                     int step, int cmpop) {
        if (body instanceof ELNode.LAMBDA b) {
            if (b.vars.length > 1 || b.varargs)
                return false;
        } else {
            return false; // FIXME: support closure invocation
        }

        String indId = b.vars.length == 1 ? b.vars[0].id : "*t0*";
        String endId = "*t1*";

        // Initialize temporary variables.
        enterControlScope();
        assert !savedVarBindings.isEmpty();
        Map<String, Integer> saved = savedVarBindings.peek();
        if (!saved.containsKey(indId))
            saved.put(indId, varIndex.remove(indId));
        if (!saved.containsKey(endId))
            saved.put(endId, varIndex.remove(endId));
        int indvar = ensureVar(indId);
        int endvar = ensureVar(endId);

        // FIXME: induction variable may be captured, should put in evaluation context.
        build(begin);
        current.emitStoreVar(indvar);
        current.emitPop();
        build(end);
        current.emitStoreVar(endvar);
        current.emitPop();

        // Begin loop.
        int headerB = allocBlockId();
        int bodyB = allocBlockId();
        int exitB = allocBlockId();

        loopStack.push(new LoopTargets(headerB, exitB));
        current.emitJump(headerB);

        // Generate loop condition.
        startBlock(headerB);
        current.emitPushVar(indvar);
        current.emitPushVar(endvar);
        current.emit1(cmpop, K_DYN, 0);
        current.emitJumpIfTrue(bodyB);
        current.emitJump(exitB);

        // Generate loop body.
        startBlock(bodyB);
        build(b.body);
        current.emitPop();

        // Increment induction variable.
        current.emitPushVar(indvar);
        emitPushConst(T_INT, Math.abs(step));
        if (step > 0)
            current.emitDynAdd();
        else
            current.emitDynSub();
        current.emitStoreVar(indvar);
        current.emitPop();
        current.emitJump(headerB);

        // Cleanup.
        startBlock(exitB);
        emitPushNull();
        leaveControlScope();
        loopStack.pop();
        return true;
    }

    private boolean buildMathReduce(ELNode[] args, int op) {
        if (args.length == 0) {
            emitPushConst(T_INT, 0);
        } else {
            build(args[0]);
            for (int i = 1; i < args.length; i++) {
                build(args[i]);
                current.emit1(op, K_DYN, 0);
            }
        }
        return true;
    }

    /**
     * Compile an expression as a lazy thunk. The expression is compiled into
     * a zero-parameter IRFunction, and a DELAY opcode is emitted to create
     * a Thunk wrapping it at runtime.
     */
    void buildThunk(ELNode expr) {
        while (expr instanceof ELNode.EXPR) {
            expr = ((ELNode.EXPR)expr).right;
        }
        if (expr instanceof ELNode.Constant) {
            // Create a LiteralWrapper for constants.
            build(expr);
            current.emitLiteral();
            return;
        }

        IRBuilder nested = new IRBuilder(this);  // share parent pool
        nested.inTailPosition = true;
        nested.lambdaName = null;   // thunk is anonymous — no recursive TCO target

        // Scan the expression for free variables from the enclosing scope.
        // A thunk is a zero-param lambda — capture semantics are identical.
        expr.accept(new DefaultVisitor() {
            final java.util.Set<String> seen = new java.util.HashSet<>();
            public void visit(ELNode.IDENT e) {
                if (seen.contains(e.id)
                    || nested.varIndex.containsKey(e.id)
                    || !varIndex.containsKey(e.id))
                    return;
                nested.capturedVars.put(e.id, nested.capturedVars.size());
                nested.ensureVar(e.id, 0);
                seen.add(e.id);
            }
        });

        nested.build(expr);
        if (!endsWithReturn(nested)) {
            int t = typeIdFromNode(expr);
            nested.current.emitReturn(t >= 0 ? t : IRFormat.T_INT);
        }
        IRFunction rawFn = nested.finish("<thunk>", 0);
        int poolIdx = putConstant(rawFn);

        // Push captured values (same pattern as buildLambda)
        if (!nested.capturedVars.isEmpty()) {
            for (Map.Entry<String, Integer> e : nested.capturedVars.entrySet()) {
                String varName = e.getKey();
                if (isCaptured.contains(varName)) {
                    int nameIdx = putConstant(varName);
                    current.emitPushGlobal(nameIdx);
                } else {
                    Integer outerIdx = varIndex.get(varName);
                    if (outerIdx != null)
                        current.emitPushVar(outerIdx);
                }
            }
        }
        current.emitDelay(poolIdx, nested.capturedVars.size());
    }

    // ── Literals: list, map, tuple, range ──

    public void visit(ELNode.CONS node) {
        if (node.delay) {
            buildThunk(node.head);
            buildThunk(node.tail);
            current.emitNewDelayCons();
        } else {
            build(node.head);
            build(node.tail);
            current.emitNewCons();
        }
    }

    public void visit(ELNode.NIL node) {
        current.emitNil();
    }

    public void visit(ELNode.MAP node) {
        // Emit key-value pairs: key1, val1, key2, val2, ...
        for (int i = 0; i < node.keys.length; i++) {
            build(node.keys[i]);
            build(node.values[i]);
        }
        current.emitNewMap(node.keys.length);
    }

    public void visit(ELNode.TUPLE node) {
        for (ELNode e : node.elems)
            build(e);
        current.emitNewTuple(node.elems.length);
    }

    public void visit(ELNode.RANGE node) {
        build(node.begin);
        build(node.next);
        if (node.exclude && node.end != null) {
            // Exclusive range [begin..<end): push end-1 for inclusive range end
            build(node.end);
            emitPushConst(T_INT, 1L);
            current.emitDynSub();
        } else {
            build(node.end);
        }
        current.emitNewRange();
    }

    public void visit(ELNode.IN node) {
        build(node.right);  // container
        build(node.left);   // element
        current.emitDynIn();
        if (node.negative)
            current.emitNot();
    }

    public void visit(ELNode.INSTANCEOF node) {
        int clsid;
        build(node.right);
        try {
            Class<?> cls = ClassResolver.getInstance(elctx).resolveClass(node.type);
            clsid = putConstant(cls);
        } catch (ClassNotFoundException e) {
            clsid = putConstant(node.type);
        }
        current.emit1(INSTOF, K_BOOL, clsid);
        if (node.negative)
            current.emitNot();
    }

    // ── Binary arithmetic ──

    public void visit(ELNode.ADD node) { buildBinaryOp(node); }
    public void visit(ELNode.SUB node) { buildBinaryOp(node); }
    public void visit(ELNode.MUL node) { buildBinaryOp(node); }
    public void visit(ELNode.DIV node) {
        if (node.op == Token.DIV)
            buildBinaryOp(node);
        else
            buildTrampoline(node); // FIXME
    }
    public void visit(ELNode.REM node)    { buildBinaryOp(node); }
    public void visit(ELNode.POW node)    { buildBinaryOp(node); }
    public void visit(ELNode.BITOR node)  { buildBinaryOp(node); }
    public void visit(ELNode.BITAND node) { buildBinaryOp(node); }
    public void visit(ELNode.XOR node)    { buildBinaryOp(node); }
    public void visit(ELNode.SHL node)    { buildBinaryOp(node); }
    public void visit(ELNode.SHR node)    { buildBinaryOp(node); }
    public void visit(ELNode.USHR node)   { buildBinaryOp(node); }

    private void buildBinaryOp(ELNode.Binary node) {
        boolean prev = inTailPosition;
        inTailPosition = false; // sub-expressions of binary ops are NOT in
        // tail position
        build(node.left);
        build(node.right);
        inTailPosition = prev;

        int l = typeIdFromNode(node.left), r = typeIdFromNode(node.right);
        if (l >= 0 && r >= 0 && !isNonNumericClassType(node.left) &&
            !isNonNumericClassType(node.right))
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

    public void visit(ELNode.NEG node)    { buildUnaryOp(node); }
    public void visit(ELNode.POS node)    { /* nop */ }
    public void visit(ELNode.BITNOT node) { buildUnaryOp(node); }
    public void visit(ELNode.EMPTY node)  { buildUnaryOp(node); }

    private void buildUnaryOp(ELNode.Unary node) {
        boolean prev = inTailPosition;
        inTailPosition = false;
        build(node.right);
        inTailPosition = prev;
        emitDynamicOp(node.op);
    }

    public void visit(ELNode.INC node) {
        buildIncDec(node.right, true, node.is_preincrement);
    }

    public void visit(ELNode.DEC node) {
        buildIncDec(node.right, false, node.is_preincrement);
    }

    /**
     * Expand ++x / x++ / --x / x-- for local variables.
     */
    private void buildIncDec(ELNode target, boolean isInc, boolean isPre) {
        // Handle parentheses expression.
        while (target instanceof ELNode.EXPR)
            target = ((ELNode.EXPR)target).right;

        // Evaluate right value.
        build(target);
        if (!isPre)
            current.emitDup();

        // Increment or decrement the value.
        emitPushConst(T_INT, 1);
        if (isInc)
            current.emitDynAdd();
        else
            current.emitDynSub();

        // Assign to right value itself.
        if (target instanceof ELNode.IDENT ident)
            buildStoreVariable(ident.id);
        else if (target instanceof ELNode.ACCESS access)
            buildStoreProperty(access);
        else // could not happen, parser doesn't allow increment/decrement on other expression
            throw new UnsupportedOperationException("Invalid increment/decrement");

        // If preincrement, stack top is the return value, otherwise pop and
        // keep duped value on top.
        if (!isPre)
            current.emitPop();
    }

    public void visit(ELNode.CAT node) {
        boolean prev = inTailPosition;
        inTailPosition = false;
        build(node.left);
        build(node.right);
        inTailPosition = prev;
        current.emitDynCat();
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
        case Token.POW -> current.emitDynPow();
        case Token.SHL -> current.emitDynShl();
        case Token.SHR -> current.emitDynShr();
        case Token.USHR -> current.emitDynUShr();
        case Token.BITAND -> current.emitDynBitAnd();
        case Token.BITOR -> current.emitDynBitOr();
        case Token.XOR -> current.emitDynXor();
        case Token.BITNOT -> current.emitDynBitNot();
        case Token.NEG -> current.emitDynNeg();
        case Token.POS -> { /* unary plus is a no-op: value already on stack */ }
        case Token.EMPTY -> current.emitDynEmpty();
        default ->
                throw new UnsupportedOperationException("Unsupported " +
                                                        "dynamic op: " + op);
        }
    }

    // ── Comparisons ──
    public void visit(ELNode.EQ node) { buildComparison(node); }
    public void visit(ELNode.NE node) { buildComparison(node); }
    public void visit(ELNode.LT node) { buildComparison(node); }
    public void visit(ELNode.LE node) { buildComparison(node); }
    public void visit(ELNode.GT node) { buildComparison(node); }
    public void visit(ELNode.GE node) { buildComparison(node); }

    private void buildComparison(ELNode.Binary node) {
        int l = typeIdFromNode(node.left), r = typeIdFromNode(node.right);
        boolean prev = inTailPosition;
        inTailPosition = false;
        build(node.left);
        build(node.right);
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
                current.emitDynNe();
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
                current.emitDynGt();
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
                current.emitDynGe();
            }
        }
        default -> throw new UnsupportedOperationException();
        }
    }

    private void emitDynamicCmp(int op) {
        switch (op) {
        case Token.EQ -> current.emitDynEq();
        case Token.NE -> current.emitDynNe();
        case Token.LT -> current.emitDynLt();
        case Token.LE -> current.emitDynLe();
        case Token.GT -> current.emitDynGt();
        case Token.GE -> current.emitDynGe();
        default -> throw new UnsupportedOperationException();
        }
    }

    // ── Identity comparison (=== / !==) ──
    public void visit(ELNode.IDEQ node) { buildIdentityCmp(node); }
    public void visit(ELNode.IDNE node) { buildIdentityCmp(node); }

    private void buildIdentityCmp(ELNode.Binary node) {
        boolean prev = inTailPosition;
        inTailPosition = false;
        build(node.left);
        build(node.right);
        inTailPosition = prev;
        if (node.op == Token.IDNE)
            current.emitIdNe();
        else
            current.emitIdEq();
    }

    // ── Logical AND/OR/NOT ──

    public void visit(ELNode.AND node) {
        int contB = allocBlockId(), endB = allocBlockId();
        build(node.left);
        current.emitDup();
        current.emitJumpIfFalse(endB);
        current.emitPop();
        build(node.right);
        current.emitJump(contB);
        startBlock(endB);
        current.emitPop();
        emitPushFalse();
        current.emitJump(contB);
        startBlock(contB);
    }


    public void visit(ELNode.OR node) {
        int contB = allocBlockId(), endB = allocBlockId();
        build(node.left);
        current.emitDup();
        current.emitJumpIfTrue(endB);
        current.emitPop();
        build(node.right);
        current.emitJump(contB);
        startBlock(endB);
        current.emitPop();
        emitPushTrue();
        current.emitJump(contB);
        startBlock(contB);
    }

    public void visit(ELNode.NOT node) {
        build(node.right);
        current.emitNot();
    }

    // ── Conditional (if/else / ?:) ──
    public void visit(ELNode.COND node) {
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
        if (ELProgram.DEBUG && currentLine > 0) {
            recordDebugLine(runningPc);
        }
        current.clear();
        currentBlockId = blockId;
    }

    // Running PC counter for debug info
    private int runningPc;

    // ── Coalesce ──
    public void visit(ELNode.COALESCE node) {
        if (node.left.op == Token.NULL) {
            build(node.right);
            return;
        }

        if (!nullable(node.left)) {
            build(node.left);
            return;
        }

        int keepB = allocBlockId();
        int nullB = allocBlockId();
        int mergeB = allocBlockId();

        build(node.left);
        current.emitDup();
        current.emitJumpIfNonNull(keepB);
        current.emitJump(nullB);

        startBlock(nullB);
        current.emitPop();
        build(node.right);
        current.emitJump(mergeB);

        startBlock(keepB);
        current.emitJump(mergeB);

        startBlock(mergeB);
    }

    private boolean nullable(ELNode node) {
        while (node instanceof ELNode.EXPR)
            node = ((ELNode.EXPR)node).right;
        return !(node instanceof ELNode.Constant ||
                 node instanceof ELNode.Composite ||
                 node instanceof ELNode.CONS ||
                 node instanceof ELNode.MAP ||
                 node instanceof ELNode.TUPLE ||
                 node instanceof ELNode.RANGE ||
                 node instanceof ELNode.LAMBDA);
    }

    public void visit(ELNode.ASSIGN node) {
        if (node instanceof ELNode.ASSIGNOP) {
            buildAssignOp((ELNode.ASSIGNOP)node);
        } else {
            if (!buildAssign(node.left, node.right))
                buildTrampoline(node);
        }
    }

    // ── Compound assignment (+=, -=, etc.) ──
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
            buildStoreVariable(ident.id);
        } else {
            buildTrampoline(node);
        }
    }

    // ── Assign/Define ──
    private boolean buildAssign(ELNode left, ELNode right) {
        while (left instanceof ELNode.EXPR) {
            left = ((ELNode.EXPR)left).right;
        }

        if (left instanceof ELNode.IDENT ident) {
            build(right);
            buildStoreVariable(ident.id);
            return true;
        }

        if (left instanceof ELNode.ACCESS access) {
            build(right);
            buildStoreProperty(access);
            return true;
        }

        if (left instanceof ELNode.TUPLE lhs &&
            right instanceof ELNode.TUPLE rhs &&
            isAssignableTuple(lhs, rhs)) {
            buildTupleAssign(lhs, rhs);
            return true;
        }

        return false;
    }

    private void buildStoreVariable(String name) {
        if (isCaptured.contains(name)) {
            // Captured by inner closure: search full eval context chain
            int nameIdx = putConstant(name);
            current.emitStoreGlobal(nameIdx);
        } else {
            int idx = varIndex.getOrDefault(name, -1);
            if (idx >= 0) {
                current.emitStoreVar(idx);
                if (globalSlots.contains(idx)) {
                    int nameIdx = putConstant(name);
                    current.emitStoreGlobal(nameIdx);
                }
            } else {
                // Variable from previous eval — only global binding exists
                int nameIdx = putConstant(name);
                current.emitStoreGlobal(nameIdx);
            }
        }
    }

    private void buildStoreProperty(ELNode.ACCESS access) {
        if (access.index instanceof ELNode.STRINGVAL) {
            // obj.prop = value — try direct field store for known Java types
            String fieldName = ((ELNode.STRINGVAL)access.index).value;
            org.operamasks.el.types.Type baseType =
                access.right != null ? access.right.inferredType : null;
            java.lang.Class<?> javaClass = resolveJavaClass(baseType);
            if (javaClass != null) {
                // Check for JavaBean setter: setXxx(type) (primary Java
                // interface)
                try {
                    var setter = BeanUtils.getWriteMethod(javaClass, fieldName);
                    if (setter != null) {
                        build(access.right); // base below value: [value, base]
                        int methodIdx = putConstant(setter);
                        current.emit2(INVOKE_SETTER, K_FN, methodIdx, 0);
                        return;
                    }
                } catch (IntrospectionException ex) { /* fallthrough */ }

                // Check for public field (fallback)
                try {
                    Field field = javaClass.getField(fieldName);
                    if (Modifier.isPublic(field.getModifiers())) {
                        build(access.right); // base below value: [value, base]
                        int nameIdx = putConstant(fieldName);
                        current.emitStoreField(nameIdx);
                        return;
                    }
                } catch (NoSuchFieldException e) { /* fall through */ }
            }
        }

        build(access.right);
        build(access.index);
        current.emitStoreProperty();
    }

    private boolean isAssignableTuple(ELNode.TUPLE lhs, ELNode.TUPLE rhs) {
        if (lhs.elems.length != rhs.elems.length)
            return false;

        for (int i = 0; i < lhs.elems.length; i++) {
            ELNode elem = lhs.elems[i];
            if (elem instanceof ELNode.IDENT)
                continue;
            if (elem instanceof ELNode.ACCESS)
                continue;
            if (elem instanceof ELNode.TUPLE t1 &&
                rhs.elems[i] instanceof ELNode.TUPLE t2 &&
                isAssignableTuple(t1, t2))
                continue;
            return false;
        }

        return true;
    }

    private void buildTupleAssign(ELNode.TUPLE lhs, ELNode.TUPLE rhs) {
        assert(lhs.elems.length == rhs.elems.length);
        if (lhs.elems.length == 0) {
            current.emitNewTuple(0);
            return;
        }

        // Must evaluate all right values before assign to left values.
        List<Integer> tmpVars = new ArrayList<>();
        buildFlattenTuple(rhs.elems, tmpVars);

        // Assign to left values sequentially.
        buildAssignFlattenTuple(lhs.elems, tmpVars);
    }

    private void buildFlattenTuple(ELNode[] elems, List<Integer> tmpVars) {
        for (ELNode elem : elems) {
            if (elem instanceof ELNode.TUPLE tt) {
                buildFlattenTuple(tt.elems, tmpVars);
            } else {
                int varIdx = ensureVar("*t" + tmpVars.size() + "*");
                tmpVars.add(varIdx);
                build(elem);
                current.emitStoreVar(varIdx);
                current.emitPop();
            }
        }
    }

    private void buildAssignFlattenTuple(ELNode[] elems, List<Integer> tmpVars) {
        for (ELNode elem : elems) {
            if (elem instanceof ELNode.TUPLE tt) {
                buildAssignFlattenTuple(tt.elems, tmpVars);
            } else if (elem instanceof ELNode.IDENT ident) {
                current.emitPushVar(tmpVars.remove(0));
                buildStoreVariable(ident.id);
            } else if (elem instanceof ELNode.ACCESS access) {
                current.emitPushVar(tmpVars.remove(0));
                buildStoreProperty(access);
            } else {
                assert(false); // already checked by isAssignableTuple
            }
        }

        // Elements kept in stack, build a tuple as assign result.
        current.emitNewTuple(elems.length);
    }

    public void visit(ELNode.DEFINE node) {
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
                        current.emitStoreVar(idx);
                    } else if (isCaptured.contains(node.id)) {
                        // Captured by inner closure: STORE_GLOBAL only
                        int nameIdx = putConstant(node.id);
                        current.emitDefineGlobal(nameIdx);
                    } else if (parent != null) {
                        // In a function, not captured: STORE_VAR only
                        int idx = ensureVar(node.id);
                        current.emitStoreVar(idx);
                    } else {
                        // Top-level: STORE_VAR + STORE_GLOBAL (persistence)
                        int idx = ensureVar(node.id);
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

            // Detect self-referential definitions where the variable appears
            // inside a lazy context (e.g. &thunk in delay cons). The lazy
            // expression is evaluated by AST trampoline or buildThunk, both
            // of which need the variable in evalContext. Mark as captured
            // so DEFINE_GLOBAL is emitted instead of STORE_VAR.
            if (!isCaptured.contains(node.id)
                && hasSelfReference(node.expr, node.id)) {
                isCaptured.add(node.id);
            }

            build(node.expr);

            // Three-tier variable storage strategy:
            // 1. Captured by inner closure → STORE_GLOBAL only (eval context)
            // 2. Function-local, not captured → STORE_VAR only (locals[])
            // 3. Top-level, not captured → STORE_VAR + STORE_GLOBAL
            // (persistence)

            // Named lambda already defined global name, no need to redefine.
            boolean isNamedLambda = node.expr instanceof ELNode.LAMBDA lam &&
                                    lam.name != null;

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
                current.emitStoreVar(idx);
            } else if (isCaptured.contains(node.id)) {
                if (!isNamedLambda) {
                    // This variable is captured by an inner closure.
                    // All accesses must go through the EvaluationContext chain
                    // so that closures see the same binding.
                    int nameIdx = putConstant(node.id);
                    current.emitDefineGlobal(nameIdx);
                }
            } else if (parent != null) {
                // Function-level define, not captured by any inner closure.
                // Local slot only — dies with the IRInterpreter invocation.
                int idx = ensureVar(node.id);
                current.emitStoreVar(idx);
            } else {
                // Top-level define: store locally (for fast access) AND
                // globally (for cross-eval persistence).
                int idx = ensureVar(node.id);
                current.emitStoreVar(idx);
                if (!isNamedLambda) {
                    int nameIdx = putConstant(node.id);
                    current.emitDefineGlobal(nameIdx);
                    globalSlots.add(idx);
                }
            }
        }
    }

    public void visit(ELNode.EXPR node) {
        build(node.right);
    }

    /**
     * Compile string interpolation (Composite) without trampoline.
     * Equivalent to AST: StringBuilder → append(coerceToString(elem)) →
     * toString().
     * Uses DYNCAT chain to concatenate elements with type coercion.
     */
    public void visit(ELNode.Composite node) {
        if (node.elems.length == 0) {
            emitPushConst(T_STRING, "");
            return;
        }
        for (ELNode elem : node.elems)
            build(elem);
        current.emitCat(node.elems.length);
    }

    public void visit(ELNode.COMPOUND node) {
        if (node.exps.length == 0)
            emitPushNull();
        for (int i = 0; i < node.exps.length - 1; i++) {
            build(node.exps[i]);
            current.emitPop();
        }
        buildTail(node.exps[node.exps.length - 1]);
    }

    // ── While ──
    public void visit(ELNode.WHILE node) {
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

    // ── Repeat (post-test loop: body executes at least once) ──
    public void visit(ELNode.REPEAT node) {
        int body = allocBlockId();
        int cont = allocBlockId();   // condition-check block
        int exit = allocBlockId();

        // continue → condition check (matching C/Java do-while semantics)
        // break → exit
        loopStack.push(new LoopTargets(cont, exit));

        // Explicit jump to body from the current block so nested loops
        // have a proper terminator (same pattern as WHILE).
        current.emitJump(body);

        startBlock(body);
        enterControlScope();
        build(node.body);
        leaveControlScope();
        current.emitPop();           // discard body result
        current.emitJump(cont);      // → check condition

        startBlock(cont);
        build(node.cond);            // evaluate condition
        current.emitJumpIfTrue(body);
        current.emitJump(exit);

        startBlock(exit);
        emitPushNull();

        loopStack.pop();
    }

    // ── For ──
    public void visit(ELNode.FOR node) {
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

    public void visit(ELNode.FOREACH node) {
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
     * .end] or [start..&lt;end].
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
    public void visit(ELNode.BREAK node) {
        current.emitJump(loopStack.peek().breakBlock());
    }

    public void visit(ELNode.CONTINUE node) {
        current.emitJump(loopStack.peek().continueBlock());
    }

    public void visit(ELNode.RETURN node) {
        if (node.right != null) {
            build(node.right);
            int t = typeIdFromNode(node.right);
            current.emitReturn(t >= 0 ? t : T_INT);
        } else
            current.emitReturnVoid();
    }

    public void visit(ELNode.THROW node) {
        build(node.cause);
        current.emitThrow();
    }

    public void visit(ELNode.TRY node) {
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
        IRBuilder nested = new IRBuilder();
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
    public void visit(ELNode.LAMBDA node) {
        // Compute scope analysis for this lambda: determine which variables
        // it captures from the enclosing scope and whether they are mutated.
        Set<String> lambdaFreeVars = new HashSet<>();
        Set<String> lambdaMutableFree = new HashSet<>();
        computeLambdaCaptures(node, lambdaFreeVars, lambdaMutableFree);

        // Mark enclosing-scope variables that this lambda captures so they
        // use STORE_GLOBAL/PUSH_GLOBAL (evalContext chain). Without this,
        // captured parameters are stored only in locals[] and the closure
        // captures them by VALUE (copy) — mutations inside the closure
        // don't propagate back to the outer scope.
        for (String varName : lambdaFreeVars) {
            if (!isCaptured.contains(varName) && varIndex.containsKey(varName)) {
                isCaptured.add(varName);
                // If this is a parameter, mark PARAM_CAPTURED so the
                // interpreter syncs its initial value to evalContext at entry.
                Integer idx = varIndex.get(varName);
                if (idx != null && idx < paramFlags.size()) {
                    int flags = paramFlags.get(idx);
                    paramFlags.set(idx, flags | IRFunction.PARAM_CAPTURED);
                }
            }
        }

        IRBuilder nested = new IRBuilder(this);
        nested.lambdaName = node.name;

        // Propagate free-variable captures into the nested builder so that
        // both reads (PUSH_GLOBAL) and writes (STORE_GLOBAL) go through the
        // evalContext chain. The enclosing scope was already marked by the
        // block above; this ensures the nested scope is consistent.
        for (String varName : lambdaFreeVars) {
            nested.isCaptured.add(varName);
        }

        // Propagate source file from the AST node
        if (node.file != null)
            nested.currentFile = node.file;
        for (ELNode.DEFINE var : node.vars) {
            int flags = var.type != null ? IRFunction.PARAM_EXPLICIT_TYPE : 0;
            if (!var.immediate) flags |= IRFunction.PARAM_LAZY;
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

        IRFunction rawFn = nested.finish(
            node.name != null ? node.name : "lambda",
            node.vars.length, nested.capturedVars);
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
                (varIndex.containsKey(ref) || isCaptured.contains(ref) ||
                 parent.varIndex.containsKey(ref) || parent.isCaptured.contains(ref))) {
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

            // Recursively collect refs from nested lambda bodies, excluding
            // the nested lambda's own parameters. This ensures that outer
            // functions know about variables captured by deeply nested
            // closures (e.g., outer(a) => \b => \c => a + b + c).
            public void visit(ELNode.LAMBDA e) {
                Set<String> nestedExcludes = new HashSet<>(excludeNames);
                for (ELNode.DEFINE v : e.vars) {
                    nestedExcludes.add(v.id);
                }
                collectVarRefs(e.body, nestedExcludes, refsOut, mutationsOut);
            }

            public void visit(ELNode.BLOCK e) {
                // Don't recurse into nested blocks either
            }
        });
    }

    // ── Trampoline ──

    public void visitNode(ELNode node) {
        // Default fallback.
        buildTrampoline(node);
    }

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
            if (ELProgram.DEBUG && currentLine > 0) {
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
            case Token.STRINGVAL, Token.LITERAL -> T_STRING;
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
     * Check whether an expression tree contains a reference to the given
     * variable name. Used to detect self-referential definitions like
     * {@code define x = [1 : &f(x)]} that need STORE_GLOBAL.
     */
    private static boolean hasSelfReference(ELNode expr, String name) {
        boolean[] found = {false};
        expr.accept(new DefaultVisitor() {
            public void visit(ELNode.IDENT e) {
                if (name.equals(e.id))
                    found[0] = true;
            }
            // Don't descend into nested definitions, blocks, or lambdas —
            // those have their own scope and can't refer to the outer var
            // being defined.
            public void visit(ELNode.DEFINE e) {}
            public void visit(ELNode.LAMBDA e) {}
        });
        return found[0];
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
                if (seen.contains(e.id) || nested.varIndex.get(e.id) != null ||
                    !enclosing.varIndex.containsKey(e.id))
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
        int maxId = b.blockMap.keySet().stream().max(Integer::compare).orElse(-1);
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

    /**
     * Record the current line for the given PC (used by debug info).
     */
    private void recordDebugLine(int pc) {
        if (ELProgram.DEBUG && currentLine > 0 && pc >= 0) {
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
        if (!ELProgram.DEBUG || pcLineTable.isEmpty())
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

    IRFunction finish(String name, int paramCount) {
        return finish(name, paramCount, null);
    }

    IRFunction finish(String name, int paramCount,
                      Map<String, Integer> captures) {
        // Seal current block and record its debug line
        if (current != null) {
            if (!current.isEmpty()) {
                int[] code = current.toArray();
                blockMap.put(currentBlockId, code);
                if (ELProgram.DEBUG && currentLine > 0) {
                    runningPc += code.length;
                    recordDebugLine(runningPc);
                }
            } else {
                current.emitReturnVoid();
                int[] code = current.toArray();
                blockMap.put(currentBlockId, code);
                if (ELProgram.DEBUG && currentLine > 0) {
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
            ordered[i] = code != null ? code : new IREmitter().emitNop().toArray();
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

        return new IRFunction(name, paramCount, captures != null ? captures.size() : 0,
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
        emitPushConst(typeId, Long.valueOf(value));
    }

    private void emitPushConst(int typeId, double value) {
        emitPushConst(typeId, Double.valueOf(value));
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
        if (node instanceof ELNode.BOOLEANVAL b)
            return b.value;
        if (node instanceof ELNode.SYMBOL s)
            return s.value;
        if (node.op == Token.NULL)
            return null;
        // Negative number: (- literal)
        if (node.op == Token.NEG && node instanceof ELNode.Unary u &&
            u.right instanceof ELNode.NUMBER n) {
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
     * &#064;data constructor names whose calls need AST trampoline (lazy args).
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
        return compile(ELEngine.createELContext(), node, true);
    }

    public static IRFunction compile(ELContext elctx, ELNode node, boolean optimize) {
        clearKnownFunctions();
        IRBytecodeCompiler.resetState();
        ScopeAnalyzer.ScopeAnalysis analysis =
            ScopeAnalyzer.analyze(null, List.of(node), null);
        IRBuilder b = new IRBuilder(elctx);
        b.analyze(analysis);
        b.build(node);
        if (!endsWithReturn(b)) {
            int typeId = b.typeIdFromNode(node);
            b.current.emitReturn(typeId >= 0 ? typeId : T_INT);
        }
        return finishIR(b.finish("<expr>", 0), 0, optimize, false);
    }

    public static IRFunction compile(ELProgram program) {
        return compile(ELEngine.createELContext(), program, false, null);
    }

    public static IRFunction compile(ELContext elctx, ELProgram program,
                                     boolean optimize, String file) {
        List<ELNode> defs = program.getDefinitions();
        List<ELNode> exps = program.getExpressions();

        clearKnownFunctions();
        IRBytecodeCompiler.resetState();  // fresh ELContext + funcRegistry

        // Run scope analysis before building IR to determine which variables
        // are captured by closures and need to go through the evaluation
        // context.
        ScopeAnalyzer.ScopeAnalysis analysis =
            ScopeAnalyzer.analyze(defs, exps, null);
        IRBuilder b = new IRBuilder(elctx);
        b.analyze(analysis);
        if (file != null)
            b.setFile(file);

        // Compile definitions for forward declaration.
        for (ELNode def : defs) {
            b.build(def);
            b.current.emitPop();
        }

        // Compile expressions
        ELNode last = null;
        if (!exps.isEmpty()) {
            for (int i = 0; i < exps.size() - 1; i++) {
                b.build(exps.get(i));
                b.current.emitPop();
            }
            last = exps.get(exps.size() - 1);
            b.build(last);
        }

        if (!endsWithReturn(b)) {
            if (last == null) {
                b.current.emitReturnVoid();
            } else {
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
            // FIXME: temporary disable type specializer until we finish IR
            //  interpreter completely.
            // fn = IRSpecializer.specialize(fn, new int[paramCount]);
            if (isLambda)
                fn = FOLDER.transform(fn);  // fold constants in specialized
            // code
        }
        return fn;
    }
}
