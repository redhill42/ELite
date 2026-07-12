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
import elite.xml.XmlNode;
import org.elite.eval.*;
import org.elite.eval.Runtime;
import org.elite.eval.closure.LiteralClosure;
import org.elite.eval.closure.MethodClosure;
import org.elite.eval.closure.TypedClosure;
import org.elite.eval.seq.Cons;
import org.elite.eval.seq.DelayCons;
import org.elite.parser.ELNode;
import org.elite.parser.Position;
import org.elite.resolver.MethodResolver;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.el.ELContext;
import javax.el.ValueExpression;
import javax.xml.XMLConstants;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;

import static org.elite.eval.ELUtils.NO_RESULT;
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
    private final IRFunction function;

    // ── Execution state ──
    private Object[] stack;
    private int sp;            // stack pointer (points to next free slot)
    private final Object[] locals;
    private int ip;  // instruction pointer (absolute offset into code[])

    public IRInterpreter(EvaluationContext context, IRFunction function) {
        this.evalContext = context;
        this.function = function;
        this.locals = new Object[function.maxLocals()];
    }

    // ── Entry point ──

    public Object execute(Object[] args) {
        return execute(args, false);
    }

    public Object execute(Object[] args, boolean isTopLevel) {
        ELContext elctx = evalContext.getELContext();

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
        int[] blockOffsets = function.blockOffsets();
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
        DebugInfo di = function.debugInfo();
        Frame frame = StackTrace.addFrame(elctx, function.name(), di.file(), di.lineForPC(0));

        try {
            return interpret();
        } catch (RuntimeException e) {
            // Update frame position to error location
            int line = di.lineForPC(ip);
            if (line > 0)
                frame.setPos(Position.make(line, 1));
            throw e;
        } finally {
            StackTrace.removeFrame(elctx);
        }
    }

    // ── Main interpreter loop ──

    private Object interpret() {
        ELContext elctx = evalContext.getELContext();
        final int[] code = function.code();
        final Object[] constantPool = function.constantPool();
        final int[] blockOffsets = function.blockOffsets();

        for (; ; ) {
            int header = code[ip];
            int op = IRFormat.opcode(header);
            int cnt = IRFormat.payload(header);
            int idx = IRFormat.operand(header);

            switch (op) {
            case NOP:
                break;

            case PUSH_CONST: {
                push(constantPool[idx]);
                break;
            }

            case PUSH_VAR: {
                push(locals[idx]);
                break;
            }

            case PUSH_GLOBAL: {
                String name = (String)constantPool[idx];
                push(resolveGlobal(name));
                break;
            }

            case PUSH_TRUE: {
                push(true);
                break;
            }

            case PUSH_FALSE: {
                push(false);
                break;
            }

            case PUSH_NULL: {
                push(null);
                break;
            }

            case POP: {
                pop();
                break;
            }

            case POP_N: {
                sp -= cnt;
                break;
            }

            case DUP: {
                push(peek());
                break;
            }

            case ADD:
            case SUB:
            case MUL:
            case DIV:
            case IDIV:
            case REM:
            case POW:
            case CAT:
            case BITAND:
            case BITOR:
            case XOR:
            case SHL:
            case SHR:
            case USHR:
            case EQ:
            case NE:
            case LT:
            case LE:
            case GT:
            case GE: {
                // Delegate to Runtime for type-resolved arithmetic (shared with
                // bytecode path)
                Object rhs = pop();
                Object lhs = pop();
                push(switch (op) {
                case ADD    -> Builtin.__add__(elctx, lhs, rhs);
                case SUB    -> Builtin.__sub__(elctx, lhs, rhs);
                case MUL    -> Builtin.__mul__(elctx, lhs, rhs);
                case DIV    -> Builtin.__div__(elctx, lhs, rhs);
                case IDIV   -> Builtin.__idiv__(elctx, lhs, rhs);
                case REM    -> Builtin.__rem__(elctx, lhs, rhs);
                case POW    -> Builtin.__pow__(elctx, lhs, rhs);
                case CAT    -> Builtin.__cat__(elctx, lhs, rhs);
                case SHL    -> Builtin.__shl__(elctx, lhs, rhs);
                case SHR    -> Builtin.__shr__(elctx, lhs, rhs);
                case USHR   -> Builtin.__ushr__(elctx, lhs, rhs);
                case EQ     -> Builtin.__eq__(elctx, lhs, rhs);
                case NE     -> Builtin.__ne__(elctx, lhs, rhs);
                case LT     -> Builtin.__lt__(elctx, lhs, rhs);
                case LE     -> Builtin.__le__(elctx, lhs, rhs);
                case GT     -> Builtin.__gt__(elctx, lhs, rhs);
                case GE     -> Builtin.__ge__(elctx, lhs, rhs);
                case BITAND -> Builtin.__bitand__(elctx, lhs, rhs);
                case BITOR  -> Builtin.__bitor__(elctx, lhs, rhs);
                case XOR    -> Builtin.__xor__(elctx, lhs, rhs);
                default -> { assert (false); yield null; }
                });
                break;
            }

            case NEG:
            case BITNOT:
            case EMPTY: {
                Object rhs = pop();
                push(switch (op) {
                case NEG    -> Builtin.__neg__(elctx, rhs);
                case BITNOT -> Builtin.__bitnot__(elctx, rhs);
                case EMPTY  -> Builtin.empty(elctx, rhs);
                default -> { assert(false); yield null; }
                });
                break;
            }

            case IDEQ: {
                Object r = pop(), l = pop();
                push(l == r);
                break;
            }

            case IDNE: {
                Object r = pop(), l = pop();
                push(l != r);
                break;
            }

            case IN: {
                Object coll = pop();
                Object elem = pop();
                push(Builtin.__in__(elctx, elem, coll));
                break;
            }

            case INSTANCEOF: {
                Object obj = pop();
                Object cls = constantPool[idx];
                if (cls instanceof Class<?>)
                    push(((Class<?>)cls).isInstance(obj));
                else
                    push(TypedClosure.typecheck(evalContext, (String)cls, obj));
                break;
            }

            case JOIN: {
                StringBuilder sb = new StringBuilder();
                for (int i = cnt - 1; i >= 0; i--)
                    sb.insert(0, pop());
                push(sb.toString());
                break;
            }

            case NOT: {
                push(!TypeCoercion.coerceToBoolean(pop()));
                break;
            }

            case JUMP: {
                ip = blockOffsets[idx];
                continue;
            }

            case JUMP_IF_TRUE: {
                boolean cond = TypeCoercion.coerceToBoolean(pop());
                if (cond) {
                    ip = blockOffsets[idx];
                    continue;
                }
                break;
            }

            case JUMP_IF_FALSE: {
                boolean cond = TypeCoercion.coerceToBoolean(pop());
                if (!cond) {
                    ip = blockOffsets[idx];
                    continue;
                }
                break;
            }

            case JUMP_IF_NULL: {
                Object v = pop();
                if (v == null) {
                    ip = blockOffsets[idx];
                    continue;
                }
                break;
            }

            case JUMP_IF_NONNULL: {
                Object v = pop();
                if (v != null) {
                    ip = blockOffsets[idx];
                    continue;
                }
                break;
            }

            case RETURN: {
                Object result = pop();
                assert sp == 0;
                return result;
            }

            case TRY: {
                // Pop closures: top → finally, handlerN..., handler1, body → bottom
                IRClosure body;
                String[] types = null;
                IRClosure[] handlers = null;
                IRClosure finalizer;

                finalizer = (IRClosure)pop();
                if (cnt > 0) {
                    types = new String[cnt];
                    handlers = new IRClosure[cnt];
                    for (int i = cnt - 1; i >= 0; i--) {
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
                break;
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

            case SYNCHRONIZED: {
                IRClosure body = (IRClosure) pop();
                Object lock = pop();
                synchronized (lock) {
                    push(new IRInterpreter(evalContext, body.function).execute(null));
                }
                break;
            }

            case ASSERT: {
                try {
                    if (cnt == 1) {
                        Boolean exp = (Boolean)pop();
                        assert exp;
                    } else {
                        Object msg = pop();
                        Boolean exp = (Boolean)pop();
                        assert exp : msg;
                    }
                } catch (AssertionError ex) {
                    throw new EvaluationException(elctx, ex);
                }
                push(null);
                break;
            }

            case ENTER_SCOPE:
                evalContext = evalContext.pushContext();
                break;

            case LEAVE_SCOPE:
                evalContext = evalContext.popContext();
                break;

            case DEFINE_GLOBAL: {
                String name = (String)constantPool[idx];
                Object val = pop();
                evalContext.setVariable(name, new LiteralClosure(val));
                break;
            }

            case STORE_GLOBAL: {
                String name = (String)constantPool[idx];
                Object val = pop();
                ValueExpression ve = evalContext.resolveVariable(name);
                if (ve == null)
                    throw new EvaluationException(elctx, _T(EL_UNDEFINED_IDENTIFIER, name));
                ve.setValue(elctx, val);
                push(val);
                break;
            }

            case STORE_VAR: {
                Object val = pop();
                locals[idx] = val;
                push(val);
                break;
            }

            case STORE_VAR_POP: {
                locals[idx] = pop();
                break;
            }

            case CLOSURE: {
                IRFunction fn = (IRFunction)constantPool[idx];
                push(new IRClosure(evalContext, fn));
                break;
            }

            case INVOKE_DIRECT: {
                IRFunction targetFn = (IRFunction)constantPool[idx];

                // Pop arguments
                Object[] args = new Object[cnt];
                for (int i = cnt - 1; i >= 0; i--)
                    args[i] = pop();

                IRInterpreter callee = new IRInterpreter(evalContext, targetFn);
                push(callee.execute(args));
                break;
            }

            case INVOKE_TARGET: {
                String id = (String)constantPool[idx];

                // Pop arguments
                Object[] args = new Object[cnt];
                for (int i = cnt - 1; i >= 0; i--)
                    args[i] = pop();

                push(invokeTarget(id, args));
                break;
            }

            case INVOKE_OPERATOR: {
                String name = (String)constantPool[idx];
                if (cnt == 1) {
                    Object rhs = pop();
                    push(invokeOperator(name, rhs));
                } else {
                    Object rhs = pop();
                    Object lhs = pop();
                    push(invokeOperator(name, lhs, rhs));
                }
                break;
            }

            case INVOKE_DYN: {
                Object result = invokeDyn(cnt);
                push(result);
                break;
            }

            case INVOKE_METHOD: {
                Method m = (Method)constantPool[idx];
                Closure[] args = new Closure[cnt];
                for (int i = cnt - 1; i >= 0; i--) {
                    Object arg = pop();
                    args[i] = (arg instanceof Closure c) ? c : new LiteralClosure(arg);
                }
                Object base = pop();
                push(ELEngine.invokeMethod(elctx, base, m, args));
                break;
            }

            case INVOKE_STATIC: {
                Method m = (Method)constantPool[idx];
                Closure[] args = new Closure[cnt];
                for (int i = cnt - 1; i >= 0; i--) {
                    Object arg = pop();
                    args[i] = (arg instanceof Closure c) ? c : new LiteralClosure(arg);
                }
                push(ELEngine.invokeMethod(elctx, null, m, args));
                break;
            }

            case INVOKE_EXPANDO: {
                Method m = (Method)constantPool[idx];
                Closure[] args = new Closure[cnt + 1]; // +1 for expando base
                for (int i = cnt; i >= 1; i--) {
                    Object arg = pop();
                    args[i] = (arg instanceof Closure c) ? c : new LiteralClosure(arg);
                }
                args[0] = new LiteralClosure(pop());
                push(ELEngine.invokeMethod(elctx, null, m, args));
                break;
            }

            case LOAD_PROPERTY: {
                Object key = pop();
                Object base = pop();
                push(Runtime.loadProperty(elctx, base, key));
                break;
            }

            case STORE_PROPERTY: {
                Object key = pop();
                Object base = pop();
                Object value = pop();
                push(Runtime.storeProperty(elctx, base, key, value));
                break;
            }

            case INVOKE_GETTER: {
                Method m = (Method)constantPool[idx];
                Object base = pop();
                try {
                    push(m.invoke(base));
                } catch (Exception e) {
                    throw new RuntimeException(_T(IR_GETTER_INVOKE_FAILED), e);
                }
                break;
            }

            case INVOKE_SETTER: {
                Method m = (Method)constantPool[idx];
                Object value = pop();
                Object base = pop();
                try {
                    m.invoke(base, value);
                    push(value);
                } catch (Exception e) {
                    throw new RuntimeException(_T(IR_SETTER_INVOKE_FAILED), e);
                }
                break;
            }

            case LOAD_FIELD: {
                Object base = pop();
                String fieldName = (String)constantPool[idx];
                push(loadField(base, fieldName));
                break;
            }

            case STORE_FIELD: {
                Object value = pop();
                Object base = pop();
                String fieldName = (String)constantPool[idx];
                push(storeField(base, fieldName, value));
                break;
            }

            case NEW_CONS: {
                Object tail = pop();
                Object head = pop();
                if (!(tail instanceof Seq))
                    tail = TypeCoercion.coerceToSeq(tail);
                push(new Cons(head, (Seq)tail));
                break;
            }

            case NEW_DELAY_CONS: {
                Closure tail = (Closure)pop();
                Closure head = (Closure)pop();
                push(new DelayCons(head, tail));
                break;
            }

            case NIL: {
                push(Cons.nil());
                break;
            }

            case NEW_MAP: {
                LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
                for (int i = cnt - 1; i >= 0; i--) {
                    Object val = pop();
                    Object key = pop();
                    map.put(key, val);
                }
                push(map);
                break;
            }

            case NEW_TUPLE: {
                Object[] elems = new Object[cnt];
                for (int i = cnt - 1; i >= 0; i--)
                    elems[i] = pop();
                push(elems);
                break;
            }

            case NEW_RANGE: {
                Object end = pop();
                Object next = pop();
                Object begin = pop();
                push(newRange(begin, next, end));
                break;
            }

            case NEW_XML: {
                Object tag;
                Object[] att_names = null, att_values = null;
                Object[] children = null;
                if (idx != 0) {
                    children = new Object[idx];
                    for (int i = idx - 1; i >= 0; i--) {
                        children[i] = pop();
                    }
                }
                if (cnt != 0) {
                    att_names = new String[cnt];
                    att_values = new String[cnt];
                    for (int i = cnt - 1; i >= 0; i--) {
                        att_values[i] = pop();
                        att_names[i] = pop();
                    }
                }
                tag = pop();

                push(newXML(tag, att_names, att_values, children));
                break;
            }

            case DECLARE_NS: {
                String prefix = (String)constantPool[idx];
                String uri = TypeCoercion.coerceToString(pop());
                evalContext.declarePrefix(prefix, uri);
                break;
            }

            case TRAMPOLINE: {
                ELNode node = (ELNode)constantPool[idx];
                Object result = node.getValue(evalContext);
                push(result);
                break;
            }

            default:
                // Unknown opcode — trampoline to AST eval
                // This handles all the instructions we haven't
                // implemented yet
                throw new UnsupportedOperationException(
                        "Unknown IR opcode: " + Opcode.name(op) +
                        " (" + op + ") at " + "ip=" + ip);
            }

            ip++;
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

    // ── Dynamic invocation ──

    private Object resolveGlobal(String id) {
        ELContext elctx = evalContext.getELContext();

        ValueExpression expr = evalContext.resolveVariable(id);
        if (expr != null) {
            return expr.getValue(elctx);
        }

        elctx.setPropertyResolved(false);
        Object value = elctx.getELResolver().getValue(elctx, null, id);
        if (elctx.isPropertyResolved()) {
            return value;
        }

        MethodClosure method = MethodResolver.getInstance(elctx)
            .resolveGlobalMethod(evalContext.getFunctionMapper(), id);
        if (method != null) {
            return method;
        }

        throw new EvaluationException(elctx, _T(EL_UNDEFINED_IDENTIFIER, id));
    }

    private Object resolveTarget(String id) {
        ELContext elctx = evalContext.getELContext();

        ValueExpression expr = evalContext.resolveVariable(id);
        if (expr != null) {
            return (expr instanceof LiteralClosure lc) ? lc.getValue(null) :
                   (expr instanceof Closure) ? expr : expr.getValue(elctx);
        }

        MethodClosure method = MethodResolver.getInstance(elctx)
            .resolveGlobalMethod(evalContext.getFunctionMapper(), id);
        if (method != null)
            return method;

        elctx.setPropertyResolved(false);
        return elctx.getELResolver().getValue(elctx, null, id);
    }

    private Object invokeTarget(String id, Object... args) {
        ELContext elctx = evalContext.getELContext();
        Object target = resolveTarget(id);
        if (target == null)
            throw new EvaluationException(elctx, _T(EL_UNDEFINED_IDENTIFIER, id));

        try {
            return ELEngine.callTarget(elctx, target, args);
        } catch (RuntimeException ex) {
            throw new EvaluationException(elctx, ex);
        }
    }

    private Object invokeDyn(int argCount) {
        // Stack layout: target below, args on top
        // Stack: ... target arg0 arg1 ... argN
        Object[] args = new Object[argCount];
        for (int i = argCount - 1; i >= 0; i--)
            args[i] = pop();
        Object target = pop();

        ELContext elctx = evalContext.getELContext();
        try {
            return ELEngine.callTarget(elctx, target, args);
        } catch (RuntimeException ex) {
            throw new EvaluationException(elctx, ex);
        }
    }

    private Object invokeOperator(String name, Object rhs) {
        ELContext elctx = evalContext.getELContext();

        if (rhs != null) {
            Object result = ELNode.Unary.invokeOperator(elctx, name, rhs);
            if (result != NO_RESULT)
                return result;
        }

        return invokeTarget(name, rhs);
    }

    private Object invokeOperator(String name, Object lhs, Object rhs) {
        ELContext elctx = evalContext.getELContext();

        // invoke operator procedure
        Object result = ELNode.Binary.invokeOperator(elctx, name, lhs, rhs);
        if (result != NO_RESULT)
            return result;

        return invokeTarget(name, lhs, rhs);
    }

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
            f.set(target, TypeCoercion.coerce(evalContext.getELContext(), value, f.getType()));
            return value; // assignment returns the value
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(_T(IR_FIELD_NOT_FOUND, fieldName,
                                          base.getClass().getName()));
        } catch (IllegalAccessException e) {
            throw new RuntimeException(_T(IR_FIELD_ACCESS_ERROR, fieldName), e);
        }
    }

    private static boolean isChar(Object o) {
        if (o instanceof Character) {
            return true;
        } else if (o instanceof String) {
            return ((String)o).length() == 1;
        } else {
            return false;
        }
    }

    private static char getChar(Object o) {
        if (o instanceof Character) {
            return (Character)o;
        } else if (o instanceof String) {
            return ((String)o).charAt(0);
        } else {
            throw new AssertionError();
        }
    }

    private Object newRange(Object begin, Object next, Object end) {
        if (isChar(begin) && (next == null || isChar(next)) && (end == null || isChar(end))) {
            char c_begin = getChar(begin);
            int c_step = (next == null) ? 1 : getChar(next) - c_begin;
            if (end == null) {
                return CharRanges.createUnboundedRange(c_begin, c_step);
            } else {
                char c_end = getChar(end);
                return CharRanges.createCharRange(c_begin, c_end, c_step);
            }
        } else {
            long l_begin = TypeCoercion.coerceToLong(begin);
            long l_step = (next == null) ? 1 : TypeCoercion.coerceToLong(next) - l_begin;
            if (end == null) {
                return Ranges.createUnboundedRange(l_begin, l_step);
            } else {
                long l_end = TypeCoercion.coerceToLong(end);
                return Ranges.createRange(l_begin, l_end, l_step);
            }
        }
    }

    private Object newXML(Object tag, Object[] att_names, Object[] att_values,
                          Object[] children) {
        ELContext elctx = evalContext.getELContext();

        try {
            Document doc = XmlNode.getContextDocument(elctx);
            Element elem;
            String name = TypeCoercion.coerceToString(tag);
            String prefix, uri;
            int colon;

            // handle element namespace
            colon = name.indexOf(':');
            if (colon == -1)
                prefix = XMLConstants.DEFAULT_NS_PREFIX;
            else
                prefix = name.substring(0, colon);
            uri = evalContext.getURI(prefix);
            if (uri == null)
                elem = doc.createElement(name);
            else
                elem = doc.createElementNS(uri, name);

            // set element attributes
            if (att_names != null) {
                for (int i = 0; i < att_names.length; i++) {
                    String key = TypeCoercion.coerceToString(att_names[i]);
                    String value = TypeCoercion.coerceToString(att_values[i]);
                    if (key.equals("xmlns") || key.startsWith("xmlns:")) {
                        uri = XMLConstants.XMLNS_ATTRIBUTE_NS_URI;
                    } else {
                        colon = key.indexOf(':');
                        if (colon == -1)
                            prefix = XMLConstants.DEFAULT_NS_PREFIX;
                        else
                            prefix = key.substring(0, colon);
                        uri = evalContext.getURI(prefix);
                    }
                    if (uri == null)
                        elem.setAttribute(key, value);
                    else
                        elem.setAttributeNS(uri, key, value);
                }
            }

            // recursively create child nodes
            if (children != null) {
                for (Object child : children) {
                    org.w3c.dom.Node node = XmlNode.coerceToNode(elctx, child);
                    if (node != null) {
                        elem.appendChild(node);
                    }
                }
            }

            return XmlNode.valueOf(elem);
        } catch (DOMException ex) {
            throw new EvaluationException(elctx, ex);
        }
    }
}
