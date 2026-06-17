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

import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.eval.EvaluationContext;
import org.operamasks.el.eval.Ranges;
import org.operamasks.el.eval.TypeCoercion;
import org.operamasks.el.eval.closure.ClosureObject;
import org.operamasks.el.eval.closure.LiteralClosure;
import org.operamasks.el.eval.closure.MethodClosure;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Position;
import org.operamasks.el.parser.Token;
import org.operamasks.el.resolver.MethodResolver;

import org.operamasks.el.eval.VariableMapperImpl;

import javax.el.ELContext;
import javax.el.ValueExpression;
import javax.el.VariableMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;

import static org.operamasks.el.ir.Opcode.*;
import static org.operamasks.el.resources.Resources.*;

/**
 * Stack-based interpreter for ELite IR.
 * <p>
 * Executes a linear int[] instruction stream using a switch-dispatch loop
 * and an operand stack. This replaces the recursive tree-walking of the
 * AST evaluator.
 *
 * <p>Dynamically-typed operations and complex features delegate to the
 * existing AST evaluator via the "trampoline" mechanism (opcode 0xE0
 * (TRAMPOLINE)).
 * As more operations are specialized, fewer trampolines are needed.
 */
public class IRInterpreter {

    // ── Key tuning parameters ──
    private static final int DEFAULT_STACK_SIZE = 256;
    private static final int DEFAULT_LOCALS_SIZE = 64;

    // ── Instance state ──
    private final ELContext elctx;
    private final IRFunction function;
    private final int[] code;
    private final Object[] constantPool;
    private final int[] blockOffsets;

    // ── Execution state ──
    private Object[] stack;
    private int sp;            // stack pointer (points to next free slot)
    private Object[] locals;
    private int ip;  // instruction pointer (absolute offset into code[])

    // ── Trampoline support ──
    private EvaluationContext evalContext;
    // ── Debug support ──
    private final boolean debug;
    private org.operamasks.el.eval.Frame frame; // current stack frame (debug only)

    public IRInterpreter(ELContext elctx, IRFunction function) {
        this(elctx, function, null);
    }

    /**
     * Create an interpreter that inherits variable bindings from an existing
     * EvaluationContext.
     */
    public IRInterpreter(ELContext elctx, IRFunction function,
                         EvaluationContext parentEnv) {
        this.elctx = elctx;
        this.function = function;
        this.code = function.code();
        this.constantPool = function.constantPool();
        this.blockOffsets = function.blockOffsets();
        this.debug = org.operamasks.el.eval.ELProgram.DEBUG;
        if (parentEnv == null)
            parentEnv = (EvaluationContext)elctx.getContext(EvaluationContext.class);
        this.evalContext = parentEnv != null ? parentEnv :
                           new EvaluationContext(elctx);
    }

    // ── Entry point ──

    public Object execute(Object[] args) {
        return execute(args, false);
    }

    public Object execute(Object[] args, boolean isTopLevel) {
        this.stack = new Object[DEFAULT_STACK_SIZE];
        this.sp = 0;
        this.locals = new Object[DEFAULT_LOCALS_SIZE];

        // evalContext is set by constructor

        // Bind arguments to locals (grow array if paramCount exceeds default)
        int needed = Math.max(function.paramCount(), args != null ? args.length : 0);
        if (needed > locals.length) growLocals(needed);
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                locals[i] = args[i];
            }
        }

        // Fill missing parameters with default values
        Object[] defs = function.defaultValues();
        if (defs != null) {
            int provided = args != null ? args.length : 0;
            for (int i = provided; i < function.paramCount(); i++) {
                if (defs[i] != null)
                    locals[i] = defs[i];
            }
        }

        // Start at first block
        ip = blockOffsets.length > 0 ? blockOffsets[0] : 0;

        // Ensure lazy sequences (DelaySeq, MappendSeq, etc.) can access the
        // ELContext via ELEngine.getCurrentELContext() when forced outside of
        // a Frame.addFrame() scope (e.g. by Java code calling .size() on a
        // lazy sequence returned from eval).
        javax.el.ELContext savedElCtx = ELEngine.setCurrentELContext(elctx);

        // Scope management:
        // - Top-level program: no pushContext — variables go directly
        //   into the program-level evalContext.
        // - Functions: pushContext() with head=null allows the child
        //   context to traverse into parent bindings for captured variable
        //   updates (setVariable finds and updates parent's Variable in place).
        if (!isTopLevel) {
            evalContext = evalContext.pushContext();
        }

        // Debug: push a stack frame for this function call
        if (debug && !isTopLevel) {
            DebugInfo di = function.debugInfo();
            String fnName = di.functionName() != null ? di.functionName() : function.name();
            String fileName = di.fileName();
            int blockPos = di.positionForBlock(0);
            frame = org.operamasks.el.eval.StackTrace.addFrame(elctx, fnName, fileName, blockPos);
        }

        // Store current EvaluationContext on ELContext so invokeTarget
        // can retrieve it for nested IRClosure/IRFunction calls.
        Object savedCtx = elctx.getContext(EvaluationContext.class);
        elctx.putContext(EvaluationContext.class, evalContext);

        try {
            return interpret();
        } catch (RuntimeException e) {
            if (debug && !(e instanceof org.operamasks.el.eval.EvaluationException)
                && !(e instanceof org.operamasks.el.eval.Control)) {
                // Update frame position to error location
                if (frame != null) {
                    DebugInfo di = function.debugInfo();
                    int line = di.lineForPC(ip);
                    if (line > 0) {
                        frame.setPos(org.operamasks.el.parser.Position.make(line, 1));
                    }
                }
                throw new org.operamasks.el.eval.EvaluationException(elctx, e);
            }
            throw e;
        } finally {
            if (frame != null) {
                org.operamasks.el.eval.StackTrace.removeFrame(elctx);
                frame = null;
            }
            if (savedCtx != null)
                elctx.putContext(EvaluationContext.class, savedCtx);
            ELEngine.setCurrentELContext(savedElCtx);
        }
    }

    // ── Main interpreter loop ──

    private Object interpret() {
        for (; ; ) {
            int header = code[ip];
            int op = IRFormat.opcode(header);
            int oc = IRFormat.opCount(header);
            int pl = IRFormat.payload(header);

            switch (op) {
                // ============ Stack ============
                case PUSH_CONST: {
                    int idx = oc == 0 ? pl : code[ip + 1];
                    push(constantPool[idx]);
                    ip += 1 + oc;
                    break;
                }
                case PUSH_VAR: {
                    int idx = pl & 0xFF;
                    ensureLocals(idx);
                    push(locals[idx]);
                    ip += 1;
                    break;
                }
                case POP: {
                    pop();
                    ip += 1;
                    break;
                }
                case DUP: {
                    push(peek());
                    ip += 1;
                    break;
                }
                case POP_N: {
                    sp -= pl;
                    ip += 1;
                    break;
                }

                // ============ Typed int arithmetic ============
                case IADD: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l + r);
                    ip += 1;
                    break;
                }
                case ISUB: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l - r);
                    ip += 1;
                    break;
                }
                case IMUL: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l * r);
                    ip += 1;
                    break;
                }
                case IDIV: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l / r);
                    ip += 1;
                    break;
                }
                case IREM: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l % r);
                    ip += 1;
                    break;
                }
                case INEG: {
                    int v = ((Number)pop()).intValue();
                    push(-v);
                    ip += 1;
                    break;
                }

                // ============ Typed long arithmetic ============
                case LADD: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l + r);
                    ip += 1;
                    break;
                }
                case LSUB: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l - r);
                    ip += 1;
                    break;
                }
                case LMUL: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l * r);
                    ip += 1;
                    break;
                }
                case LDIV: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l / r);
                    ip += 1;
                    break;
                }
                case LREM: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l % r);
                    ip += 1;
                    break;
                }
                case LNEG: {
                    long v = ((Number)pop()).longValue();
                    push(-v);
                    ip += 1;
                    break;
                }

                // ============ Typed double arithmetic ============
                case DADD: {
                    double r = ((Number)pop()).doubleValue();
                    double l = ((Number)pop()).doubleValue();
                    push(l + r);
                    ip += 1;
                    break;
                }
                case DSUB: {
                    double r = ((Number)pop()).doubleValue();
                    double l = ((Number)pop()).doubleValue();
                    push(l - r);
                    ip += 1;
                    break;
                }
                case DMUL: {
                    double r = ((Number)pop()).doubleValue();
                    double l = ((Number)pop()).doubleValue();
                    push(l * r);
                    ip += 1;
                    break;
                }
                case DDIV: {
                    double r = ((Number)pop()).doubleValue();
                    double l = ((Number)pop()).doubleValue();
                    push(l / r);
                    ip += 1;
                    break;
                }
                case DNEG: {
                    double v = ((Number)pop()).doubleValue();
                    push(-v);
                    ip += 1;
                    break;
                }

                // ============ Power ============
                case IPOW: {
                    int e = (Integer)pop();
                    int b = (Integer)pop();
                    push((int)Math.pow(b, e));
                    ip += 1;
                    break;
                }
                case LPOW: {
                    long e = ((Number)pop()).longValue();
                    long b = ((Number)pop()).longValue();
                    push((long)Math.pow(b, e));
                    ip += 1;
                    break;
                }
                case DPOW: {
                    double e = ((Number)pop()).doubleValue();
                    double b = ((Number)pop()).doubleValue();
                    push(Math.pow(b, e));
                    ip += 1;
                    break;
                }

                // ============ Typed bitwise (int) ============
                case IAND: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l & r);
                    ip += 1;
                    break;
                }
                case IOR: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l | r);
                    ip += 1;
                    break;
                }
                case IXOR: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l ^ r);
                    ip += 1;
                    break;
                }
                case ISHL: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l << r);
                    ip += 1;
                    break;
                }
                case ISHR: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l >> r);
                    ip += 1;
                    break;
                }
                case IUSHR: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l >>> r);
                    ip += 1;
                    break;
                }
                case IBITNOT: {
                    int v = ((Number)pop()).intValue();
                    push(~v);
                    ip += 1;
                    break;
                }

                // ============ Typed bitwise (long) ============
                case LAND: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l & r);
                    ip += 1;
                    break;
                }
                case LOR: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l | r);
                    ip += 1;
                    break;
                }
                case LXOR: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l ^ r);
                    ip += 1;
                    break;
                }
                case LSHL: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l << r);
                    ip += 1;
                    break;
                }
                case LSHR: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l >> r);
                    ip += 1;
                    break;
                }
                case LUSHR: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l >>> r);
                    ip += 1;
                    break;
                }
                case LBITNOT: {
                    long v = ((Number)pop()).longValue();
                    push(~v);
                    ip += 1;
                    break;
                }

                // ============ Dynamic arithmetic ============
                case DYNADD:
                case DYNSUB:
                case DYNMUL:
                case DYNDIV:
                case DYNREM:
                case DYNNEG:
                case DYNPOW:
                case DYNCAT:
                case DYNEQ:
                case DYNLT:
                case DYNLE: {
                    push(dynamicOp(op));
                    ip += 1;
                    break;
                }

                // ============ Typed int comparisons ============
                case IEQ: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l == r);
                    ip += 1;
                    break;
                }
                case INE: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l != r);
                    ip += 1;
                    break;
                }
                case ILT: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l < r);
                    ip += 1;
                    break;
                }
                case ILE: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l <= r);
                    ip += 1;
                    break;
                }
                case IGT: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l > r);
                    ip += 1;
                    break;
                }
                case IGE: {
                    int r = ((Number)pop()).intValue();
                    int l = ((Number)pop()).intValue();
                    push(l >= r);
                    ip += 1;
                    break;
                }

                case LEQ: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l == r);
                    ip += 1;
                    break;
                }
                case LNE: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l != r);
                    ip += 1;
                    break;
                }
                case LLT: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l < r);
                    ip += 1;
                    break;
                }
                case LLE: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l <= r);
                    ip += 1;
                    break;
                }
                case LGT: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l > r);
                    ip += 1;
                    break;
                }
                case LGE: {
                    long r = ((Number)pop()).longValue();
                    long l = ((Number)pop()).longValue();
                    push(l >= r);
                    ip += 1;
                    break;
                }

                case DEQ: {
                    double r = ((Number)pop()).doubleValue();
                    double l = ((Number)pop()).doubleValue();
                    push(l == r);
                    ip += 1;
                    break;
                }
                case DNE: {
                    double r = ((Number)pop()).doubleValue();
                    double l = ((Number)pop()).doubleValue();
                    push(l != r);
                    ip += 1;
                    break;
                }
                case DLT: {
                    double r = ((Number)pop()).doubleValue();
                    double l = ((Number)pop()).doubleValue();
                    push(l < r);
                    ip += 1;
                    break;
                }
                case DLE: {
                    double r = ((Number)pop()).doubleValue();
                    double l = ((Number)pop()).doubleValue();
                    push(l <= r);
                    ip += 1;
                    break;
                }
                case DGT: {
                    double r = ((Number)pop()).doubleValue();
                    double l = ((Number)pop()).doubleValue();
                    push(l > r);
                    ip += 1;
                    break;
                }
                case DGE: {
                    double r = ((Number)pop()).doubleValue();
                    double l = ((Number)pop()).doubleValue();
                    push(l >= r);
                    ip += 1;
                    break;
                }

                // ============ Boolean constants ============
                case PUSH_TRUE: {
                    push(true);
                    ip += 1;
                    break;
                }
                case PUSH_FALSE: {
                    push(false);
                    ip += 1;
                    break;
                }
                case PUSH_NULL: {
                    push(null);
                    ip += 1;
                    break;
                }

                // ============ Control flow ============
                case JUMP: {
                    int target = oc == 0 ? pl : code[ip + 1];
                    ip = blockOffsets[target];
                    break;
                }
                case JUMP_IF_TRUE: {
                    boolean cond = TypeCoercion.coerceToBoolean(pop());
                    if (cond) {
                        ip = blockOffsets[oc == 0 ? pl : code[ip + 1]];
                    } else {
                        ip += 1 + oc;
                    }
                    break;
                }
                case JUMP_IF_FALSE: {
                    boolean cond = TypeCoercion.coerceToBoolean(pop());
                    if (!cond) {
                        ip = blockOffsets[oc == 0 ? pl : code[ip + 1]];
                    } else {
                        ip += 1 + oc;
                    }
                    break;
                }
                case JUMP_IF_NULL: {
                    Object v = pop();
                    if (v == null) {
                        ip = blockOffsets[oc == 0 ? pl : code[ip + 1]];
                    } else {
                        ip += 1 + oc;
                    }
                    break;
                }
                case JUMP_IF_NONNULL: {
                    Object v = pop();
                    if (v != null) {
                        ip = blockOffsets[oc == 0 ? pl : code[ip + 1]];
                    } else {
                        ip += 1 + oc;
                    }
                    break;
                }

                // ============ Function calls ============
                case INVOKE_TAIL: {
                    int argc = pl;
                    for (int i = argc - 1; i >= 0; i--) {
                        ensureLocals(i); locals[i] = pop();
                    }
                    // Reset operand stack — old intermediate values
                    // from previous iteration must not accumulate.
                    sp = 0;
                    ip = blockOffsets[0];
                    break;
                }
                case INVOKE_DIRECT: {
                    int funcIdx = pl;  // function pool index in payload
                    // argCount in first operand
                    int argc = oc == 0 ? 0 : code[ip + 1];
                    IRFunction targetFn = (IRFunction)constantPool[funcIdx];
                    // Pop arguments
                    Object[] args = new Object[argc];
                    for (int i = argc - 1; i >= 0; i--)
                        args[i] = pop();
                    // Closures must go through dynamicInvoke so captured
                    // values are expanded from the IRClosure object.
                    if (targetFn.captureCount() > 0) {
                        push(targetFn);
                        for (int i = 0; i < argc; i++)
                            push(args[i]);
                        push(dynamicInvoke(argc));
                    } else {
                        IRInterpreter callee = new IRInterpreter(elctx, targetFn,
                                evalContext);
                        push(callee.execute(args));
                    }
                    ip += 1 + oc;
                    break;
                }
                case INVOKE_DYN: {
                    int argc = pl;  // argCount is always in payload
                    Object result = dynamicInvoke(argc);
                    push(result);
                    ip += 1 + oc;
                    break;
                }
                case INVOKE: {
                    int argc = pl;
                    Object result = dynamicInvoke(argc);
                    push(result);
                    ip += 1 + oc;
                    break;
                }

                // ============ Return ============
                case RETURN: {
                    Object result = pop();
                    return result;
                }
                case RETURN_VOID: {
                    return null;
                }
                case SCOPE_ENTER:
                case SCOPE_EXIT: {
                    // NOP — control-flow scopes are handled purely at
                    // compile time via slot allocation. Closure scope
                    // isolation is handled by execute()'s pushContext().
                    ip += 1;
                    break;
                }
                case THROW: {
                    Object cause = pop();
                    if (cause instanceof RuntimeException re)
                        throw re;
                    if (cause instanceof Throwable t)
                        throw new org.operamasks.el.eval.UserException(elctx,
                                t);
                    if (cause instanceof String s)
                        throw new org.operamasks.el.eval.UserException(elctx,
                                s);
                    throw new org.operamasks.el.eval.UserException(elctx);
                }

                // ============ Memory / variables ============
                case STORE_VAR: {
                    int idx = pl & 0xFFFF;
                    Object val = pop();
                    locals[idx] = val;
                    push(val);
                    ip += 1 + oc;
                    break;
                }
                case PUSH_GLOBAL: {
                    int nameIdx = oc == 0 ? pl : code[ip + 1];
                    String name = (String)constantPool[nameIdx];
                    Object val = resolveGlobal(name);
                    push(val);
                    ip += 1 + oc;
                    break;
                }
                case STORE_GLOBAL: {
                    int nameIdx = oc == 0 ? pl : code[ip + 1];
                    String name = (String)constantPool[nameIdx];
                    Object val = pop();
                    storeGlobal(name, val);
                    push(val);  // assignment returns the value
                    ip += 1 + oc;
                    break;
                }
                case STORE_DEEP: {
                    int nameIdx = oc == 0 ? pl : code[ip + 1];
                    String name = (String)constantPool[nameIdx];
                    Object val = pop();
                    storeAssign(name, val);
                    push(val);
                    ip += 1 + oc;
                    break;
                }

                // ============ Increment / Decrement ============
                case INC: {
                    int idx = pl;
                    Object val = locals[idx];
                    if (val instanceof Long l)
                        locals[idx] = l + 1;
                    else if (val instanceof Integer i)
                        locals[idx] = i + 1;
                    else if (val instanceof Double d)
                        locals[idx] = d + 1.0;
                    else
                        locals[idx] = ((Number)val).longValue() + 1;
                    push(locals[idx]);
                    ip += 1;
                    break;
                }
                case DEC: {
                    int idx = pl;
                    Object val = locals[idx];
                    if (val instanceof Long l)
                        locals[idx] = l - 1;
                    else if (val instanceof Integer i)
                        locals[idx] = i - 1;
                    else if (val instanceof Double d)
                        locals[idx] = d - 1.0;
                    else
                        locals[idx] = ((Number)val).longValue() - 1;
                    push(locals[idx]);
                    ip += 1;
                    break;
                }

                // ============ Property / index access ============
                case LOAD_PROPERTY: {
                    Object key = pop();
                    Object base = pop();
                    push(loadProperty(base, key));
                    ip += 1;
                    break;
                }
                case STORE_PROPERTY: {
                    Object key = pop();
                    Object base = pop();
                    Object value = pop();
                    storeProperty(base, key, value);
                    push(value);
                    ip += 1;
                    break;
                }

                // ============ Direct field access ============
                case LOAD_FIELD: {
                    Object base = pop();
                    String fieldName = (String)constantPool[pl];
                    push(loadField(base, fieldName));
                    ip += 1 + oc;
                    break;
                }
                case STORE_FIELD: {
                    Object value = pop();
                    Object base = pop();
                    String fieldName = (String)constantPool[pl];
                    push(storeField(base, fieldName, value));
                    ip += 1 + oc;
                    break;
                }

                // ============ Collections ============
                case NEW_LIST: {
                    int count = pl;
                    Object[] elements = new Object[count];
                    for (int i = count - 1; i >= 0; i--)
                        elements[i] = pop();
                    // Wrap in ListSeq so Seq methods (mappend, map, etc.)
                    // are available and JVM module restrictions on
                    // java.util.Arrays$ArrayList are avoided.
                    java.util.List<Object> raw = java.util.Arrays.asList(elements);
                    push(org.operamasks.el.eval.seq.ListSeq.make(raw));
                    ip += 1;
                    break;
                }
                case NEW_MAP: {
                    int count = pl;
                    LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
                    for (int i = count - 1; i >= 0; i--) {
                        Object val = pop();
                        Object key = pop();
                        map.put(key, val);
                    }
                    push(map);
                    ip += 1;
                    break;
                }
                case NEW_RANGE: {
                    Object end = pop();
                    Object begin = pop();
                    push(Ranges.createRange(((Number)begin).longValue(),
                                            ((Number)end).longValue(), 1));
                    ip += 1;
                    break;
                }
                case NEW_TUPLE: {
                    int count = pl;
                    Object[] elems = new Object[count];
                    for (int i = count - 1; i >= 0; i--)
                        elems[i] = pop();
                    push(elems);
                    ip += 1;
                    break;
                }

                // ============ Iteration ============
                case GET_ITER: {
                    Object coll = pop();
                    push(getIterator(coll));
                    ip += 1;
                    break;
                }
                case ITER_NEXT: {
                    java.util.Iterator<?> it = (java.util.Iterator<?>)pop();
                    Object next = it.hasNext() ? it.next() : null;
                    push(it);    // iterator first (bottom)
                    push(next);  // value on top (popped by ITER_DONE or
                                 // STORE_VAR)
                    ip += 1;
                    break;
                }
                case ITER_DONE: {
                    Object val = pop();
                    if (val == null) {
                        ip = blockOffsets[oc == 0 ? pl : code[ip + 1]];
                    } else {
                        ip += 1 + oc;
                    }
                    break;
                }

                // ============ DynIn ============
                case DYNIN: {
                    Object elem = pop();
                    Object coll = pop();
                    push(elite.rt.Runtime.dynIn(coll, elem));
                    ip += 1;
                    break;
                }

                // ============ Unary logic ============
                case NOT: {
                    push(!TypeCoercion.coerceToBoolean(pop()));
                    ip += 1;
                    break;
                }

                // ============ Concatenation ============
                case CAT: {
                    Object r = pop(), l = pop();
                    push(String.valueOf(l) + String.valueOf(r));
                    ip += 1;
                    break;
                }

                // ============ JavaBean getter call ============
                case INVOKE_GETTER: {
                    Method m = (Method)constantPool[pl];
                    Object base = pop();
                    try {
                        push(m.invoke(base));
                    } catch (Exception e) {
                        throw new RuntimeException(_T(IR_GETTER_INVOKE_FAILED), e);
                    }
                    ip += 1 + oc;
                    break;
                }
                case INVOKE_SETTER: {
                    Method m = (Method)constantPool[pl];
                    Object value = pop();
                    Object base = pop();
                    try {
                        m.invoke(base, value);
                        push(value);
                    } catch (Exception e) {
                        throw new RuntimeException(_T(IR_SETTER_INVOKE_FAILED), e);
                    }
                    ip += 1 + oc;
                    break;
                }
                case INVOKE_METHOD: {
                    Method m = (Method)constantPool[pl];
                    int argc = oc > 0 ? code[ip + 1] : 0;
                    Object[] args = new Object[argc];
                    for (int i = argc - 1; i >= 0; i--)
                        args[i] = pop();
                    Object base = pop();
                    try {
                        // Coerce args to match method's parameter types
                        Class<?>[] paramTypes = m.getParameterTypes();
                        for (int i = 0; i < argc && i < paramTypes.length; i++) {
                            args[i] = coerceArg(args[i], paramTypes[i]);
                        }
                        push(m.invoke(base, args));
                    } catch (Exception e) {
                        throw new RuntimeException(_T(IR_METHOD_INVOKE_FAILED), e);
                    }
                    ip += 1 + oc;
                    break;
                }

                case INVOKE_DYN_METHOD: {
                    String key = (String) constantPool[pl];
                    int argc = oc > 0 ? code[ip + 1] : 0;
                    Object[] args = new Object[argc];
                    for (int i = argc - 1; i >= 0; i--)
                        args[i] = pop();
                    Object base = pop();
                    elite.lang.Closure[] closures = ELEngine.getCallArgs(args);

                    // 1) ClosureObject dynamic dispatch (mirrors AST ACCESS.invoke).
                    //    Monads and other custom objects handle method calls via
                    //    invokeSpecial / invokeDynamic.
                    if (base instanceof org.operamasks.el.eval.closure.ClosureObject co) {
                        Object result = co.invoke(evalContext.getELContext(), key, closures);
                        if (result != org.operamasks.el.eval.ELUtils.NO_RESULT) {
                            push(result);
                            ip += 1 + oc;
                            break;
                        }
                        // NO_RESULT: fall through to MethodResolver
                    }

                    // 2) Resolve method/property by name on base via loadProperty
                    //    (DataClass unwrap → ELResolver → MethodResolver fallback).
                    Object resolved = loadProperty(base, key);
                    if (resolved instanceof org.operamasks.el.eval.closure.MethodClosure mc) {
                        // Method found — invoke with base as this,
                        // ELContext injection handled by invokeMethod.
                        push(mc.invoke(elctx, base, closures));
                    } else if (argc == 0) {
                        // 0-arg on a non-callable (bean property like .size()
                        // on a Java List): return the value as-is.
                        push(resolved);
                    } else {
                        throw new RuntimeException(
                            _T(EL_METHOD_NOT_FOUND, base.getClass().getName(), key));
                    }
                    ip += 1 + oc;
                    break;
                }

                // ============ Closure creation ============
                case CLOSURE: {
                    int funcIdx = pl;
                    int captureCount = oc > 0 ? code[ip + 1] : 0;
                    IRFunction fn = (IRFunction)constantPool[funcIdx];
                    Object[] captured = new Object[captureCount];
                    for (int i = captureCount - 1; i >= 0; i--)
                        captured[i] = pop();
                    push(new IRClosure(fn, captured));
                    ip += 1 + oc;
                    break;
                }

                // ============ Trampoline to AST evaluator ============
                case TRAMPOLINE: {
                    int poolIdx = pl;
                    Object obj = constantPool[poolIdx];
                    // TryDescriptor wraps pre-compiled IR blocks; evaluate
                    // the original TRY node
                    if (obj instanceof TryDescriptor td) {
                        obj = td.tryNode;
                    }
                    ELNode node = (ELNode)obj;
                    // Sync locals → evalContext so the AST evaluator can
                    // see function parameters and let-bindings.
                    syncLocalsToGlobals();
                    Object result = node.getValue(evalContext);
                    push(result);
                    // Sync back: AST evaluation may have modified variables
                    // through the evalContext chain. Copy changes back to
                    // local slots so subsequent PUSH_VAR sees them.
                    syncLocalsFromGlobals();
                    ip += 1 + oc;
                    break;
                }

                // ============ Type guard ============
                case GUARD_TYPE: {
                    int typeId = IRFormat.payload(code[ip]);
                    int deoptBlockId = oc > 0 ? code[ip + 1] : 0;
                    Object val = peek();
                    if (!checkType(val, typeId)) {
                        if (deoptBlockId == Opcode.STRICT_GUARD) {
                            String expected = IRFormat.primTypeName(typeId);
                            String actual = val == null ? "null" :
                                            val.getClass().getName();
                            throw new RuntimeException(_T(IR_TYPE_MISMATCH, expected, actual));
                        }
                        ip = blockOffsets[deoptBlockId];
                        break;
                    }
                    ip += 1 + oc;
                    break;
                }

                // ============ NOP ============
                case NOP:
                    ip += 1;
                    break;

                default: {
                    // Unknown opcode — trampoline to AST eval
                    // This handles all the instructions we haven't
                    // implemented yet
                    throw new UnsupportedOperationException(
                            "Unknown IR " + "opcode: " + Opcode.name(op) +
                            " (" + op + ") at " + "ip=" + ip);
                }
            }
        }
    }

    // ── Stack helpers ──

    private void push(Object v) {
        if (sp >= stack.length)
            growStack();
        stack[sp++] = v;
    }

    private Object pop() {
        return stack[--sp];
    }

    private Object peek() {
        return stack[sp - 1];
    }

    private void growStack() {
        int newSize = stack.length * 2;
        Object[] newStack = new Object[newSize];
        System.arraycopy(stack, 0, newStack, 0, stack.length);
        stack = newStack;
    }

    /** Ensure locals array has at least minCapacity slots. */
    private void growLocals(int minCapacity) {
        int newSize = Math.max(locals.length * 2, minCapacity);
        Object[] newLocals = new Object[newSize];
        System.arraycopy(locals, 0, newLocals, 0, locals.length);
        locals = newLocals;
    }

    /** Ensure locals[idx] is accessible, growing the array if needed. */
    private void ensureLocals(int idx) {
        if (idx >= locals.length) growLocals(idx + 1);
    }

    // ── Dynamic operation support ──

    private Object dynamicOp(int irOpcode) {
        // Unary negate: only one operand on stack
        if (irOpcode == DYNNEG) {
            Object val = pop();
            if (val instanceof ClosureObject)
                return trampolineBinaryOp(irOpcode, val, null);
            return elite.rt.Runtime.dynNeg(val);
        }

        Object rhs = pop();
        Object lhs = pop();
        // For non-Number operands (e.g. ClosureObject from user-defined
        // classes),
        // delegate to AST operator resolution via trampoline node.
        if (needsTrampolineDispatch(lhs, rhs)) {
            return trampolineBinaryOp(irOpcode, lhs, rhs);
        }
        // Delegate to Runtime for type-resolved arithmetic (shared with
        // bytecode path)
        return switch (irOpcode) {
            case DYNADD -> elite.rt.Runtime.dynAdd(lhs, rhs);
            case DYNSUB -> elite.rt.Runtime.dynSub(lhs, rhs);
            case DYNMUL -> elite.rt.Runtime.dynMul(lhs, rhs);
            case DYNDIV -> elite.rt.Runtime.dynDiv(lhs, rhs);
            case DYNREM -> elite.rt.Runtime.dynRem(lhs, rhs);
            case DYNPOW -> elite.rt.Runtime.dynPow(lhs, rhs);
            case DYNCAT -> elite.rt.Runtime.dynCat(elctx, lhs, rhs);
            case DYNEQ -> elite.rt.Runtime.dynEq(lhs, rhs);
            case DYNLT -> elite.rt.Runtime.dynLt(lhs, rhs);
            case DYNLE -> elite.rt.Runtime.dynLe(lhs, rhs);
            default -> { assert(false); yield null; }
        };
    }

    private static boolean needsTrampolineDispatch(Object lhs, Object rhs) {
        if (lhs instanceof ClosureObject || rhs instanceof ClosureObject)
            return true;
        // Non-numeric non-String operands (e.g. Measure, user-defined
        // types) need AST operator resolution to find overloaded operators.
        if (lhs != null && !(lhs instanceof Number) && !(lhs instanceof String))
            return true;
        if (rhs != null && !(rhs instanceof Number) && !(rhs instanceof String))
            return true;
        return false;
    }

    private Object trampolineBinaryOp(int irOpcode, Object lhs, Object rhs) {
        int tokenOp = switch (irOpcode) {
            case DYNADD -> Token.ADD;
            case DYNSUB -> Token.SUB;
            case DYNMUL -> Token.MUL;
            case DYNDIV -> Token.DIV;
            case DYNREM -> Token.REM;
            case DYNPOW -> Token.POW;
            case DYNCAT -> Token.CAT;
            case DYNEQ -> Token.EQ;
            case DYNLT -> Token.LT;
            case DYNLE -> Token.LE;
            default -> { assert(false); yield 0; }
        };
        int pos = Position.make(0, 0);
        var leftC = new ELNode.CONST(pos, lhs);
        var rightC = new ELNode.CONST(pos, rhs);
        var infix = new ELNode.INFIX(pos, Token.opNames[tokenOp], 100,
                                     leftC, rightC);
        return infix.getValue(evalContext);
    }

    // ── Dynamic invocation ──

    private Object dynamicInvoke(int argCount) {
        // Stack layout: target below, args on top
        // Stack: ... target arg0 arg1 ... argN
        Object[] args = new Object[argCount];
        for (int i = argCount - 1; i >= 0; i--) {
            args[i] = pop();
        }
        Object target = pop();
        // IRFunction/IRClosure expansion duplicated across 3 places:
        // IRInterpreter.dynamicInvoke, Runtime.invokeDyn, ELEngine.invokeTarget.
        // Consolidate into a shared helper if a 4th occurrence is needed.
        // Handle IRFunction target (from inline lambda): execute directly
        if (target instanceof IRFunction irFn) {
            return new IRInterpreter(elctx, irFn, evalContext).execute(args);
        }
        // Handle IRClosure target: expand args with captured values
        if (target instanceof IRClosure closure) {
            IRFunction irFn = closure.function;
            int paramCount = irFn.paramCount();
            int captureCount = irFn.captureCount();
            Object[] expandedArgs = new Object[paramCount + captureCount];
            System.arraycopy(args, 0, expandedArgs, 0, Math.min(args.length,
                    paramCount));
            System.arraycopy(closure.captured, 0, expandedArgs, paramCount,
                    captureCount);
            return new IRInterpreter(elctx, irFn, evalContext).execute(expandedArgs);
        }
        try {
            // Use ELEngine's invoke mechanism with Closure[] conversion
            javax.el.ELContext elctx = evalContext.getELContext();
            elite.lang.Closure[] closures = ELEngine.getCallArgs(args);
            return ELEngine.invokeTarget(elctx, target, closures);
        } catch (Exception e) {
            throw new RuntimeException(_T(IR_DYNAMIC_INVOKE_FAILED), e);
        }
    }

    // ── Helpers ──

    /**
     * Coerce a method argument to the expected Java parameter type.
     */
    private Object coerceArg(Object arg, Class<?> paramType) {
        return TypeCoercion.coerce(evalContext.getELContext(), arg, paramType);
    }

    /**
     * Check if a runtime value matches the expected primitive type ID.
     */
    private static boolean checkType(Object val, int typeId) {
        if (val == null)
            return false;
        return switch (typeId) {
            case IRFormat.T_INT ->
                    val instanceof Integer || val instanceof Short ||
                    val instanceof Byte;
            case IRFormat.T_LONG ->
                    val instanceof Long || val instanceof Integer ||
                    val instanceof Short || val instanceof Byte;
            case IRFormat.T_DOUBLE ->
                    val instanceof Double || val instanceof Float ||
                    val instanceof Long || val instanceof Integer;
            case IRFormat.T_BOOL -> val instanceof Boolean;
            case IRFormat.T_STRING -> val instanceof String;
            default -> true;  // unknown type → pass
        };
    }

    // ── Global variable storage ──

    private void storeGlobal(String name, Object value) {
        // define: create/update in current scope only (head-bounded search)
        LiteralClosure lc = new LiteralClosure(value);
        if (evalContext != null) {
            evalContext.setVariable(name, lc);
        }
    }

    private void storeAssign(String name, Object value) {
        // assign: search full chain, throw if not found
        LiteralClosure lc = new LiteralClosure(value);
        if (evalContext != null) {
            evalContext.setVariableDeep(name, lc);
        }
    }

    /**
     * Before a TRAMPOLINE, copy IR local variable values into the
     * EvaluationContext so the AST evaluator can see them.
     * Uses regular setVariable (current-scope only) to avoid overwriting
     * parent parameters during recursive calls.
     */
    private void syncLocalsToGlobals() {
        String[] names = function.varNames();
        if (names == null) return;
        for (int i = 0; i < names.length && i < locals.length; i++) {
            if (names[i] != null) {
                ensureLocals(i);
                evalContext.setVariable(names[i], new LiteralClosure(locals[i]));
            }
        }
    }

    /**
     * After a TRAMPOLINE, copy any changes the AST evaluator made back
     * to local slots. Reads from evalContext (not VariableMapper) to
     * align with the new scope architecture.
     */
    private void syncLocalsFromGlobals() {
        String[] names = function.varNames();
        if (names == null) return;
        for (int i = 0; i < names.length && i < locals.length; i++) {
            if (names[i] != null) {
                ensureLocals(i);
                ValueExpression ve = evalContext.resolveVariable(names[i]);
                if (ve != null) {
                    locals[i] = ve.getValue(elctx);
                }
            }
        }
    }

    // ── Direct field access ──

    private Object loadField(Object base, String fieldName) {
        if (base == null)
            throw new NullPointerException(_T(IR_FIELD_READ_FROM_NULL, fieldName));
        try {
            Class<?> cls = (base instanceof Class<?> c) ? c : base.getClass();
            Field f = cls.getField(fieldName);
            Object target = Modifier.isStatic(f.getModifiers()) ? null : base;
            return f.get(target);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(_T(IR_FIELD_NOT_FOUND, fieldName, base.getClass().getName()));
        } catch (IllegalAccessException e) {
            throw new RuntimeException(_T(IR_FIELD_ACCESS_ERROR, fieldName), e);
        }
    }

    private Object storeField(Object base, String fieldName, Object value) {
        if (base == null)
            throw new NullPointerException(_T(IR_FIELD_WRITE_TO_NULL, fieldName));
        try {
            Class<?> cls = (base instanceof Class<?> c) ? c : base.getClass();
            Field f = cls.getField(fieldName);
            Object target = Modifier.isStatic(f.getModifiers()) ? null : base;
            f.set(target, coerceArg(value, f.getType()));
            return value; // assignment returns the value
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(_T(IR_FIELD_NOT_FOUND, fieldName, base.getClass().getName()));
        } catch (IllegalAccessException e) {
            throw new RuntimeException(_T(IR_FIELD_ACCESS_ERROR, fieldName), e);
        }
    }

    // ── Property / index access ──

    private Object loadProperty(Object base, Object key) {
        if (base == null)
            return null;
        // DataClass wraps a Java Class (e.g. from import). Unwrap to
        // the underlying java.lang.Class so the ELResolver can find
        // static methods (getInstance, etc.).
        if (base instanceof org.operamasks.el.eval.closure.DataClass dc)
            base = dc.getJavaClass();
        ELContext elctx = evalContext.getELContext();
        Object result;
        try {
            elctx.setPropertyResolved(false);
            result = elctx.getELResolver().getValue(elctx, base, key);
            if (elctx.isPropertyResolved())
                return result;
        } catch (javax.el.PropertyNotFoundException e) {
            // Some ELResolvers (BeanELResolver) throw instead of
            // setting isPropertyResolved(false). Catch and fall
            // through to method resolution below.
        }
        // Property not found — try method resolution (mirrors ELNode.ACCESS.invoke).
        // Static methods like UnitFormat.getInstance() resolve through this path.
        if (key instanceof String) {
            String name = (String) key;
            org.operamasks.el.resolver.MethodResolver mr =
                org.operamasks.el.resolver.MethodResolver.getInstance(elctx);
            org.operamasks.el.eval.closure.MethodClosure mc = null;
            if (base instanceof Class<?> cls) {
                // Static method first, then instance method on Class itself
                mc = mr.resolveStaticMethod(cls, name);
                if (mc == null)
                    mc = mr.resolveMethod(cls, name);
                if (mc == null)
                    mc = mr.resolveMethod(Class.class, name);
            } else {
                mc = mr.resolveMethod(base.getClass(), name);
            }
            if (mc != null)
                return mc;
        }
        throw new RuntimeException(_T(EL_PROPERTY_NOT_FOUND, base.getClass().getName(), key));
    }

    private void storeProperty(Object base, Object key, Object value) {
        javax.el.ELContext elctx = evalContext.getELContext();
        elctx.getELResolver().setValue(elctx, base, key, value);
    }

    // ── Global variable resolution ──

    private Object resolveGlobal(String name) {
        javax.el.ELContext elctx = evalContext.getELContext();

        // 1) Check the EvaluationContext's own variable resolution chain
        ValueExpression ve = evalContext.resolveVariable(name);
        if (ve != null) {
            return ve.getValue(elctx);
        }

        // 2) Check the FunctionMapper for global/imported functions
        MethodResolver mr = MethodResolver.getInstance(elctx);
        if (mr != null) {
            MethodClosure mc = mr.resolveGlobalMethod(
                    elctx.getFunctionMapper(), name);
            if (mc != null)
                return mc;
        }

        // 3) Try ELResolver chain
        elctx.setPropertyResolved(false);
        Object result = elctx.getELResolver().getValue(elctx, null, name);
        if (elctx.isPropertyResolved())
            return result;

        throw new RuntimeException(_T(EL_UNDEFINED_IDENTIFIER, name));
    }

    // ── Iterator helpers ──

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static java.util.Iterator<?> getIterator(Object coll) {
        if (coll instanceof Iterable)
            return ((Iterable)coll).iterator();
        if (coll instanceof Object[])
            return java.util.Arrays.asList((Object[])coll).iterator();
        if (coll.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(coll);
            Object[] arr = new Object[len];
            for (int i = 0; i < len; i++)
                arr[i] = java.lang.reflect.Array.get(coll, i);
            return java.util.Arrays.asList(arr).iterator();
        }
        if (coll instanceof elite.lang.Seq seq) {
            return new java.util.Iterator<>() {
                elite.lang.Seq s = seq;

                public boolean hasNext() {
                    return !s.isEmpty();
                }

                public Object next() {
                    Object h = s.head();
                    s = s.tail();
                    return h;
                }
            };
        }
        throw new RuntimeException(_T(IR_CANNOT_ITERATE, coll.getClass().getName()));
    }
}
