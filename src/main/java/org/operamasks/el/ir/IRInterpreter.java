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

import elite.lang.Closure;
import org.operamasks.el.eval.*;
import org.operamasks.el.eval.closure.ClosureObject;
import org.operamasks.el.eval.closure.LiteralClosure;
import org.operamasks.el.eval.closure.MethodClosure;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Position;
import org.operamasks.el.parser.Token;
import org.operamasks.el.resolver.MethodResolver;

import javax.el.ELContext;
import javax.el.ValueExpression;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;

import static org.operamasks.el.eval.ELUtils.NO_RESULT;
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
    private EvaluationContext evalContext;
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

    // ── Debug support ──
    private final boolean debug;
    private org.operamasks.el.eval.Frame frame; // current stack frame (debug only)

    public IRInterpreter(EvaluationContext context, IRFunction function) {
        this.evalContext = context;
        this.elctx = context.getELContext();
        this.function = function;
        this.code = function.code();
        this.constantPool = function.constantPool();
        this.blockOffsets = function.blockOffsets();
        this.debug = ELProgram.DEBUG;
    }

    // ── Entry point ──

    public Object execute(Object[] args) {
        return execute(args, null, false);
    }

    public Object execute(Object[] args, Object[] captured) {
        return execute(args, captured, false);
    }

    public Object execute(Object[] args, Object[] captured,
                          boolean isTopLevel) {
        this.stack = new Object[DEFAULT_STACK_SIZE];
        this.sp = 0;
        this.locals = new Object[DEFAULT_LOCALS_SIZE];

        int nvars = function.paramCount();
        int argc = args != null ? args.length : 0;

        Object[] defs = function.defaultValues();
        if ((argc > nvars) || (argc < nvars && defs == null))
            throw new EvaluationException(elctx, _T(EL_FN_BAD_ARG_COUNT, function.name(), nvars, argc));

        // Bind arguments to locals (grow array if paramCount exceeds default)
        int needed = nvars + (captured != null ? captured.length : 0);
        if (needed > locals.length)
            growLocals(needed);

        if (args != null) {
            System.arraycopy(args, 0, locals, 0, args.length);
        }

        // Fill missing parameters with default values.
        // Use paramCount (not args.length) as the upper bound for provided args
        // because expanded args from INVOKE_DYN/IRClosure include capture slots
        // at the end, inflating args.length.
        if (defs != null) {
            for (int i = argc; i < nvars; i++) {
                if (defs[i] != null)
                    locals[i] = defs[i];
            }
        }

        // Copy captured variables.
        if (captured != null) {
            System.arraycopy(captured, 0, locals, nvars, captured.length);
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

        // Sync captured parameters to evalContext so inner closures can
        // read and modify them via PUSH_GLOBAL/STORE_GLOBAL. Parameters are
        // slot-only by default — this copies their initial values into the
        // evalContext chain.
        int[] pFlags = function.paramFlags();
        String[] vNames = function.varNames();
        if (pFlags != null && vNames != null) {
            for (int i = 0; i < Math.min(pFlags.length, vNames.length); i++) {
                if ((pFlags[i] & IRFunction.PARAM_CAPTURED) != 0) {
                    defineGlobal(vNames[i], locals[i]);
                }
            }
        }

        // Debug: push a stack frame for this function call
        if (debug && !isTopLevel) {
            DebugInfo di = function.debugInfo();
            String fnName = di.functionName() != null ? di.functionName() : function.name();
            String fileName = di.fileName();
            int blockPos = di.positionForBlock(0);
            frame = StackTrace.addFrame(elctx, fnName, fileName, blockPos);
        }

        // Store current EvaluationContext on ELContext so invokeTarget
        // can retrieve it for nested IRClosure/IRFunction calls.
        Object savedCtx = elctx.getContext(EvaluationContext.class);
        elctx.putContext(EvaluationContext.class, evalContext);

        try {
            return interpret();
        } catch (RuntimeException e) {
            if (debug && !(e instanceof EvaluationException) && !(e instanceof Control)) {
                // Update frame position to error location
                if (frame != null) {
                    DebugInfo di = function.debugInfo();
                    int line = di.lineForPC(ip);
                    if (line > 0) {
                        frame.setPos(Position.make(line, 1));
                    }
                }
                throw new EvaluationException(elctx, e);
            }
            throw e;
        } finally {
            if (frame != null) {
                StackTrace.removeFrame(elctx);
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
            case DYNSHL:
            case DYNSHR:
            case DYNUSHR:
            case DYNEQ:
            case DYNNE:
            case DYNLT:
            case DYNLE:
            case DYNGT:
            case DYNGE:
            case DYNAND:
            case DYNOR:
            case DYNXOR:
            case DYNNOT: {
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
                    ensureLocals(i);
                    locals[i] = pop();
                }
                // Reset operand stack — old intermediate values
                // from previous iteration must not accumulate.
                sp = 0;
                ip = blockOffsets[0];
                break;
            }
            case INVOKE_DIRECT: {
                // function pool index in payload
                // argCount in first operand
                int funcIdx = pl;
                int argc = oc == 0 ? 0 : code[ip + 1];
                IRFunction targetFn = (IRFunction)constantPool[funcIdx];

                // Pop arguments
                Object[] args = new Object[argc];
                for (int i = argc - 1; i >= 0; i--)
                    args[i] = pop();

                IRInterpreter callee = new IRInterpreter(evalContext, targetFn);
                push(callee.execute(args));
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
            case THROW: {
                Object cause = pop();
                if (cause instanceof RuntimeException re)
                    throw re;
                if (cause instanceof Throwable t)
                    throw new UserException(elctx, t);
                if (cause instanceof String s)
                    throw new UserException(elctx, s);
                throw new UserException(elctx);
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
            case DEFINE_GLOBAL: {
                int nameIdx = oc == 0 ? pl : code[ip + 1];
                String name = (String)constantPool[nameIdx];
                Object val = pop();
                defineGlobal(name, val);
                push(val);  // definition returns the value
                ip += 1 + oc;
                break;
            }
            case STORE_GLOBAL: {
                int nameIdx = oc == 0 ? pl : code[ip + 1];
                String name = (String)constantPool[nameIdx];
                Object val = pop();
                storeGlobal(name, val);
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
                Object next = pop();
                Object begin = pop();
                push(elite.lang.Runtime.newRange(begin, next, end));
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
                push(elite.lang.Runtime.dynIn(elctx, coll, elem));
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
                int argc = pl;
                Object[] args = new Object[argc];
                for (int i = argc - 1; i >= 0; i--)
                    args[i] = pop();
                Object key = pop();
                Object base = pop();
                push(elite.lang.Runtime.invokeDynMethod(elctx, base, key, args));
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
                // Capture the current evalContext so captured variable
                // reads and writes inside the closure resolve against
                // the original enclosing scope.
                push(new IRClosure(evalContext, fn, captured));
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
                        String actual = val == null ? "null" : val.getClass().getName();
                        throw new RuntimeException(_T(IR_TYPE_MISMATCH, expected, actual));
                    }
                    ip = blockOffsets[deoptBlockId];
                    break;
                }
                ip += 1 + oc;
                break;
            }

            // ============ Identity comparison ============
            case IDEQ: {
                Object r = pop();
                Object l = pop();
                push(l == r);
                ip += 1;
                break;
            }
            case IDNE: {
                Object r = pop();
                Object l = pop();
                push(l != r);
                ip += 1;
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
                        "Unknown IR " +  "opcode: " + Opcode.name(op) +
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

    /**
     * Ensure locals array has at least minCapacity slots.
     */
    private void growLocals(int minCapacity) {
        int newSize = Math.max(locals.length * 2, minCapacity);
        Object[] newLocals = new Object[newSize];
        System.arraycopy(locals, 0, newLocals, 0, locals.length);
        locals = newLocals;
    }

    /**
     * Ensure locals[idx] is accessible, growing the array if needed.
     */
    private void ensureLocals(int idx) {
        if (idx >= locals.length)
            growLocals(idx + 1);
    }

    // ── Dynamic operation support ──

    private Object dynamicOp(int irOpcode) {
        // Unary negate: only one operand on stack
        if (irOpcode == DYNNEG) {
            Object val = pop();
            return elite.lang.Runtime.dynNeg(elctx, val);
        }
        if (irOpcode == DYNNOT) {
            Object val = pop();
            return elite.lang.Runtime.dynBitNot(elctx, val);
        }

        // Delegate to Runtime for type-resolved arithmetic (shared with
        // bytecode path)
        Object rhs = pop();
        Object lhs = pop();
        return switch (irOpcode) {
            case DYNADD -> elite.lang.Runtime.dynAdd(elctx, lhs, rhs);
            case DYNSUB -> elite.lang.Runtime.dynSub(elctx, lhs, rhs);
            case DYNMUL -> elite.lang.Runtime.dynMul(elctx, lhs, rhs);
            case DYNDIV -> elite.lang.Runtime.dynDiv(elctx, lhs, rhs);
            case DYNREM -> elite.lang.Runtime.dynRem(elctx, lhs, rhs);
            case DYNPOW -> elite.lang.Runtime.dynPow(elctx, lhs, rhs);
            case DYNCAT -> elite.lang.Runtime.dynCat(elctx, lhs, rhs);
            case DYNSHL -> elite.lang.Runtime.dynShl(elctx, lhs, rhs);
            case DYNSHR -> elite.lang.Runtime.dynShr(elctx, lhs, rhs);
            case DYNUSHR -> elite.lang.Runtime.dynUShr(elctx, lhs, rhs);
            case DYNEQ -> elite.lang.Runtime.dynEq(elctx, lhs, rhs);
            case DYNNE -> elite.lang.Runtime.dynNe(elctx, lhs, rhs);
            case DYNLT -> elite.lang.Runtime.dynLt(elctx, lhs, rhs);
            case DYNLE -> elite.lang.Runtime.dynLe(elctx, lhs, rhs);
            case DYNGT -> elite.lang.Runtime.dynGt(elctx, lhs, rhs);
            case DYNGE -> elite.lang.Runtime.dynGe(elctx, lhs, rhs);
            case DYNAND -> elite.lang.Runtime.dynBitAnd(elctx, lhs, rhs);
            case DYNOR -> elite.lang.Runtime.dynBitOr(elctx, lhs, rhs);
            case DYNXOR -> elite.lang.Runtime.dynXor(elctx, lhs, rhs);
            default -> { assert (false); yield null; }
        };
    }

    // ── Dynamic invocation ──

    private Object dynamicInvoke(int argCount) {
        // Stack layout: target below, args on top
        // Stack: ... target arg0 arg1 ... argN
        Object[] args = new Object[argCount];
        for (int i = argCount - 1; i >= 0; i--)
            args[i] = pop();
        Object target = pop();

        // Use ELEngine's invoke mechanism with Closure[] conversion
        javax.el.ELContext elctx = evalContext.getELContext();
        elite.lang.Closure[] closures = ELEngine.getCallArgs(args);
        return ELEngine.invokeTarget(elctx, target, closures);
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
                    val instanceof Integer || val instanceof Short || val instanceof Byte;
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

    private void defineGlobal(String name, Object value) {
        // define: create/update in current scope only (head-bounded search)
        LiteralClosure lc = new LiteralClosure(value);
        if (evalContext != null) {
            evalContext.setVariable(name, lc);
        }
    }

    private void storeGlobal(String name, Object value) {
        // assign: search full chain, throw if not found
        ValueExpression ve = evalContext.resolveVariable(name);
        if (ve == null)
            throw new EvaluationException(elctx, _T(EL_UNDEFINED_IDENTIFIER, name));
        ve.setValue(elctx, value);
    }

    /**
     * Before a TRAMPOLINE, copy IR local variable values into the
     * EvaluationContext so the AST evaluator can see them.
     * Uses regular setVariable (current-scope only) to avoid overwriting
     * parent parameters during recursive calls.
     */
    private void syncLocalsToGlobals() {
        String[] names = function.varNames();
        if (names == null)
            return;
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
        if (names == null)
            return;
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
            throw new RuntimeException(_T(IR_FIELD_NOT_FOUND, fieldName,
                    base.getClass().getName()));
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
            throw new RuntimeException(_T(IR_FIELD_NOT_FOUND, fieldName,
                    base.getClass().getName()));
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
            String name = (String)key;
            MethodResolver mr = MethodResolver.getInstance(elctx);
            MethodClosure mc = null;
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
        throw new RuntimeException(_T(EL_PROPERTY_NOT_FOUND,
                base.getClass().getName(), key));
    }

    private void storeProperty(Object base, Object key, Object value) {
        ELContext elctx = evalContext.getELContext();
        elctx.getELResolver().setValue(elctx, base, key, value);
    }

    // ── Global variable resolution ──

    private Object resolveGlobal(String name) {
        ELContext elctx = evalContext.getELContext();

        // 1) Check the EvaluationContext's own variable resolution chain
        ValueExpression ve = evalContext.resolveVariable(name);
        if (ve != null) {
            return ve.getValue(elctx);
        }

        // 2) Check the FunctionMapper for global/imported functions
        MethodResolver mr = MethodResolver.getInstance(elctx);
        if (mr != null) {
            MethodClosure mc = mr.resolveGlobalMethod(elctx.getFunctionMapper(), name);
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
