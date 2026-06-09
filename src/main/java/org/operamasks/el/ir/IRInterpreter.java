package org.operamasks.el.ir;

import javax.el.ELContext;
import java.math.BigDecimal;
import java.math.BigInteger;

import org.operamasks.el.eval.*;
import org.operamasks.el.parser.ELNode;
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
                case IPOS: { ip+=1; break; } // int pos is nop

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

                // ============ Dynamic arithmetic (delegate to AST) ============
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
                    int argc = pl;  // argCount stored in payload
                    // Pop new arguments from stack (reverse order)
                    for (int i = argc - 1; i >= 0; i--) {
                        locals[i] = pop();
                    }
                    // Jump to function entry — no stack growth!
                    ip = blockOffsets[0];
                    break;
                }
                case INVOKE_DYN: {
                    int argc = oc == 0 ? pl : code[ip + 1];
                    Object result = dynamicInvoke(argc);
                    push(result);
                    ip += 1 + oc;
                    break;
                }
                case INVOKE: {
                    int argc = oc == 0 ? pl : code[ip + 1];
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

                // ============ Property / index access ============
                case LOAD_PROPERTY: {
                    Object key = pop();
                    Object base = pop();
                    push(loadProperty(base, key));
                    ip += 1;
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
                    push(next);
                    push(it);  // push iterator back for next iteration
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

                // ============ Trampoline to AST evaluator ============
                case 0xE0: { // OP_INTERP_TRAMPOLINE
                    int poolIdx = oc == 0 ? pl : code[ip + 1];
                    ELNode node = (ELNode) constantPool[poolIdx];
                    Object result = node.getValue(evalContext);
                    push(result);
                    ip += 1 + oc;
                    break;
                }

                // ============ Guards (not yet used, pass through) ============
                case GUARD_TYPE:
                case GUARD_NONNULL:
                    ip += 1 + oc;
                    break;

                case DEOPT:
                    // Fall back to full AST interpretation for this function
                    throw new UnsupportedOperationException("deopt not yet implemented");

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
        if (x instanceof Integer && y instanceof Integer) return ((Integer)x) / ((Integer)y);
        if (x instanceof Long && y instanceof Long) return ((Long)x) / ((Long)y);
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
        // Stack layout: [target] [arg1] ... [argN]
        // After popping args, target is at sp - argCount - 1
        // Actually: args are pushed left-to-right, then target
        // Stack: ... arg0 arg1 ... argN target
        Object target = pop();
        Object[] args = new Object[argCount];
        for (int i = argCount - 1; i >= 0; i--) {
            args[i] = pop();
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

    // ── Global variable storage ──

    private void storeGlobal(String name, Object value) {
        evalContext.setVariable(name,
            new org.operamasks.el.eval.closure.LiteralClosure(value));
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

    // ── Global variable resolution ──

    private Object resolveGlobal(String name) {
        javax.el.ELContext elctx = evalContext.getELContext();

        // First: check the EvaluationContext's own variable resolution chain
        javax.el.ValueExpression ve = evalContext.resolveVariable(name);
        if (ve != null) {
            return ve.getValue(elctx);
        }

        // Try ELResolver chain
        elctx.setPropertyResolved(false);
        Object result = elctx.getELResolver().getValue(elctx, null, name);
        if (elctx.isPropertyResolved()) return result;

        throw new RuntimeException("Undefined identifier: " + name);
    }

    // ── Iterator helpers ──

    @SuppressWarnings({"unchecked","rawtypes"})
    private static java.util.Iterator<?> getIterator(Object coll) {
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
