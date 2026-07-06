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

import elite.lang.Builtin;
import elite.lang.Closure;
import elite.lang.MathLib;
import elite.lang.Seq;
import elite.lang.annotation.Data;
import elite.lang.annotation.Expando;
import org.elite.eval.ELEngine;
import org.elite.eval.ELProgram;
import org.elite.eval.Runtime;
import org.elite.eval.TypeCoercion;
import org.elite.eval.closure.ClassDefinition;
import org.elite.eval.closure.ClosureObject;
import org.elite.eval.closure.MethodClosure;
import org.elite.parser.*;
import org.elite.resolver.ClassResolver;
import org.elite.resolver.MethodResolver;

import javax.el.ELContext;
import javax.xml.XMLConstants;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

import static org.elite.ir.IRFormat.*;
import static org.elite.ir.Opcode.*;
import static org.elite.resources.Resources.*;

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

    // The IRFunction to build.
    private final IRFunction func;

    // Tracking current scope.
    SymbolTable.Scope currentScope;

    // Tracking temporary slot allocation.
    private int maxLocals;
    private int nextTempSlot;
    private final Deque<Integer> freeSlots = new ArrayDeque<>();

    // ── Block management (stored by ID, output in ID order) ──
    final Map<Integer, int[]> blockMap = new LinkedHashMap<>();
    IREmitter current;
    int currentBlockId = 0;
    int nextBlockId = 1;  // 0 is the initial block

    // ── Constant pool (maybe shared with parent builder) ──
    private Map<Object, Integer> constIndex = new HashMap<>();
    List<Object> constants = new ArrayList<>();

    // ── Loop stack ──
    private record LoopTargets(int continueBlock, int breakBlock) {}
    private final Deque<LoopTargets> loopStack = new ArrayDeque<>();

    // ── Tail-call optimization ──
    boolean inTailPosition = true;

    // ── Debug info ──
    private String currentFile;
    private int currentPos = Position.NOPOS;
    private final Map<Integer, Integer> linePcMapping = new HashMap<>();

    /**
     * Create a top-level builder.  The symbol table must already be built
     * (Phase 1) so that AST nodes carry slot/captured annotations.
     */
    IRBuilder(ELContext elctx, IRFunction func, SymbolTable.Scope scope) {
        this.elctx = elctx;
        this.func = func;
        this.currentBlockId = 0;
        this.current = new IREmitter();
        this.currentScope = scope;
    }

    /**
     * Create a nested builder sharing the parent's constant pool, import
     * context, and symbol table.
     */
    private IRBuilder(IRBuilder parent, IRFunction func, SymbolTable.Scope scope) {
        assert(parent != null);
        this.func = func;
        this.elctx = parent.elctx;
        this.currentBlockId = 0;
        this.current = new IREmitter();
        this.currentScope = scope;
        this.currentFile = parent.currentFile;

        // Share constants with parent so pool indices are consistent
        this.constants = parent.constants;
        this.constIndex = parent.constIndex;
    }

    /** Set the source file name for debug info (called before compilation). */
    void setFile(String file) {
        this.currentFile = file;
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

    private ParseException reportError(int pos, String message) {
        return new ParseException(currentFile, Position.line(pos), Position.column(pos),
                                  message);
    }

    // ============ MAIN DISPATCH ============

    private void buildNode(ELNode node) {
        if (node == null) {
            current.emitPushNull();
            return;
        }

        if (node.pos != Position.NOPOS)
            currentPos = node.pos;

        if (node.scope != null) {
            SymbolTable.Scope prevScope = currentScope;
            currentScope = node.scope;
            if (!(node instanceof ELNode.LAMBDA) && node.scope.hasCaptures()) {
                // We need to set up new evaluation context if any variables
                // captured in this scope.
                current.emit1(ENTER_SCOPE, K_NONE, 0);
                node.accept(this);
                current.emit1(LEAVE_SCOPE, K_NONE, 0);
            } else {
                node.accept(this);
            }
            currentScope = prevScope;
        } else {
            node.accept(this);
        }

        if (node.pos != Position.NOPOS)
            recordDebugLine(runningPc + current.size());
    }

    void build(ELNode node) {
        boolean prev = inTailPosition;
        inTailPosition = false;
        buildNode(node);
        inTailPosition = prev;
    }

    /**
     * Build a node in tail position (preserves current tail status).
     */
    private void buildTail(ELNode node) {
        buildNode(node);
    }

    private int build(ELNode[] nodes) {
        for (ELNode node : nodes)
            build(node);
        return nodes.length;
    }

    // ── Literals ──

    public void visit(ELNode.NUMBER node) {
        Number n = node.value;
        if (n instanceof Integer || n instanceof Short || n instanceof Byte) {
            buildConst(n.intValue());
        } else if (n instanceof Long) {
            long v = n.longValue();
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE)
                buildConst((int)v);
            else
                buildConst(v);
        } else if (n instanceof Double || n instanceof Float) {
            buildConst(n.doubleValue());
        } else {
            buildConst(n);
        }
    }

    public void visit(ELNode.REGEXP node) {
        buildConst(node.value);
    }

    public void visit(ELNode.STRINGVAL node) {
        buildConst(node.value);
    }

    public void visit(ELNode.LITERAL node) {
        buildConst(node.value);
    }

    public void visit(ELNode.CHARVAL node) {
        buildConst(node.value);
    }

    public void visit(ELNode.BOOLEANVAL node) {
        buildConst(node.value);
    }

    public void visit(ELNode.NULL node) {
        current.emitPushNull();
    }

    public void visit(ELNode.SYMBOL node) {
        buildConst(node.value);
    }

    public void visit(ELNode.ACCESS node) {
        // Neither getter nor field — fall back to ELResolver
        // (could be a method reference, static member, or nested class)
        build(node.right);   // base object
        build(node.index);   // key
        current.emitLoadProperty();
    }

    // ── Identifiers ──
    public void visit(ELNode.IDENT node) {
        if (node.symbol == null || node.symbol.captured) {
            int nameIdx = putConstant(node.id);
            current.emitPushGlobal(nameIdx);
        } else {
            current.emitPushVar(node.symbol.slot);
        }
    }

    // ── Apply ──
    public void visit(ELNode.APPLY node) {
        ELNode base = node.right;

        if (base instanceof ELNode.IDENT ident) {
            if (ident.symbol != null) {
                if (inTailPosition && ident.symbol.func == this.func) {
                    // TCO: build args (never in tail position), emit INVOKE_TAIL
                    int argc = buildCallArgs(node.pos, (ELNode.LAMBDA)ident.symbol.node,
                                              node.args, node.keys);
                    current.emitInvokeTail(argc);
                    return;
                }

                if (ident.symbol.func != null) {
                    int funcIdx = putConstant(ident.symbol.func);
                    int argc = buildCallArgs(node.pos, (ELNode.LAMBDA)ident.symbol.node,
                                             node.args, node.keys);
                    current.emitInvokeDirect(funcIdx, argc);
                    return;
                }

                if (ident.symbol.node instanceof ELNode.CLASSDEF) {
                    // The target is a CLASSDEF, so the defined object must be
                    // a ClassDefinition, just invoke the "invoke" method
                    // on target.

                    // Fist, build ident node to generate PUSH_GLOBAL
                    // or PUSH_VAR instruction.
                    build(ident);

                    // Then invoke the ClassDefinition.invoke method to create
                    // new instance of user defined class.
                    build(node.args);
                    current.emitNewTuple(node.args.length);
                    emitInvokeMethod(ClassDefinition.class, "invoke",
                                     ELContext.class, Object[].class);
                    return;
                }
            }

            if (ident.symbol == null) {
                // Resolve builtin function.
                if (tryBuildGlobalMethodCall(ident.id, node.args))
                    return;

                // Resolve target at runtime if the given id is not a local var
                int nameIdx = putConstant(ident.id);
                build(node.args);
                current.emitInvokeTarget(nameIdx, node.args.length);
                return;
            }
        }

        if (base instanceof ELNode.ACCESS acc) {
            // Try to resolve direct method for known Java types.
            if (acc.index instanceof ELNode.STRINGVAL key) {
                if (tryBuildDirectMethodCall(acc.right, key.value, node.args))
                    return;
            }

            // resolve method at runtime
            build(acc.right);
            build(acc.index);
            build(node.args);
            current.emitInvokeDynMethod(node.args.length);
            return;
        }

        build(base);

        if (base instanceof ELNode.LAMBDA lam && lam.symbol != null && lam.symbol.func != null) {
            // Inlined lambda no longer used.
            current.emitPop();

            // Inline lambda call.
            int funcIdx = putConstant(lam.symbol.func);
            int argc = buildCallArgs(node.pos, lam, node.args, node.keys);
            current.emitInvokeDirect(funcIdx, argc);
            return;
        }

        // evaluate base and generate dynamic call
        build(node.args);
        current.emitInvokeDyn(node.args.length);
    }

    private int indexOfVar(String name, ELNode.DEFINE[] vars, boolean varargs) {
        int nvars = vars.length - (varargs ? 1 : 0);
        for (int i = 0; i < nvars; i++) {
            if (name.equals(vars[i].id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Build arguments for a direct call, handling default and named parameters.
     * Returns the total number of arguments built (including defaults).
     */
    private int buildCallArgs(int pos, ELNode.LAMBDA lambda, ELNode[] args, String[] keys) {
        int argc = args.length;
        int nvars = lambda.vars.length;
        ELNode[] xargs = null;

        boolean hasDefaults = false;
        for (ELNode.DEFINE var : lambda.vars) {
            if (var.expr != null) {
                hasDefaults = true;
                break;
            }
        }

        if (argc < nvars && hasDefaults) {
            // pad with default values
            xargs = new ELNode[nvars];
        } else if (lambda.varargs ? (argc < nvars-1) : (argc != nvars)) {
            throw reportError(pos, _T(EL_FN_BAD_ARG_COUNT, lambda.name, nvars, argc));
        }

        // Rearrange named arguments
        int k = nvars-1; // index to vararg list
        if (keys != null) {
            for (int i = 0; i < argc; i++) {
                if (keys[i] != null) {
                    int j = indexOfVar(keys[i], lambda.vars, lambda.varargs);
                    if (j == -1) {
                        if (!lambda.varargs || k >= argc)
                            throw reportError(pos, _T(EL_UNKNOWN_ARG_NAME, keys[i]));
                        if (xargs == null)
                            xargs = new ELNode[argc];
                        xargs[k++] = args[i];
                    } else {
                        if (xargs == null)
                            xargs = new ELNode[argc];
                        xargs[j] = args[i];
                    }
                }
            }
        }

        if (xargs != null) {
            int j = 0;

            // Rearrange non-named arguments
            for (int i = 0; i < argc; i++) {
                if (keys == null || keys[i] == null) {
                    while (xargs[j] != null)
                        j++;
                    xargs[j++] = args[i];
                }
            }

            // Assign default values
            args = xargs;
            argc = xargs.length;
            for (; j < argc; j++) {
                if (args[j] == null) {
                    if (lambda.vars[j].expr == null)
                        throw reportError(pos, _T(EL_MISSING_ARG_VALUE, lambda.vars[j].id));
                    args[j] = lambda.vars[j].expr;
                }
            }
        }

        // Build argument list, include varargs that then build to tuple.
        build(args);

        // Build tuple for var arg list.
        if (lambda.varargs)
            current.emitNewTuple(argc - (nvars - 1));

        return nvars;
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

    private String getBaseClassName(ELNode base) {
        if (base instanceof ELNode.IDENT var) {
            if (var.symbol == null)
                return var.id;
            else if (var.symbol.node instanceof ELNode.DEFINE def &&
                     def.expr instanceof ELNode.CLASS c)
                return c.name;
        } else if (base instanceof ELNode.ACCESS) {
            StringBuilder sb = new StringBuilder();
            while (base instanceof ELNode.ACCESS acc) {
                if (acc.index instanceof ELNode.STRINGVAL s) {
                    sb.insert(0, s.value);
                    sb.insert(0, '.');
                    base = acc.right;
                } else {
                    return null;
                }
            }
            if (base instanceof ELNode.IDENT var && var.symbol == null) {
                sb.insert(0, var.id);
                return sb.toString();
            }
        }

        return null;
    }

    private boolean tryBuildDirectMethodCall(ELNode base, String name, ELNode[] args) {
        String baseClassName = getBaseClassName(base);
        if (baseClassName != null) {
            Class<?> cls = resolveClassAtCompileTime(baseClassName);
            if (cls != null) {
                MethodClosure mc = MethodResolver.getInstance(elctx)
                    .resolveStaticMethod(cls, name);
                if (mc != null) {
                    Method method = mc.getJavaMethod();
                    if (method != null) {
                        build(args);
                        emitInvokeStatic(method);
                        return true;
                    }
                }
            }
        }

        Class<?> baseClass = Object.class;
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

        if (base != null)
            build(base);

        // Build fixed arguments.
        int i = 0;
        for (; iarg < nargs; iarg++, i++) {
            build(args[i]);
        }

        // Build variable arguments.
        if (vargs) {
            for (; i < args.length; i++) {
                build(args[i]);
            }
        }

        int methodIdx = putConstant(method);
        if (expando)
            current.emitInvokeExpando(methodIdx, args.length);
        else if (base == null)
            current.emitInvokeStatic(methodIdx, args.length);
        else
            current.emitInvokeMethod(methodIdx, args.length);
        return true;
    }

    /**
     * Build direct IR for well known builtin functions.
     */
    private boolean buildBuiltin(Method method, ELNode base, ELNode[] args) {
        if (method.getDeclaringClass() == Builtin.class) {
            switch (method.getName()) {
            case "begin":
                if (args.length == 0) {
                    current.emitPushNull();
                    return true;
                }
                for (int i = 0; i < args.length - 1; i++) {
                    build(args[i]);
                    current.emitPop();
                }
                build(args[args.length - 1]);
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
                build(exp);
                return true;
            }

            case "list":
                build(args);
                current.emitNil();
                for (int i = 0; i < args.length; i++)
                    current.emitNewCons();
                return true;

            case "range":
                assert args.length == 3;
                build(args[0]);
                current.emitDup();
                build(args[2]);
                emitDynBinOp(Token.ADD);
                build(args[1]);
                current.emitNewRange();
                return true;

            case "upto":
                return buildStepBuiltin(base, args[0], args[1], 1, Token.LE);
            case "downto":
                return buildStepBuiltin(base, args[0], args[1], -1, Token.GE);
            case "step":
                assert args.length == 3;
                if (args[1] instanceof ELNode.NUMBER n) {
                    int step = n.value.intValue();
                    if (step != 0)
                        return buildStepBuiltin(base, args[0], args[2], step,
                                                step > 0 ? Token.LE : Token.GE);
                }
                return false;
            case "times":
                return buildStepBuiltin(new ELNode.NUMBER(0, 0), base, args[0], 1, Token.LT);
            }
        }

        if (method.getDeclaringClass() == MathLib.class) {
            switch (method.getName()) {
            case "sum":
                return buildMathReduce(args, Token.ADD);
            case "difference":
                return buildMathReduce(args, Token.SUB);
            case "product":
                return buildMathReduce(args, Token.MUL);
            case "divide":
                return buildMathReduce(args, Token.DIV);

            case "remainder":
                build(args[0]);
                build(args[1]);
                emitDynBinOp(Token.REM);
                return true;

            case "pow":
                build(args[0]);
                build(args[1]);
                emitDynBinOp(Token.POW);
                return true;
            }
        }

        return false;
    }

    private boolean buildStepBuiltin(ELNode begin, ELNode end, ELNode body,
                                     int step, int cmpop) {
        // Initialize temporary variables.
        Slot indSlot = new Slot();
        Slot endSlot = new Slot();
        Slot bodySlot = new Slot();

        build(body);
        bodySlot.store();
        current.emitPop();

        build(begin);
        indSlot.store();
        current.emitPop();
        build(end);
        endSlot.store();
        current.emitPop();

        // Begin loop.
        int headerB = allocBlockId();
        int bodyB = allocBlockId();
        int exitB = allocBlockId();

        loopStack.push(new LoopTargets(headerB, exitB));
        current.emitJump(headerB);

        // Generate loop condition.
        startBlock(headerB);
        indSlot.load();
        endSlot.load();
        emitDynBinOp(cmpop);
        current.emitJumpIfTrue(bodyB);
        current.emitJump(exitB);

        // Generate loop body.
        startBlock(bodyB);
        if (body instanceof ELNode.LAMBDA b) {
            bodySlot.load();
            if (b.vars.length > 1)
                throw reportError(body.pos, _T(EL_FN_BAD_ARG_COUNT,
                                              b.name == null ? "<lambda>" : b.name,
                                              b.vars.length, 1));
            if (b.vars.length == 1)
                indSlot.load();
            if (b.symbol != null) {
                int funcIdx = putConstant(b.symbol.func);
                current.emitInvokeDirect(funcIdx, b.vars.length);
            } else {
                current.emitInvokeDyn(b.vars.length);
            }
        } else if (!tryBuildOptimizedGlobalCall(body, indSlot)) {
            bodySlot.load();
            indSlot.load();
            current.emitInvokeDyn(1);
        }
        current.emitPop();

        // Increment induction variable.
        indSlot.load();
        buildConst(Math.abs(step));
        emitDynBinOp(step > 0 ? Token.ADD : Token.SUB);
        indSlot.store();
        current.emitPop();
        current.emitJump(headerB);

        // Cleanup.
        startBlock(exitB);
        current.emitPushNull();
        loopStack.pop();
        release(endSlot);
        release(indSlot);
        release(bodySlot);
        return true;
    }

    private boolean tryBuildOptimizedGlobalCall(ELNode base, Slot arg) {
        if (!(base instanceof ELNode.IDENT v))
            return false;

        if (v.symbol == null) {
            var mc = MethodResolver.getInstance(elctx).resolveGlobalMethod(v.id);
            if (mc == null)
                return false;

            Method method = mc.getJavaMethod();
            if (method == null)
                return false;

            int paramCount = method.getParameterCount();
            if (paramCount > 0 && method.getParameterTypes()[0] == ELContext.class)
                paramCount--;
            if (paramCount > 1 && method.isVarArgs())
                paramCount--;
            if (paramCount > 1)
                throw reportError(base.pos, _T(EL_FN_BAD_ARG_COUNT, v.id, paramCount, 1));
            if (paramCount == 1)
                arg.load();
            current.emitInvokeStatic(putConstant(method), paramCount);
            return true;
        } else if (v.symbol.node instanceof ELNode.LAMBDA b) {
            if (b.vars.length > 1)
                throw reportError(base.pos, _T(EL_FN_BAD_ARG_COUNT,
                                              b.name == null ? "<lambda>" : b.name,
                                              b.vars.length, 1));
            if (b.vars.length == 1)
                arg.load();
            current.emitInvokeDirect(putConstant(b.symbol.func), b.vars.length);
            return true;
        }

        return false;
    }

    private boolean buildMathReduce(ELNode[] args, int op) {
        if (args.length == 1 && args[0] instanceof ELNode.TUPLE t) {
            if (t.elems.length == 0) {
                buildConst(0);
            } else {
                build(args[0]);
                for (int i = 1; i < t.elems.length; i++) {
                    build(t.elems[i]);
                    emitDynBinOp(op);
                }
            }
            return true;
        }
        return false;
    }

    // ── Literals: list, map, tuple, range ──

    public void visit(ELNode.CONS node) {
        build(node.head);
        build(node.tail);
        if (node.delay)
            current.emitNewDelayCons();
        else
            current.emitNewCons();
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
        build(node.elems);
        current.emitNewTuple(node.elems.length);
    }

    public void visit(ELNode.RANGE node) {
        build(node.begin);
        build(node.next);
        if (node.exclude && node.end != null) {
            // Exclusive range [begin..<end): push end-1 for inclusive range end
            build(node.end);
            buildConst(1);
            emitDynBinOp(Token.SUB);
        } else {
            build(node.end);
        }
        current.emitNewRange();
    }

    public void visit(ELNode.ARRAY node) {
        // Resolve component type at compile time, default to Object.class.
        Object componentType = resolveClassAtCompileTime(node.type);
        if (componentType == null)
            componentType = node.type; // use string that resolved at runtime

        if (componentType instanceof Class &&
            buildConstantDimensionArray(node, (Class<?>)componentType))
            return;

        buildConst(componentType);

        // Build dimension expressions into a tuple.
        if (node.dims == null) {
            current.emitPushNull();
        } else {
            for (int i = 0; i < node.dims.length; i++)
                build(node.dims[i]);
            current.emitNewTuple(node.dims.length);
        }

        // Build init expressions into a tuple.
        if (node.init == null) {
            current.emitPushNull();
        } else {
            for (int i = 0; i < node.init.length; i++)
                build(node.init[i]);
            current.emitNewTuple(node.init.length);
        }

        emitInvokeStatic(Runtime.class, "newArray",
            ELContext.class, Object.class, Object[].class, Object[].class);
    }

    private boolean buildConstantDimensionArray(ELNode.ARRAY node, Class<?> type) {
        if (node.dims != null) {
            for (ELNode e : node.dims) {
                if (e instanceof ELNode.NUMBER n && n.value instanceof Integer)
                    continue;
                return false;
            }
        }

        if (node.dims == null || node.dims.length == 1) {
            int length = 0;
            if (node.dims != null)
                length = ((ELNode.NUMBER)node.dims[0]).value.intValue();
            if (node.init != null && length < node.init.length)
                length = node.init.length;

            buildConst(type);
            buildConst(length);
            emitInvokeStatic(Array.class, "newInstance", Class.class, int.class);

            if (node.init != null) {
                Slot tmpSlot = new Slot();
                tmpSlot.store();
                for (int i = 0; i < node.init.length; i++) {
                    buildConst(i);
                    build(node.init[i]);
                    if (type != Object.class) {
                        buildConst(type);
                        emitInvokeStatic(TypeCoercion.class, "coerce",
                            Object.class, Class.class);
                    }
                    emitInvokeStatic(Array.class, "set", Object.class, int.class, Object.class);
                    current.emitPop();
                    tmpSlot.load();
                }
                tmpSlot.release();
            }
        } else {
            Slot tmpSlot = new Slot();
            buildConst(type);
            buildConst(int.class);
            buildConst(node.dims.length);
            emitInvokeStatic(Array.class, "newInstance", Class.class, int.class);
            tmpSlot.store();
            for (int i = 0; i < node.dims.length; i++) {
                buildConst(i);
                buildConst(((ELNode.NUMBER)node.dims[i]).value.intValue());
                emitInvokeStatic(Array.class, "set", Object.class, int.class, Object.class);
                current.emitPop();
                tmpSlot.load();
            }
            emitInvokeStatic(Array.class, "newInstance", Class.class, int[].class);
            tmpSlot.release();
        }

        // FIXME: handle multi dimensional array
        return true;
    }

    public void visit(ELNode.XML node) {
        int namespaces = 0;
        Slot[] tmpSlots = null;

        if (node.keys != null) {
            for (ELNode key : node.keys) {
                if (key instanceof ELNode.STRINGVAL str &&
                    (str.value.equals("xmlns") || str.value.startsWith("xmlns:")))
                    namespaces++;
            }
        }

        // Setup environment and declare namespaces.
        if (namespaces != 0) {
            current.emit1(ENTER_SCOPE, K_NONE, 0);
            for (int i = 0; i < node.keys.length; i++) {
                if (node.keys[i] instanceof ELNode.STRINGVAL str &&
                    (str.value.equals("xmlns") || str.value.startsWith("xmlns:"))) {
                    String prefix;
                    if (str.value.equals("xmlns"))
                        prefix = XMLConstants.DEFAULT_NS_PREFIX;
                    else
                        prefix = str.value.substring(6);
                    if (node.values[i] instanceof ELNode.Constant) {
                        build(node.values[i]);
                    } else {
                        if (tmpSlots == null)
                            tmpSlots = new Slot[node.keys.length];
                        tmpSlots[i] = new Slot();
                        build(node.values[i]);
                        tmpSlots[i].store();
                    }
                    current.emit1(DECLARE_NS, K_NONE, putConstant(prefix));
                }
            }
        }

        // Build XML tag, attributes, and children.
        build(node.tag);
        if (node.keys != null) {
            assert node.keys.length == node.values.length;
            for (int i = 0; i < node.keys.length; i++) {
                build(node.keys[i]);
                if (tmpSlots != null && tmpSlots[i] != null)
                    tmpSlots[i].load();
                else
                    build(node.values[i]);
            }
        }
        if (node.children != null) {
            for (int i = 0; i < node.children.length; i++) {
                build(node.children[i]);
            }
        }
        current.emit2(NEW_XML, K_NONE, node.keys == null ? 0 : node.keys.length,
                      node.children == null ? 0 : node.children.length);

        if (namespaces != 0) {
            current.emit1(LEAVE_SCOPE, K_NONE, 0);
        }

        if (tmpSlots != null) {
            for (Slot slot : tmpSlots) {
                if (slot != null)
                    slot.release();
            }
        }
    }

    public void visit(ELNode.IN node) {
        build(node.right);  // container
        build(node.left);   // element
        current.emitDynIn();
        if (node.negative)
            current.emitNot();
    }

    public void visit(ELNode.INSTANCEOF node) {
        build(node.right);
        emitInstOf(node.type);
        if (node.negative)
            current.emitNot();
    }

    private void emitInstOf(Class<?> cls) {
        int clsid = putConstant(cls);
        current.emit1(INSTOF, K_BOOL, clsid);
    }

    private void emitInstOf(String name) {
        int clsid;
        try {
            Class<?> cls = ClassResolver.getInstance(elctx).resolveClass(name);
            clsid = putConstant(cls);
        } catch (ClassNotFoundException e) {
            clsid = putConstant(name);
        }
        current.emit1(INSTOF, K_BOOL, clsid);
    }

    // ── Binary and unary arithmetic ──

    public void visit(ELNode.ADD node)    { buildBinaryOp(node); }
    public void visit(ELNode.SUB node)    { buildBinaryOp(node); }
    public void visit(ELNode.MUL node)    { buildBinaryOp(node); }
    public void visit(ELNode.DIV node)    { buildBinaryOp(node); }
    public void visit(ELNode.REM node)    { buildBinaryOp(node); }
    public void visit(ELNode.POW node)    { buildBinaryOp(node); }
    public void visit(ELNode.SHL node)    { buildBinaryOp(node); }
    public void visit(ELNode.CAT node)    { buildBinaryOp(node); }
    public void visit(ELNode.SHR node)    { buildBinaryOp(node); }
    public void visit(ELNode.USHR node)   { buildBinaryOp(node); }
    public void visit(ELNode.BITAND node) { buildBinaryOp(node); }
    public void visit(ELNode.BITOR node)  { buildBinaryOp(node); }
    public void visit(ELNode.XOR node)    { buildBinaryOp(node); }
    public void visit(ELNode.EQ node)     { buildBinaryOp(node); }
    public void visit(ELNode.NE node)     { buildBinaryOp(node); }
    public void visit(ELNode.LT node)     { buildBinaryOp(node); }
    public void visit(ELNode.LE node)     { buildBinaryOp(node); }
    public void visit(ELNode.GT node)     { buildBinaryOp(node); }
    public void visit(ELNode.GE node)     { buildBinaryOp(node); }

    public void visit(ELNode.NEG node)    {
        build(node.right);
        current.emitDynNeg();
    }

    public void visit(ELNode.POS node)    { /* nop */ }
    public void visit(ELNode.BITNOT node) { buildUnaryOp(node); }
    public void visit(ELNode.EMPTY node)  { buildUnaryOp(node); }

    private void buildBinaryOp(ELNode.Binary node) {
        build(node.left);
        build(node.right);
        emitDynBinOp(node.op);
    }

    private void emitDynBinOp(int op) {
        switch (op) {
            case Token.ADD    -> current.emitDynAdd();
            case Token.SUB    -> current.emitDynSub();
            case Token.MUL    -> current.emitDynMul();
            case Token.DIV    -> current.emitDynDiv();
            case Token.IDIV   -> current.emitLDiv();
            case Token.REM    -> current.emitDynRem();
            case Token.POW    -> current.emitDynPow();
            case Token.CAT    -> current.emitDynCat();
            case Token.SHL    -> current.emitDynShl();
            case Token.SHR    -> current.emitDynShr();
            case Token.USHR   -> current.emitDynUShr();
            case Token.BITAND -> current.emitDynBitAnd();
            case Token.BITOR  -> current.emitDynBitOr();
            case Token.XOR    -> current.emitDynXor();
            case Token.EQ     -> current.emitDynEq();
            case Token.NE     -> current.emitDynNe();
            case Token.LT     -> current.emitDynLt();
            case Token.LE     -> current.emitDynLe();
            case Token.GT     -> current.emitDynGt();
            case Token.GE     -> current.emitDynGe();
            default -> throw new UnsupportedOperationException();
        }
    }

    private void buildUnaryOp(ELNode.Unary node) {
        build(node.right);
        emitDynUnOp(node.op);
    }

    private void emitDynUnOp(int op) {
        switch (op) {
        case Token.BITNOT -> current.emitDynBitNot();
        case Token.NEG -> current.emitDynNeg();
        case Token.POS -> { /* unary plus is a no-op: value already on stack */ }
        case Token.EMPTY ->  current.emitDynEmpty();
        default -> throw new UnsupportedOperationException();
        }
    }

    public void visit(ELNode.IDEQ node) {
        build(node.left);
        build(node.right);
        current.emitIdEq();
    }

    public void visit(ELNode.IDNE node) {
        build(node.left);
        build(node.right);
        current.emitIdNe();
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
        // Evaluate right value.
        build(target);
        if (!isPre)
            current.emitDup();

        // Increment or decrement the value.
        buildConst(1);
        emitDynBinOp(isInc ? Token.ADD : Token.SUB);

        // Assign to right value itself.
        if (target instanceof ELNode.IDENT ident)
            buildStoreVariable(ident);
        else if (target instanceof ELNode.ACCESS access)
            buildStoreProperty(access);
        else // could not happen, parser doesn't allow increment/decrement on other expression
            throw new UnsupportedOperationException("Invalid increment/decrement");

        // If preincrement, stack top is the return value, otherwise pop and
        // keep duped value on top.
        if (!isPre)
            current.emitPop();
    }

    public void visit(ELNode.PREFIX node) {
        int nameIdx = putConstant(node.name);
        build(node.right);
        current.emitInvokeOperator(nameIdx, 1);
    }

    public void visit(ELNode.INFIX node) {
        int nameIdx = putConstant(node.name);
        build(node.left);
        build(node.right);
        current.emitInvokeOperator(nameIdx, 2);
    }

    // ── Logical AND/OR/NOT ──

    public void visit(ELNode.AND node) {
        int contB = allocBlockId();
        build(node.left);
        current.emitDup();
        current.emitJumpIfFalse(contB);
        current.emitPop();
        build(node.right);
        current.emitJump(contB);
        startBlock(contB);
    }


    public void visit(ELNode.OR node) {
        int contB = allocBlockId();
        build(node.left);
        current.emitDup();
        current.emitJumpIfTrue(contB);
        current.emitPop();
        build(node.right);
        current.emitJump(contB);
        startBlock(contB);
    }

    public void visit(ELNode.NOT node) {
        build(node.right);
        current.emitNot();
    }

    // ── Conditional (if/else / ?:) ──
    public void visit(ELNode.COND node) {
        // Optimize constant condition.
        if (node.cond instanceof ELNode.BOOLEANVAL b) {
            if (b.value) {
                // always true
                build(node.left);
            } else {
                // always false
                build(node.right);
            }
            return;
        }

        int thenB = allocBlockId();
        int elseB = allocBlockId();
        int mergeB = allocBlockId();

        build(node.cond);
        current.emitJumpIfTrue(thenB);
        current.emitJump(elseB);

        sealAndStart(thenB);
        buildTail(node.left);
        current.emitJump(mergeB);

        sealAndStart(elseB);
        buildTail(node.right);
        current.emitJump(mergeB);

        sealAndStart(mergeB);
    }

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

        int contB = allocBlockId();
        build(node.left);
        current.emitDup();
        current.emitJumpIfNonNull(contB);
        current.emitPop();
        build(node.right);
        current.emitJump(contB);
        startBlock(contB);
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
        if (node.left instanceof ELNode.IDENT ident) {
            build(node.right);
            buildStoreVariable(ident);
            return;
        }

        if (node.left instanceof ELNode.ACCESS access) {
            build(node.right);
            buildStoreProperty(access);
            return;
        }

        if (node.left instanceof ELNode.TUPLE lhs) {
            if (node.right instanceof ELNode.TUPLE rhs &&
                isAssignableTuple(lhs, rhs)) {
                buildTupleAssign(lhs, rhs);
            } else {
                int failBlock = allocBlockId();
                int doneBlock = allocBlockId();

                build(node.right);
                buildDynamicTupleAssign(lhs, failBlock);
                current.emitJump(doneBlock);
                startBlock(failBlock);
                buildConst("tuple pattern not match");
                current.emitThrow();
                current.emitJump(doneBlock);
                startBlock(doneBlock);
            }
            return;
        }

        // should not happen, parser disabled other assign syntax
        throw new AssertionError();
    }

    public void visit(ELNode.ASSIGNOP node) {
        // Invoke dynamic assignment operator
        buildConst(node.binary.op);
        build(node.left);
        build(node.right);
        emitInvokeStatic(Runtime.class, "invokeAssignOp", ELContext.class, int.class,
                         Object.class, Object.class);

        // Now perform assignment.
        if (node.left instanceof ELNode.IDENT ident) {
            buildStoreVariable(ident);
        } else if (node.left instanceof ELNode.ACCESS access) {
            buildStoreProperty(access);
        } else {
            // should not happen, parser disabled other assignop syntax
            throw new AssertionError();
        }
    }

    private void buildStoreVariable(ELNode.IDENT ident) {
        if (ident.symbol == null || ident.symbol.captured) {
            int nameIdx = putConstant(ident.id);
            current.emitStoreGlobal(nameIdx);
        } else {
            int idx = ident.symbol.slot;
            current.emitStoreVar(idx);
        }
    }

    private void buildStoreProperty(ELNode.ACCESS access) {
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
        List<Slot> tmpSlots = new ArrayList<>();
        buildFlattenTuple(rhs.elems, tmpSlots);

        // Assign to left values sequentially.
        buildAssignFlattenTuple(lhs.elems, tmpSlots);
    }

    private void buildFlattenTuple(ELNode[] elems, List<Slot> tmpSlots) {
        for (ELNode elem : elems) {
            if (elem instanceof ELNode.TUPLE tt) {
                buildFlattenTuple(tt.elems, tmpSlots);
            } else {
                Slot varSlot = new Slot();
                tmpSlots.add(varSlot);
                build(elem);
                varSlot.store();
                current.emitPop();
            }
        }
    }

    private void buildAssignFlattenTuple(ELNode[] elems, List<Slot> tmpSlots) {
        for (ELNode elem : elems) {
            if (elem instanceof ELNode.TUPLE tt) {
                buildAssignFlattenTuple(tt.elems, tmpSlots);
            } else if (elem instanceof ELNode.IDENT ident) {
                Slot slot = tmpSlots.remove(0);
                slot.load();
                slot.release();
                buildStoreVariable(ident);
            } else if (elem instanceof ELNode.ACCESS access) {
                Slot slot = tmpSlots.remove(0);
                slot.load();
                slot.release();
                buildStoreProperty(access);
            } else {
                assert(false); // already checked by isAssignableTuple
            }
        }

        // Elements kept in stack, build a tuple as assign result.
        current.emitNewTuple(elems.length);
    }

    private void buildDynamicTupleAssign(ELNode.TUPLE lhs, int failBlock) {
        Slot rhsSlot = new Slot();
        rhsSlot.store();

        emitInvokeMethod(Object.class, "getClass");
        emitInvokeMethod(Class.class, "isArray");
        current.emitJumpIfFalse(failBlock);

        rhsSlot.load();
        emitInvokeStatic(Array.class, "getLength", Object.class);
        buildConst(lhs.elems.length);
        current.emitIEq();
        current.emitJumpIfFalse(failBlock);

        for (int i = 0; i < lhs.elems.length; i++) {
            rhsSlot.load();
            buildConst(i);
            emitInvokeStatic(Array.class, "get", Object.class, int.class);
            if (lhs.elems[i] instanceof ELNode.IDENT ident)
                buildStoreVariable(ident);
            else if (lhs.elems[i] instanceof ELNode.ACCESS acc)
                buildStoreProperty(acc);
            else if (lhs.elems[i] instanceof ELNode.TUPLE t)
                buildDynamicTupleAssign(t, failBlock);
            else
                throw new UnsupportedOperationException();
        }

        // Tuple elements still on stack, build a tuple as return value
        current.emitNewTuple(lhs.elems.length);
        rhsSlot.release();
    }

    public void visit(ELNode.DEFINE node) {
        if (node.expr != null) {
            // All DEFINE nodes should carry a symbol annotation from the Phase 1
            // pre-pass.  If one is missing (e.g. dynamically generated node),
            // fall through to buildTrampoline.
            if (node.symbol == null) {
                buildTrampoline(node);
                return;
            }

            // CLASS nodes (from import): push the raw Class constant
            if (node.expr instanceof ELNode.CLASS clsNode) {
                Class<?> cls = resolveClassAtCompileTime(clsNode.name);
                if (cls != null) {
                    buildConst(cls);
                } else {
                    buildTrampoline(node);
                    return;
                }
            } else {
                build(node.expr);
            }

            if (node.symbol.captured) {
                int nameIdx = putConstant(node.id);
                current.emitDefineGlobal(nameIdx);
            } else {
                // Always store locally for fast access within this function.
                current.emitStoreVar(node.symbol.slot);
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
            buildConst("");
        } else {
            build(node.elems);
            current.emitCat(node.elems.length);
        }
    }

    public void visit(ELNode.COMPOUND node) {
        if (node.exps.length == 0) {
            current.emitPushNull();
            return;
        }

        for (int i = 0; i < node.exps.length - 1; i++) {
            if (!(node.exps[i] instanceof ELNode.Constant)) {
                build(node.exps[i]);
                current.emitPop();
            }
        }

        buildTail(node.exps[node.exps.length - 1]);
    }

    public void visit(ELNode.WHILE node) {
        if (node.cond instanceof ELNode.BOOLEANVAL b && !b.value) {
            // Skip whole loop if condition is false.
            current.emitPushNull();
            return;
        }

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
        build(node.body);
        current.emitPop();
        current.emitJump(header);

        startBlock(exit);
        current.emitPushNull();

        // Exit block falls through to next — add RETURN at toplevel by caller
        loopStack.pop();
    }

    public void visit(ELNode.REPEAT node) {
        if (node.cond instanceof ELNode.BOOLEANVAL b && !b.value) {
            // Repeat while false just loop once.
            build(node.body);
            return;
        }

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
        build(node.body);
        current.emitPop();           // discard body result
        current.emitJump(cont);      // → check condition

        startBlock(cont);
        build(node.cond);            // evaluate condition
        current.emitJumpIfTrue(body);
        current.emitJump(exit);

        startBlock(exit);
        current.emitPushNull();

        loopStack.pop();
    }

    public void visit(ELNode.FOR node) {
        int body = allocBlockId();
        int header = node.cond != null ? allocBlockId() : body;
        int cont = allocBlockId();
        int exit = allocBlockId();

        loopStack.push(new LoopTargets(cont, exit));

        if (node.init != null) {
            for (ELNode e : node.init) {
                build(e);
                current.emitPop();
            }
        }
        current.emitJump(header);

        if (node.cond != null) {
            startBlock(header);
            build(node.cond);
            current.emitJumpIfTrue(body);
            current.emitJump(exit);
        }

        startBlock(body);
        if (node.body != null && !(node.body instanceof ELNode.NULL)) {
            build(node.body);
            current.emitPop();
        }
        current.emitJump(cont);

        startBlock(cont);
        if (node.step != null) {
            for (ELNode e : node.step) {
                build(e);
                current.emitPop();
            }
        }
        current.emitJump(header);

        startBlock(exit);
        current.emitPushNull();
        loopStack.pop();
    }

    public void visit(ELNode.FOREACH node) {
        if (node.range instanceof ELNode.RANGE r) {
            if (r.isConstant())
                buildConstantRangedFor(node.var, node.index, r, node.body);
            else
                buildDynamicRangedFor(node.var, node.index, r, node.body);
        } else {
            buildIterateFor(node);
        }
    }

    private void buildConstantRangedFor(ELNode.DEFINE var, ELNode.DEFINE index,
                                        ELNode.RANGE range, ELNode body) {
        // Optimize for constant range.
        long begin = ((ELNode.NUMBER)range.begin).value.longValue();
        long step = 1;
        if (range.next != null) {
            step = ((ELNode.NUMBER)range.next).value.longValue() - begin;
        }

        long count = -1;
        if (range.end != null) {
            long end = ((ELNode.NUMBER)range.end).value.longValue();
            if (range.exclude)
                end--;
            count = (end - begin) / step + 1;
            if (count <= 0) {
                current.emitPushNull();
                return;
            }
        }

        // Register loop variable first to claim its pre-allocated slot,
        // then allocate temp vars after it to avoid slot collisions.
        Slot varSlot = var.symbol != null ? new Slot(var) : null;
        Slot idxSlot = new Slot(index);

        buildConst(0L);
        idxSlot.define();
        current.emitPop();
        if (varSlot != null) {
            buildConst(begin);
            varSlot.define();
            current.emitPop();
        }

        // Begin loop.
        int bodyB = allocBlockId();
        int headerB = range.end != null ? allocBlockId() : bodyB;
        int contB = allocBlockId();
        int exitB = allocBlockId();

        loopStack.push(new LoopTargets(contB, exitB));
        current.emitJump(headerB);

        // Generate loop condition.
        if (range.end != null) {
            startBlock(headerB);
            idxSlot.load();
            buildConst(count);
            current.emitLLt();
            current.emitJumpIfTrue(bodyB);
            current.emitJump(exitB);
        }

        // Generate loop body.
        startBlock(bodyB);
        if (body != null && !(body instanceof ELNode.NULL)) {
            build(body);
            current.emitPop();
        }
        current.emitJump(contB);

        // Generate loop step.
        startBlock(contB);
        idxSlot.load();
        buildConst(1L);
        current.emitLAdd();
        idxSlot.store();
        current.emitPop();

        if (varSlot != null) {
            varSlot.load();
            buildConst(step);
            current.emitLAdd();
            varSlot.store();
            current.emitPop();
        }
        current.emitJump(headerB);

        // Cleanup
        startBlock(exitB);
        current.emitPushNull();
        loopStack.pop();
        release(varSlot);
        release(idxSlot);
    }

    private void buildDynamicRangedFor(ELNode.DEFINE var, ELNode.DEFINE index,
                                       ELNode.RANGE range, ELNode body) {
        // Register loop variable first to claim its pre-allocated slot.
        Slot varSlot = new Slot(var);
        Slot idxSlot = new Slot(index);
        Slot stepSlot = null;
        Slot countSlot = null;

        // Initialize local variables.
        if (range.next != null) {
            stepSlot = new Slot();
            build(range.next);
            build(range.begin);
            varSlot.define();
            current.emitLSub();
            stepSlot.store(); // step = next - begin
            current.emitPop();
        } else {
            build(range.begin);
            varSlot.define();
            current.emitPop();
        }

        if (range.end != null) {
            countSlot = new Slot();
            build(range.end);
            if (range.exclude) {
                buildConst(1L);
                current.emitLSub();
            }
            varSlot.load();
            current.emitLSub();
            if (stepSlot != null) {
                stepSlot.load();
                current.emitLDiv();
            }
            buildConst(1L);
            current.emitLAdd();
            countSlot.store(); // count = (end - begin) / step + 1
            current.emitPop();
        }

        buildConst(0L);
        idxSlot.store();
        current.emitPop();

        int bodyB = allocBlockId();
        int headerB = range.end != null ? allocBlockId() : bodyB;
        int contB = allocBlockId();
        int exitB = allocBlockId();

        loopStack.push(new LoopTargets(contB, exitB));
        current.emitJump(headerB);

        // Generate loop condition.
        if (countSlot != null) {
            startBlock(headerB);
            idxSlot.load();
            countSlot.load();
            current.emitLLt();
            current.emitJumpIfTrue(bodyB);
            current.emitJump(exitB);
        }

        // Generate loop body.
        startBlock(bodyB);
        if (body != null && !(body instanceof ELNode.NULL)) {
            build(body);
            current.emitPop();
        }
        current.emitJump(contB);

        // Generate loop step.
        startBlock(contB);
        idxSlot.load();
        buildConst(1L);
        current.emitLAdd();
        idxSlot.store();
        current.emitPop();

        varSlot.load();
        if (stepSlot != null)
            stepSlot.load();
        else
            buildConst(1L);
        emitDynBinOp(Token.ADD);
        varSlot.store();
        current.emitPop();
        current.emitJump(headerB);

        // Cleanup
        startBlock(exitB);
        current.emitPushNull();
        loopStack.pop();
        release(varSlot);
        release(idxSlot);
        release(stepSlot);
        release(countSlot);
    }

    private void buildIterateFor(ELNode.FOREACH node) {
        int header = allocBlockId();
        int body = allocBlockId();
        int exit = allocBlockId();

        loopStack.push(new LoopTargets(header, exit));

        // Register loop variable first to claim its pre-allocated slot.
        Slot varSlot = node.var.symbol != null ? new Slot(node.var) : null;
        Slot idxSlot = null;
        if (node.index != null) {
            idxSlot = new Slot(node.index);
            buildConst(-1L);
            idxSlot.define();
            current.emitPop();
        }
        Slot iterSlot = new Slot();

        build(node.range);
        emitInvokeStatic(Runtime.class, "getIterator", Object.class);
        iterSlot.store();
        current.emitJumpIfNull(exit);
        current.emitJump(header);

        startBlock(header);
        iterSlot.load();
        emitInvokeMethod(Iterator.class, "hasNext");
        current.emitJumpIfFalse(exit);
        current.emitJump(body);

        startBlock(body);
        iterSlot.load();
        emitInvokeMethod(Iterator.class, "next");
        if (varSlot != null)
            varSlot.store();
        current.emitPop();

        if (node.index != null) {
            idxSlot.load();
            buildConst(1L);
            current.emitIAdd();
            idxSlot.store();
            current.emitPop();
        }

        if (node.body != null && !(node.body instanceof ELNode.NULL)) {
            build(node.body);
            current.emitPop();
        }
        current.emitJump(header);

        startBlock(exit);
        current.emitPushNull();
        loopStack.pop();
        release(varSlot);
        release(idxSlot);
        release(iterSlot);
    }

    public void visit(ELNode.BREAK node) {
        if (loopStack.isEmpty())
            throw reportError(node.pos, _T(EL_STATEMENT_NOT_IN_LOOP, "break"));
        current.emitJump(loopStack.peek().breakBlock());
    }

    public void visit(ELNode.CONTINUE node) {
        if (loopStack.isEmpty())
            throw reportError(node.pos, _T(EL_STATEMENT_NOT_IN_LOOP, "continue"));
        current.emitJump(loopStack.peek().continueBlock());
    }

    public void visit(ELNode.RETURN node) {
        if (node.right != null) {
            build(node.right);
            current.emitReturn();
        } else
            current.emitReturnVoid();
    }

    public void visit(ELNode.THROW node) {
        build(node.cause);
        current.emitThrow();
    }

    public void visit(ELNode.ASSERT node) {
        build(node.exp);
        build(node.msg);
        current.emitAssert();
    }

    public void visit(ELNode.TRY node) {
        // Compile try body (zero-param closure).
        build(node.body);

        // Handlers: each handler is a DEFINE(id = exception var, expr = body).
        int handlerCount = node.handlers != null ? node.handlers.length : 0;
        for (int i = 0; i < handlerCount; i++) {
            buildConst(node.types[i]);
            build(node.handlers[i]);
        }

        // Finally (optional, zero-param closure).
        if (node.finalizer != null)
            build(node.finalizer);
        else
            current.emitPushNull();

        current.emitTry(handlerCount);
    }

    // ── Synchronized ──

    public void visit(ELNode.SYNCHRONIZED node) {
        build(node.exp);
        build(node.body);
        current.emitSynchronized();
    }

    // ── Lambda ──
    public void visit(ELNode.LAMBDA node) {
        IRFunction func;
        if (node.symbol != null)
            func = node.symbol.func;
        else {
            func = new IRFunction("<lambda>", node.vars.length);
            node.symbol = new SymbolTable.Symbol(node.scope, "");
            node.symbol.func = func;
        }

        IRBuilder nested = new IRBuilder(this, func, node.scope);

        // Propagate source file from the AST node
        if (node.file != null)
            nested.currentFile = node.file;

        // Reserve slots for all pre-allocated variables in this lambda
        // scope.  Temp vars allocated via defineLocalVar will then start
        // above the max pre-allocated slot, avoiding collisions.
        nested.reserveSlots(node.scope.maxSlots);

        for (ELNode.DEFINE var : node.vars) {
            // Define global for captured lamba parameters.
            if (var.symbol != null && var.symbol.captured) {
                nested.current.emitPushVar(var.symbol.slot);
                nested.current.emitDefineGlobal(nested.putConstant(var.id));
                nested.current.emitPop();
            }
        }

        nested.buildTail(node.body);

        IRFunction fn = nested.finish(false);
        fn = fn.withDefaults(getDefaultValues(node.vars));
        current.emitClosure(putConstant(fn));
    }


    /**
     * Extract default parameter values from lambda definitions.
     */
    private Object[] getDefaultValues(ELNode.DEFINE[] vars) {
        Object[] defs = null;
        for (int i = 0; i < vars.length; i++) {
            if (vars[i].expr != null) {
                if (defs == null)
                    defs = new Object[vars.length];
                defs[i] = getConstantValue(vars[i].expr);
            }
        }
        return defs;
    }

    /**
     * Extract a constant value from an ELNode, or null if non-constant.
     */
    private Object getConstantValue(ELNode node) {
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
        if (node instanceof ELNode.REGEXP re)
            return re.value;
        if (node instanceof ELNode.NULL)
            return null;
        throw reportError(node.pos, _T(EL_DEFAULT_VALUE_NOT_CONSTANT));
    }

    // ── Pattern matching ──

    /**
     * Compile a MATCH expression as a series of if-else chains.
     * Unsupported patterns (NEW for now) cause the entire MATCH
     * to fall back to trampoline.
     */
    public void visit(ELNode.MATCH node) {
        // Evaluate all args, store in temp locals except it's already a local var.
        int nargs = node.args.length;
        Slot[] argSlots = new Slot[nargs];
        for (int i = 0; i < nargs; i++) {
            if (node.args[i] instanceof ELNode.IDENT ident &&
                ident.symbol != null && !ident.symbol.captured) {
                argSlots[i] = new Slot(ident);
            } else {
                argSlots[i] = new Slot();
                build(node.args[i]);
                argSlots[i].store();
                current.emitPop();
            }
        }

        int exitBlock = allocBlockId();
        int[] nextCase = new int[node.alts.length + 1]; // +1 for default

        // Allocate blocks for each case entry point
        for (int ci = 0; ci < node.alts.length; ci++)
            nextCase[ci] = allocBlockId();
        nextCase[node.alts.length] = allocBlockId(); // default/error block

        // Jump to first case
        current.emitJump(nextCase[0]);

        for (int ci = 0; ci < node.alts.length; ci++) {
            ELNode.CASE c = node.alts[ci];
            int failBlock = nextCase[ci + 1];

            sealAndStart(nextCase[ci]);

            // Each case gets its own control scope for variable bindings.
            // On failure, leaveControlScope discards bindings.
            SymbolTable.Scope prevScope = currentScope;
            currentScope = c.scope;
            if (c.scope.hasCaptures())
                current.emit1(ENTER_SCOPE, K_NONE, 0);

            // Compile patterns for each column
            boolean alwaysFail = false;
            if (c.patterns != null) {
                for (ELNode.Pattern p : c.patterns) {
                    if (checkForAlwaysFail((ELNode)p)) {
                        alwaysFail = true;
                        break;
                    }
                }
            }

            if (alwaysFail) {
                // Completely skip this case
                current.emitJump(failBlock);
            } else {
                if (c.patterns != null) {
                    for (int pi = 0; pi < c.patterns.length; pi++) {
                        argSlots[pi].load();
                        if (compileMatchPattern(argSlots[pi], (ELNode)c.patterns[pi], failBlock))
                            current.emitJumpIfFalse(failBlock);
                    }
                }

                if (c.guards == null) {
                    // no guards, evaluate the single body
                    assert c.bodies != null && c.bodies.length == 1;
                    buildTail(c.bodies[0]);
                    current.emitJump(exitBlock);
                } else {
                    // Evaluate each guard and body
                    assert c.bodies.length == c.guards.length;
                    for (int i = 0; i < c.guards.length; i++) {
                        int nextGuard = -1;
                        if (c.guards[i] != null) {
                            if (i != c.guards.length - 1) {
                                nextGuard = allocBlockId();
                                build(c.guards[i]);
                                current.emitJumpIfFalse(nextGuard);
                            } else {
                                build(c.guards[i]);
                                current.emitJumpIfFalse(failBlock);
                            }
                        }
                        buildTail(c.bodies[i]);
                        current.emitJump(exitBlock);
                        if (nextGuard != -1)
                            startBlock(nextGuard);
                    }
                }
            }

            // On failure: discard case bindings, go to next case
            sealAndStart(failBlock);

            // Leave the case scope
            if (c.scope.hasCaptures())
                current.emit1(LEAVE_SCOPE, K_NONE, 0);
            currentScope = prevScope;

            // Falls through to next case entry (unless this was the last case)
            if (ci + 1 < node.alts.length)
                current.emitJump(nextCase[ci + 1]);
        }

        // Default block
        sealAndStart(nextCase[node.alts.length]);
        if (node.deflt != null) {
            buildTail(node.deflt);
        } else {
            buildConst("no pattern matched");
            current.emitThrow();
        }
        current.emitJump(exitBlock);

        sealAndStart(exitBlock);
        for (Slot slot : argSlots)
            slot.release();
    }

    /**
     * Compile a single pattern check, leaving TRUE on stack if matched.
     */
    private boolean compileMatchPattern(Slot argSlot, ELNode pat, int failBlock) {
        if (pat instanceof ELNode.DEFINE def) {
            // Type check if annotated
            if (def.type != null) {
                emitInstOf(def.type);
                current.emitJumpIfFalse(failBlock);
            }

            // As-pattern check
            if (def.expr != null) {
                if (def.type != null)
                    argSlot.load();
                if (compileMatchPattern(argSlot, def.expr, failBlock))
                    current.emitJumpIfFalse(failBlock);
            }

            // Wildcard: always matches
            if ("_".equals(def.id)) {
                current.emitPop();
                return false;
            }

            // Variable binding -> bind to new pattern variable.
            if (def.type != null || def.expr != null)
                argSlot.load();
            if (def.symbol.captured)
                current.emitDefineGlobal(putConstant(def.id));
            else
                current.emitStoreVar(def.symbol.slot);
            current.emitPop();
            return false;
        }

        if (pat instanceof ELNode.IDENT var) {
            current.emitPushVar(var.symbol.slot);
            emitDynBinOp(Token.EQ);
            return true;
        }

        if (pat instanceof ELNode.NOT not) {
            if (compileMatchPattern(argSlot, not.right, failBlock))
                current.emitNot();
            else
                current.emitPushFalse();
            return true;
        }

        if (pat instanceof ELNode.OR or) {
            if (checkForAlwaysFail(or.left)) {
                return compileMatchPattern(argSlot, or.right, failBlock);
            } else if (checkForAlwaysFail(or.right)) {
                return compileMatchPattern(argSlot, or.left, failBlock);
            }

            int tryRight = allocBlockId();
            int done = allocBlockId();

            // Left branch, argSlot already on stack top.
            if (compileMatchPattern(argSlot, or.left, tryRight))
                current.emitJumpIfFalse(tryRight); // matched -> skip right
            current.emitJump(done);

            sealAndStart(tryRight);
            argSlot.load();
            if (compileMatchPattern(argSlot, or.right, failBlock))
                current.emitJumpIfFalse(failBlock);
            current.emitJump(done);
            sealAndStart(done);
            return false;
        }

        if (pat instanceof ELNode.NUMBER n) {
            buildConst(n.value);
            emitDynBinOp(Token.EQ);
            return true;
        }

        if (pat instanceof ELNode.STRINGVAL s) {
            buildConst(s.value);
            emitDynBinOp(Token.EQ);
            return true;
        }

        if (pat instanceof ELNode.BOOLEANVAL b) {
            buildConst(b.value);
            emitDynBinOp(Token.EQ);
            return true;
        }

        if (pat instanceof ELNode.CHARVAL c) {
            buildConst(c.value);
            emitDynBinOp(Token.EQ);
            return true;
        }

        if (pat instanceof ELNode.NULL) {
            current.emitJumpIfNonNull(failBlock);
            return false;
        }

        if (pat instanceof ELNode.SYMBOL sym) {
            buildConst(sym.value);
            current.emitIdEq();
            return true;
        }

        if (pat instanceof ELNode.CLASS cls) {
            emitInstOf(cls.name);
            return true;
        }

        if (pat instanceof ELNode.REGEXP re) {
            emitInstOf(String.class);
            current.emitJumpIfFalse(failBlock);
            emitPushConst(K_NONE, re.value); // the pattern
            argSlot.load();                  // the string to match
            emitInvokeMethod(java.util.regex.Pattern.class, "matcher", CharSequence.class);
            emitInvokeMethod(java.util.regex.Matcher.class, "matches");
            return true;
        }

        if (pat instanceof ELNode.EXPR e) {
            build(e.right);
            emitDynBinOp(Token.EQ);
            return true;
        }

        if (pat instanceof ELNode.TUPLE t) {
            Slot tmpSlot = null;

            emitInvokeMethod(Object.class, "getClass");
            emitInvokeMethod(Class.class, "isArray");
            current.emitJumpIfFalse(failBlock);

            argSlot.load();
            emitInvokeStatic(Array.class, "getLength", Object.class);
            buildConst(t.elems.length);
            current.emitIEq();
            current.emitJumpIfFalse(failBlock);

            for (int i = 0; i < t.elems.length; i++) {
                argSlot.load();
                buildConst(i);
                emitInvokeStatic(Array.class, "get", Object.class, int.class);
                if (!isSimplePattern(t.elems[i])) {
                    if (tmpSlot == null)
                        tmpSlot = new Slot();
                    tmpSlot.store();
                }
                if (compileMatchPattern(tmpSlot, t.elems[i], failBlock))
                    current.emitJumpIfFalse(failBlock);
            }
            release(tmpSlot);
            return false;
        }

        if (pat instanceof ELNode.CONS cons) {
            Slot seqSlot = new Slot();
            Slot tmpSlot = null;
            if (!isSimplePattern(cons.head) || !isSimplePattern(cons.tail))
                tmpSlot = new Slot();

            emitInstOf(List.class);
            current.emitJumpIfFalse(failBlock);
            argSlot.load();
            emitInvokeStatic(TypeCoercion.class, "coerceToSeq", Object.class);
            seqSlot.store();
            emitInvokeMethod(List.class, "isEmpty");
            current.emitJumpIfTrue(failBlock);

            seqSlot.load();
            emitInvokeMethod(Seq.class, "head");
            if (!isSimplePattern(cons.head))
                tmpSlot.store();
            if (compileMatchPattern(tmpSlot, cons.head, failBlock))
                current.emitJumpIfFalse(failBlock);

            seqSlot.load();
            emitInvokeMethod(Seq.class, "tail");
            if (!isSimplePattern(cons.tail))
                tmpSlot.store();
            if (compileMatchPattern(tmpSlot, cons.tail, failBlock))
                current.emitJumpIfFalse(failBlock);

            release(tmpSlot);
            release(seqSlot);
            return false;
        }

        if (pat instanceof ELNode.NIL) {
            emitDynUnOp(Token.EMPTY);
            return true;
        }

        if (pat instanceof ELNode.RANGE) {
            current.emitPop(); // we will re-push arg after build tuple
            build(pat);
            argSlot.load();
            emitInvokeMethod(List.class, "contains", Object.class);
            return true;
        }

        if (pat instanceof ELNode.MAP map) {
            Slot tmpSlot = null;
            for (int i = 0; i < map.keys.length; i++) {
                assert map.keys[i] instanceof ELNode.STRINGVAL;
                buildConst(((ELNode.STRINGVAL)map.keys[i]).value);
                emitInvokeStatic(Runtime.class, "loadProperty", ELContext.class,
                                 Object.class, Object.class);
                if (!isSimplePattern(map.values[i])) {
                    if (tmpSlot == null)
                        tmpSlot = new Slot();
                    tmpSlot.store();
                }
                if (compileMatchPattern(tmpSlot, map.values[i], failBlock))
                    current.emitJumpIfFalse(failBlock);
                if (i != map.keys.length - 1)
                    argSlot.load();
            }
            release(tmpSlot);
            return false;
        }

        if (pat instanceof ELNode.NEW data) {
            ELNode.IDENT base = (ELNode.IDENT)data.base;
            ELNode[] args = data.args;
            int argc = args.length;

            if (base.symbol != null &&
                base.symbol.node instanceof ELNode.CLASSDEF cdef) {
                Slot cdefSlot = new Slot(base);
                Slot targetSlot = new Slot();
                Slot tmpSlot = null;

                emitInstOf(ClosureObject.class);
                current.emitJumpIfFalse(failBlock);

                cdefSlot.load();
                argSlot.load();
                emitInvokeMethod(ClosureObject.class, "get_owner");
                targetSlot.store();
                emitInvokeMethod(ClosureObject.class, "get_class");
                emitInvokeMethod(ClassDefinition.class, "isAssignableFrom",
                    ELContext.class, ClassDefinition.class);
                current.emitJumpIfFalse(failBlock);

                if (argc == 0)
                    return false;

                if (data.keys != null) {
                    // matches for closure object properties
                    for (int i = 0; i < argc; i++) {
                        targetSlot.load();
                        buildConst(data.keys[i]);
                        emitInvokeMethod(ClosureObject.class, "getValue",
                            ELContext.class, Object.class);
                        if (!isSimplePattern(args[i])) {
                            if (tmpSlot == null)
                                tmpSlot = new Slot();
                            tmpSlot.store();
                        }
                        if (compileMatchPattern(tmpSlot, args[i], failBlock))
                            current.emitJumpIfFalse(failBlock);
                    }
                } else {
                    // matches for constructor variables
                    targetSlot.load();
                    emitInvokeMethod(ClosureObject.class, "get_this");
                    targetSlot.store();

                    assert cdef.vars.length == argc; // already checked
                    for (int i = 0; i < argc; i++) {
                        ELNode arg = args[i];
                        buildConst(cdef.vars[i].id);
                        emitInvokeMethod(ClosureObject.class, "get_closure", ELContext.class, String.class);
                        emitInvokeMethod(Closure.class, "getValue", ELContext.class);
                        if (!isSimplePattern(args[i])) {
                            if (tmpSlot == null)
                                tmpSlot = new Slot();
                            tmpSlot.store();
                        }
                        if (compileMatchPattern(tmpSlot, arg, failBlock))
                            current.emitJumpIfFalse(failBlock);
                        if (i != argc - 1)
                            targetSlot.load();
                    }
                }

                release(targetSlot);
                release(tmpSlot);
            } else {
                String className;
                String[] slots = null;

                if (base.symbol != null && base.symbol.node instanceof ELNode.CLASS c) {
                    className = c.name;
                    slots = c.slots;
                } else {
                    className = base.id;
                }

                Class<?> cls = resolveClassAtCompileTime(className);
                if (cls == null) {
                    emitInstOf(className);
                    return true;
                }

                if (argc == 0) {
                    emitInstOf(cls);
                    return true;
                }

                if (data.keys != null) {
                    slots = data.keys;
                } else {
                    if (slots == null) {
                        Data d = cls.getAnnotation(Data.class);
                        if (d != null)
                            slots = d.value();
                    }
                    assert slots != null && slots.length == argc; // already checked
                }

                emitInstOf(cls);
                current.emitJumpIfFalse(failBlock);

                Slot tmpSlot = null;
                for (int i = 0; i < argc; i++) {
                    argSlot.load();
                    buildConst(slots[i]);
                    current.emitLoadProperty();
                    if (!isSimplePattern(args[i])) {
                        if (tmpSlot == null)
                            tmpSlot = new Slot();
                        tmpSlot.store();
                    }
                    if (compileMatchPattern(tmpSlot, args[i], failBlock))
                        current.emitJumpIfFalse(failBlock);
                }

                release(tmpSlot);
            }

            return false;
        }

        // Should not reach here
        throw new UnsupportedOperationException();
    }

    private boolean isSimplePattern(ELNode pat) {
        if (pat instanceof ELNode.DEFINE def)
            return def.type == null && def.expr == null;

        if (pat instanceof ELNode.REGEXP)
            return false;

        return pat instanceof ELNode.Constant ||
               pat instanceof ELNode.IDENT ||
               pat instanceof ELNode.NOT ||
               pat instanceof ELNode.EXPR;
    }

    private boolean checkForAlwaysFail(ELNode pat) {
        if (pat instanceof ELNode.NEW data) {
            ELNode.IDENT base = (ELNode.IDENT)data.base;
            ELNode[] args = data.args;
            int argc = args.length;

            for (ELNode e : args) {
                if (checkForAlwaysFail(e))
                    return true;
            }

            if (argc == 0 || data.keys != null)
                return false;

            if (base.symbol != null && base.symbol.node instanceof ELNode.CLASSDEF cdef) {
                return cdef.vars == null || cdef.vars.length != argc;
            } else {
                String className;
                String[] slots = null;

                if (base.symbol != null && base.symbol.node instanceof ELNode.CLASS c) {
                    className = c.name;
                    slots = c.slots;
                } else {
                    className = base.id;
                }

                Class<?> cls = resolveClassAtCompileTime(className);
                if (cls != null) {
                    if (slots == null) {
                        Data d = cls.getAnnotation(Data.class);
                        if (d != null)
                            slots = d.value();
                    }
                    return slots == null || slots.length != argc;
                }
            }

            return false;
        }

        if (pat instanceof ELNode.OR or) {
            return checkForAlwaysFail(or.left) && checkForAlwaysFail(or.right);
        }

        if (pat instanceof ELNode.TUPLE t) {
            for (ELNode p : t.elems) {
                if (checkForAlwaysFail(p))
                    return true;
            }
            return false;
        }

        if (pat instanceof ELNode.CONS c) {
            return checkForAlwaysFail(c.head) || checkForAlwaysFail(c.tail);
        }

        if (pat instanceof ELNode.MAP m) {
            for (ELNode e : m.values)
                if (checkForAlwaysFail(e))
                    return true;
            return false;
        }

        return false;
    }

    public void visit(ELNode.LET node) {
        Slot argSlot;
        if (node.right instanceof ELNode.IDENT ident &&
            ident.symbol != null && !ident.symbol.captured) {
            argSlot = new Slot(ident);
        } else {
            argSlot = new Slot();
            build(node.right);
            argSlot.store();
            current.emitPop();
        }

        int exitBlock = allocBlockId();
        int failBlock = allocBlockId();

        argSlot.load();
        if (compileMatchPattern(argSlot, node.left, failBlock))
            current.emitJumpIfFalse(failBlock);
        current.emitJump(exitBlock);

        startBlock(failBlock);
        buildConst("pattern not match");
        current.emitThrow();
        current.emitJump(exitBlock);

        startBlock(exitBlock);
        current.emitPop();
        argSlot.load();
        argSlot.release();
    }

    public void visit(ELNode.NEW node) {
        // NEW can be used to create new instance of a Java class, a user defined elite
        // class or a data class. First let me try Java class, then lookup symbol to see
        // if the target is a CLASSDEF, then fallback to trampoline.
        String className = node.getClassName();

        if (node.props != null || node.keys != null) {
            // FIXME: we cannot handle properties and named arguments yet.
            buildTrampoline(node);
            return;
        }

        if (node.base instanceof ELNode.IDENT var && var.symbol != null) {
            if (var.symbol.node instanceof ELNode.CLASSDEF) {
                // Load the ClassDefinition.
                build(node.base);

                // Invoke ClassDefinition.invoke with arguments.
                // FIXME: handle named arguments for constructor
                build(node.args);
                current.emitNewTuple(node.args.length);
                emitInvokeMethod(ClassDefinition.class, "invoke", ELContext.class, Object[].class);
                return;
            }

            if (var.symbol.node instanceof ELNode.DEFINE def &&
                def.expr instanceof ELNode.CLASS c && c.slots == null) {
                className = c.name;
            }
        }

        Class<?> cls = resolveClassAtCompileTime(className);
        if (cls != null) {
            buildConst(cls);
            build(node.args);
            current.emitNewTuple(node.args.length);
            emitInvokeStatic(ELEngine.class, "newInstance", ELContext.class, Class.class, Object[].class);
            return;
        }

        // Otherwise, fallback to trampoline.
        buildTrampoline(node);
    }

    public void visit(ELNode.CONST node) {
        buildConst(node.value);
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
            if (currentPos != Position.NOPOS)
                recordDebugLine(runningPc);
            current.clear();
        }
        currentBlockId = blockId;
        current = new IREmitter();
    }

    /**
     * Seal current block into blockMap and start a new block with the given ID.
     */
    private void sealAndStart(int blockId) {
        int[] code = current.toArray();
        blockMap.put(currentBlockId, code);
        runningPc += code.length;
        if (currentPos != Position.NOPOS)
            recordDebugLine(runningPc);
        current.clear();
        currentBlockId = blockId;
    }

    // Running PC counter for debug info
    private int runningPc;

    // ── Local management ──

    private class Slot {
        private final int slot;
        private final boolean captured;
        private final boolean isTemporary;

        Slot() {
            slot = allocLocalVar();
            captured = false;
            isTemporary = true;
        }

        Slot(ELNode.DEFINE var) {
            if (var != null && var.symbol != null) {
                captured = var.symbol.captured;
                slot = captured ? putConstant(var.id) : var.symbol.slot;
                isTemporary = false;
            } else {
                slot = allocLocalVar();
                captured = false;
                isTemporary = true;
            }
        }

        Slot(ELNode.IDENT var) {
            if (var != null && var.symbol != null) {
                captured = var.symbol.captured;
                slot = captured ? putConstant(var.id) : var.symbol.slot;
                isTemporary = false;
            } else {
                slot = allocLocalVar();
                captured = false;
                isTemporary = true;
            }
        }

        void define() {
            if (captured)
                current.emitDefineGlobal(slot);
            else
                current.emitStoreVar(slot);
        }

        void store() {
            if (captured)
                current.emitPushGlobal(slot);
            else
                current.emitStoreVar(slot);
        }

        void load() {
            if (captured)
                current.emitPushGlobal(slot);
            else
                current.emitPushVar(slot);
        }

        void release() {
            if (isTemporary)
                freeSlots.push(slot);
        }

        private int allocLocalVar() {
            if (!freeSlots.isEmpty()) {
                return freeSlots.pop();
            }
            int slot = nextTempSlot++;
            maxLocals = Math.max(maxLocals, nextTempSlot);
            return slot;
        }
    }

    private void release(Slot slot) {
        if (slot != null)
            slot.release();
    }

    /**
     * Reserve space in `varNames` up to (and including) the given slot index.
     * Temp vars allocated after this call will start above the reserved range.
     */
    private void reserveSlots(int maxSlots) {
        maxLocals = nextTempSlot = maxSlots;
    }

    // ── Finalization ──

    /**
     * Record the current line for the given PC (used by debug info).
     */
    private void recordDebugLine(int pc) {
        if (currentPos != Position.NOPOS) {
            int line = Position.line(currentPos);
            linePcMapping.compute(line, (k, v) ->
                v == null ? pc : Math.max(pc, v));
        }
    }

    /**
     * Build DebugInfo from collected data.
     */
    private DebugInfo buildDebugInfo() {
        // We need a sorted map from pc to line.
        SortedMap<Integer, Integer> pcLineMapping = new TreeMap<>();
        for (Map.Entry<Integer, Integer> kv : linePcMapping.entrySet()) {
            pcLineMapping.put(kv.getValue(), kv.getKey());
        }

        // Build the pc to line mapping table.
        IntList pcLineTable = new IntList();
        for (Map.Entry<Integer, Integer> kv : pcLineMapping.entrySet()) {
            pcLineTable.add(kv.getKey());
            pcLineTable.add(kv.getValue());
        }

        return new DebugInfo(currentFile, pcLineTable.toArray());
    }

    IRFunction finish(boolean noReturn) {
        if (!endsWithReturn()) {
            if (noReturn)
                current.emitReturnVoid();
            else
                current.emitReturn();
        }

        // Seal current block and record its debug line
        if (current != null) {
            int[] code = current.toArray();
            blockMap.put(currentBlockId, code);
            if (currentPos != Position.NOPOS) {
                runningPc += code.length;
                recordDebugLine(runningPc);
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

        func.populate(merged.toArray(), maxLocals, offsets,
                      constants.toArray(new Object[0]),
                      buildDebugInfo(), null);
        return func;
    }

    private boolean endsWithReturn(InstructionView v) {
        int lastOp = -1;
        while (v.inBounds()) {
            lastOp = v.opcode();
            v.advance();
        }
        return lastOp == RETURN || lastOp == RETURN_VOID;
    }

    private boolean endsWithReturn() {
        if (current != null && !current.isEmpty()) {
            if (endsWithReturn(current.view()))
                return true;
        }

        int maxId = blockMap.keySet().stream().max(Integer::compare).orElse(-1);
        if (maxId < 0)
            return false;
        int[] lb = blockMap.get(maxId);
        if (lb == null || lb.length == 0)
            return false;
        return endsWithReturn(new InstructionView(lb, 0));
    }

    // ── Convenience emits ──

    private int putConstant(Object value) {
        return constIndex.computeIfAbsent(value, k -> {
            constants.add(k);
            return constants.size() - 1;
        });
    }

    private void emitPushConst(int typeId, Object value) {
        int idx = putConstant(value);
        int kind = (typeId >= 0) ? K_PRIM : K_NONE;
        int payload = idx & 0xFFFF;
        if (idx < 0x10000)
            current.emit1(PUSH_CONST, kind, payload);
        else
            current.emit2(PUSH_CONST, kind, idx >>> 16, idx & 0xFFFF);
    }

    private void buildConst(Boolean value) {
        if (value)
            current.emitPushTrue();
        else
            current.emitPushFalse();
    }

    private void buildConst(int value) {
        emitPushConst(T_INT, value);
    }

    private void buildConst(long value) {
        emitPushConst(T_LONG, value);
    }

    private void buildConst(double value) {
        emitPushConst(T_DOUBLE, value);
    }

    private void buildConst(String value) {
        emitPushConst(T_STRING, value);
    }

    private void buildConst(Object value) {
        if (value == null)
            current.emitPushNull();
        else
            emitPushConst(K_NONE, value);
    }

    private void emitInvokeMethod(Class<?> c, String name, Class<?>... types) {
        try {
            Method method = c.getMethod(name, types);
            int argc = types.length;
            if (argc > 0 && types[0] == ELContext.class)
                argc--;
            int methodIdx = putConstant(method);
            current.emitInvokeMethod(methodIdx, argc);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private void emitInvokeStatic(Class<?> c, String name, Class<?>... types) {
        try {
            Method method = c.getMethod(name, types);
            emitInvokeStatic(method);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private void emitInvokeStatic(Method method) {
        Class<?>[] types = method.getParameterTypes();
        int argc = types.length;
        if (argc > 0 && types[0] == ELContext.class)
            argc--;
        int methodIdx = putConstant(method);
        current.emitInvokeStatic(methodIdx, argc);
    }

    // ── Static API ──

    public static IRFunction compile(ELContext elctx, ELNode node) {
        IRBytecodeCompiler.resetState();
        SymbolTable symTable = SymbolTableBuilder.build(node);
        IRFunction func = new IRFunction("<expr>", 0);
        IRBuilder b = new IRBuilder(elctx, func, symTable.currentScope());
        b.build(node);
        return b.finish(false);
    }

    public static IRFunction compile(ELContext elctx, ELProgram program) {
        SymbolTable symTable = SymbolTableBuilder.build(program);
        reportSymbolTableError(program, symTable);

        List<ELNode> defs = program.getDefinitions();
        List<ELNode> exps = program.getExpressions();

        IRBytecodeCompiler.resetState();

        IRFunction func = new IRFunction("<program>", 0);
        IRBuilder b = new IRBuilder(elctx, func, symTable.currentScope());
        b.setFile(program.getFilename());

        // Reserve slots for all pre-allocated program-level variables.
        // After this, defineLocalVar will allocate temp vars above the max slot.
        b.reserveSlots(symTable.currentScope().maxSlots);

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

        return b.finish(last == null);
    }

    private static void reportSymbolTableError(ELProgram prog, SymbolTable symTable) {
        if (symTable.getRedefinitions().isEmpty())
            return;

        StringBuilder sb = new StringBuilder();
        String file = prog.getFilename();
        for (SymbolTable.Redefinition redef : symTable.getRedefinitions()) {
            sb.append("\n");
            if (file != null)
                sb.append(file).append(':');
            sb.append(Position.line(redef.pos())).append(':')
                .append(Position.column(redef.pos())).append(": ");
            sb.append(_T(EL_REDEFINED_IDENTIFIER, redef.id(),
                         Position.line(redef.previousPos())));
        }
        throw new ParseException(file, 1, 1, sb.toString());
    }
}
