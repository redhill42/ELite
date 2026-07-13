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
import org.elite.eval.seq.Cons;
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

    // The compiled IRProgram.
    IRProgram program;

    // The IRFunction to build.
    private final IRFunction func;

    // Tracking current scope.
    private SymbolTable.Scope currentScope;

    // Tracking temporary slot allocation.
    private int maxLocals;
    private int nextTempSlot;
    private final Deque<Integer> freeSlots = new ArrayDeque<>();

    private static class Block {
        final int id;
        final IntList code;
        final Map<Integer, Integer> lineMap = new HashMap<>();
        int pc;

        BitSet predecessors = new BitSet();
        BitSet successors = new BitSet();
        int mappedId;

        Block(int id, int[] code, Map<Integer, Integer> lineMap) {
            this.id = id;
            this.code = new IntList(code);
            this.lineMap.putAll(lineMap);
        }

        void removeInstruction(int offset) {
            removeInstruction(offset, 1);
        }

        void removeInstruction(int offset, int length) {
            // Update line map.
            lineMap.replaceAll((k, v) -> v > offset ? v - offset : v);

            // Remove the instruction from list
            code.remove(offset, length);
        }
    }

    // Peephole optimizer.
    private final PeepholeOpt peephole;

    // ── Block management
    private final List<Block> blocks = new ArrayList<>();
    private final IREmitter current;
    private int currentBlockId = 0;
    private int nextBlockId = 1;  // 0 is the initial block
    private int exitBlock = -1;

    // ── Constant pool (maybe shared with parent builder) ──
    private Map<Object, Integer> constIndex = new HashMap<>();
    private List<Object> constants = new ArrayList<>();

    // ── Loop stack ──
    private record LoopTargets(int continueBlock, int breakBlock) {}
    private final Deque<LoopTargets> loopStack = new ArrayDeque<>();

    // ── Tail-call optimization ──
    private boolean inTailPosition = true;

    // ── Debug info ──
    private String currentFile;
    private final Map<Integer, Integer> linePcMapping = new HashMap<>();

    /**
     * Create a top-level builder.  The symbol table must already be built
     * so that AST nodes carry slot/captured annotations.
     */
    IRBuilder(ELContext elctx, IRProgram program, IRFunction func,
              SymbolTable.Scope scope) {
        this.elctx = elctx;
        this.program = program;
        this.func = func;
        this.peephole = new PeepholeOpt(elctx, this);
        this.current = new IREmitter(peephole);
        this.currentScope = scope;
    }

    /**
     * Create a nested builder sharing the parent's constant pool, import
     * context, and symbol table.
     */
    private IRBuilder(IRBuilder parent, IRFunction func, SymbolTable.Scope scope) {
        assert(parent != null);
        this.program = parent.program;
        this.func = func;
        this.elctx = parent.elctx;
        this.peephole = parent.peephole;
        this.current = new IREmitter(peephole);
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
     * Returns null if resolution fails.
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

        if (node.scope != null) {
            SymbolTable.Scope prevScope = currentScope;
            currentScope = node.scope;
            if (!(node instanceof ELNode.LAMBDA) && node.scope.hasCaptures()) {
                // We need to set up new evaluation context if any variables
                // captured in this scope.
                current.emitEnterScope();
                node.accept(this);
                current.emitLeaveScope();
            } else {
                node.accept(this);
            }
            currentScope = prevScope;
        } else {
            node.accept(this);
        }

        if (node.pos != Position.NOPOS) {
            int line = Position.line(node.pos);
            int pc = current.size();
            linePcMapping.compute(line, (k, v) ->
                v == null ? pc : Math.max(pc, v));
        }
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

    private void build(ELNode[] nodes) {
        for (ELNode node : nodes)
            build(node);
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
        buildConst(node.value.toString());
        emitInvokeStatic(java.util.regex.Pattern.class, "compile", String.class);
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
        buildConst(node.value.getName());
        emitInvokeStatic(elite.lang.Symbol.class, "valueOf", String.class);
    }

    public void visit(ELNode.ACCESS node) {
        build(node.right);
        build(node.index);
        current.emitLoadProperty();
    }

    public void visit(ELNode.IDENT node) {
        if (node.symbol == null || node.symbol.captured) {
            current.emitPushGlobal(putConstant(node.id));
        } else {
            current.emitPushVar(node.symbol.slot);
        }
    }

    public void visit(ELNode.APPLY node) {
        ELNode base = node.right;

        if (base instanceof ELNode.IDENT ident) {
            if (ident.symbol != null) {
                if (inTailPosition && ident.symbol.func == this.func) {
                    // TCO: build args and save to local slots.
                    ELNode.LAMBDA lambda = (ELNode.LAMBDA)ident.symbol.def.expr;
                    ELNode[] args = getCallArgs(node.pos, lambda, node.args, node.keys);
                    int argc = buildCallArgs(lambda, args);
                    for (int i = argc; --i >= 0; ) {
                        current.emitStoreVar(i);
                        current.emitPop();
                    }

                    // Jump to first block.
                    current.emitJump(0);
                    return;
                }

                if (ident.symbol.func != null) {
                    var lambda = (ELNode.LAMBDA)ident.symbol.def.expr;
                    ELNode[] args = getCallArgs(node.pos, lambda, node.args, node.keys);
                    buildDirectCall(ident.symbol, args);
                    return;
                }

                if (ident.symbol.def.expr instanceof ELNode.CLASSDEF) {
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
            current.emitNewTuple(node.args.length);
            emitInvokeStatic(Runtime.class, "invoke", ELContext.class, Object.class,
                             Object.class, Object[].class);
            return;
        }

        build(base);

        if (base instanceof ELNode.LAMBDA lam && lam.symbol != null &&
            lam.symbol.func != null) {
            // Lambda closure no longer used.
            current.emitPop();

            // One-shot lambda call.
            ELNode[] args = getCallArgs(node.pos, lam, node.args, node.keys);
            buildDirectCall(lam.symbol, args);
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
    private ELNode[] getCallArgs(int pos, ELNode.LAMBDA lambda, ELNode[] args, String[] keys) {
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
            for (; j < xargs.length; j++) {
                if (xargs[j] == null) {
                    if (lambda.vars[j].expr == null)
                        throw reportError(pos, _T(EL_MISSING_ARG_VALUE, lambda.vars[j].id));
                    xargs[j] = lambda.vars[j].expr;
                }
            }

            args = xargs;
        }

        return args;
    }

    private int buildCallArgs(ELNode.LAMBDA lambda, ELNode[] args) {
        // Build argument list, include varargs that then build to tuple.
        build(args);

        // Build tuple for var arg list.
        if (lambda.varargs)
            current.emitNewTuple(args.length - (lambda.vars.length - 1));

        return lambda.vars.length;
    }

    private void buildDirectCall(SymbolTable.Symbol sym, ELNode... args) {
        IRFunction fn = sym.func;
        ELNode.LAMBDA lambda = (ELNode.LAMBDA)sym.def.expr;

        if (!isInlineOpportunity(sym)) {
            int argc = buildCallArgs(lambda, args);
            current.emitInvokeDirect(putConstant(sym.func), argc);
            return;
        }

        // Scan for slots in inlined function.
        BitSet readSlots = new BitSet();
        BitSet modSlots = new BitSet();
        for (var v = new InstructionView(fn.code(), 0); v.inBounds(); v.advance()) {
            if (v.opcode() == PUSH_VAR)
                readSlots.set(v.varIndex());
            if (v.opcode() == STORE_VAR || v.opcode() == STORE_VAR_POP)
                modSlots.set(v.varIndex());
        }

        // Allocate slots for inline function local variables.
        Slot[] slots = new Slot[fn.maxLocals()];
        for (int i = 0; i < args.length; i++) {
            // If the slot never read, no need to allocate new slot.
            // Argument still need to build for side effects.
            if (!readSlots.get(i)) {
                build(args[i]);
                current.emitPop();
                continue;
            }

            // Reuse slot for read only slot.
            if (!modSlots.get(i)) {
                if (args[i] instanceof ELNode.IDENT ident &&
                    ident.symbol != null && !ident.symbol.captured) {
                    slots[i] = new Slot(ident);
                    continue;
                }
                if (args[i] instanceof ELNode.Constant) {
                    slots[i] = null;
                    continue;
                }
            }

            // Build argument and store to local slot.
            build(args[i]);
            slots[i] = new Slot();
            slots[i].store();
            current.emitPop();
        }
        for (int i = args.length; i < fn.maxLocals(); i++) {
            if (readSlots.get(i))
                slots[i] = new Slot();
        }

        inlineInstructions(sym, slots, args);

        for (Slot slot : slots)
            release(slot);
    }

    private void buildDirectCall(SymbolTable.Symbol sym, Slot... args) {
        IRFunction fn = sym.func;

        if (!isInlineOpportunity(sym)) {
            for (Slot arg : args)
                arg.load();
            current.emitInvokeDirect(putConstant(fn), args.length);
            return;
        }

        // Scan for slots in inlined function.
        BitSet readSlots = new BitSet();
        BitSet modSlots = new BitSet();
        for (var v = new InstructionView(fn.code(), 0); v.inBounds(); v.advance()) {
            if (v.opcode() == PUSH_VAR)
                readSlots.set(v.varIndex());
            if (v.opcode() == STORE_VAR || v.opcode() == STORE_VAR_POP)
                modSlots.set(v.varIndex());
        }

        // Allocate slots for function local variables.
        Slot[] slots = new Slot[fn.maxLocals()];
        for (int i = 0; i < args.length; i++) {
            if (readSlots.get(i)) {
                if (!modSlots.get(i)) {
                    slots[i] = args[i];
                } else {
                    slots[i] = new Slot();
                    args[i].load();
                    slots[i].store();
                    current.emitPop();
                }
            }
        }
        for (int i = args.length; i < fn.maxLocals(); i++) {
            if (readSlots.get(i))
                slots[i] = new Slot();
        }

        inlineInstructions(sym, slots, null);

        for (Slot slot : slots)
            release(slot);
    }

    private void inlineInstructions(SymbolTable.Symbol sym, Slot[] slots, ELNode[] args) {
        IRFunction fn = sym.func;

        // Build block map.
        int[] blockMap = new int[fn.blockCount()];
        for (int i = 0; i < fn.blockCount(); i++)
            blockMap[i] = allocBlockId();

        boolean hasCaptures = sym.def.expr.scope.hasCaptures();
        if (hasCaptures)
            current.emitEnterScope();

        for (var v = new InstructionView(fn.code(), 0); v.inBounds(); v.advance()) {
            int blockId = fn.blockOfPc(v.offset());
            if (blockId != -1) {
                current.emitJump(blockMap[blockId]);
                startBlock(blockMap[blockId]);
            }

            if (v.opcode() == PUSH_VAR) {
                Slot newSlot = slots[v.varIndex()];
                if (newSlot == null) {
                    // Push constant.
                    int idx = v.varIndex();
                    assert idx < args.length && args[idx] instanceof ELNode.Constant;
                    build(args[idx]);
                } else {
                    // Remap local variable to new slot.
                    current.emit(v.opcode(), v.payload(), newSlot.slot);
                }
            } else if (v.opcode() == STORE_VAR || v.opcode() == STORE_VAR_POP) {
                Slot newSlot = slots[v.varIndex()];
                if (newSlot == null) {
                    // Ignore write only slot.
                    if (v.opcode() == STORE_VAR_POP)
                        current.emitPop();
                } else {
                    // Remap local variable to new slot.
                    current.emit(v.opcode(), v.payload(), newSlot.slot);
                }
            } else if (v.isJump()) {
                current.emit(v.opcode(), v.payload(), blockMap[v.jumpTarget()]);
            } else if (v.opcode() == RETURN) {
                // We have guaranteed single entry single exit.
                assert v.offset() == fn.code().length - 1;
                break;
            } else if (v.opcode() != NOP) {
                current.emit(v.opcode(), v.payload(), v.operand());
            }
        }

        if (hasCaptures)
            current.emitLeaveScope();
    }

    private boolean isInlineOpportunity(SymbolTable.Symbol sym) {
        if (ELProgram.OPT_LEVEL == 0)
            return false;
        if (((ELNode.LAMBDA)sym.def.expr).varargs)
            return false; // FIXME: handle varargs
        if (sym.func.isDeclaration() || sym.func.code().length > 50)
            return false;

        // Check self recursion function.
        InstructionView v = new InstructionView(sym.func.code(), 0);
        for (; v.inBounds(); v.advance()) {
            if (v.opcode() == INVOKE_DIRECT &&
                constants.get(v.poolIndex()) == sym.func)
                return false;
        }

        return true;
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
            else if (var.symbol.def.expr instanceof ELNode.CLASS c)
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
        // Build body to make sure one-shot lambda is built.
        build(body);

        // We have three path to call the step body:
        //  1) direct call, if and only if the body is a lambda
        //  2) global call, if the body is a global function reference (e.g. print)
        //  3) dynamic call, fallback for unresolved call targets.

        // Determine whether the body can be inlined or direct call.
        boolean direct = false;
        SymbolTable.Symbol sym =
            (body instanceof ELNode.IDENT ident)   ? ident.symbol :
            (body instanceof ELNode.LAMBDA lambda) ? lambda.symbol : null;
        if (sym != null && sym.func != null) {
            if (sym.func.paramCount() > 1)
                throw reportError(body.pos, _T(EL_FN_BAD_ARG_COUNT, sym.func.name(),
                                               sym.func.paramCount(), 1));
            direct = true;
        }

        // Check if the body is a global function.
        Method global = direct ? null : getGlobalForStepBody(body);

        // Initialize temporary variables.
        Slot indSlot = new Slot();
        Slot endSlot = new Slot();
        Slot bodySlot = null;

        if (direct || global != null) {
            // Discard body closure if direct call or global call.
            current.emitPop();
        } else {
            // Store body reference for dynamic call.
            bodySlot = new Slot();
            bodySlot.store();
            current.emitPop();
        }

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
        if (direct) {
            if (sym.func.paramCount() == 1) {
                buildDirectCall(sym, indSlot);
            } else {
                buildDirectCall(sym, new Slot[0]);
            }
        } else if (global != null) {
            indSlot.load();
            current.emitInvokeStatic(putConstant(global), 1);
        } else {
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

    private Method getGlobalForStepBody(ELNode body) {
        if (!(body instanceof ELNode.IDENT v))
            return null;

        if (v.symbol != null)
            return null;

        var mc = MethodResolver.getInstance(elctx).resolveGlobalMethod(v.id);
        if (mc == null)
            return null;

        Method method = mc.getJavaMethod();
        if (method == null)
            return null;

        int paramCount = method.getParameterCount();
        if (paramCount > 0 && method.getParameterTypes()[0] == ELContext.class)
            paramCount--;
        if (paramCount != 1 || method.isVarArgs())
            return null;
        return method;
    }

    private boolean buildMathReduce(ELNode[] args, int op) {
        if (args.length == 0) {
            buildConst(0);
            return true;
        }

        build(args[0]);
        for (int i = 1; i < args.length; i++) {
            build(args[i]);
            emitDynBinOp(op);
        }
        return true;
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
            current.emitEnterScope();
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
                    current.emitDeclareNS(putConstant(prefix));
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
        current.emitNewXML(node.keys == null ? 0 : node.keys.length,
                           node.children == null ? 0 : node.children.length);

        if (namespaces != 0) {
            current.emitLeaveScope();
        }

        if (tmpSlots != null) {
            for (Slot slot : tmpSlots) {
                if (slot != null)
                    slot.release();
            }
        }
    }

    public void visit(ELNode.IN node) {
        build(node.left);
        build(node.right);
        current.emitIn();
        if (node.negative)
            current.emitNot();
    }

    public void visit(ELNode.INSTANCEOF node) {
        if (node.type.indexOf('.') == -1) {
            SymbolTable.Symbol sym = currentScope.lookup(node.type);
            if (sym != null && sym.def.expr instanceof ELNode.CLASSDEF) {
                emitPushSymbol(sym);
                build(node.right);
                emitInvokeMethod(ClassDefinition.class, "isInstance", ELContext.class,
                                 Object.class);
                if (node.negative)
                    current.emitNot();
                return;
            }
        }

        build(node.right);
        emitInstanceOf(node.type);
        if (node.negative)
            current.emitNot();
    }

    private void emitInstanceOf(Class<?> cls) {
        current.emitInstanceOf(putConstant(cls));
    }

    private void emitInstanceOf(String name) {
        int clsid;
        try {
            Class<?> cls = ClassResolver.getInstance(elctx).resolveClass(name);
            clsid = putConstant(cls);
        } catch (ClassNotFoundException e) {
            clsid = putConstant(name);
        }
        current.emitInstanceOf(clsid);
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
    public void visit(ELNode.IDEQ node)   { buildBinaryOp(node); }
    public void visit(ELNode.IDNE node)   { buildBinaryOp(node); }

    public void visit(ELNode.POS node)    { /* nop */ }
    public void visit(ELNode.NEG node)    { buildUnaryOp(node); }
    public void visit(ELNode.BITNOT node) { buildUnaryOp(node); }
    public void visit(ELNode.EMPTY node)  { buildUnaryOp(node); }

    private void buildBinaryOp(ELNode.Binary node) {
        build(node.left);
        build(node.right);
        emitDynBinOp(node.op);
    }

    private void emitDynBinOp(int op) {
        switch (op) {
            case Token.ADD    -> current.emitAdd();
            case Token.SUB    -> current.emitSub();
            case Token.MUL    -> current.emitMul();
            case Token.DIV    -> current.emitDiv();
            case Token.IDIV   -> current.emitIDiv();
            case Token.REM    -> current.emitRem();
            case Token.POW    -> current.emitPow();
            case Token.CAT    -> current.emitCat();
            case Token.SHL    -> current.emitShl();
            case Token.SHR    -> current.emitShr();
            case Token.USHR   -> current.emitUShr();
            case Token.BITAND -> current.emitBitAnd();
            case Token.BITOR  -> current.emitBitOr();
            case Token.XOR    -> current.emitXor();
            case Token.EQ     -> current.emitEq();
            case Token.NE     -> current.emitNe();
            case Token.LT     -> current.emitLt();
            case Token.LE     -> current.emitLe();
            case Token.GT     -> current.emitGt();
            case Token.GE     -> current.emitGe();
            case Token.IDEQ   -> current.emitIdEq();
            case Token.IDNE   -> current.emitIdNe();
            default -> throw new UnsupportedOperationException();
        }
    }

    private void buildUnaryOp(ELNode.Unary node) {
        build(node.right);
        emitDynUnOp(node.op);
    }

    private void emitDynUnOp(int op) {
        switch (op) {
        case Token.BITNOT -> current.emitBitNot();
        case Token.NEG    -> current.emitNeg();
        case Token.POS    -> { /* unary plus is a no-op: value already on stack */ }
        case Token.EMPTY  ->  current.emitEmpty();
        default -> throw new UnsupportedOperationException();
        }
    }

    public void visit(ELNode.PREFIX node) {
        if (node.oper.symbol != null) {
            if (node.oper.symbol.func != null) {
                buildDirectCall(node.oper.symbol, node.right);
            } else {
                build(node.oper);
                build(node.right);
                current.emitInvokeDyn(1);
            }
        } else {
            int nameIdx = putConstant(node.oper.id);
            build(node.right);
            current.emitInvokeOperator(nameIdx, 1);
        }
    }

    public void visit(ELNode.INFIX node) {
        if (node.oper.symbol != null) {
            if (node.oper.symbol.func != null) {
                buildDirectCall(node.oper.symbol, node.left, node.right);
            } else {
                build(node.oper);
                build(node.left);
                build(node.right);
                current.emitInvokeDyn(2);
            }
        } else {
            int nameIdx = putConstant(node.oper.id);
            build(node.left);
            build(node.right);
            current.emitInvokeOperator(nameIdx, 2);
        }
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
        int thenB = allocBlockId();
        int elseB = allocBlockId();
        int mergeB = allocBlockId();

        build(node.cond);
        current.emitJumpIfTrue(thenB);
        current.emitJump(elseB);

        startBlock(thenB);
        buildTail(node.left);
        current.emitJump(mergeB);

        startBlock(elseB);
        buildTail(node.right);
        current.emitJump(mergeB);

        startBlock(mergeB);
    }

    public void visit(ELNode.COALESCE node) {
        int contB = allocBlockId();
        build(node.left);
        current.emitDup();
        current.emitJumpIfNonNull(contB);
        current.emitPop();
        build(node.right);
        current.emitJump(contB);
        startBlock(contB);
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
        current.emitEq(K_INT);
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
        // All DEFINE nodes should carry a symbol annotation. Missing expr or symbol can
        // happen only on pattern or lambda parameters which are handled by pattern or
        // lambda compilation.  For normal definition they should always present.
        assert node.expr != null && node.symbol != null;

        // CLASS nodes (from import): push the raw Class constant
        if (node.expr instanceof ELNode.CLASS c) {
            Class<?> cls = resolveClassAtCompileTime(c.name);
            if (cls == null)
                throw reportError(c.pos, "class not found: " + c.name);
            buildConst(cls);
        } else {
            build(node.expr);
        }

        // Define global or local variable according to it's captured flag.
        if (node.symbol.captured) {
            current.emitDefineGlobal(putConstant(node.id));
            current.emitPushNull();
        } else {
            current.emitStoreVar(node.symbol.slot);
        }
    }

    public void visit(ELNode.EXPR node) {
        build(node.right);
    }

    public void visit(ELNode.Composite node) {
        if (node.elems.length == 0) {
            buildConst("");
        } else {
            build(node.elems);
            current.emitJoin(node.elems.length);
        }
    }

    public void visit(ELNode.COMPOUND node) {
        if (node.exps.length == 0) {
            current.emitPushNull();
            return;
        }

        for (int i = 0; i < node.exps.length - 1; i++) {
            if (current.isDead())
                return;
            if (!(node.exps[i] instanceof ELNode.Constant)) {
                build(node.exps[i]);
                current.emitPop();
            }
        }

        if (!current.isDead())
            buildTail(node.exps[node.exps.length - 1]);
    }

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
        build(node.body);
        current.emitPop();
        current.emitJump(header);

        startBlock(exit);
        current.emitPushNull();

        // Exit block falls through to next — add RETURN at toplevel by caller
        loopStack.pop();
    }

    public void visit(ELNode.FOR node) {
        int header = allocBlockId();
        int body = allocBlockId();
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

        startBlock(header);
        build(node.cond);
        current.emitJumpIfTrue(body);
        current.emitJump(exit);

        startBlock(body);
        if (!(node.body instanceof ELNode.NULL)) {
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
            current.emitLt(K_INT);
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
        current.emitAdd(K_LONG);
        idxSlot.store();
        current.emitPop();

        if (varSlot != null) {
            varSlot.load();
            buildConst(step);
            current.emitAdd(K_LONG);
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
            current.emitSub(K_LONG);
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
                current.emitSub(K_LONG);
            }
            varSlot.load();
            current.emitSub(K_LONG);
            if (stepSlot != null) {
                stepSlot.load();
                current.emitDiv(K_LONG);
            }
            buildConst(1L);
            current.emitAdd(K_LONG);
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
            current.emitLt(K_LONG);
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
        current.emitAdd(K_LONG);
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
        if (node.range instanceof ELNode.CONS) {
            emitInvokeMethod(Iterable.class, "iterator");
            iterSlot.store();
            current.emitPop();
        } else {
            emitInvokeStatic(Runtime.class, "getIterator", Object.class);
            iterSlot.store();
            current.emitJumpIfNull(exit);
        }
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
            current.emitAdd(K_INT);
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
        // Make sure single entry single exit.
        if (exitBlock == -1)
            exitBlock = allocBlockId();
        if (node.right != null) {
            buildTail(node.right);
            current.emitJump(exitBlock);
        } else {
            current.emitPushNull();
            current.emitJump(exitBlock);
        }
    }

    private void emitReturn() {
        if (exitBlock != -1)
            startBlock(exitBlock);
        current.emitReturn();
    }

    public void visit(ELNode.THROW node) {
        build(node.cause);
        current.emitThrow();
    }

    public void visit(ELNode.ASSERT node) {
        build(node.exp);
        if (node.msg != null)
            build(node.msg);
        current.emitAssert(node.msg == null ? 1 : 2);
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
            // For anonymous lambda, use a pseudo Symbol to store IRFunction skeleton
            // so call-site can emit direct call.
            func = new IRFunction("<lambda>", node.vars.length);
            ELNode.DEFINE tmpdef = new ELNode.DEFINE(node.pos, "", null, null, node);
            node.symbol = new SymbolTable.Symbol(node.scope, tmpdef);
            node.symbol.func = func;
        }

        if (program != null)
            program.add(func);

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
            }
        }

        nested.buildTail(node.body);
        nested.emitReturn();

        IRFunction fn = nested.finish();
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
                defs[i] = const_value(vars[i].expr);
            }
        }
        return defs;
    }

    private Object const_value(ELNode node) {
        if (node instanceof ELNode.NUMBER x)
            return x.value;
        if (node instanceof ELNode.STRINGVAL x)
            return x.value;
        if (node instanceof ELNode.CHARVAL x)
            return x.value;
        if (node instanceof ELNode.BOOLEANVAL x)
            return x.value;
        if (node instanceof ELNode.SYMBOL x)
            return x.value;
        if (node instanceof ELNode.REGEXP x)
            return x.value;
        if (node instanceof ELNode.NIL)
            return Cons.nil();
        if (node instanceof ELNode.NULL)
            return null;

        if (node instanceof ELNode.TUPLE x) {
            Object[] a = new Object[x.elems.length];
            for (int i = 0; i < a.length; i++)
                a[i] = const_value(x.elems[i]);
            return a;
        }

        if (node instanceof ELNode.CONS x && !x.delay) {
            Object h = const_value(x.head);
            Object t = const_value(x.tail);
            if (t instanceof Seq)
                return new Cons(h, (Seq)t);
        }

        throw reportError(node.pos, _T(EL_DEFAULT_VALUE_NOT_CONSTANT));
    }

    // ── Pattern matching ──

    /**
     * Compile a MATCH expression as a series of if-else chains.
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

        // Allocate blocks for each case entry point
        int[] nextCase = new int[node.alts.length + 1]; // +1 for default
        for (int ci = 0; ci < node.alts.length; ci++)
            nextCase[ci] = allocBlockId();
        nextCase[node.alts.length] = allocBlockId(); // default/error block

        int exitBlock = allocBlockId();

        // Jump to first case
        current.emitJump(nextCase[0]);

        for (int ci = 0; ci < node.alts.length; ci++) {
            ELNode.CASE c = node.alts[ci];
            int failBlock = nextCase[ci + 1];

            startBlock(nextCase[ci]);

            // Each case gets its own control scope for variable bindings.
            // On failure, leaveControlScope discards bindings.
            SymbolTable.Scope prevScope = currentScope;
            currentScope = c.scope;
            if (c.scope.hasCaptures())
                current.emitEnterScope();

            // Compile patterns for each column
            if (c.patterns != null) {
                for (int pi = 0; pi < c.patterns.length; pi++) {
                    argSlots[pi].load();
                    compileMatchPattern(argSlots[pi], (ELNode)c.patterns[pi], failBlock);
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

            // Leave the case scope
            if (c.scope.hasCaptures())
                current.emitLeaveScope();
            currentScope = prevScope;
        }

        // Default block
        startBlock(nextCase[node.alts.length]);
        if (node.deflt != null) {
            buildTail(node.deflt);
        } else {
            buildConst(_T(EL_PATTERN_NOT_MATCH));
            current.emitThrow();
        }
        current.emitJump(exitBlock);

        startBlock(exitBlock);
        for (Slot slot : argSlots)
            slot.release();
    }

    /**
     * Compile a single pattern check, leaving TRUE on stack if matched.
     */
    private void compileMatchPattern(Slot argSlot, ELNode pat, int failBlock) {
        if (pat instanceof ELNode.DEFINE def) {
            // Type check if annotated
            if (def.type != null) {
                emitInstanceOf(def.type);
                current.emitJumpIfFalse(failBlock);
            }

            // As-pattern check
            if (def.expr != null) {
                if (def.type != null)
                    argSlot.load();
                compileMatchPattern(argSlot, def.expr, failBlock);
                current.emitJumpIfFalse(failBlock);
            }

            // Wildcard: always matches
            if ("_".equals(def.id)) {
                current.emitPop();
                current.emitPushTrue();
                return;
            }

            // Variable binding -> bind to new pattern variable.
            if (def.type != null || def.expr != null)
                argSlot.load();
            if (def.symbol.captured)
                current.emitDefineGlobal(putConstant(def.id));
            else
                current.emitStoreVarPop(def.symbol.slot);
            current.emitPushTrue();
            return;
        }

        if (pat instanceof ELNode.IDENT var) {
            if (var.symbol.captured)
                current.emitPushGlobal(putConstant(var.id));
            else
                current.emitPushVar(var.symbol.slot);
            emitDynBinOp(Token.EQ);
            return;
        }

        if (pat instanceof ELNode.NOT not) {
            compileMatchPattern(argSlot, not.right, failBlock);
            current.emitNot();
            return;
        }

        if (pat instanceof ELNode.OR or) {
            int tryRight = allocBlockId();
            int done = allocBlockId();

            // Left branch, argSlot already on stack top.
            compileMatchPattern(argSlot, or.left, tryRight);
            current.emitJumpIfFalse(tryRight);
            current.emitJump(done); // matched -> skip right

            startBlock(tryRight);
            argSlot.load();
            compileMatchPattern(argSlot, or.right, failBlock);
            current.emitJumpIfFalse(failBlock);
            current.emitJump(done);
            startBlock(done);
            current.emitPushTrue();
            return;
        }

        if (pat instanceof ELNode.NUMBER n) {
            buildConst(n.value);
            emitDynBinOp(Token.EQ);
            return;
        }

        if (pat instanceof ELNode.STRINGVAL s) {
            buildConst(s.value);
            emitDynBinOp(Token.EQ);
            return;
        }

        if (pat instanceof ELNode.BOOLEANVAL b) {
            buildConst(b.value);
            emitDynBinOp(Token.EQ);
            return;
        }

        if (pat instanceof ELNode.CHARVAL c) {
            buildConst(c.value);
            emitDynBinOp(Token.EQ);
            return;
        }

        if (pat instanceof ELNode.NULL) {
            current.emitJumpIfNonNull(failBlock);
            current.emitPushTrue();
            return;
        }

        if (pat instanceof ELNode.SYMBOL sym) {
            buildConst(sym.value);
            current.emitIdEq();
            return;
        }

        if (pat instanceof ELNode.CLASS cls) {
            emitInstanceOf(cls.name);
            return;
        }

        if (pat instanceof ELNode.REGEXP re) {
            emitInstanceOf(String.class);
            current.emitJumpIfFalse(failBlock);
            buildConst(re.value); // the pattern
            argSlot.load();                  // the string to match
            emitInvokeMethod(java.util.regex.Pattern.class, "matcher", CharSequence.class);
            emitInvokeMethod(java.util.regex.Matcher.class, "matches");
            return;
        }

        if (pat instanceof ELNode.EXPR e) {
            build(e.right);
            emitDynBinOp(Token.EQ);
            return;
        }

        if (pat instanceof ELNode.TUPLE t) {
            Slot tmpSlot = null;

            emitInvokeMethod(Object.class, "getClass");
            emitInvokeMethod(Class.class, "isArray");
            current.emitJumpIfFalse(failBlock);

            argSlot.load();
            emitInvokeStatic(Array.class, "getLength", Object.class);
            buildConst(t.elems.length);
            current.emitEq(K_INT);
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
                compileMatchPattern(tmpSlot, t.elems[i], failBlock);
                current.emitJumpIfFalse(failBlock);
            }
            release(tmpSlot);
            current.emitPushTrue();
            return;
        }

        if (pat instanceof ELNode.CONS cons) {
            Slot seqSlot = new Slot();
            Slot tmpSlot = null;
            if (!isSimplePattern(cons.head) || !isSimplePattern(cons.tail))
                tmpSlot = new Slot();

            emitInstanceOf(List.class);
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
            compileMatchPattern(tmpSlot, cons.head, failBlock);
            current.emitJumpIfFalse(failBlock);

            seqSlot.load();
            emitInvokeMethod(Seq.class, "tail");
            if (!isSimplePattern(cons.tail))
                tmpSlot.store();
            compileMatchPattern(tmpSlot, cons.tail, failBlock);
            current.emitJumpIfFalse(failBlock);

            release(tmpSlot);
            release(seqSlot);
            current.emitPushTrue();
            return;
        }

        if (pat instanceof ELNode.NIL) {
            emitDynUnOp(Token.EMPTY);
            return;
        }

        if (pat instanceof ELNode.RANGE) {
            current.emitPop(); // we will re-push arg after build tuple
            build(pat);
            argSlot.load();
            emitInvokeMethod(List.class, "contains", Object.class);
            return;
        }

        if (pat instanceof ELNode.MAP map) {
            Slot tmpSlot = null;
            for (int i = 0; i < map.keys.length; i++) {
                assert map.keys[i] instanceof ELNode.STRINGVAL;
                buildConst(((ELNode.STRINGVAL)map.keys[i]).value);
                current.emitLoadProperty();
                if (!isSimplePattern(map.values[i])) {
                    if (tmpSlot == null)
                        tmpSlot = new Slot();
                    tmpSlot.store();
                }
                compileMatchPattern(tmpSlot, map.values[i], failBlock);
                current.emitJumpIfFalse(failBlock);
                if (i != map.keys.length - 1)
                    argSlot.load();
            }
            release(tmpSlot);
            current.emitPushTrue();
            return;
        }

        if (pat instanceof ELNode.NEW data) {
            ELNode.IDENT base = (ELNode.IDENT)data.base;
            ELNode[] args = data.args;
            int argc = args.length;

            if (base.symbol != null &&
                base.symbol.def.expr instanceof ELNode.CLASSDEF cdef) {
                Slot cdefSlot = new Slot(base);
                Slot targetSlot = new Slot();
                Slot tmpSlot = null;

                if (data.keys == null && cdef.vars == null || cdef.vars.length != argc) {
                    current.emitPushFalse();
                    return;
                }

                emitInstanceOf(ClosureObject.class);
                current.emitJumpIfFalse(failBlock);

                cdefSlot.load();
                argSlot.load();
                emitInvokeMethod(ClosureObject.class, "get_owner");
                targetSlot.store();
                emitInvokeMethod(ClosureObject.class, "get_class");
                emitInvokeMethod(ClassDefinition.class, "isAssignableFrom",
                    ELContext.class, ClassDefinition.class);
                current.emitJumpIfFalse(failBlock);

                if (argc == 0) {
                    current.emitPushTrue();
                    return;
                }

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
                        compileMatchPattern(tmpSlot, args[i], failBlock);
                        current.emitJumpIfFalse(failBlock);
                    }
                } else {
                    // matches for constructor variables
                    targetSlot.load();
                    emitInvokeMethod(ClosureObject.class, "get_this");
                    targetSlot.store();

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
                        compileMatchPattern(tmpSlot, arg, failBlock);
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

                if (base.symbol != null && base.symbol.def.expr instanceof ELNode.CLASS c) {
                    className = c.name;
                    slots = c.slots;
                } else {
                    className = base.id;
                }

                Class<?> cls = resolveClassAtCompileTime(className);
                if (cls == null) {
                    emitInstanceOf(className);
                    return;
                }

                if (argc == 0) {
                    emitInstanceOf(cls);
                    return;
                }

                if (data.keys != null) {
                    slots = data.keys;
                } else {
                    if (slots == null) {
                        Data d = cls.getAnnotation(Data.class);
                        if (d != null)
                            slots = d.value();
                    }
                    if (slots == null || slots.length != argc) {
                        current.emitPushFalse();
                        return;
                    }
                }

                emitInstanceOf(cls);
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
                    compileMatchPattern(tmpSlot, args[i], failBlock);
                    current.emitJumpIfFalse(failBlock);
                }
                release(tmpSlot);
            }

            current.emitPushTrue();
            return;
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

        int failBlock = allocBlockId();
        int exitBlock = allocBlockId();

        argSlot.load();
        compileMatchPattern(argSlot, node.left, failBlock);
        current.emitJumpIfFalse(failBlock);
        current.emitJump(exitBlock);

        startBlock(failBlock);
        buildConst("pattern not match");
        current.emitThrow();
        current.emitJump(exitBlock);

        startBlock(exitBlock);
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
            if (var.symbol.def.expr instanceof ELNode.CLASSDEF) {
                // Load the ClassDefinition.
                build(node.base);

                // Invoke ClassDefinition.invoke with arguments.
                // FIXME: handle named arguments for constructor
                build(node.args);
                current.emitNewTuple(node.args.length);
                emitInvokeMethod(ClassDefinition.class, "invoke", ELContext.class, Object[].class);
                return;
            }

            if (var.symbol.def.expr instanceof ELNode.CLASS c && c.slots == null) {
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
        current.emit(TRAMPOLINE, 0, poolIdx);
    }

    // ── Block management ──

    private int allocBlockId() {
        return nextBlockId++;
    }

    /**
     * Seal current block into blockMap and start a new block with the given ID.
     */
    private void startBlock(int blockId) {
        assert blockId != currentBlockId;
        int[] code = current.toArray();
        blocks.add(new Block(currentBlockId, code, linePcMapping));
        current.clear();
        currentBlockId = blockId;
        linePcMapping.clear();
    }

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
            if (captured) {
                current.emitDefineGlobal(slot);
                current.emitPushNull();
            } else {
                current.emitStoreVar(slot);
            }
        }

        void store() {
            if (captured)
                current.emitStoreGlobal(slot);
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

    IRFunction finish() {
        // Finish current block.
        int[] code = current.toArray();
        blocks.add(new Block(currentBlockId, code, linePcMapping));

        // Scan instructions to build CFG.
        Map<Integer, Block> blockMap = new HashMap<>();
        for (Block block : blocks)
            blockMap.put(block.id, block);
        for (Block block : blocks) {
            for (var v = new InstructionView(block.code); v.inBounds(); v.advance()) {
                if (v.isJump()) {
                    Block target = blockMap.get(v.jumpTarget());
                    block.successors.set(target.id);
                    target.predecessors.set(block.id);
                }
            }
        }

        // Run optimization passes.
        boolean opt = ELProgram.OPT_LEVEL != 0;
        if (opt) {
            jumpThreadingOpt(blockMap);
            deadBlockElim(blockMap);
            deadStoreElim();
        }

        // Merge block into contiguous code.
        IntList merged = new IntList();
        for (Block block : blocks) {
            if (block.id == 0 || !block.predecessors.isEmpty()) {
                // Swap jump condition to make fallthrough opportunity.
                if (merged.size() >= 2) {
                    var v1 = new InstructionView(merged.data(), merged.size() - 2, merged.size());
                    var v2 = v1.peek();
                    if (v1.isJump() && v1.opcode() != JUMP &&
                        v1.jumpTarget() == block.id && // fallthrough
                        v2.opcode() == JUMP) {
                        int target1 = v1.jumpTarget();
                        int target2 = v2.jumpTarget();
                        v1.replace(inverseJump(v1.opcode()), 0, target2);
                        v2.replace(JUMP, 0, target1);
                    }
                }

                // Remove fallthrough jump and NOPs and end of previous block.
                boolean fallthrough = false;
                int term = merged.back();
                if (IRFormat.opcode(term) == JUMP && IRFormat.operand(term) == block.id) {
                    merged.reset(merged.size() - 1);
                    while (!merged.isEmpty() && IRFormat.opcode(merged.back()) == NOP)
                        merged.reset(merged.size() - 1);
                    fallthrough = true;
                }

                block.pc = merged.size();

                if ((fallthrough && block.predecessors.cardinality() == 1) ||
                    ELProgram.OPT_LEVEL == 3) {
                    // Run full peephole optimizer on merged code.
                    // FIXME: peephole opt can merge code, this will ruin
                    //  debug line table
                    InstructionView v = new InstructionView(block.code);
                    for (; v.inBounds(); v.advance()) {
                        boolean cjump = v.isJump() && v.opcode() != JUMP;
                        if (peephole.run(merged, v.opcode(), v.operand())) {
                            // A conditional jump is optimized to unconditional
                            // jump, the current block is dead.
                            if (cjump && IRFormat.opcode(merged.back()) == JUMP)
                                break;
                            continue;
                        }
                        merged.add(v.header());
                    }
                } else {
                    merged.addAll(block.code);
                }
            }
        }

        int[] offsets = buildBlockOffsets(merged);

        func.populate(merged.toArray(), maxLocals, offsets,
                      constants.toArray(new Object[0]),
                      buildDebugInfo(), null);
        return func;
    }

    private static int inverseJump(int opcode) {
        return switch (opcode) {
            case JUMP_IF_TRUE -> JUMP_IF_FALSE;
            case JUMP_IF_FALSE -> JUMP_IF_TRUE;
            case JUMP_IF_NULL -> JUMP_IF_NONNULL;
            case JUMP_IF_NONNULL -> JUMP_IF_NULL;
            default -> opcode;
        };
    }

    private void jumpThreadingOpt(Map<Integer, Block> blockMap) {
        Map<Integer, Integer> threadingJumps = new HashMap<>();
        for (Block block : blocks) {
            // If the only instruction in a block is a jump, threading
            // jumps to target.
            if (block.code.size() == 1) {
                int header = block.code.get(0);
                if (IRFormat.opcode(header) == JUMP) {
                    int target = IRFormat.operand(header);
                    threadingJumps.put(block.id, target);
                    threadingJumps.replaceAll((k, v) -> v == block.id ? target : v);
                }
            }
        }

        // Update CFG.
        for (Map.Entry<Integer, Integer> e : threadingJumps.entrySet()) {
            Block from = blockMap.get(e.getKey());
            Block to = blockMap.get(e.getValue());
            for (int i = from.predecessors.nextSetBit(0); i >= 0;
                 i = from.predecessors.nextSetBit(i+1)) {
                Block pred = blockMap.get(i);
                pred.successors.clear(from.id);
                pred.successors.set(to.id);
                to.predecessors.set(pred.id);
            }
            from.predecessors.clear(); // dead
        }

        // Apply all threading jumps. May produce dead blocks.
        if (!threadingJumps.isEmpty()) {
            for (Block block : blocks) {
                InstructionView v = new InstructionView(block.code);
                for (; v.inBounds(); v.advance()) {
                    if (v.isJump()) {
                        int target = threadingJumps.getOrDefault(v.jumpTarget(), -1);
                        if (target != -1)
                            v.replace(v.opcode(), 0, target);
                    }
                }
            }
        }
    }

    private void deadBlockElim(Map<Integer, Block> blockMap) {
        BitSet visited = new BitSet();
        boolean changed;
        do {
            changed = false;
            for (Block block : blocks) {
                if (visited.get(block.id))
                    continue;
                if (block.id != 0 && block.predecessors.isEmpty()) {
                    // Make all successors dead.
                    for (int i = block.successors.nextSetBit(0); i >= 0;
                         i = block.successors.nextSetBit(i+1)) {
                        Block succ = blockMap.get(i);
                        succ.predecessors.clear(block.id);
                    }
                    visited.set(block.id);
                    changed = true;
                }
            }
        } while (changed);
    }

    /**
     * Eliminate dead stores: STORE_VAR/STORE_VAR_POP whose slot is never
     * read (no matching PUSH_VAR).  Dead STORE_VAR becomes NOP (value
     * stays on stack), dead STORE_VAR_POP becomes POP.  Unused slots are
     * then compacted away via remapping.
     */
    private void deadStoreElim() {
        // Collect used slots.
        BitSet usedSlots = new BitSet();
        Block pred = null;
        for (Block block : blocks) {
            if (block.id != 0 && block.predecessors.isEmpty())
                continue;
            final int[] data = block.code.data();
            final int n = block.code.size();
            for (int i = 0; i < n; i++) {
                // We only check read slot, write only slots are dead.
                int inst = data[i];
                if (IRFormat.opcode(inst) == PUSH_VAR) {
                    // Look back in previous block. If the previous block ends
                    // with a STORE_VAR_POP and fallthrough to this block. The
                    // STORE_VAR_POP and PUSH_VAR will fold to STORE_VAR.
                    // The PUSH_VAR is eliminated, so this is not a REAL load.
                    // The STORE_VAR_POP and PUSH_VAR should be removed together.
                    // But we can't do this here, we must scan for other REAL
                    // load to determine whether the pair can be eliminated.
                    int varIndex = IRFormat.operand(inst);
                    if (i == 0 && pred != null && pred.code.size() >= 2 &&
                        block.predecessors.cardinality() == 1 &&
                        block.predecessors.get(pred.id) &&
                        IRFormat.match(pred.code.back(1), STORE_VAR_POP, varIndex)) {
                        // We set a flag in instruction payload to indicates this
                        // situation, the next scan can determine whether the instruction
                        // can be removed or keep.
                        pred.code.set(pred.code.size() - 2,
                                      IRFormat.pack(STORE_VAR_POP, 1, varIndex));
                        block.code.set(0, IRFormat.pack(PUSH_VAR, 1, varIndex));
                    } else {
                        usedSlots.set(varIndex);
                    }
                }
            }
            pred = block;
        }

        // Preserve function parameters.
        usedSlots.set(0, func.paramCount());

        // Remove unused slots.
        int[] slotMap = new int[maxLocals];
        int nextSlot = 0;
        for (int i = 0; i < maxLocals; i++) {
            if (usedSlots.get(i))
                slotMap[i] = nextSlot++;
            else
                slotMap[i] = -1;
        }

        // Nothing to remap if all slots are used and contiguous.
        if (nextSlot == maxLocals)
            return;

        // Remap slot indices and remove dead stores.
        for (Block block : blocks) {
            if (block.id != 0 && block.predecessors.isEmpty())
                continue;

            final int[] data = block.code.data();
            final int n = block.code.size();
            for (int i = 0; i < n; i++) {
                int inst = data[i];
                int op = IRFormat.opcode(inst);
                int varIndex = IRFormat.operand(inst);

                switch (op) {
                case PUSH_VAR:
                    if ((varIndex = slotMap[varIndex]) == -1) {
                        // This must be a flagged instruction to be removed
                        // when merged into previous block that ends with
                        // STORE_VAR_POP.
                        assert IRFormat.payload(inst) != 0;
                        data[i] = IRFormat.pack(NOP, 0, 0);
                    } else {
                        data[i] = IRFormat.pack(PUSH_VAR, 0, varIndex);
                    }
                    break;

                case STORE_VAR:
                    if ((varIndex = slotMap[varIndex]) == -1) {
                        // Remove dead store.
                        data[i] = IRFormat.pack(NOP, 0, 0);
                    } else {
                        data[i] = IRFormat.pack(STORE_VAR, 0, varIndex);
                    }
                    break;

                case STORE_VAR_POP:
                    if ((varIndex = slotMap[varIndex]) == -1) {
                        // Dead store and pop.
                        if (IRFormat.payload(inst) != 0) {
                            // This flagged instruction need to be removed when
                            // merged into fallthrough block with PUSH_VAR.
                            data[i] = IRFormat.pack(NOP, 0, 0);
                        } else if (i != 0 && switch (IRFormat.opcode(data[i-1])) {
                                      case PUSH_NULL, PUSH_CONST, PUSH_TRUE, PUSH_FALSE,
                                           PUSH_VAR, PUSH_GLOBAL, DUP, CLOSURE, NIL -> true;
                                      default -> false;
                                      }) {
                            // Remove PUSH and dead store.
                            data[i-1] = data[i] = IRFormat.pack(NOP, 0, 0);
                        } else {
                            // Replace dead store with POP.
                            data[i] = IRFormat.pack(POP, 0, 0);
                        }
                    } else {
                        data[i] = IRFormat.pack(STORE_VAR_POP, 0, varIndex);
                    }
                    break;
                }
            }
        }

        maxLocals = nextSlot;
    }

    private int[] buildBlockOffsets(IntList code) {
        // Get all reachable blocks.
        BitSet targets = new BitSet();
        targets.set(0);
        for (var v = new InstructionView(code.data(), 0, code.size());
             v.inBounds(); v.advance()) {
            if (v.isJump())
                targets.set(v.jumpTarget());
        }

        // Remove hole in block IDs.
        List<Block> compactBlocks = new ArrayList<>();
        Map<Integer, Integer> remap = new HashMap<>();
        Map<Integer, Integer> pcMap = new HashMap<>();
        for (Block block : blocks) {
            if (targets.get(block.id)) {
                int dupId = pcMap.getOrDefault(block.pc, -1);
                if (dupId != -1) {
                    block.mappedId = dupId;
                    remap.put(block.id, block.mappedId);
                } else {
                    block.mappedId = compactBlocks.size();
                    compactBlocks.add(block);
                    remap.put(block.id, block.mappedId);
                    pcMap.put(block.pc, block.mappedId);
                }
            }
        }

        // Remap block IDs after dead block eliminated.
        for (var v = new InstructionView(code.data(), 0, code.size());
             v.inBounds(); v.advance()) {
            if (v.isJump()) {
                int mappedId = remap.get(v.jumpTarget());
                v.replace(v.opcode(), 0, mappedId);
            }
        }


        // Build offset table.
        int[] offsets = new int[compactBlocks.size()];
        for (Block block : compactBlocks) {
            offsets[block.mappedId] = block.pc;
        }
        return offsets;
    }

    private DebugInfo buildDebugInfo() {
        // Consolidate debug info.
        SortedMap<Integer, Integer> pcLineMapping = new TreeMap<>();
        for (Block block : blocks) {
            if (block.id != 0 && block.predecessors.isEmpty())
                continue;
            for (var kv : block.lineMap.entrySet()) {
                pcLineMapping.put(kv.getValue() + block.pc, kv.getKey());
            }
        }

        // Build the pc to line mapping table.
        IntList pcLineTable = new IntList();
        for (var kv : pcLineMapping.entrySet()) {
            pcLineTable.add(kv.getKey());
            pcLineTable.add(kv.getValue());
        }

        return new DebugInfo(currentFile, pcLineTable.toArray());
    }

    // ── Convenience emits ──

    int putConstant(Object value) {
        return constIndex.computeIfAbsent(value, k -> {
            constants.add(k);
            return constants.size() - 1;
        });
    }

    Object getConstant(int index) {
        return constants.get(index);
    }

    private void buildConst(Object value) {
        int idx = putConstant(value);
        current.emitPushConst(idx);
    }

    private void buildConst(Boolean value) {
        if (value)
            current.emitPushTrue();
        else
            current.emitPushFalse();
    }

    private void emitPushSymbol(SymbolTable.Symbol sym) {
        if (sym.captured) {
            current.emitPushGlobal(putConstant(sym.name));
        } else {
            current.emitPushVar(sym.slot);
        }
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
        IRBuilder b = new IRBuilder(elctx, null, func, symTable.currentScope());
        b.build(node);
        b.current.emitReturn();
        return b.finish();
    }

    public static IRProgram compile(ELContext elctx, ELProgram program) {
        SymbolTable symTable = SymbolTableBuilder.build(program);
        reportSymbolTableError(program, symTable);

        List<ELNode> defs = program.getDefinitions();
        List<ELNode> exps = program.getExpressions();

        IRBytecodeCompiler.resetState();

        IRFunction func = new IRFunction("<program>", 0);
        IRProgram output = new IRProgram(func);
        IRBuilder b = new IRBuilder(elctx, output, func, symTable.currentScope());
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
        if (!exps.isEmpty()) {
            for (int i = 0; i < exps.size() - 1; i++) {
                b.build(exps.get(i));
                b.current.emitPop();
            }
            b.build(exps.get(exps.size() - 1));
            b.emitReturn();
        } else {
            b.current.emitPushNull();
            b.emitReturn();
        }

        b.finish();
        return output;
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
