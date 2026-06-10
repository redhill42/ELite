package org.operamasks.el.ir;

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

    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();
    private static final String LOCALS_DESC = "[Ljava/lang/Object;";
    private static final String EXECUTE_DESC = "(" + LOCALS_DESC + ")Ljava/lang/Object;";

    private final IRFunction fn;
    private final ClassWriter cw;
    private final MethodVisitor mv;
    private final String className;
    private final String internalName;

    public static CompiledFunction compile(IRFunction fn) {
        String name = "ELiteCompiled$" + CLASS_COUNTER.incrementAndGet();
        byte[] bc = new IRBytecodeCompiler(fn, name).compileBytecode();
        try {
            Class<?> c = new ClassLoader(IRBytecodeCompiler.class.getClassLoader()) {
                Class<?> define() { return defineClass(name, bc, 0, bc.length); }
            }.define();
            java.lang.reflect.Method m = c.getMethod("execute", Object[].class);
            return new CompiledFunction(m);
        } catch (Exception e) {
            throw new RuntimeException("Bytecode compile failed", e);
        }
    }

    private IRBytecodeCompiler(IRFunction fn, String className) {
        this.fn = fn;
        this.className = className;
        this.internalName = className.replace('.', '/');

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
        for (int b = 0; b < fn.blockCount(); b++) compileBlock(b);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private void compileBlock(int blockId) {
        mv.visitLabel(new Label());
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
            case PUSH_TRUE  -> mv.visitInsn(A_ICONST_1);
            case PUSH_FALSE -> mv.visitInsn(A_ICONST_0);
            case PUSH_NULL  -> mv.visitInsn(A_ACONST_NULL);

            case IADD -> { emitUnboxInt(2); mv.visitInsn(A_IADD); emitBoxInt(); }
            case ISUB -> { emitUnboxInt(2); mv.visitInsn(A_ISUB); emitBoxInt(); }
            case IMUL -> { emitUnboxInt(2); mv.visitInsn(A_IMUL); emitBoxInt(); }
            case IDIV -> { emitUnboxInt(2); mv.visitInsn(A_IDIV); emitBoxInt(); }
            case IREM -> { emitUnboxInt(2); mv.visitInsn(A_IREM); emitBoxInt(); }
            case INEG -> { emitUnboxInt(1); mv.visitInsn(A_INEG); emitBoxInt(); }

            case IEQ -> emitICmp(A_IF_ICMPEQ); case INE -> emitICmp(A_IF_ICMPNE);
            case ILT -> emitICmp(A_IF_ICMPLT); case ILE -> emitICmp(A_IF_ICMPLE);
            case IGT -> emitICmp(A_IF_ICMPGT); case IGE -> emitICmp(A_IF_ICMPGE);

            case LEQ -> emitLCmp(A_IFEQ); case LNE -> emitLCmp(A_IFNE);
            case LLT -> emitLCmp(A_IFLT); case LLE -> emitLCmp(A_IFLE);
            case LGT -> emitLCmp(A_IFGT); case LGE -> emitLCmp(A_IFGE);

            case DEQ -> emitDCmp(A_IFEQ); case DNE -> emitDCmp(A_IFNE);
            case DLT -> emitDCmp(A_IFLT); case DLE -> emitDCmp(A_IFLE);
            case DGT -> emitDCmp(A_IFGT); case DGE -> emitDCmp(A_IFGE);

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
            case NOP -> {}
            default -> throw new UnsupportedOperationException("BC: " + Opcode.name(op));
        }
    }

    private void emitICmp(int jvmOp) {
        Label t = new Label(), e = new Label();
        mv.visitJumpInsn(jvmOp, t);
        mv.visitInsn(A_ICONST_0); mv.visitJumpInsn(A_GOTO, e);
        mv.visitLabel(t); mv.visitInsn(A_ICONST_1); mv.visitLabel(e);
    }
    private void emitDCmp(int jvmOp) {
        mv.visitInsn(A_DCMPG);
        Label t = new Label(), e = new Label();
        mv.visitJumpInsn(jvmOp, t);
        mv.visitInsn(A_ICONST_0); mv.visitJumpInsn(A_GOTO, e);
        mv.visitLabel(t); mv.visitInsn(A_ICONST_1); mv.visitLabel(e);
    }
    private void emitLCmp(int jvmOp) {
        mv.visitInsn(A_LCMP);
        Label t = new Label(), e = new Label();
        mv.visitJumpInsn(jvmOp, t);
        mv.visitInsn(A_ICONST_0); mv.visitJumpInsn(A_GOTO, e);
        mv.visitLabel(t); mv.visitInsn(A_ICONST_1); mv.visitLabel(e);
    }

    private void emitUnboxInt(int count) {
        // Unbox `count` Integer objects from top of stack to ints
        // Stack: ... Integer_N ... Integer_1 → ... int_N ... int_1
        if (count == 2) {
            mv.visitInsn(A_SWAP);  // Integer2 Integer1 → Integer1 Integer2
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
            mv.visitInsn(A_SWAP);  // Integer1 int2 → int2 Integer1
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
            mv.visitInsn(A_SWAP);  // int2 int1 → int1 int2
        } else {
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
        }
    }
    private void emitBoxInt() {
        mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
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
        CompiledFunction(java.lang.reflect.Method m) { this.method = m; }
        public Object execute(Object[] locals) {
            try { return method.invoke(null, (Object) locals); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
