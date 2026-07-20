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
import org.elite.eval.EvaluationContext;
import org.elite.eval.EvaluationException;
import org.elite.eval.Frame;
import org.elite.eval.Runtime;
import org.elite.eval.StackTrace;
import org.elite.eval.TypeCoercion;
import org.elite.eval.UserException;
import org.elite.eval.closure.LiteralClosure;
import org.elite.eval.closure.TypedClosure;
import org.elite.eval.seq.Cons;
import org.elite.eval.seq.DelayCons;
import org.elite.parser.ELNode;
import org.elite.parser.Position;

import javax.el.ELContext;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
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
      throw new EvaluationException(elctx,
                                    _T(EL_FN_BAD_ARG_COUNT, function.name(),
                                       nvars, argc));

    if (args != null)
      System.arraycopy(args, 0, locals, 0, args.length);

    // Fill missing parameters with default values.
    // Use paramCount (not args.length) as the upper bound for provided args
    // because expanded args from INVOKE_DYN/IRClosure include capture slots
    // at the end, inflating args.length.
    if (defs != null)
      System.arraycopy(defs, argc, locals, argc, nvars - argc);

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
    Frame frame = StackTrace.addFrame(elctx, function.name(), di.file(),
                                      di.lineForPC(0));

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

      case PUSH_ENV: {
        push(evalContext);
        break;
      }

      case PUSH_CTX: {
        push(elctx);
        break;
      }

      case PUSH_VAR: {
        push(locals[idx]);
        break;
      }

      case PUSH_GLOBAL: {
        String name = (String)constantPool[idx];
        push(Runtime.resolveGlobal(name, evalContext));
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
          case EMPTY  -> Builtin.__empty__(elctx, rhs);
          default -> { assert (false); yield null; }
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
        IRClosure body = (IRClosure)pop();
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
        push(Runtime.storeGlobal(val, name, evalContext));
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
        IRInterpreter callee = new IRInterpreter(evalContext, targetFn);
        Object[] args = (Object[])pop();
        push(callee.execute(args));
        break;
      }

      case INVOKE_METHOD: {
        Method m = (Method)constantPool[idx];
        Object[] args = new Object[m.getParameterCount()];
        for (int i = m.getParameterCount(); i >= 1; i--)
          args[i - 1] = pop();

        Object base = null;
        if (!Modifier.isStatic(m.getModifiers()))
          base = pop();

        try {
          Object result = m.invoke(base, args);
          if (m.getReturnType() != Void.TYPE)
            push(result);
        } catch (InvocationTargetException ex) {
          if (ex.getTargetException() instanceof EvaluationException)
            throw (EvaluationException)ex.getTargetException();
          throw new EvaluationException(elctx, ex.getTargetException());
        } catch (Exception ex) {
          throw new EvaluationException(elctx, ex);
        }
        break;
      }

      case NEW:
        // Ignore now, will create instance at CONSTRUCTOR
        break;
      case CONSTRUCTOR: {
        try {
          Constructor<?> cons = (Constructor<?>)constantPool[idx];
          Object[] args = new Object[cons.getParameterCount()];
          for (int i = cons.getParameterCount(); i >= 1; i--)
            args[i - 1] = pop();
          push(cons.newInstance(args));
        } catch (Exception ex) {
          throw new AssertionError(ex);
        }
        break;
      }

      case NEW_ARRAY: {
        Class<?> c = (Class<?>)constantPool[idx];
        push(Array.newInstance(c, cnt));
        break;
      }

      case LOAD_ARRAY: {
        push(Array.get(pop(), cnt));
        break;
      }

      case STORE_ARRAY: {
        Object value = pop();
        Object array = pop();
        Array.set(array, cnt, value);
        break;
      }

      case LOAD_FIELD: {
        try {
          Field field = (Field)constantPool[idx];
          Object obj = null;
          if (!Modifier.isStatic(field.getModifiers()))
            obj = pop();
          push(field.get(obj));
        } catch (IllegalAccessException ex) {
          throw new EvaluationException(elctx, ex);
        }
        break;
      }

      case STORE_FIELD: {
        try {
          Field field = (Field)constantPool[idx];
          Object value = pop();
          Object obj = null;
          if (!Modifier.isStatic(field.getModifiers()))
            obj = pop();
          field.set(obj, value);
        } catch (IllegalAccessException e) {
          throw new EvaluationException(elctx, e);
        }
        break;
      }

      case CHECKCAST, BOX, UNBOX:
        // No effect for IR interpreter.
        break;

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

      case NEW_TUPLE: {
        Object[] elems = new Object[cnt];
        for (int i = cnt - 1; i >= 0; i--)
          elems[i] = pop();
        push(elems);
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
          "Unknown IR opcode: " + Opcode.name(op) + " (" + op + ") at " +
          "ip=" + ip);
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
}
