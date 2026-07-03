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
import elite.lang.Seq;
import org.elite.eval.*;
import org.elite.eval.Runtime;
import org.elite.eval.closure.LiteralClosure;
import org.elite.eval.closure.TypedClosure;
import org.elite.eval.seq.Cons;
import org.elite.eval.seq.DelayCons;
import org.elite.parser.ELNode;
import org.elite.parser.Position;

import javax.el.ELContext;
import javax.el.ValueExpression;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;

import static org.elite.ir.Opcode.*;
import static org.elite.resources.Resources.*;

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
    private Frame frame; // current stack frame (debug only)

    public IRInterpreter(EvaluationContext context, IRFunction function) {
        this.evalContext = context;
        this.elctx = context.getELContext();
        this.function = function;
        this.code = function.code();
        this.locals = new Object[function.maxLocalCount()];
        this.constantPool = function.constantPool();
        this.blockOffsets = function.blockOffsets();
        this.debug = ELProgram.DEBUG;
    }

    // ── Entry point ──

    public Object execute(Object[] args) {
        return execute(args, false);
    }

    public Object execute(Object[] args, boolean isTopLevel) {
        this.stack = new Object[DEFAULT_STACK_SIZE];
        this.sp = 0;

        int nvars = function.paramCount();
        int argc = args != null ? args.length : 0;

        Object[] defs = function.defaultValues();
        if ((argc > nvars) || (argc < nvars && defs == null))
            throw new EvaluationException(elctx, _T(EL_FN_BAD_ARG_COUNT,
                                          function.name(), nvars, argc));

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

        // Start at first block
        ip = blockOffsets.length > 0 ? blockOffsets[0] : 0;

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
            frame = StackTrace.addFrame(elctx, fnName, fileName, blockPos);
        }

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
                int idx = pl & 0xFFFF;
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
            case DYNXOR: {
                push(dynamicBinaryOp(op));
                ip += 1;
                break;
            }

            case DYNNEG:
            case DYNNOT:
            case DYNEMPTY: {
                push(dynamicUnaryOp(op));
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
            case INVOKE_TARGET: {
                // target name pool index in payload
                // argCount in first operand
                int nameIdx = pl;
                int argc = oc == 0 ? 0 : code[ip + 1];
                String id = (String)constantPool[nameIdx];

                // Pop arguments
                Object[] args = new Object[argc];
                for (int i = argc - 1; i >= 0; i--)
                    args[i] = pop();

                push(Runtime.invokeTarget(evalContext, id, args));
                ip += 1 + oc;
                break;
            }
            case INVOKE_OPERATOR: {
                int nameIdx = pl;
                int argc = oc == 0 ? 0 : code[ip + 1];
                String name = (String)constantPool[nameIdx];
                if (argc == 1) {
                    Object rhs = pop();
                    push(Runtime.invokeOperator(evalContext, name, rhs));
                } else {
                    Object rhs = pop();
                    Object lhs = pop();
                    push(Runtime.invokeOperator(evalContext, name, lhs, rhs));
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

            // ============ Return ============
            case RETURN:
                return pop();
            case RETURN_VOID:
                return null;
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
            case SYNCHRONIZED: {
                IRClosure body = (IRClosure) pop();
                Object lock = pop();
                synchronized (lock) {
                    push(new IRInterpreter(evalContext, body.function).execute(null));
                }
                ip += 1;
                break;
            }
            case TRY: {
                // Pop closures: top → finally, handlerN..., handler1, body → bottom
                int handlerCount = pl & 0xFFFF;
                IRClosure body;
                String[] types = null;
                IRClosure[] handlers = null;
                IRClosure finalizer;

                finalizer = (IRClosure)pop();
                if (handlerCount > 0) {
                    types = new String[handlerCount];
                    handlers = new IRClosure[handlerCount];
                    for (int i = handlerCount - 1; i >= 0; i--) {
                        handlers[i] = (IRClosure)pop();
                        types[i] = (String)pop();
                    }
                }
                body = (IRClosure)pop();

                Object result;
                try {
                    result = new IRInterpreter(evalContext, body.function).execute(null);
                } catch (RuntimeException | Error ex) {
                    Throwable t = ex;
                    if (ex instanceof EvaluationException) {
                        t = ex.getCause();
                        if (t == null)
                            t = ex;
                    }

                    IRClosure handler = null;
                    if (handlers != null) {
                        for (int i = 0; i < handlers.length; i++) {
                            if (types[i] != null) {
                                if (TypedClosure.typecheck(evalContext, types[i], t)) {
                                    handler = handlers[i];
                                    break;
                                }
                            } else {
                                handler = handlers[i];
                                break;
                            }
                        }
                    }

                    if (handler != null) {
                        result = new IRInterpreter(evalContext, handler.function)
                            .execute(new Object[]{t});
                    } else {
                        // Rethrow exception
                        throw ex;
                    }
                } finally {
                    if (finalizer != null) {
                        new IRInterpreter(evalContext, finalizer.function).execute(null);
                    }
                }

                push(result);
                ip += 1;
                break;
            }
            case ASSERT: {
                Object msg = pop();
                Boolean exp = (Boolean)pop();
                try {
                    if (msg == null) {
                        assert exp;
                    } else {
                        assert exp : msg;
                    }
                } catch (AssertionError ex) {
                    throw new EvaluationException(elctx, ex);
                }
                push(null);
                ip += 1;
                break;
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
                Object val = Runtime.resolveGlobal(evalContext, name);
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

            // ============ Property / index access ============
            case LOAD_PROPERTY: {
                Object key = pop();
                Object base = pop();
                push(Runtime.loadProperty(elctx, base, key));
                ip += 1;
                break;
            }
            case STORE_PROPERTY: {
                Object key = pop();
                Object base = pop();
                Object value = pop();
                push(Runtime.storeProperty(elctx, base, key, value));
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
            case NEW_CONS: {
                Object tail = pop();
                Object head = pop();
                if (!(tail instanceof Seq))
                    tail = TypeCoercion.coerceToSeq(tail);
                push(new Cons(head, (Seq)tail));
                ip += 1;
                break;
            }
            case NEW_DELAY_CONS: {
                Closure tail = (Closure)pop();
                Closure head = (Closure)pop();
                push(new DelayCons(head, tail));
                ip += 1;
                break;
            }
            case NIL: {
                push(Cons.nil());
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
                push(Runtime.newRange(begin, next, end));
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

            // ============ DynIn ============
            case DYNIN: {
                Object elem = pop();
                Object coll = pop();
                push(Builtin.__in__(elctx, elem, coll));
                ip += 1;
                break;
            }

            case INSTOF: {
                Object obj = pop();
                Object cls = constantPool[pl];
                if (cls instanceof Class<?>)
                    push(((Class<?>)cls).isInstance(obj));
                else
                    push(TypedClosure.typecheck(evalContext, (String)cls, obj));
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
                int count = pl;
                StringBuilder sb = new StringBuilder();
                for (int i = count - 1; i >= 0; i--)
                    sb.insert(0, pop());
                push(sb.toString());
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
                Closure[] args = new Closure[argc];
                for (int i = argc - 1; i >= 0; i--) {
                    Object arg = pop();
                    args[i] = (arg instanceof Closure c) ? c : new LiteralClosure(arg);
                }
                Object base = pop();
                push(ELEngine.invokeMethod(elctx, base, m, args));
                ip += 1 + oc;
                break;
            }
            case INVOKE_STATIC: {
                Method m = (Method)constantPool[pl];
                int argc = oc > 0 ? code[ip + 1] : 0;
                Closure[] args = new Closure[argc];
                for (int i = argc - 1; i >= 0; i--) {
                    Object arg = pop();
                    args[i] = (arg instanceof Closure c) ? c : new LiteralClosure(arg);
                }
                push(ELEngine.invokeMethod(elctx, null, m, args));
                ip += 1 + oc;
                break;
            }
            case INVOKE_EXPANDO: {
                Method m = (Method)constantPool[pl];
                int argc = oc > 0 ? code[ip + 1] : 0;
                Closure[] args = new Closure[argc + 1]; // +1 for expando base
                for (int i = argc; i >= 1; i--) {
                    Object arg = pop();
                    args[i] = (arg instanceof Closure c) ? c : new LiteralClosure(arg);
                }
                args[0] = new LiteralClosure(pop());
                push(ELEngine.invokeMethod(elctx, null, m, args));
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
                push(Runtime.invokeDynMethod(elctx, base, key, args));
                ip += 1 + oc;
                break;
            }

            // ============ Closure creation ============
            case CLOSURE: {
                int funcIdx = pl;
                IRFunction fn = (IRFunction)constantPool[funcIdx];
                push(new IRClosure(evalContext, fn));
                ip += 1 + oc;
                break;
            }

            case ENTER_SCOPE:
                evalContext = evalContext.pushContext();
                ip += 1;
                break;
            case LEAVE_SCOPE:
                evalContext = evalContext.popContext();
                ip += 1;
                break;

            // ============ Trampoline to AST evaluator ============
            case TRAMPOLINE: {
                int poolIdx = pl;
                Object obj = constantPool[poolIdx];
                // TryDescriptor wraps pre-compiled IR blocks; evaluate
                // the original TRY node
                ELNode node = (ELNode)obj;
                Object result = node.getValue(evalContext);
                push(result);
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

            default:
                // Unknown opcode — trampoline to AST eval
                // This handles all the instructions we haven't
                // implemented yet
                throw new UnsupportedOperationException(
                        "Unknown IR " +  "opcode: " + Opcode.name(op) +
                        " (" + op + ") at " + "ip=" + ip);
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

    // ── Dynamic operation support ──

    private Object dynamicBinaryOp(int irOpcode) {
        // Delegate to Runtime for type-resolved arithmetic (shared with
        // bytecode path)
        Object rhs = pop();
        Object lhs = pop();
        return switch (irOpcode) {
            case DYNADD -> Builtin.__add__(elctx, lhs, rhs);
            case DYNSUB -> Builtin.__sub__(elctx, lhs, rhs);
            case DYNMUL -> Builtin.__mul__(elctx, lhs, rhs);
            case DYNDIV -> Builtin.__div__(elctx, lhs, rhs);
            case DYNREM -> Builtin.__rem__(elctx, lhs, rhs);
            case DYNPOW -> Builtin.__pow__(elctx, lhs, rhs);
            case DYNCAT -> Builtin.__cat__(elctx, lhs, rhs);
            case DYNSHL -> Builtin.__shl__(elctx, lhs, rhs);
            case DYNSHR -> Builtin.__shr__(elctx, lhs, rhs);
            case DYNUSHR -> Builtin.__ushr__(elctx, lhs, rhs);
            case DYNEQ -> Builtin.__eq__(elctx, lhs, rhs);
            case DYNNE -> Builtin.__ne__(elctx, lhs, rhs);
            case DYNLT -> Builtin.__lt__(elctx, lhs, rhs);
            case DYNLE -> Builtin.__le__(elctx, lhs, rhs);
            case DYNGT -> Builtin.__gt__(elctx, lhs, rhs);
            case DYNGE -> Builtin.__ge__(elctx, lhs, rhs);
            case DYNAND -> Builtin.__bitand__(elctx, lhs, rhs);
            case DYNOR -> Builtin.__bitor__(elctx, lhs, rhs);
            case DYNXOR -> Builtin.__xor__(elctx, lhs, rhs);
            default -> { assert (false); yield null; }
        };
    }

    private Object dynamicUnaryOp(int irOpcode) {
        Object rhs = pop();
        return switch (irOpcode) {
            case DYNNEG -> Builtin.__neg__(elctx, rhs);
            case DYNNOT -> Builtin.__bitnot__(elctx, rhs);
            case DYNEMPTY -> Builtin.empty(elctx, rhs);
            default -> { assert(false); yield null; }
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
        evalContext.setVariable(name, lc);
    }

    private void storeGlobal(String name, Object value) {
        // assign: search full chain, throw if not found
        ValueExpression ve = evalContext.resolveVariable(name);
        if (ve == null)
            throw new EvaluationException(elctx, _T(EL_UNDEFINED_IDENTIFIER, name));
        ve.setValue(elctx, value);
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
}
