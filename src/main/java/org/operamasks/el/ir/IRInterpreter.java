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

import javax.el.ELContext;
import java.math.BigDecimal;
import java.math.BigInteger;

import org.operamasks.el.eval.*;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Position;
import org.operamasks.el.parser.Token;
import elite.lang.Rational;
import elite.lang.Decimal;

import static org.operamasks.el.ir.Opcode.*;

/**
 * Stack-based interpreter for ELite IR.
 *
 * Executes a linear int[] instruction stream using a switch-dispatch loop
 * and an operand stack. This replaces the recursive tree-walking of the
 * AST evaluator.
 *
 * <p>Dynamically-typed operations and complex features delegate to the
 * existing AST evaluator via the "trampoline" mechanism (opcode 0xE0).
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
    private int ip;            // instruction pointer (absolute offset into code[])

    // ── Trampoline support ──
    private EvaluationContext evalContext;

    public IRInterpreter(ELContext elctx, IRFunction function) {
        this(elctx, function, null);
    }

    /** Create an interpreter that inherits variable bindings from an existing EvaluationContext. */
    public IRInterpreter(ELContext elctx, IRFunction function, EvaluationContext parentEnv) {
        this.elctx = elctx;
        this.function = function;
        this.code = function.code();
        this.constantPool = function.constantPool();
        this.blockOffsets = function.blockOffsets();
        this.evalContext = parentEnv != null ? parentEnv : new EvaluationContext(elctx);
    }

    // ── Entry point ──

    public Object execute(Object[] args) {
        this.stack = new Object[DEFAULT_STACK_SIZE];
        this.sp = 0;
        this.locals = new Object[DEFAULT_LOCALS_SIZE];

        // evalContext is set by constructor

        // Bind arguments to locals
        if (args != null) {
            for (int i = 0; i < args.length && i < locals.length; i++) {
                locals[i] = args[i];
            }
        }

        // Start at first block
        ip = blockOffsets.length > 0 ? blockOffsets[0] : 0;

        return interpret();
    }

    // ── Main interpreter loop ──

    private Object interpret() {
        for (;;) {
            int header = code[ip];
            int op = IRFormat.opcode(header);
            int k  = IRFormat.kind(header);
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
                    push(locals[idx]);
                    ip += 1;
                    break;
                }
                case POP: { pop(); ip += 1; break; }
                case DUP: { push(peek()); ip += 1; break; }
                case POP_N: {
                    sp -= pl; ip += 1;
                    break;
                }

                // ============ Typed int arithmetic ============
                case IADD: { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l+r); ip+=1; break; }
                case ISUB: { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l-r); ip+=1; break; }
                case IMUL: { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l*r); ip+=1; break; }
                case IDIV: { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l/r); ip+=1; break; }
                case IREM: { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l%r); ip+=1; break; }
                case INEG: { int v=((Number)pop()).intValue(); push(-v); ip+=1; break; }

                // ============ Typed long arithmetic ============
                case LADD: { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l+r); ip+=1; break; }
                case LSUB: { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l-r); ip+=1; break; }
                case LMUL: { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l*r); ip+=1; break; }
                case LDIV: { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l/r); ip+=1; break; }
                case LREM: { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l%r); ip+=1; break; }
                case LNEG: { long v=((Number)pop()).longValue(); push(-v); ip+=1; break; }

                // ============ Typed double arithmetic ============
                case DADD: { double r=((Number)pop()).doubleValue(),l=((Number)pop()).doubleValue(); push(l+r); ip+=1; break; }
                case DSUB: { double r=((Number)pop()).doubleValue(),l=((Number)pop()).doubleValue(); push(l-r); ip+=1; break; }
                case DMUL: { double r=((Number)pop()).doubleValue(),l=((Number)pop()).doubleValue(); push(l*r); ip+=1; break; }
                case DDIV: { double r=((Number)pop()).doubleValue(),l=((Number)pop()).doubleValue(); push(l/r); ip+=1; break; }
                case DNEG: { double v=((Number)pop()).doubleValue(); push(-v); ip+=1; break; }

                // ============ Power ============
                case IPOW: { int e=(Integer)pop(),b=(Integer)pop(); push((int)Math.pow(b,e)); ip+=1; break; }
                case LPOW: { long e=((Number)pop()).longValue(),b=((Number)pop()).longValue(); push((long)Math.pow(b,e)); ip+=1; break; }
                case DPOW: { double e=((Number)pop()).doubleValue(),b=((Number)pop()).doubleValue(); push(Math.pow(b,e)); ip+=1; break; }

                // ============ Typed bitwise (int) ============
                case IAND: { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l&r); ip+=1; break; }
                case IOR:  { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l|r); ip+=1; break; }
                case IXOR: { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l^r); ip+=1; break; }
                case ISHL: { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l<<r); ip+=1; break; }
                case ISHR: { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l>>r); ip+=1; break; }
                case IUSHR:{ int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l>>>r); ip+=1; break; }
                case IBITNOT: { int v=((Number)pop()).intValue(); push(~v); ip+=1; break; }

                // ============ Typed bitwise (long) ============
                case LAND: { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l&r); ip+=1; break; }
                case LOR:  { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l|r); ip+=1; break; }
                case LXOR: { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l^r); ip+=1; break; }
                case LSHL: { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l<<r); ip+=1; break; }
                case LSHR: { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l>>r); ip+=1; break; }
                case LUSHR:{ long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l>>>r); ip+=1; break; }
                case LBITNOT:{ long v=((Number)pop()).longValue(); push(~v); ip+=1; break; }

                // ============ Dynamic arithmetic ============
                case DYNADD: case DYNSUB: case DYNMUL: case DYNDIV:
                case DYNREM: case DYNNEG: case DYNPOW:
                case DYNCAT:
                case DYNEQ: case DYNLT: case DYNLE: case DYNIN: {
                    push(dynamicOp(op));
                    ip += 1;
                    break;
                }

                // ============ Typed int comparisons ============
                case IEQ: { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l==r); ip+=1; break; }
                case INE: { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l!=r); ip+=1; break; }
                case ILT: { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l<r);  ip+=1; break; }
                case ILE: { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l<=r); ip+=1; break; }
                case IGT: { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l>r);  ip+=1; break; }
                case IGE: { int r=((Number)pop()).intValue(),l=((Number)pop()).intValue(); push(l>=r); ip+=1; break; }

                case LEQ: { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l==r); ip+=1; break; }
                case LNE: { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l!=r); ip+=1; break; }
                case LLT: { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l<r);  ip+=1; break; }
                case LLE: { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l<=r); ip+=1; break; }
                case LGT: { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l>r);  ip+=1; break; }
                case LGE: { long r=((Number)pop()).longValue(),l=((Number)pop()).longValue(); push(l>=r); ip+=1; break; }

                case DEQ: { double r=((Number)pop()).doubleValue(),l=((Number)pop()).doubleValue(); push(l==r); ip+=1; break; }
                case DNE: { double r=((Number)pop()).doubleValue(),l=((Number)pop()).doubleValue(); push(l!=r); ip+=1; break; }
                case DLT: { double r=((Number)pop()).doubleValue(),l=((Number)pop()).doubleValue(); push(l<r);  ip+=1; break; }
                case DLE: { double r=((Number)pop()).doubleValue(),l=((Number)pop()).doubleValue(); push(l<=r); ip+=1; break; }
                case DGT: { double r=((Number)pop()).doubleValue(),l=((Number)pop()).doubleValue(); push(l>r);  ip+=1; break; }
                case DGE: { double r=((Number)pop()).doubleValue(),l=((Number)pop()).doubleValue(); push(l>=r); ip+=1; break; }

                // ============ Boolean constants ============
                case PUSH_TRUE:  { push(true);  ip+=1; break; }
                case PUSH_FALSE: { push(false); ip+=1; break; }
                case PUSH_NULL:  { push(null);  ip+=1; break; }

                // ============ Control flow ============
                case JUMP: {
                    int target = oc == 0 ? pl : code[ip + 1];
                    ip = blockOffsets[target];
                    break;
                }
                case JUMP_IF_TRUE: {
                    boolean cond = coerceToBoolean(pop());
                    if (cond) { ip = blockOffsets[oc == 0 ? pl : code[ip + 1]]; }
                    else { ip += 1 + oc; }
                    break;
                }
                case JUMP_IF_FALSE: {
                    boolean cond = coerceToBoolean(pop());
                    if (!cond) { ip = blockOffsets[oc == 0 ? pl : code[ip + 1]]; }
                    else { ip += 1 + oc; }
                    break;
                }
                case JUMP_IF_NULL: {
                    Object v = pop();
                    if (v == null) { ip = blockOffsets[oc == 0 ? pl : code[ip + 1]]; }
                    else { ip += 1 + oc; }
                    break;
                }
                case JUMP_IF_NONNULL: {
                    Object v = pop();
                    if (v != null) { ip = blockOffsets[oc == 0 ? pl : code[ip + 1]]; }
                    else { ip += 1 + oc; }
                    break;
                }

                // ============ Function calls ============
                case INVOKE_TAIL: {
                    int argc = pl;
                    for (int i = argc - 1; i >= 0; i--) {
                        locals[i] = pop();
                    }
                    ip = blockOffsets[0];
                    break;
                }
                case INVOKE_DIRECT: {
                    int funcIdx = pl;  // function pool index in payload
                    int argc = oc == 0 ? 0 : code[ip + 1];  // argCount in first operand
                    IRFunction targetFn = (IRFunction) constantPool[funcIdx];
                    // Pop arguments
                    Object[] args = new Object[argc];
                    for (int i = argc - 1; i >= 0; i--) args[i] = pop();
                    // Direct call: avoids ELEngine.invokeTarget overhead
                    IRInterpreter callee = new IRInterpreter(elctx, targetFn, evalContext);
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
                    if (cause instanceof RuntimeException re) throw re;
                    if (cause instanceof Throwable t)
                        throw new org.operamasks.el.eval.UserException(elctx, t);
                    if (cause instanceof String s)
                        throw new org.operamasks.el.eval.UserException(elctx, s);
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
                    String name = (String) constantPool[nameIdx];
                    Object val = resolveGlobal(name);
                    push(val);
                    ip += 1 + oc;
                    break;
                }
                case STORE_GLOBAL: {
                    int nameIdx = oc == 0 ? pl : code[ip + 1];
                    String name = (String) constantPool[nameIdx];
                    Object val = pop();
                    storeGlobal(name, val);
                    push(val);  // assignment returns the value
                    ip += 1 + oc;
                    break;
                }

                // ============ Increment / Decrement ============
                case INC: {
                    int idx = pl;
                    Object val = locals[idx];
                    if (val instanceof Long l) locals[idx] = l + 1;
                    else if (val instanceof Integer i) locals[idx] = i + 1;
                    else if (val instanceof Double d) locals[idx] = d + 1.0;
                    else locals[idx] = ((Number) val).longValue() + 1;
                    push(locals[idx]);
                    ip += 1;
                    break;
                }
                case DEC: {
                    int idx = pl;
                    Object val = locals[idx];
                    if (val instanceof Long l) locals[idx] = l - 1;
                    else if (val instanceof Integer i) locals[idx] = i - 1;
                    else if (val instanceof Double d) locals[idx] = d - 1.0;
                    else locals[idx] = ((Number) val).longValue() - 1;
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
                    String fieldName = (String) constantPool[pl];
                    push(loadField(base, fieldName));
                    ip += 1 + oc;
                    break;
                }
                case STORE_FIELD: {
                    Object value = pop();
                    Object base = pop();
                    String fieldName = (String) constantPool[pl];
                    push(storeField(base, fieldName, value));
                    ip += 1 + oc;
                    break;
                }

                // ============ Collections ============
                case NEW_LIST: {
                    int count = pl;
                    Object[] elements = new Object[count];
                    for (int i = count - 1; i >= 0; i--) elements[i] = pop();
                    push(java.util.Arrays.asList(elements));
                    ip += 1;
                    break;
                }
                case NEW_MAP: {
                    int count = pl;
                    java.util.LinkedHashMap<Object, Object> map = new java.util.LinkedHashMap<>();
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
                    push(org.operamasks.el.eval.Ranges.createRange(
                        ((Number) begin).longValue(), ((Number) end).longValue(), 1));
                    ip += 1;
                    break;
                }
                case NEW_TUPLE: {
                    int count = pl;
                    Object[] elems = new Object[count];
                    for (int i = count - 1; i >= 0; i--) elems[i] = pop();
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
                    java.util.Iterator<?> it = (java.util.Iterator<?>) pop();
                    Object next = it.hasNext() ? it.next() : null;
                    push(it);    // iterator first (bottom)
                    push(next);  // value on top (popped by ITER_DONE or STORE_VAR)
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

                // ============ Contains ============
                case CONTAINS: {
                    Object elem = pop();
                    Object coll = pop();
                    push(contains(coll, elem));
                    ip += 1;
                    break;
                }

                // ============ Unary logic ============
                case NOT: {
                    push(!coerceToBoolean(pop()));
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
                    java.lang.reflect.Method m = (java.lang.reflect.Method) constantPool[pl];
                    Object base = pop();
                    try { push(m.invoke(base)); }
                    catch (Exception e) { throw new RuntimeException("getter invoke failed", e); }
                    ip += 1 + oc;
                    break;
                }
                case INVOKE_SETTER: {
                    java.lang.reflect.Method m = (java.lang.reflect.Method) constantPool[pl];
                    Object value = pop();
                    Object base = pop();
                    try { m.invoke(base, value); push(value); }
                    catch (Exception e) { throw new RuntimeException("setter invoke failed", e); }
                    ip += 1 + oc;
                    break;
                }
                case INVOKE_METHOD: {
                    java.lang.reflect.Method m = (java.lang.reflect.Method) constantPool[pl];
                    int argc = oc > 0 ? code[ip + 1] : 0;
                    Object[] args = new Object[argc];
                    for (int i = argc - 1; i >= 0; i--) args[i] = pop();
                    Object base = pop();
                    try {
                        // Coerce args to match method's parameter types
                        java.lang.Class<?>[] paramTypes = m.getParameterTypes();
                        for (int i = 0; i < argc && i < paramTypes.length; i++) {
                            args[i] = coerceArg(args[i], paramTypes[i]);
                        }
                        push(m.invoke(base, args));
                    } catch (Exception e) { throw new RuntimeException("method invoke failed", e); }
                    ip += 1 + oc;
                    break;
                }

                // ============ Closure creation ============
                case CLOSURE: {
                    int funcIdx = pl;
                    int captureCount = oc > 0 ? code[ip + 1] : 0;
                    IRFunction fn = (IRFunction) constantPool[funcIdx];
                    Object[] captured = new Object[captureCount];
                    for (int i = captureCount - 1; i >= 0; i--) captured[i] = pop();
                    push(new IRClosure(fn, captured));
                    ip += 1 + oc;
                    break;
                }

                // ============ Trampoline to AST evaluator ============
                case 0xE0: { // OP_INTERP_TRAMPOLINE
                    int poolIdx = oc == 0 ? pl : code[ip + 1];
                    ELNode node = (ELNode) constantPool[poolIdx];
                    Object result = node.getValue(evalContext);
                    push(result);
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
                            throw new RuntimeException(
                                "Type mismatch: expected " + expected + ", got " + actual);
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
                    // This handles all the instructions we haven't implemented yet
                    throw new UnsupportedOperationException(
                        "Unknown IR opcode: " + Opcode.name(op) + " (" + op + ") at ip=" + ip);
                }
            }
        }
    }

    // ── Stack helpers ──

    private void push(Object v) {
        if (sp >= stack.length) growStack();
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

    private Object dynamicOp(int irOpcode) {
        Object rhs = pop();
        Object lhs = pop();
        // For non-Number operands (e.g. ClosureObject from user-defined classes),
        // delegate to AST operator resolution via trampoline node.
        if (needsTrampolineDispatch(lhs, rhs)) {
            return trampolineBinaryOp(irOpcode, lhs, rhs);
        }
        return switch (irOpcode) {
            case DYNADD -> dynamicAdd(lhs, rhs);
            case DYNSUB -> dynamicSub(lhs, rhs);
            case DYNMUL -> dynamicMul(lhs, rhs);
            case DYNDIV -> dynamicDiv(lhs, rhs);
            case DYNREM -> dynamicRem(lhs, rhs);
            case DYNNEG -> dynamicNeg(lhs);
            case DYNPOW -> dynamicPow(lhs, rhs);
            case DYNCAT -> dynamicCat(lhs, rhs);
            case DYNEQ  -> lhs == null ? rhs == null : lhs.equals(rhs);
            case DYNLT  -> dynamicLt(lhs, rhs);
            case DYNLE  -> dynamicLe(lhs, rhs);
            default -> dynamicAdd(lhs, rhs); // fallback
        };
    }
    private static boolean needsTrampolineDispatch(Object lhs, Object rhs) {
        return (lhs instanceof org.operamasks.el.eval.closure.ClosureObject
             || rhs instanceof org.operamasks.el.eval.closure.ClosureObject);
    }
    private Object trampolineBinaryOp(int irOpcode, Object lhs, Object rhs) {
        int tokenOp = switch (irOpcode) {
            case DYNADD -> Token.ADD; case DYNSUB -> Token.SUB;
            case DYNMUL -> Token.MUL; case DYNDIV -> Token.DIV;
            case DYNREM -> Token.REM;
            case DYNPOW -> Token.POW; case DYNCAT -> Token.CAT;
            case DYNEQ  -> Token.EQ;  case DYNLT  -> Token.LT;
            case DYNLE  -> Token.LE;
            default -> Token.ADD;
        };
        int pos = org.operamasks.el.parser.Position.make(0, 0);
        var leftC  = new org.operamasks.el.parser.ELNode.CONST(pos, lhs);
        var rightC = new org.operamasks.el.parser.ELNode.CONST(pos, rhs);
        var infix = new org.operamasks.el.parser.ELNode.INFIX(pos, Token.opNames[tokenOp], 100, leftC, rightC);
        return infix.getValue(evalContext);
    }

    // Simplified dynamic dispatch — mirrors ELNode.Arithmetic.evaluate()
    private Number dynamicAdd(Object x, Object y) {
        if (x instanceof Integer && y instanceof Integer) return ((Integer)x) + ((Integer)y);
        if (x instanceof Long && y instanceof Long) return ((Long)x) + ((Long)y);
        if (x instanceof Double && y instanceof Double) return ((Double)x) + ((Double)y);
        if (x instanceof BigDecimal && y instanceof BigDecimal) return ((BigDecimal)x).add((BigDecimal)y);
        if (x instanceof BigInteger && y instanceof BigInteger) return ((BigInteger)x).add((BigInteger)y);
        if (x instanceof Rational && y instanceof Rational) return ((Rational)x).add((Rational)y).reduce();
        if (x instanceof Decimal && y instanceof Decimal) return ((Decimal)x).add((Decimal)y);
        // Coerce to double as fallback
        return ((Number)x).doubleValue() + ((Number)y).doubleValue();
    }

    private Number dynamicSub(Object x, Object y) {
        if (x instanceof Integer && y instanceof Integer) return ((Integer)x) - ((Integer)y);
        if (x instanceof Long && y instanceof Long) return ((Long)x) - ((Long)y);
        if (x instanceof Double && y instanceof Double) return ((Double)x) - ((Double)y);
        return ((Number)x).doubleValue() - ((Number)y).doubleValue();
    }

    private Number dynamicMul(Object x, Object y) {
        if (x instanceof Integer && y instanceof Integer) return ((Integer)x) * ((Integer)y);
        if (x instanceof Long && y instanceof Long) return ((Long)x) * ((Long)y);
        if (x instanceof Double && y instanceof Double) return ((Double)x) * ((Double)y);
        return ((Number)x).doubleValue() * ((Number)y).doubleValue();
    }

    private Number dynamicDiv(Object x, Object y) {
        // ELite / semantics: float division for non-evenly-divisible integers
        if (x instanceof Integer && y instanceof Integer) {
            int xi=(Integer)x, yi=(Integer)y;
            if (yi==0) throw new ArithmeticException("Division by zero");
            return (xi % yi == 0) ? xi / yi : (double)xi / (double)yi;
        }
        if (x instanceof Long && y instanceof Long) {
            long xl=(Long)x, yl=(Long)y;
            if (yl==0) throw new ArithmeticException("Division by zero");
            return (xl % yl == 0) ? xl / yl : (double)xl / (double)yl;
        }
        if (x instanceof Double && y instanceof Double) return ((Double)x) / ((Double)y);
        return ((Number)x).doubleValue() / ((Number)y).doubleValue();
    }

    private Number dynamicRem(Object x, Object y) {
        if (x instanceof Integer && y instanceof Integer) return ((Integer)x) % ((Integer)y);
        return ((Number)x).doubleValue() % ((Number)y).doubleValue();
    }

    private Number dynamicNeg(Object x) {
        if (x instanceof Integer) return -((Integer)x);
        if (x instanceof Long) return -((Long)x);
        if (x instanceof Double) return -((Double)x);
        return -((Number)x).doubleValue();
    }

    private Number dynamicPow(Object x, Object y) {
        return Math.pow(((Number)x).doubleValue(), ((Number)y).doubleValue());
    }

    private String dynamicCat(Object x, Object y) {
        return String.valueOf(x) + String.valueOf(y);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private boolean dynamicLt(Object x, Object y) {
        if (x instanceof Comparable && y instanceof Comparable) {
            return ((Comparable)x).compareTo(y) < 0;
        }
        return String.valueOf(x).compareTo(String.valueOf(y)) < 0;
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private boolean dynamicLe(Object x, Object y) {
        if (x instanceof Comparable && y instanceof Comparable) {
            return ((Comparable)x).compareTo(y) <= 0;
        }
        return String.valueOf(x).compareTo(String.valueOf(y)) <= 0;
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
            System.arraycopy(args, 0, expandedArgs, 0, Math.min(args.length, paramCount));
            System.arraycopy(closure.captured, 0, expandedArgs, paramCount, captureCount);
            return new IRInterpreter(elctx, irFn, evalContext).execute(expandedArgs);
        }
        try {
            // Use ELEngine's invoke mechanism with Closure[] conversion
            javax.el.ELContext elctx = evalContext.getELContext();
            elite.lang.Closure[] closures = org.operamasks.el.eval.ELEngine.getCallArgs(args);
            return org.operamasks.el.eval.ELEngine.invokeTarget(elctx, target, closures);
        } catch (Exception e) {
            throw new RuntimeException("dynamic invoke failed", e);
        }
    }

    // ── Helpers ──

    private static boolean coerceToBoolean(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v == null) return false;
        if (v instanceof String) return !((String) v).isEmpty();
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        return true;
    }

    /** Coerce a method argument to the expected Java parameter type. */
    private static Object coerceArg(Object arg, Class<?> paramType) {
        if (arg == null) return null;
        if (paramType.isInstance(arg)) return arg;
        if (arg instanceof Number n) {
            if (paramType == int.class || paramType == Integer.class)
                return n.intValue();
            if (paramType == long.class || paramType == Long.class)
                return n.longValue();
            if (paramType == double.class || paramType == Double.class)
                return n.doubleValue();
            if (paramType == float.class || paramType == Float.class)
                return n.floatValue();
            if (paramType == short.class || paramType == Short.class)
                return n.shortValue();
            if (paramType == byte.class || paramType == Byte.class)
                return n.byteValue();
        }
        return arg;
    }

    /** Check if a runtime value matches the expected primitive type ID. */
    private static boolean checkType(Object val, int typeId) {
        if (val == null) return false;
        return switch (typeId) {
            case IRFormat.T_INT    -> val instanceof Integer || val instanceof Short
                                   || val instanceof Byte || val instanceof Long;
            case IRFormat.T_LONG   -> val instanceof Long || val instanceof Integer
                                   || val instanceof Short || val instanceof Byte;
            case IRFormat.T_DOUBLE -> val instanceof Double || val instanceof Float
                                   || val instanceof Long || val instanceof Integer;
            case IRFormat.T_BOOL   -> val instanceof Boolean;
            case IRFormat.T_STRING -> val instanceof String;
            default -> true;  // unknown type → pass
        };
    }

    // ── Global variable storage ──

    private void storeGlobal(String name, Object value) {
        evalContext.setVariable(name,
            new org.operamasks.el.eval.closure.LiteralClosure(value));
    }

    // ── Direct field access ──

    private Object loadField(Object base, String fieldName) {
        if (base == null) throw new NullPointerException("Cannot read field '" + fieldName + "' from null");
        try {
            Class<?> cls = (base instanceof Class<?> c) ? c : base.getClass();
            java.lang.reflect.Field f = cls.getField(fieldName);
            Object target = java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? null : base;
            return f.get(target);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Field not found: " + fieldName + " on " + base.getClass().getName());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot access field: " + fieldName, e);
        }
    }

    private Object storeField(Object base, String fieldName, Object value) {
        if (base == null) throw new NullPointerException("Cannot write field '" + fieldName + "' to null");
        try {
            Class<?> cls = (base instanceof Class<?> c) ? c : base.getClass();
            java.lang.reflect.Field f = cls.getField(fieldName);
            Object target = java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? null : base;
            f.set(target, coerceArg(value, f.getType()));
            return value; // assignment returns the value
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Field not found: " + fieldName + " on " + base.getClass().getName());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot access field: " + fieldName, e);
        }
    }

    // ── Property / index access ──

    private Object loadProperty(Object base, Object key) {
        if (base == null) return null;
        javax.el.ELContext elctx = evalContext.getELContext();
        elctx.setPropertyResolved(false);
        Object result = elctx.getELResolver().getValue(elctx, base, key);
        if (!elctx.isPropertyResolved()) {
            throw new RuntimeException("Property not found: " + key + " on " + base.getClass().getName());
        }
        return result;
    }

    private void storeProperty(Object base, Object key, Object value) {
        javax.el.ELContext elctx = evalContext.getELContext();
        elctx.getELResolver().setValue(elctx, base, key, value);
    }

    // ── Global variable resolution ──

    private Object resolveGlobal(String name) {
        javax.el.ELContext elctx = evalContext.getELContext();

        // 1) Check the EvaluationContext's own variable resolution chain
        javax.el.ValueExpression ve = evalContext.resolveVariable(name);
        if (ve != null) {
            return ve.getValue(elctx);
        }

        // 2) Check the FunctionMapper for global/imported functions
        org.operamasks.el.resolver.MethodResolver mr =
            org.operamasks.el.resolver.MethodResolver.getInstance(elctx);
        if (mr != null) {
            org.operamasks.el.eval.closure.MethodClosure mc =
                mr.resolveGlobalMethod(elctx.getFunctionMapper(), name);
            if (mc != null) return mc;
        }

        // 3) Try ELResolver chain
        elctx.setPropertyResolved(false);
        Object result = elctx.getELResolver().getValue(elctx, null, name);
        if (elctx.isPropertyResolved()) return result;

        throw new RuntimeException("Undefined identifier: " + name);
    }

    // ── Iterator helpers ──

    @SuppressWarnings({"unchecked","rawtypes"})
    public static java.util.Iterator<?> getIterator(Object coll) {
        if (coll instanceof Iterable) return ((Iterable) coll).iterator();
        if (coll instanceof Object[]) return java.util.Arrays.asList((Object[]) coll).iterator();
        if (coll.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(coll);
            Object[] arr = new Object[len];
            for (int i = 0; i < len; i++) arr[i] = java.lang.reflect.Array.get(coll, i);
            return java.util.Arrays.asList(arr).iterator();
        }
        if (coll instanceof elite.lang.Seq seq) {
            return new java.util.Iterator<>() {
                elite.lang.Seq s = seq;
                public boolean hasNext() { return !s.isEmpty(); }
                public Object next() { Object h = s.head(); s = s.tail(); return h; }
            };
        }
        throw new RuntimeException("Cannot iterate over: " + coll.getClass().getName());
    }

    // ── Contains helper ──

    private static boolean contains(Object coll, Object elem) {
        if (coll instanceof java.util.Collection) return ((java.util.Collection<?>) coll).contains(elem);
        if (coll instanceof Object[]) {
            for (Object o : (Object[]) coll) if (java.util.Objects.equals(o, elem)) return true;
            return false;
        }
        if (coll instanceof elite.lang.Seq seq) {
            while (!seq.isEmpty()) {
                if (java.util.Objects.equals(seq.head(), elem)) return true;
                seq = seq.tail();
            }
            return false;
        }
        return false;
    }
}
