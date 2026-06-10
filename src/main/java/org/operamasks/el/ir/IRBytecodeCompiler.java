package org.operamasks.el.ir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.*;
import static org.operamasks.el.ir.Opcode.*;

/**
 * Compiles IRFunction to JVM bytecode. Phase 1: typed arithmetic, return.
 */
public class IRBytecodeCompiler {

    // ASM opcode aliases (prefixed to avoid conflict with Opcode.*)
    private static final int A_ALOAD = 25, A_ARETURN = 176, A_RETURN = 177, A_ASTORE = 58, A_ACONST_NULL = 1;
    private static final int A_ICONST_0 = 3, A_ICONST_1 = 4, A_LCONST_0 = 9, A_LCONST_1 = 10;
    private static final int A_DCONST_0 = 14, A_DCONST_1 = 15;
    private static final int A_BIPUSH = 16, A_SIPUSH = 17;
    private static final int A_IADD = 96, A_ISUB = 100, A_IMUL = 104, A_IDIV = 108, A_IREM = 112, A_INEG = 116;
    private static final int A_LADD = 97, A_LSUB = 101, A_LMUL = 105, A_LDIV = 109, A_LREM = 113, A_LNEG = 117;
    private static final int A_DADD = 99, A_DSUB = 103, A_DMUL = 107, A_DDIV = 111, A_DNEG = 119;
    private static final int A_DCMPG = 152, A_LCMP = 148;
    private static final int A_IF_ICMPEQ = 159, A_IF_ICMPNE = 160, A_IF_ICMPLT = 161;
    private static final int A_IF_ICMPLE = 164, A_IF_ICMPGT = 163, A_IF_ICMPGE = 162;
    private static final int A_IFEQ = 153, A_IFNE = 154, A_IFLT = 155, A_IFLE = 158, A_IFGT = 157, A_IFGE = 156;
    private static final int A_INVOKESPECIAL = 183, A_INVOKESTATIC = 184, A_INVOKEVIRTUAL = 182;
    private static final int A_CHECKCAST = 192, A_AALOAD = 50, A_AASTORE = 83;
    private static final int A_DUP = 89, A_POP = 87, A_SWAP = 95, A_GOTO = 167;
    private static final int A_DUP_X1 = 90, A_ANEWARRAY = 189;

    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();
    private static final String LOCALS_DESC = "[Ljava/lang/Object;";
    private static final String EXECUTE_DESC = "(" + LOCALS_DESC + ")Ljava/lang/Object;";

    private final IRFunction fn;
    private final ClassWriter cw;
    private final MethodVisitor mv;
    private final String className;
    private final String internalName;
    private final Label[] blockLabels;

    public static CompiledFunction compile(IRFunction fn) {
        String name = "ELiteCompiled$" + CLASS_COUNTER.incrementAndGet();
        byte[] bc = new IRBytecodeCompiler(fn, name).compileBytecode();
        try {
            Class<?> c = new ClassLoader(IRBytecodeCompiler.class.getClassLoader()) {
                Class<?> define() { return defineClass(name, bc, 0, bc.length); }
            }.define();
            java.lang.reflect.Method m = c.getMethod("execute", Object[].class);
            return new CompiledFunction(m, bc);
        } catch (Exception e) {
            throw new RuntimeException("Bytecode compile failed", e);
        }
    }

    private IRBytecodeCompiler(IRFunction fn, String className) {
        this.fn = fn;
        this.className = className;
        this.internalName = className.replace('.', '/');
        this.blockLabels = new Label[fn.blockCount()];

        cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(61, 1 | 0x20, internalName, null, "java/lang/Object", null);

        MethodVisitor cm = cw.visitMethod(1, "<init>", "()V", null, null);
        cm.visitCode();
        cm.visitVarInsn(A_ALOAD, 0);
        cm.visitMethodInsn(A_INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        cm.visitInsn(A_RETURN);
        cm.visitMaxs(1, 1);
        cm.visitEnd();

        mv = cw.visitMethod(1 | 8, "execute", EXECUTE_DESC, null, null);
    }

    private byte[] compileBytecode() {
        mv.visitCode();
        // Allocate labels for all blocks first (for forward references)
        for (int b = 0; b < fn.blockCount(); b++) {
            blockLabels[b] = new Label();
        }
        for (int b = 0; b < fn.blockCount(); b++) compileBlock(b);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private void compileBlock(int blockId) {
        mv.visitLabel(blockLabels[blockId]);
        int start = fn.blockStart(blockId);
        int end = (blockId + 1 < fn.blockCount()) ? fn.blockStart(blockId + 1) : fn.code().length;
        InstructionView v = new InstructionView(fn.code(), start);
        while (v.inBounds() && v.offset() < end) {
            compileInst(v);
            v.advance();
        }
    }

    private void compileInst(InstructionView v) {
        int op = v.opcode(), oc = v.opCount(), pl = v.payload();
        switch (op) {
            case PUSH_CONST -> emitPush(v);
            case PUSH_TRUE  -> { mv.visitInsn(A_ICONST_1); mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false); }
            case PUSH_FALSE -> { mv.visitInsn(A_ICONST_0); mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false); }
            case PUSH_NULL  -> mv.visitInsn(A_ACONST_NULL);

            case IADD -> { emitUnboxInt(2); mv.visitInsn(A_IADD); emitBoxInt(); }
            case ISUB -> { emitUnboxInt(2); mv.visitInsn(A_ISUB); emitBoxInt(); }
            case IMUL -> { emitUnboxInt(2); mv.visitInsn(A_IMUL); emitBoxInt(); }
            case IDIV -> { emitUnboxInt(2); mv.visitInsn(A_IDIV); emitBoxInt(); }
            case IREM -> { emitUnboxInt(2); mv.visitInsn(A_IREM); emitBoxInt(); }
            case INEG -> { emitUnboxInt(1); mv.visitInsn(A_INEG); emitBoxInt(); }
            case IPOW -> emitCall2("intPow");
            case LADD -> { emitUnboxLong(2); mv.visitInsn(A_LADD); emitBoxLong(); }
            case LSUB -> { emitUnboxLong(2); mv.visitInsn(A_LSUB); emitBoxLong(); }
            case LMUL -> { emitUnboxLong(2); mv.visitInsn(A_LMUL); emitBoxLong(); }
            case LDIV -> { emitUnboxLong(2); mv.visitInsn(A_LDIV); emitBoxLong(); }
            case LREM -> { emitUnboxLong(2); mv.visitInsn(A_LREM); emitBoxLong(); }
            case LNEG -> { emitUnboxLong(1); mv.visitInsn(A_LNEG); emitBoxLong(); }
            case LPOW -> emitCall2("longPow");
            case DADD -> { emitUnboxDouble(2); mv.visitInsn(A_DADD); emitBoxDouble(); }
            case DSUB -> { emitUnboxDouble(2); mv.visitInsn(A_DSUB); emitBoxDouble(); }
            case DMUL -> { emitUnboxDouble(2); mv.visitInsn(A_DMUL); emitBoxDouble(); }
            case DDIV -> { emitUnboxDouble(2); mv.visitInsn(A_DDIV); emitBoxDouble(); }
            case DNEG -> { emitUnboxDouble(1); mv.visitInsn(A_DNEG); emitBoxDouble(); }
            case DPOW -> emitCall2("doublePow");

            case IEQ -> { emitUnboxInt(2); emitICmp(A_IF_ICMPEQ); }
            case INE -> { emitUnboxInt(2); emitICmp(A_IF_ICMPNE); }
            case ILT -> { emitUnboxInt(2); emitICmp(A_IF_ICMPLT); }
            case ILE -> { emitUnboxInt(2); emitICmp(A_IF_ICMPLE); }
            case IGT -> { emitUnboxInt(2); emitICmp(A_IF_ICMPGT); }
            case IGE -> { emitUnboxInt(2); emitICmp(A_IF_ICMPGE); }

            case LEQ -> emitLCmp(A_IFEQ); case LNE -> emitLCmp(A_IFNE);
            case LLT -> emitLCmp(A_IFLT); case LLE -> emitLCmp(A_IFLE);
            case LGT -> emitLCmp(A_IFGT); case LGE -> emitLCmp(A_IFGE);

            case DEQ -> { emitUnboxDouble(2); emitDCmp(A_IFEQ); }
            case DNE -> { emitUnboxDouble(2); emitDCmp(A_IFNE); }
            case DLT -> { emitUnboxDouble(2); emitDCmp(A_IFLT); }
            case DLE -> { emitUnboxDouble(2); emitDCmp(A_IFLE); }
            case DGT -> { emitUnboxDouble(2); emitDCmp(A_IFGT); }
            case DGE -> { emitUnboxDouble(2); emitDCmp(A_IFGE); }

            case PUSH_VAR -> {
                mv.visitVarInsn(A_ALOAD, 0);
                emitIntConst(v.varIndex());
                mv.visitInsn(A_AALOAD);
            }
            case STORE_VAR -> {
                mv.visitVarInsn(A_ALOAD, 0);
                mv.visitInsn(A_SWAP);
                emitIntConst(pl & 0xFFFF);
                mv.visitInsn(A_SWAP);
                mv.visitInsn(A_AASTORE);
            }
            case DUP -> mv.visitInsn(A_DUP);
            case POP -> mv.visitInsn(A_POP);
            case POP_N -> { for (int i=0; i<pl; i++) mv.visitInsn(A_POP); }
            case RETURN -> { mv.visitInsn(A_ARETURN); }  // already boxed
            case RETURN_VOID -> { mv.visitInsn(A_ACONST_NULL); mv.visitInsn(A_ARETURN); }
            // ─── Control flow ───
            case JUMP -> mv.visitJumpInsn(A_GOTO, blockLabels[v.jumpTarget()]);
            case JUMP_IF_TRUE -> {
                unboxBoolean();
                mv.visitJumpInsn(154, blockLabels[v.jumpTarget()]); // IFNE
            }
            case JUMP_IF_FALSE -> {
                unboxBoolean();
                mv.visitJumpInsn(153, blockLabels[v.jumpTarget()]); // IFEQ
            }
            case JUMP_IF_NULL -> mv.visitJumpInsn(198, blockLabels[v.jumpTarget()]); // IFNULL
            case JUMP_IF_NONNULL -> mv.visitJumpInsn(199, blockLabels[v.jumpTarget()]); // IFNONNULL
            case INVOKE_TAIL -> {
                // Pop args, store to locals, jump to entry block
                int argc = pl;
                for (int i = argc - 1; i >= 0; i--) {
                    mv.visitVarInsn(A_ALOAD, 0);  // locals array
                    mv.visitInsn(A_SWAP);          // val, array → array, val
                    emitIntConst(i);
                    mv.visitInsn(A_SWAP);
                    mv.visitInsn(A_AASTORE);       // locals[i] = val
                }
                mv.visitJumpInsn(A_GOTO, blockLabels[0]); // jump to entry
            }
            case NOT -> {
                unboxBoolean();
                // Negate: ICONST_1 XOR
                Label t = new Label(), e = new Label();
                mv.visitJumpInsn(154, t); // IFNE → true
                mv.visitInsn(A_ICONST_1); mv.visitJumpInsn(A_GOTO, e);
                mv.visitLabel(t); mv.visitInsn(A_ICONST_0); mv.visitLabel(e);
                mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
            }
            // ─── Function calls ───
            case INVOKE_DIRECT -> {
                int funcIdx = pl;
                int argc = oc == 0 ? 0 : v.operand(0);
                IRFunction target = (IRFunction) fn.constantPool()[funcIdx];
                emitPackArgsAndCall(argc, true, target);
            }
            case INVOKE_DYN, INVOKE -> {
                int argc = oc == 0 ? pl : v.operand(0);
                emitPackArgsAndCall(argc, false, null);
            }
            // ─── Property access, globals ───
            case LOAD_PROPERTY -> emitCall2("loadProp");
            case STORE_PROPERTY -> emitCall3("storeProp");
            case PUSH_GLOBAL, PUSH_GLOBAL_N -> emitCall1("pushGlobal", v);
            case STORE_GLOBAL -> {
                int idx = v.payload();
                String name = (String) fn.constantPool()[idx];
                mv.visitLdcInsn(name);
                mv.visitInsn(A_SWAP);
                mv.visitMethodInsn(A_INVOKESTATIC, "org/operamasks/el/ir/IRBytecodeCompiler",
                    "storeGlobal", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
            }

            // ─── Collections ───
            case NEW_LIST  -> emitCallN("newList", pl);
            case NEW_MAP   -> emitCallN("newMap", pl);
            case NEW_TUPLE -> emitCallN("newTuple", pl);
            case NEW_RANGE -> emitCall2("newRange");

            // ─── Iteration ───
            case GET_ITER  -> emitCall1Obj("getIter");
            case ITER_NEXT -> emitCall1Obj("iterNext");
            case ITER_DONE -> {
                emitCall1Obj("iterNext"); // pop iterator, push next value
                mv.visitJumpInsn(198, blockLabels[v.jumpTarget()]); // IFNULL → done
            }

            // ─── Bitwise (via helpers) ───
            case IAND, LAND -> emitCall2("bitAnd");
            case IOR, LOR   -> emitCall2("bitOr");
            case IXOR, LXOR -> emitCall2("bitXor");
            case ISHL, LSHL -> emitCall2("bitShl");
            case ISHR, LSHR -> emitCall2("bitShr");
            case IUSHR, LUSHR -> emitCall2("bitUshr");
            case IBITNOT, LBITNOT -> emitCall1Obj("bitNot");

            // Trampoline: fall back to AST evaluator for complex ops (try/catch/throw etc.)
            case 0xE0 -> {
                int poolIdx = v.constPoolIndex();
                Object node = fn.constantPool()[poolIdx];
                mv.visitLdcInsn(node);
                mv.visitMethodInsn(A_INVOKESTATIC, "org/operamasks/el/ir/IRBytecodeCompiler",
                    "trampoline", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
            case NOP -> {}
            // Dynamic ops: call static helper methods directly
            case DYNADD -> emitDynCall("dynAdd", 2);
            case DYNSUB -> emitDynCall("dynSub", 2);
            case DYNMUL -> emitDynCall("dynMul", 2);
            case DYNDIV -> emitDynCall("dynDiv", 2);
            case DYNREM -> emitDynCall("dynRem", 2);
            case DYNNEG -> emitDynCall("dynNeg", 1);
            case DYNPOW -> emitDynCall("dynPow", 2);
            case DYNCAT -> emitDynCall("dynCat", 2);
            case DYNEQ  -> emitDynCall("dynEq", 2);
            case DYNLT  -> emitDynCall("dynLt", 2);
            case DYNLE  -> emitDynCall("dynLe", 2);
            case DYNIN  -> emitDynCall("dynIn", 2);
            default -> throw new UnsupportedOperationException("BC: " + Opcode.name(op));
        }
    }

    private void emitICmp(int jvmOp) {
        Label t = new Label(), e = new Label();
        mv.visitJumpInsn(jvmOp, t);
        mv.visitInsn(A_ICONST_0); mv.visitJumpInsn(A_GOTO, e);
        mv.visitLabel(t); mv.visitInsn(A_ICONST_1); mv.visitLabel(e);
        // Box to Boolean
        mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
    }
    private void emitDCmp(int jvmOp) {
        mv.visitInsn(A_DCMPG);
        Label t = new Label(), e = new Label();
        mv.visitJumpInsn(jvmOp, t);
        mv.visitInsn(A_ICONST_0); mv.visitJumpInsn(A_GOTO, e);
        mv.visitLabel(t); mv.visitInsn(A_ICONST_1); mv.visitLabel(e);
        mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
    }
    private void emitLCmp(int jvmOp) {
        mv.visitInsn(A_LCMP);
        Label t = new Label(), e = new Label();
        mv.visitJumpInsn(jvmOp, t);
        mv.visitInsn(A_ICONST_0); mv.visitJumpInsn(A_GOTO, e);
        mv.visitLabel(t); mv.visitInsn(A_ICONST_1); mv.visitLabel(e);
        mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
    }

    private void emitUnboxInt(int count) {
        // Stack: ... obj_N ... obj_1 → ... int_N ... int_1 (same order, unboxed)
        if (count == 2) {
            mv.visitInsn(A_SWAP);  // obj2 obj1 → obj1 obj2
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
            mv.visitInsn(A_SWAP);  // obj1 int2 → int2 obj1
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
            // Stack: int2 int1 (lhs at top, rhs below — correct for IREM/ISUB/etc.)
        } else {
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
        }
    }
    /** Pack args from stack into Object[] and call helper. */
    private void emitPackArgsAndCall(int argc, boolean direct, IRFunction target) {
        // Pop argc args from stack, pack into Object[], call helper
        if (argc == 0) {
            mv.visitInsn(A_ICONST_0);
            mv.visitTypeInsn(A_ANEWARRAY, "java/lang/Object");
        } else {
            int[] tempSlots = new int[argc];
            for (int i = 0; i < argc; i++) tempSlots[i] = i + 1;
            for (int i = argc - 1; i >= 0; i--) mv.visitVarInsn(A_ASTORE, tempSlots[i]);
            emitIntConst(argc);
            mv.visitTypeInsn(A_ANEWARRAY, "java/lang/Object");
            for (int i = 0; i < argc; i++) {
                mv.visitInsn(A_DUP);
                emitIntConst(i);
                mv.visitVarInsn(A_ALOAD, tempSlots[i]);
                mv.visitInsn(A_AASTORE);
            }
        }
        if (direct) {
            // Register function and pass its int ID via ldc
            int funcId = registerFunction(target);
            emitIntConst(funcId);
            mv.visitInsn(A_SWAP); // args, id → id, args
            mv.visitMethodInsn(A_INVOKESTATIC, "org/operamasks/el/ir/IRBytecodeCompiler",
                "invokeDirect", "(I[Ljava/lang/Object;)Ljava/lang/Object;", false);
        } else {
            // Target is on the stack (below the args we popped)
            mv.visitInsn(A_SWAP); // args, target → target, args
            mv.visitMethodInsn(A_INVOKESTATIC, "org/operamasks/el/ir/IRBytecodeCompiler",
                "invokeDyn", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        }
    }

    // Thread-local state to avoid shared mutable state across tests
    private static final ThreadLocal<javax.el.ELContext> localELCtx =
        ThreadLocal.withInitial(() -> org.operamasks.el.eval.ELEngine.createELContext());

    // Optional caller ELContext (set by ELProgram; used for global variable access)
    private static final ThreadLocal<javax.el.ELContext> callerELCtx = new ThreadLocal<>();

    /** Set the caller's ELContext for global variable resolution. */
    public static void setCallerELCtx(javax.el.ELContext ctx) { callerELCtx.set(ctx); }

    private static final ThreadLocal<java.util.Map<Integer, IRFunction>> localFuncRegistry =
        ThreadLocal.withInitial(java.util.concurrent.ConcurrentHashMap::new);

    private static final ThreadLocal<AtomicInteger> localFuncIdCounter =
        ThreadLocal.withInitial(() -> new AtomicInteger(0));

    /** Clear all thread-local state. Call before each program execution. */
    public static void resetState() {
        localELCtx.remove();
        localFuncRegistry.remove();
        localFuncIdCounter.remove();
    }

    private static javax.el.ELContext elctx() { return localELCtx.get(); }
    private static java.util.Map<Integer, IRFunction> funcRegistry() { return localFuncRegistry.get(); }

    private int registerFunction(IRFunction fn) {
        int id = localFuncIdCounter.get().incrementAndGet();
        funcRegistry().put(id, fn);
        return id;
    }

    /** Direct call to a compiled or interpreted IRFunction. */
    public static Object invokeDirect(int funcId, Object[] args) {
        IRFunction fn = funcRegistry().get(funcId);
        if (fn == null) throw new RuntimeException("Function not registered: " + funcId);
        return new IRInterpreter(elctx(), fn).execute(args);
    }

    // ── Simple call helpers (1-2 args popped from stack) ──

    private void emitCall2(String method) {
        mv.visitMethodInsn(A_INVOKESTATIC, "org/operamasks/el/ir/IRBytecodeCompiler",
            method, "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
    }
    private void emitCall3(String method) {
        mv.visitMethodInsn(A_INVOKESTATIC, "org/operamasks/el/ir/IRBytecodeCompiler",
            method, "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
    }
    private void emitCall1Obj(String method) {
        mv.visitMethodInsn(A_INVOKESTATIC, "org/operamasks/el/ir/IRBytecodeCompiler",
            method, "(Ljava/lang/Object;)Ljava/lang/Object;", false);
    }
    private void emitCall1(String method, InstructionView v) {
        // Stack has the value to store. For storeGlobal: pop value.
        // For pushGlobal: just call helper with name from pool.
        int idx = v.constPoolIndex();
        String name = (String) fn.constantPool()[idx];
        mv.visitLdcInsn(name);
        mv.visitMethodInsn(A_INVOKESTATIC, "org/operamasks/el/ir/IRBytecodeCompiler",
            method, "(Ljava/lang/String;)Ljava/lang/Object;", false);
    }
    private void emitCallN(String method, int count) {
        // Pop count values from stack, pack into Object[], call helper
        if (count == 0) {
            mv.visitInsn(A_ICONST_0);
            mv.visitTypeInsn(A_ANEWARRAY, "java/lang/Object");
        } else {
            int[] slots = new int[count];
            for (int i = 0; i < count; i++) slots[i] = i + 1;
            for (int i = count - 1; i >= 0; i--) mv.visitVarInsn(A_ASTORE, slots[i]);
            emitIntConst(count);
            mv.visitTypeInsn(A_ANEWARRAY, "java/lang/Object");
            for (int i = 0; i < count; i++) {
                mv.visitInsn(A_DUP); emitIntConst(i);
                mv.visitVarInsn(A_ALOAD, slots[i]); mv.visitInsn(A_AASTORE);
            }
        }
        mv.visitMethodInsn(A_INVOKESTATIC, "org/operamasks/el/ir/IRBytecodeCompiler",
            method, "([Ljava/lang/Object;)Ljava/lang/Object;", false);
    }

    // ── Property, global, collection helpers ──

    public static Object storeProp(Object base, Object key, Object value) {
        javax.el.ELContext c = callerELCtx.get() != null ? callerELCtx.get() : elctx();
        c.getELResolver().setValue(c, base, key, value);
        return value;
    }

    public static Object intPow(Object x, Object y) { return (long)Math.pow(((Number)x).intValue(), ((Number)y).intValue()); }
    public static Object longPow(Object x, Object y) { return (long)Math.pow(((Number)x).longValue(), ((Number)y).longValue()); }
    public static Object doublePow(Object x, Object y) { return Math.pow(((Number)x).doubleValue(), ((Number)y).doubleValue()); }

    /** Trampoline: evaluate an ELNode via AST interpreter (for try/catch/throw etc.). */
    public static Object trampoline(Object nodeObj) {
        org.operamasks.el.parser.ELNode node = (org.operamasks.el.parser.ELNode) nodeObj;
        javax.el.ELContext c = callerELCtx.get() != null ? callerELCtx.get() : elctx();
        return node.getValue(new org.operamasks.el.eval.EvaluationContext(c));
    }

    public static Object loadProp(Object base, Object key) {
        javax.el.ELContext c = callerELCtx.get() != null ? callerELCtx.get() : elctx();
        c.setPropertyResolved(false);
        Object r = c.getELResolver().getValue(c, base, key);
        if (!c.isPropertyResolved()) throw new RuntimeException("Property not found: " + key);
        return r;
    }
    public static Object pushGlobal(String name) {
        javax.el.ELContext c = callerELCtx.get() != null ? callerELCtx.get() : elctx();
        javax.el.ValueExpression ve = c.getVariableMapper().resolveVariable(name);
        if (ve != null) return ve.getValue(c);
        c.setPropertyResolved(false);
        Object r = c.getELResolver().getValue(c, null, name);
        if (c.isPropertyResolved()) return r;
        throw new RuntimeException("Undefined: " + name);
    }
    public static Object storeGlobal(String name, Object value) {
        javax.el.ELContext c = callerELCtx.get() != null ? callerELCtx.get() : elctx();
        c.getVariableMapper().setVariable(name,
            new org.operamasks.el.eval.closure.LiteralClosure(value));
        return value;
    }
    public static Object newList(Object[] elems) { return java.util.Arrays.asList(elems); }
    public static Object newMap(Object[] kvs) {
        java.util.LinkedHashMap<Object,Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) m.put(kvs[i], kvs[i+1]);
        return m;
    }
    public static Object newTuple(Object[] elems) { return elems; }
    public static Object newRange(Object begin, Object end) {
        return org.operamasks.el.eval.Ranges.createRange(
            ((Number)begin).longValue(), ((Number)end).longValue(), 1);
    }
    public static Object getIter(Object coll) { return IRInterpreter.getIterator(coll); }
    public static Object iterNext(Object it) {
        java.util.Iterator<?> iter = (java.util.Iterator<?>) it;
        return iter.hasNext() ? iter.next() : null;
    }
    public static Object bitAnd(Object a, Object b) { return ((Number)a).longValue() & ((Number)b).longValue(); }
    public static Object bitOr(Object a, Object b)  { return ((Number)a).longValue() | ((Number)b).longValue(); }
    public static Object bitXor(Object a, Object b) { return ((Number)a).longValue() ^ ((Number)b).longValue(); }
    public static Object bitShl(Object a, Object b) { return ((Number)a).longValue() << ((Number)b).longValue(); }
    public static Object bitShr(Object a, Object b) { return ((Number)a).longValue() >> ((Number)b).longValue(); }
    public static Object bitUshr(Object a, Object b){ return ((Number)a).longValue() >>> ((Number)b).longValue(); }
    public static Object bitNot(Object a) { return ~((Number)a).longValue(); }

    /** Dynamic call: delegate to ELEngine.invokeTarget. */
    public static Object invokeDyn(Object target, Object[] args) {
        elite.lang.Closure[] closures = org.operamasks.el.eval.ELEngine.getCallArgs(args);
        return org.operamasks.el.eval.ELEngine.invokeTarget(elctx(), target, closures);
    }

    private void unboxBoolean() {
        mv.visitTypeInsn(A_CHECKCAST, "java/lang/Boolean");
        mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
    }

    private void emitBoxInt() {
        mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
    }

    private void emitUnboxLong(int count) {
        if (count == 2) {
            mv.visitInsn(A_SWAP);
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
            mv.visitInsn(A_SWAP);
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
        } else {
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
        }
    }
    private void emitBoxLong() {
        mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
    }
    private void emitBoxDouble() {
        mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
    }

    private void emitUnboxDouble(int count) {
        if (count == 2) {
            mv.visitInsn(A_SWAP);
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
            mv.visitInsn(A_SWAP);
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
            mv.visitInsn(A_SWAP);
        } else {
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
        }
    }

    private void emitDynCall(String method, int argCount) {
        String desc = argCount == 2
            ? "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
            : "(Ljava/lang/Object;)Ljava/lang/Object;";
        mv.visitMethodInsn(A_INVOKESTATIC, "org/operamasks/el/ir/IRBytecodeCompiler",
            method, desc, false);
    }

    // ── Dynamic operation helpers (called from compiled bytecode) ──

    public static Object dynAdd(Object x, Object y) {
        return ((Number)x).doubleValue() + ((Number)y).doubleValue();
    }
    public static Object dynSub(Object x, Object y) {
        return ((Number)x).doubleValue() - ((Number)y).doubleValue();
    }
    public static Object dynMul(Object x, Object y) {
        return ((Number)x).doubleValue() * ((Number)y).doubleValue();
    }
    public static Object dynDiv(Object x, Object y) {
        if (x instanceof Long xl && y instanceof Long yl) {
            if (yl == 0) throw new ArithmeticException("/ by zero");
            return (xl % yl == 0) ? xl / yl : (double)xl / (double)yl;
        }
        if (x instanceof Integer xi && y instanceof Integer yi) {
            if (yi == 0) throw new ArithmeticException("/ by zero");
            return (xi % yi == 0) ? xi / yi : (double)xi / (double)yi;
        }
        return ((Number)x).doubleValue() / ((Number)y).doubleValue();
    }
    public static Object dynRem(Object x, Object y) {
        return ((Number)x).doubleValue() % ((Number)y).doubleValue();
    }
    public static Object dynNeg(Object x) {
        return -((Number)x).doubleValue();
    }
    public static Object dynPow(Object x, Object y) {
        return Math.pow(((Number)x).doubleValue(), ((Number)y).doubleValue());
    }
    public static Object dynCat(Object x, Object y) {
        return String.valueOf(x) + String.valueOf(y);
    }
    public static Object dynEq(Object x, Object y) {
        return java.util.Objects.equals(x, y);
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    public static Object dynLt(Object x, Object y) {
        if (x instanceof Comparable a && y instanceof Comparable b) return a.compareTo(b) < 0;
        return String.valueOf(x).compareTo(String.valueOf(y)) < 0;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    public static Object dynLe(Object x, Object y) {
        if (x instanceof Comparable a && y instanceof Comparable b) return a.compareTo(b) <= 0;
        return String.valueOf(x).compareTo(String.valueOf(y)) <= 0;
    }
    public static Object dynIn(Object x, Object y) {
        if (y instanceof java.util.Collection<?> c) return c.contains(x);
        return false;
    }

    private void emitPush(InstructionView v) {
        Object val = fn.constantPool()[v.constPoolIndex()];
        if (val instanceof Integer i) {
            emitIntConst(i);
            mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        } else if (val instanceof Long l) {
            emitLongConst(l);
            mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
        } else if (val instanceof Double d) {
            emitDoubleConst(d);
            mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
        } else if (val instanceof Boolean b) {
            mv.visitInsn(b ? A_ICONST_1 : A_ICONST_0);
            mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        } else {
            mv.visitLdcInsn(val);
        }
    }

    private void emitIntConst(int i) {
        if (i >= -1 && i <= 5) mv.visitInsn(A_ICONST_0 + i);
        else if (i >= Byte.MIN_VALUE && i <= Byte.MAX_VALUE) mv.visitIntInsn(A_BIPUSH, i);
        else if (i >= Short.MIN_VALUE && i <= Short.MAX_VALUE) mv.visitIntInsn(A_SIPUSH, i);
        else mv.visitLdcInsn(i);
    }
    private void emitLongConst(long l) {
        if (l == 0) mv.visitInsn(A_LCONST_0);
        else if (l == 1) mv.visitInsn(A_LCONST_1);
        else mv.visitLdcInsn(l);
    }
    private void emitDoubleConst(double d) {
        if (d == 0.0) mv.visitInsn(A_DCONST_0);
        else if (d == 1.0) mv.visitInsn(A_DCONST_1);
        else mv.visitLdcInsn(d);
    }

    public static class CompiledFunction {
        private final java.lang.reflect.Method method;
        private final byte[] bytecode;

        CompiledFunction(java.lang.reflect.Method m, byte[] bc) {
            this.method = m; this.bytecode = bc;
        }

        public Object execute(Object[] locals) {
            try { return method.invoke(null, (Object) locals); }
            catch (Exception e) { throw new RuntimeException(e); }
        }

        /** Return a human-readable disassembly of the generated bytecode. */
        public String bytecodeAsString() {
            if (bytecode == null) return "[no bytecode]";
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            new ClassReader(bytecode).accept(new BytecodePrinter(pw), 0);
            return sw.toString();
        }
    }

    /** Simple ASM ClassVisitor that prints method bytecode. */
    private static class BytecodePrinter extends ClassVisitor {
        private final PrintWriter pw;
        BytecodePrinter(PrintWriter pw) { super(589824); this.pw = pw; }

        @Override
        public void visit(int version, int access, String name, String sig,
                          String superName, String[] interfaces) {
            pw.println(name + ":");
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                          String sig, String[] exceptions) {
            if (name.equals("<init>")) return null;
            pw.println("  " + name + desc + ":");
            return new MethodPrinter(pw);
        }
    }

    private static class MethodPrinter extends MethodVisitor {
        private final PrintWriter pw;
        MethodPrinter(PrintWriter pw) { super(589824); this.pw = pw; }

        @Override
        public void visitInsn(int opcode) {
            pw.printf("    %s\n", opcodeName(opcode));
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            pw.printf("    %s %d\n", opcodeName(opcode), operand);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            pw.printf("    %s %d\n", opcodeName(opcode), varIndex);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            pw.printf("    %s %s\n", opcodeName(opcode), type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            pw.printf("    %s %s.%s %s\n", opcodeName(opcode), owner, name, desc);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            pw.printf("    %s %s.%s%s\n", opcodeName(opcode), owner, name, desc);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            pw.printf("    %s L%d\n", opcodeName(opcode), System.identityHashCode(label) & 0xFFFF);
        }

        @Override
        public void visitLdcInsn(Object cst) {
            pw.printf("    ldc %s\n", cst);
        }

        @Override
        public void visitLabel(Label label) {
            pw.printf("  L%d:\n", System.identityHashCode(label) & 0xFFFF);
        }

        private static String opcodeName(int op) {
            return switch (op) {
                case 0 -> "nop"; case 1 -> "aconst_null"; case 2 -> "iconst_m1";
                case 3 -> "iconst_0"; case 4 -> "iconst_1"; case 5 -> "iconst_2";
                case 6 -> "iconst_3"; case 7 -> "iconst_4"; case 8 -> "iconst_5";
                case 9 -> "lconst_0"; case 10 -> "lconst_1";
                case 14 -> "dconst_0"; case 15 -> "dconst_1";
                case 16 -> "bipush"; case 17 -> "sipush";
                case 18 -> "ldc"; case 21 -> "iload"; case 25 -> "aload";
                case 46 -> "iaload"; case 50 -> "aaload";
                case 75 -> "astore_0"; case 76 -> "astore_1";
                case 77 -> "astore_2"; case 78 -> "astore_3"; case 58 -> "astore";
                case 79 -> "iastore"; case 83 -> "aastore";
                case 87 -> "pop"; case 89 -> "dup"; case 90 -> "dup_x1"; case 95 -> "swap";
                case 96 -> "iadd"; case 100 -> "isub"; case 104 -> "imul";
                case 108 -> "idiv"; case 112 -> "irem"; case 116 -> "ineg";
                case 97 -> "ladd"; case 101 -> "lsub"; case 105 -> "lmul";
                case 109 -> "ldiv"; case 113 -> "lrem"; case 117 -> "lneg";
                case 99 -> "dadd"; case 103 -> "dsub"; case 107 -> "dmul";
                case 111 -> "ddiv"; case 119 -> "dneg";
                case 148 -> "lcmp"; case 152 -> "dcmpg";
                case 153 -> "ifeq"; case 154 -> "ifne"; case 155 -> "iflt";
                case 156 -> "ifge"; case 157 -> "ifgt"; case 158 -> "ifle";
                case 159 -> "if_icmpeq"; case 160 -> "if_icmpne";
                case 161 -> "if_icmplt"; case 162 -> "if_icmpge";
                case 163 -> "if_icmpgt"; case 164 -> "if_icmple";
                case 167 -> "goto"; case 176 -> "areturn"; case 177 -> "return";
                case 178 -> "getstatic"; case 179 -> "putstatic";
                case 182 -> "invokevirtual"; case 183 -> "invokespecial";
                case 184 -> "invokestatic"; case 185 -> "invokeinterface";
                case 187 -> "new"; case 189 -> "anewarray";
                case 192 -> "checkcast"; case 198 -> "ifnull"; case 199 -> "ifnonnull";
                default -> "op_" + op;
            };
        }
    }
}
