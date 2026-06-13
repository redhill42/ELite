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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
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
    private static final int A_INVOKEINTERFACE = 185;
    private static final int A_CHECKCAST = 192, A_AALOAD = 50, A_AASTORE = 83;
    private static final int A_DUP = 89, A_POP = 87, A_SWAP = 95, A_GOTO = 167;
    private static final int A_DUP_X1 = 90, A_ANEWARRAY = 189;

    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();
    private static final String ELCTX_DESC = "Ljavax/el/ELContext;";
    private static final String LOCALS_DESC = "[Ljava/lang/Object;";
    private static final String EXECUTE_DESC = "(" + ELCTX_DESC + LOCALS_DESC + ")Ljava/lang/Object;";
    /** Slot for ELContext (first parameter of generated execute). */
    private static final int S_CTX = 0;
    /** Slot for locals array. */
    private static final int S_LOCALS = 1;
    /** First slot available for temporary values. */
    private static final int S_TMP = 2;

    private final IRFunction fn;
    private final ClassWriter cw;
    private final MethodVisitor mv;
    private final String className;
    private final String internalName;
    private final Label[] blockLabels;
    private final String methodDescriptor;
    private final int[] argTypeIds;      // param types for typed mode, null for Object[] mode
    private boolean typedMode;

    // Shared class loader so compiled callees are visible to callers
    private static final SingleLoader LOADER = new SingleLoader();

    private static class SingleLoader extends ClassLoader {
        SingleLoader() { super(IRBytecodeCompiler.class.getClassLoader()); }
        Class<?> define(String name, byte[] bc) {
            return defineClass(name, bc, 0, bc.length);
        }
    }

    public static CompiledFunction compile(IRFunction fn) {
        return compileWithTypes(fn, null);
    }

    /** Compile with typed params. argTypes[i] = T_INT/T_LONG/T_DOUBLE or -1 for Object. */
    public static CompiledFunction compileWithTypes(IRFunction fn, int[] argTypes) {
        String name = "ELiteCompiled$" + CLASS_COUNTER.incrementAndGet();
        String desc = typeDescriptor(argTypes);
        // Register IRFunction constant pool so CLOSURE bytecode can look up via funcIdx
        elite.rt.Runtime.setFuncPool(fn.constantPool());
        byte[] bc = new IRBytecodeCompiler(fn, name, desc, argTypes).compileBytecode();
        try {
            Class<?> c = LOADER.define(name, bc);
            String methodDesc = desc != null ? desc : EXECUTE_DESC;
            java.lang.reflect.Method m = c.getMethod("execute",
                desc != null ? typeParamClasses(argTypes) : new Class[]{javax.el.ELContext.class, Object[].class});
            return new CompiledFunction(m, bc, name, argTypes, fn.maxLocalCount());
        } catch (Exception e) {
            throw new RuntimeException("Bytecode compile failed", e);
        }
    }

    private static boolean allKnown(int[] types) {
        if (types == null) return false;
        for (int t : types) if (t < 0) return false;
        return true;
    }

    /** Build JVM type descriptor from arg types, e.g. "(II)Ljava/lang/Object;". */
    static String typeDescriptor(int[] argTypes) {
        if (argTypes == null || !allKnown(argTypes)) return null;
        StringBuilder sb = new StringBuilder("(Ljavax/el/ELContext;"); // ELContext first
        for (int t : argTypes) {
            sb.append(switch (t) {
                case IRFormat.T_INT, IRFormat.T_BOOL -> "I";
                case IRFormat.T_LONG -> "J";
                case IRFormat.T_DOUBLE -> "D";
                default -> "Ljava/lang/Object;";
            });
        }
        sb.append(")Ljava/lang/Object;");
        return sb.toString();
    }

    private static Class<?>[] typeParamClasses(int[] argTypes) {
        if (argTypes == null) return new Class[]{javax.el.ELContext.class, Object[].class};
        Class<?>[] cs = new Class[argTypes.length + 1];
        cs[0] = javax.el.ELContext.class;
        for (int i = 0; i < argTypes.length; i++) {
            cs[i + 1] = switch (argTypes[i]) {
                case IRFormat.T_INT, IRFormat.T_BOOL -> int.class;
                case IRFormat.T_LONG -> long.class;
                case IRFormat.T_DOUBLE -> double.class;
                default -> Object.class;
            };
        }
        return cs;
    }

    private IRBytecodeCompiler(IRFunction fn, String className, String desc, int[] argTypes) {
        this.fn = fn;
        this.className = className;
        this.internalName = className.replace('.', '/');
        this.blockLabels = new Label[fn.blockCount()];
        this.methodDescriptor = desc;
        this.argTypeIds = argTypes;
        this.typedMode = desc != null;

        cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(61, 1 | 0x20, internalName, null, "java/lang/Object", null);

        MethodVisitor cm = cw.visitMethod(1, "<init>", "()V", null, null);
        cm.visitCode();
        cm.visitVarInsn(A_ALOAD, 0); // this
        cm.visitMethodInsn(A_INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        cm.visitInsn(A_RETURN);
        cm.visitMaxs(1, 1);
        cm.visitEnd();

        String methodDesc = desc != null ? desc : EXECUTE_DESC;
        mv = cw.visitMethod(1 | 8, "execute", methodDesc, null, null);
        // In typed mode: box params into Object[] locals array at entry
        if (typedMode && argTypeIds != null) {
            // Compute total JVM slots used by params (slot 0 = ELContext)
            int totalSlots = 1; // ELContext in slot 0
            for (int t : argTypeIds) totalSlots += (t == IRFormat.T_LONG || t == IRFormat.T_DOUBLE) ? 2 : 1;
            int arrSlot = totalSlots; // put locals array after all params + ELContext
            // Create Object[paramCount]
            emitIntConst(argTypeIds.length);
            mv.visitTypeInsn(A_ANEWARRAY, "java/lang/Object");
            mv.visitVarInsn(A_ASTORE, arrSlot); // locals array in safe slot
            // Box each param into locals[i]
            int jvmSlot = 1; // start after ELContext
            for (int i = 0; i < argTypeIds.length; i++) {
                mv.visitVarInsn(A_ALOAD, arrSlot); // locals array
                emitIntConst(i);
                int t = argTypeIds[i];
                switch (t) {
                    case IRFormat.T_INT, IRFormat.T_BOOL -> mv.visitVarInsn(21, jvmSlot);
                    case IRFormat.T_LONG -> mv.visitVarInsn(22, jvmSlot);
                    case IRFormat.T_DOUBLE -> mv.visitVarInsn(24, jvmSlot);
                    default -> mv.visitVarInsn(A_ALOAD, jvmSlot);
                }
                switch (t) {
                    case IRFormat.T_INT -> mv.visitMethodInsn(A_INVOKESTATIC,
                        "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                    case IRFormat.T_LONG -> mv.visitMethodInsn(A_INVOKESTATIC,
                        "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                    case IRFormat.T_DOUBLE -> mv.visitMethodInsn(A_INVOKESTATIC,
                        "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
                }
                mv.visitInsn(A_AASTORE);
                jvmSlot += (t == IRFormat.T_LONG || t == IRFormat.T_DOUBLE) ? 2 : 1;
            }
            // Now the body uses Object[]-based access (slot arrSlot has the locals array)
            // PUSH_VAR/STORE_VAR are already compiled to use ALOAD 0 → AALOAD pattern
            // which loads from locals array at slot 0. We need to remap slot 0 → arrSlot.
            // Copy array to slot 0 for body's Object[]-based PUSH_VAR/STORE_VAR
            mv.visitVarInsn(A_ALOAD, arrSlot);
            mv.visitVarInsn(A_ASTORE, S_LOCALS);
            // Body uses Object[] access, not typed access
            this.typedMode = false;
        }
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
                if (typedMode && v.varIndex() < argTypeIds.length) {
                    emitTypedLoad(v.varIndex());
                } else {
                    mv.visitVarInsn(A_ALOAD, S_LOCALS);
                    emitIntConst(v.varIndex());
                    mv.visitInsn(A_AALOAD);
                }
            }
            case STORE_VAR -> {
                int varIdx = pl & 0xFFFF;
                if (typedMode && varIdx < argTypeIds.length) {
                    int t = argTypeIds[varIdx];
                    // Store typed value AND keep on stack (assignment returns value)
                    emitTypedStore(varIdx, t);
                } else {
                    mv.visitVarInsn(A_ALOAD, S_LOCALS);
                    mv.visitInsn(A_SWAP);
                    emitIntConst(varIdx);
                    mv.visitInsn(A_SWAP);
                    mv.visitInsn(A_AASTORE);
                }
            }
            case DUP -> mv.visitInsn(A_DUP);
            case POP -> mv.visitInsn(A_POP);
            case POP_N -> { for (int i=0; i<pl; i++) mv.visitInsn(A_POP); }
            case RETURN -> { mv.visitInsn(A_ARETURN); }  // already boxed
            case RETURN_VOID -> { mv.visitInsn(A_ACONST_NULL); mv.visitInsn(A_ARETURN); }
            case THROW -> {
                // Wrap non-RuntimeException in UserException, then throw
                mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
                    "wrapThrow", "(Ljava/lang/Object;)Ljava/lang/RuntimeException;", false);
                mv.visitInsn(191); // ATHROW
            }
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
                    mv.visitVarInsn(A_ALOAD, S_LOCALS);  // locals array
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
                // Self-recursive in typed mode → direct typed call
                if (typedMode && target == fn && argc > 0 && allKnown(argTypeIds)) {
                    CompiledFunction calleeCf = compileOrGet(target, argTypeIds);
                    emitPackArgsAndCall(argc, true, calleeCf);
                } else {
                    // General case: register funcId, call invokeDirect at runtime
                    int funcId = registerOrGetId(target);
                    emitPackArgsAndCall(argc, true, funcId);
                }
            }
            case INVOKE_DYN -> {
                int argc = pl; // emitInvokeDyn stores argCount in payload
                emitPackArgsAndCall(argc, false, null);
            }
            case INVOKE -> {
                int argc = oc == 0 ? pl : v.operand(0); // emitInvoke stores argCount in operand(0)
                emitPackArgsAndCall(argc, false, null);
            }
            // ─── Property access, globals ───
            case LOAD_PROPERTY -> {
                // Stack: [base, key]. Need [ctx, base, key].
                mv.visitVarInsn(A_ASTORE, S_TMP);      // key → temp
                mv.visitVarInsn(A_ALOAD, S_CTX);        // [base, ctx]
                mv.visitInsn(A_SWAP);                    // [ctx, base]
                mv.visitVarInsn(A_ALOAD, S_TMP);        // [ctx, base, key]
                mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
                    "loadProp", "(Ljavax/el/ELContext;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
            case STORE_PROPERTY -> {
                // Stack: [value, base, key]. Need [ctx, base, key, value].
                mv.visitVarInsn(A_ASTORE, S_TMP + 2);  // key
                mv.visitVarInsn(A_ASTORE, S_TMP + 1);  // base
                mv.visitVarInsn(A_ASTORE, S_TMP);      // value
                mv.visitVarInsn(A_ALOAD, S_CTX);
                mv.visitVarInsn(A_ALOAD, S_TMP + 1);   // base
                mv.visitVarInsn(A_ALOAD, S_TMP + 2);   // key
                mv.visitVarInsn(A_ALOAD, S_TMP);       // value
                mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
                    "storeProp", "(Ljavax/el/ELContext;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
            case LOAD_FIELD -> {
                int idx = v.payload();
                String name = (String) fn.constantPool()[idx];
                // Stack: [base]. LDC name → [base, name] — name on top (2nd param ✓)
                mv.visitLdcInsn(name);
                mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
                    "loadField", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            }
            case STORE_FIELD -> {
                int idx = v.payload();
                String name = (String) fn.constantPool()[idx];
                // Stack: [value, base]. LDC name → [value, base, name] — name on top (3rd param ✓)
                mv.visitLdcInsn(name);
                mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
                    "storeFieldBC", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            }
            case PUSH_GLOBAL -> {
                int idx = v.payload();
                String name = (String) fn.constantPool()[idx];
                mv.visitVarInsn(A_ALOAD, S_CTX);    // ctx
                mv.visitLdcInsn(name);               // name
                mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
                    "pushGlobal", "(Ljavax/el/ELContext;Ljava/lang/String;)Ljava/lang/Object;", false);
            }
            case STORE_GLOBAL -> {
                int idx = v.payload();
                String name = (String) fn.constantPool()[idx];
                // Stack: [value]. Need [ctx, name, value].
                mv.visitVarInsn(A_ASTORE, S_TMP);    // save value
                mv.visitVarInsn(A_ALOAD, S_CTX);     // ctx
                mv.visitLdcInsn(name);                // name
                mv.visitVarInsn(A_ALOAD, S_TMP);     // value
                mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
                    "storeGlobal", "(Ljavax/el/ELContext;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
            }

            // ─── Collections ───
            case NEW_LIST  -> emitCallN("newList", pl);
            case NEW_MAP   -> emitCallN("newMap", pl * 2); // pl = pair count, 2 stack values per pair
            case NEW_TUPLE -> emitCallN("newTuple", pl);
            case NEW_RANGE -> emitCall2("newRange");

            // ─── Iteration ───
            case GET_ITER  -> emitCall1Obj("getIter");
            case ITER_NEXT -> emitCall1Obj("iterNext");
            case ITER_DONE -> {
                // Stack: [iterator, next_value_on_top]
                // Pop next_value, jump to exit if null
                mv.visitJumpInsn(198, blockLabels[v.jumpTarget()]); // IFNULL → done
            }

            case CONTAINS -> emitCall2("contains");

            // ─── Bitwise (via helpers) ───
            case IAND, LAND -> emitCall2("bitAnd");
            case IOR, LOR   -> emitCall2("bitOr");
            case IXOR, LXOR -> emitCall2("bitXor");
            case ISHL, LSHL -> emitCall2("bitShl");
            case ISHR, LSHR -> emitCall2("bitShr");
            case IUSHR, LUSHR -> emitCall2("bitUshr");
            case IBITNOT, LBITNOT -> emitCall1Obj("bitNot");

            case INVOKE_GETTER -> {
                java.lang.reflect.Method m = (java.lang.reflect.Method) fn.constantPool()[v.constPoolIndex()];
                emitDirectGetter(m);
            }
            case CLOSURE -> {
                int funcIdx = pl;
                int captureCount = v.opCount() > 0 ? v.operand(0) : 0;
                // Pack captureCount values from stack into Object[]
                if (captureCount > 0) {
                    int[] ts = new int[captureCount];
                    for (int i = 0; i < captureCount; i++) ts[i] = i + S_TMP;
                    for (int i = captureCount - 1; i >= 0; i--) mv.visitVarInsn(A_ASTORE, ts[i]);
                    emitIntConst(captureCount);
                    mv.visitTypeInsn(A_ANEWARRAY, "java/lang/Object");
                    for (int i = 0; i < captureCount; i++) {
                        mv.visitInsn(A_DUP); emitIntConst(i);
                        mv.visitVarInsn(A_ALOAD, ts[i]); mv.visitInsn(A_AASTORE);
                    }
                } else {
                    mv.visitInsn(A_ICONST_0);
                    mv.visitTypeInsn(A_ANEWARRAY, "java/lang/Object");
                }
                // Push funcIdx as int (not IRFunction via LDC — ASM doesn't support it)
                emitIntConst(funcIdx);
                mv.visitInsn(A_SWAP);  // [capturedArray, int] → [int, capturedArray]
                mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
                    "createClosureById",
                    "(I[Ljava/lang/Object;)Lorg/operamasks/el/ir/IRClosure;", false);
            }

            case INVOKE_METHOD -> {
                java.lang.reflect.Method m = (java.lang.reflect.Method) fn.constantPool()[v.constPoolIndex()];
                int argc = v.opCount() > 0 ? v.operand(0) : 0;
                emitDirectMethod(m, argc);
            }

            case INVOKE_SETTER -> {
                java.lang.reflect.Method m = (java.lang.reflect.Method) fn.constantPool()[v.constPoolIndex()];
                emitDirectSetter(m);
            }

            // Trampoline: evaluate AST node via Runtime helper.
            // TRY nodes fall back to AST evaluation (JVM exception tables not yet implemented).
            case TRAMPOLINE -> {
                int poolIdx = v.constPoolIndex();
                Object nodeObj = fn.constantPool()[poolIdx];
                if (nodeObj instanceof TryDescriptor td) {
                    emitTryCatch(td);
                } else {
                    mv.visitVarInsn(A_ALOAD, S_CTX);
                    mv.visitLdcInsn(poolIdx);
                    mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
                        "trampolineById", "(Ljavax/el/ELContext;I)Ljava/lang/Object;", false);
                }
            }
            case GUARD_TYPE -> {
                int typeId = v.payload() & 0xFF;
                int deoptBlockId = v.opCount() > 0 ? v.operand(0) : 0;
                // Bytecode backend cannot do multi-entry deopt blocks.
                // Use strict guard (throw on mismatch). If guard fails,
                // ELProgram catches the exception and falls back to IR
                // interpreter, which handles deopt correctly.
                mv.visitInsn(A_DUP);
                emitIntConst(typeId);
                mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
                    "guardTypeStrict", "(Ljava/lang/Object;I)V", false);
            }
            case INC -> {
                int varIdx = pl;
                mv.visitVarInsn(A_ALOAD, S_LOCALS);
                emitIntConst(varIdx);
                mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
                    "incLocal", "([Ljava/lang/Object;I)Ljava/lang/Object;", false);
            }
            case DEC -> {
                int varIdx = pl;
                mv.visitVarInsn(A_ALOAD, S_LOCALS);
                emitIntConst(varIdx);
                mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
                    "decLocal", "([Ljava/lang/Object;I)Ljava/lang/Object;", false);
            }
            case SCOPE_ENTER, SCOPE_EXIT -> { mv.visitInsn(0); }  // JVM NOP for frame consistency
            case NOP -> {}
            // Dynamic ops: call static helper methods directly
            case DYNADD -> emitDynCall("dynAdd", 2);
            case DYNSUB -> emitDynCall("dynSub", 2);
            case DYNMUL -> emitDynCall("dynMul", 2);
            case DYNDIV -> emitDynCall("dynDiv", 2);
            case DYNREM -> emitDynCall("dynRem", 2);
            case DYNNEG -> emitDynCall("dynNeg", 1);
            case DYNPOW -> emitDynCall("dynPow", 2);
            case DYNCAT -> {
                // dynCat needs ELContext for TypeCoercion.coerce on array ops.
                // Stack: [x, y]. Need [ctx, x, y].
                mv.visitVarInsn(A_ASTORE, S_TMP + 1);  // y → temp
                mv.visitVarInsn(A_ASTORE, S_TMP);      // x → temp
                mv.visitVarInsn(A_ALOAD, S_CTX);        // ctx
                mv.visitVarInsn(A_ALOAD, S_TMP);        // x
                mv.visitVarInsn(A_ALOAD, S_TMP + 1);    // y
                mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
                    "dynCat", "(Ljavax/el/ELContext;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
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
    private final Map<IRFunction, CompiledFunction> calleeCache = new HashMap<>();
    private final Map<IRFunction, Integer> funcIdMap = new HashMap<>();
    private int nextFuncId = 1;

    private int registerOrGetId(IRFunction target) {
        return funcIdMap.computeIfAbsent(target, fn -> {
            int id = funcIdCounter.get().incrementAndGet();
            funcRegistry().put(id, fn);
            return id;
        });
    }

    private CompiledFunction compileOrGet(IRFunction target, int[] argTypes) {
        if (argTypes != null && allKnown(argTypes)) {
            // Typed call: cache by (target, typeSignature)
            String key = target.toString() + java.util.Arrays.toString(argTypes);
            return calleeCache.computeIfAbsent(target, fn -> {
                IRFunction specialized = IRSpeclializer.specialize(fn, argTypes);
                return compileWithTypes(specialized, argTypes);
            });
        }
        return calleeCache.computeIfAbsent(target, fn -> {
            IRFunction specialized = IRSpeclializer.specialize(fn, new int[fn.paramCount()]);
            return compile(specialized);
        });
    }

    /** Pack args, call invokeDirect(funcId, Object[]) at runtime (supports typed optimization). */
    private void emitPackArgsAndCall(int argc, boolean direct, int funcId) {
        // Pack args into Object[] array
        if (argc == 0) {
            mv.visitInsn(A_ICONST_0);
            mv.visitTypeInsn(A_ANEWARRAY, "java/lang/Object");
        } else {
            int[] tempSlots = new int[argc];
            for (int i = 0; i < argc; i++) tempSlots[i] = i + S_TMP;
            for (int i = argc - 1; i >= 0; i--) mv.visitVarInsn(A_ASTORE, tempSlots[i]);
            emitIntConst(argc);
            mv.visitTypeInsn(A_ANEWARRAY, "java/lang/Object");
            for (int i = 0; i < argc; i++) {
                mv.visitInsn(A_DUP); emitIntConst(i);
                mv.visitVarInsn(A_ALOAD, tempSlots[i]); mv.visitInsn(A_AASTORE);
            }
        }
        // Stack: [argsArray]. Need [ELContext, funcId, argsArray].
        mv.visitVarInsn(A_ALOAD, S_CTX);          // [argsArray, ctx]
        mv.visitInsn(A_SWAP);                      // [ctx, argsArray]
        emitIntConst(funcId);                      // [ctx, argsArray, int]
        mv.visitInsn(A_SWAP);                      // [ctx, int, argsArray]
        mv.visitMethodInsn(A_INVOKESTATIC, "org/operamasks/el/ir/IRBytecodeCompiler",
            "invokeDirect", "(Ljavax/el/ELContext;I[Ljava/lang/Object;)Ljava/lang/Object;", false);
    }

    /** Pack args from stack into Object[] and call helper. */
    private void emitPackArgsAndCall(int argc, boolean direct, CompiledFunction cf) {
        // Pop argc args from stack, pack into Object[], call helper
        if (argc == 0) {
            mv.visitInsn(A_ICONST_0);
            mv.visitTypeInsn(A_ANEWARRAY, "java/lang/Object");
        } else {
            int[] tempSlots = new int[argc];
            for (int i = 0; i < argc; i++) tempSlots[i] = i + S_TMP;
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
        if (direct && cf != null) {
            String desc = cf.methodDescriptor();
            if (desc != null && cf.argTypes() != null && argc > 0) {
                // Typed call with ELContext: push ctx, unbox args, store to temps, reload in order
                mv.visitVarInsn(A_ALOAD, S_CTX);   // ELContext is first typed param
                int[] types = cf.argTypes();
                int[] slots = new int[argc];
                int nextSlot = S_TMP + 2; // after ctx + locals
                // Step 1: pop args in reverse, unbox, store to temp slots
                for (int i = argc - 1; i >= 0; i--) {
                    int t = types[i];
                    slots[i] = nextSlot;
                    compileUnbox(t);
                    switch (t) {
                        case IRFormat.T_INT, IRFormat.T_BOOL -> mv.visitVarInsn(54 /* ISTORE */, nextSlot);
                        case IRFormat.T_LONG -> mv.visitVarInsn(55 /* LSTORE */, nextSlot);
                        case IRFormat.T_DOUBLE -> mv.visitVarInsn(57 /* DSTORE */, nextSlot);
                        default -> mv.visitVarInsn(A_ASTORE, nextSlot);
                    }
                    nextSlot += (t == IRFormat.T_LONG || t == IRFormat.T_DOUBLE) ? 2 : 1;
                }
                // Step 2: reload args in forward order
                for (int i = 0; i < argc; i++) {
                    int t = types[i];
                    switch (t) {
                        case IRFormat.T_INT, IRFormat.T_BOOL -> mv.visitVarInsn(21 /* ILOAD */, slots[i]);
                        case IRFormat.T_LONG -> mv.visitVarInsn(22 /* LLOAD */, slots[i]);
                        case IRFormat.T_DOUBLE -> mv.visitVarInsn(24 /* DLOAD */, slots[i]);
                        default -> mv.visitVarInsn(A_ALOAD, slots[i]);
                    }
                }
                // Step 3: call typed method (desc already includes ELContext prefix)
                mv.visitMethodInsn(A_INVOKESTATIC, cf.internalName(), "execute", desc, false);
            } else {
                // Generic Object[] call with ELContext
                mv.visitVarInsn(A_ALOAD, S_CTX);
                mv.visitMethodInsn(A_INVOKESTATIC, cf.internalName(), "execute",
                    "(Ljavax/el/ELContext;[Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
        } else if (direct) {
            // No compiled function — invokeDyn with ELContext from slot 0
            // Stack: [target, argsArray]. Need [ctx, target, argsArray].
            mv.visitVarInsn(A_ASTORE, S_TMP);      // args → temp
            mv.visitVarInsn(A_ALOAD, S_CTX);        // ctx
            mv.visitInsn(A_SWAP);                    // target, ctx → ctx, target
            mv.visitVarInsn(A_ALOAD, S_TMP);        // args
            mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
                "invokeDyn", "(Ljavax/el/ELContext;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        } else {
            // Stack: [target, argsArray]. Need [ctx, target, argsArray].
            mv.visitVarInsn(A_ASTORE, S_TMP);       // args → temp
            mv.visitVarInsn(A_ALOAD, S_CTX);         // ctx
            mv.visitInsn(A_SWAP);                     // ctx, target → target, ctx
            mv.visitVarInsn(A_ALOAD, S_TMP);         // args
            mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
                "invokeDyn", "(Ljavax/el/ELContext;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        }
    }

    private static final ThreadLocal<java.util.Map<Integer, IRFunction>> funcRegistry =
        ThreadLocal.withInitial(java.util.HashMap::new);
    private static final ThreadLocal<AtomicInteger> funcIdCounter =
        ThreadLocal.withInitial(() -> new AtomicInteger(0));

    /** Clear all thread-local state. Call before each program execution. */
    public static void resetState() {
        funcRegistry.remove();
        funcIdCounter.remove();
    }
    private static java.util.Map<Integer, IRFunction> funcRegistry() { return funcRegistry.get(); }

    // registerFunction removed — use compileOrGet + calleeCache instead

    // Cache of compiled functions for fast direct calls
    private static final ThreadLocal<java.util.Map<IRFunction, CompiledFunction>> compiledCache =
        ThreadLocal.withInitial(java.util.HashMap::new);

    /** Direct call: use compiled version if available, specialize on first call. */
    public static Object invokeDirect(javax.el.ELContext elctx, int funcId, Object[] args) {
        IRFunction fn = funcRegistry().get(funcId);
        if (fn == null) throw new RuntimeException("Function not registered: " + funcId);
        // Check cache first
        CompiledFunction cf = compiledCache.get().get(fn);
        if (cf == null) {
            int[] argTypes = inferTypes(args);
            IRFunction specialized = args.length > 0 ? IRSpeclializer.specialize(fn, argTypes) : fn;
            cf = compile(specialized);  // generic Object[] version
            compiledCache.get().put(fn, cf);
        }
        return cf.execute(elctx, args);
    }

    private static int[] inferTypes(Object[] args) {
        int[] types = new int[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = typeOf(args[i]);
        }
        return types;
    }

    private static int typeOf(Object v) {
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) return IRFormat.T_INT;
        if (v instanceof Long) return IRFormat.T_LONG;
        if (v instanceof Double || v instanceof Float) return IRFormat.T_DOUBLE;
        if (v instanceof Boolean) return IRFormat.T_BOOL;
        if (v instanceof String) return IRFormat.T_STRING;
        return -1;
    }

    // ── Simple call helpers (1-2 args popped from stack) ──

    private void emitCall2(String method) {
        mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
            method, "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
    }
    private void emitCall1Obj(String method) {
        mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
            method, "(Ljava/lang/Object;)Ljava/lang/Object;", false);
    }
    private void emitCallN(String method, int count) {
        // Pop count values from stack, pack into Object[], call helper
        if (count == 0) {
            mv.visitInsn(A_ICONST_0);
            mv.visitTypeInsn(A_ANEWARRAY, "java/lang/Object");
        } else {
            int[] slots = new int[count];
            for (int i = 0; i < count; i++) slots[i] = i + S_TMP;
            for (int i = count - 1; i >= 0; i--) mv.visitVarInsn(A_ASTORE, slots[i]);
            emitIntConst(count);
            mv.visitTypeInsn(A_ANEWARRAY, "java/lang/Object");
            for (int i = 0; i < count; i++) {
                mv.visitInsn(A_DUP); emitIntConst(i);
                mv.visitVarInsn(A_ALOAD, slots[i]); mv.visitInsn(A_AASTORE);
            }
        }
        mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
            method, "([Ljava/lang/Object;)Ljava/lang/Object;", false);
    }

    /** Emit a direct invokevirtual/interface call for a getter method. */
    private void emitDirectGetter(java.lang.reflect.Method m) {
        Class<?> ownerClass = m.getDeclaringClass();
        String owner = Type.getInternalName(ownerClass);
        String name = m.getName();
        String desc = Type.getMethodDescriptor(m);
        boolean isInterface = ownerClass.isInterface();
        Class<?> returnType = m.getReturnType();

        // Stack: [Object(base)]
        mv.visitTypeInsn(A_CHECKCAST, owner);
        // Stack: [Owner(base)]
        int invokeOp = isInterface ? A_INVOKEINTERFACE : A_INVOKEVIRTUAL;
        mv.visitMethodInsn(invokeOp, owner, name, desc, isInterface);
        // Stack: [returnType] — may be primitive, box if needed
        emitBoxIfPrimitive(returnType);
    }

    /** Emit a direct invokevirtual/interface call for a setter method.
     *  Stack in: [Object(value), Object(base)] — value on top, base below.
     *  Stack out: [Object(value)] — assignment returns the assigned value. */
    private void emitDirectSetter(java.lang.reflect.Method m) {
        Class<?> ownerClass = m.getDeclaringClass();
        String owner = Type.getInternalName(ownerClass);
        String name = m.getName();
        String desc = Type.getMethodDescriptor(m);
        boolean isInterface = ownerClass.isInterface();
        Class<?> paramType = m.getParameterTypes()[0];
        int invokeOp = isInterface ? A_INVOKEINTERFACE : A_INVOKEVIRTUAL;

        // Stack: [Object(value), Object(base)]
        mv.visitVarInsn(A_ASTORE, S_TMP);  // save original value → slot 1
        // Stack: [Object(base)]
        mv.visitTypeInsn(A_CHECKCAST, owner);
        // Stack: [Owner(base)]
        mv.visitVarInsn(A_ALOAD, S_TMP);  // load value
        // Stack: [Owner(base), Object(value)]

        // Narrow/unbox value to match setter parameter type
        emitUnboxIfPrimitive(paramType);
        // Stack: [Owner(base), ParamType(value)] — ready for invoke

        mv.visitMethodInsn(invokeOp, owner, name, desc, isInterface);
        // Stack: [] (void return from setter)
        mv.visitVarInsn(A_ALOAD, S_TMP);  // push original value as result
        // Stack: [Object(value)]
    }

    /** Emit a direct invoke for a method with argc args.
     *  Stack in (instance): [argN, ..., arg1, Object(base)].
     *  Stack in (static):   [argN, ..., arg1] — no receiver.
     *  Stack out: [Object(result)]. */
    private void emitDirectMethod(java.lang.reflect.Method m, int argc) {
        Class<?> ownerClass = m.getDeclaringClass();
        String owner = Type.getInternalName(ownerClass);
        String name = m.getName();
        String desc = Type.getMethodDescriptor(m);
        boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
        boolean isInterface = ownerClass.isInterface();
        Class<?>[] paramTypes = m.getParameterTypes();
        Class<?> returnType = m.getReturnType();

        // Save args from top to bottom
        for (int i = argc; i >= 1; i--) {
            mv.visitVarInsn(A_ASTORE, S_TMP + i - 1);
        }

        if (!isStatic) {
            mv.visitTypeInsn(A_CHECKCAST, owner);
        }

        // Load args in order, narrow/unbox each
        for (int i = 0; i < argc; i++) {
            mv.visitVarInsn(A_ALOAD, S_TMP + i);
            emitUnboxIfPrimitive(paramTypes[i]);
        }

        int invokeOp = isStatic ? A_INVOKESTATIC
                     : isInterface ? A_INVOKEINTERFACE
                     : A_INVOKEVIRTUAL;
        mv.visitMethodInsn(invokeOp, owner, name, desc, isInterface);
        emitBoxIfPrimitive(returnType);
    }

    /** Box a primitive return value on the stack into its wrapper type. */
    private void emitBoxIfPrimitive(Class<?> type) {
        if (type == int.class) {
            mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        } else if (type == long.class) {
            mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
        } else if (type == double.class) {
            mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
        } else if (type == float.class) {
            mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
        } else if (type == boolean.class) {
            mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        } else if (type == short.class) {
            mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
        } else if (type == byte.class) {
            mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
        } else if (type == char.class) {
            mv.visitMethodInsn(A_INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
        } else if (type == void.class) {
            mv.visitInsn(A_ACONST_NULL);
        }
        // reference types (including Object) — no boxing needed
    }

    /** Emit unbox + checkcast for a method parameter: [Object] → [primitive]. */
    private void emitUnboxIfPrimitive(Class<?> type) {
        if (type == int.class) {
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
        } else if (type == long.class) {
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
        } else if (type == double.class) {
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
        } else if (type == float.class) {
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
        } else if (type == boolean.class) {
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Boolean");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
        } else if (type == short.class) {
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "shortValue", "()S", false);
        } else if (type == byte.class) {
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "byteValue", "()B", false);
        } else if (type == char.class) {
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Character");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
        } else {
            // Reference type — just checkcast
            mv.visitTypeInsn(A_CHECKCAST, Type.getInternalName(type));
        }
    }

    /** Unbox top-of-stack Object to primitive based on type. */
    private void compileUnbox(int typeId) {
        switch (typeId) {
            case IRFormat.T_INT -> {
                mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
            }
            case IRFormat.T_LONG -> {
                mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
            }
            case IRFormat.T_DOUBLE -> {
                mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
            }
            // T_BOOL and Object: leave as Object reference
        }
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
            // Store both to temp slots to avoid SWAP issues with long (cat 2)
            mv.visitVarInsn(A_ASTORE, S_TMP + 1);  // rhs → slot 3
            mv.visitVarInsn(A_ASTORE, S_TMP);  // lhs → slot 2
            // Load and unbox lhs (slot 2)
            mv.visitVarInsn(A_ALOAD, S_TMP);
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
            // Load and unbox rhs (slot 3)
            mv.visitVarInsn(A_ALOAD, S_TMP + 1);
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
            // Store both to temp slots to avoid SWAP issues with double (cat 2)
            mv.visitVarInsn(A_ASTORE, S_TMP + 1);  // rhs → slot 3
            mv.visitVarInsn(A_ASTORE, S_TMP);  // lhs → slot 2
            // Load and unbox lhs (slot 2)
            mv.visitVarInsn(A_ALOAD, S_TMP);
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
            // Load and unbox rhs (slot 3)
            mv.visitVarInsn(A_ALOAD, S_TMP + 1);
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
        } else {
            mv.visitTypeInsn(A_CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(A_INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
        }
    }

    /** Emit typed load from local var slot. */
    private void emitTypedLoad(int varIdx) {
        int t = argTypeIds[varIdx];
        int slot = typedSlot(varIdx);
        switch (t) {
            case IRFormat.T_INT, IRFormat.T_BOOL -> mv.visitVarInsn(21 /* ILOAD */, slot);
            case IRFormat.T_LONG -> mv.visitVarInsn(22 /* LLOAD */, slot);
            case IRFormat.T_DOUBLE -> mv.visitVarInsn(24 /* DLOAD */, slot);
            default -> mv.visitVarInsn(A_ALOAD, slot);
        }
    }

    /** Emit typed store to local var slot. */
    private void emitTypedStore(int varIdx, int t) {
        int slot = typedSlot(varIdx);
        // For STORE_VAR, we need to store AND keep the value on stack (assignment returns value)
        // So we DUP first, then store
        switch (t) {
            case IRFormat.T_INT, IRFormat.T_BOOL -> { mv.visitInsn(89 /* DUP */); mv.visitVarInsn(54 /* ISTORE */, slot); }
            case IRFormat.T_LONG -> { mv.visitInsn(92 /* DUP2 */); mv.visitVarInsn(55 /* LSTORE */, slot); }
            case IRFormat.T_DOUBLE -> { mv.visitInsn(92 /* DUP2 */); mv.visitVarInsn(57 /* DSTORE */, slot); }
            default -> { mv.visitInsn(89 /* DUP */); mv.visitVarInsn(A_ASTORE, slot); }
        }
    }

    /** Compute JVM local var slot from IR var index (accounting for category-2 types). */
    private int typedSlot(int varIdx) {
        int slot = 1; // offset for ELContext in slot 0
        for (int i = 0; i < varIdx; i++) {
            int t = argTypeIds[i];
            slot += (t == IRFormat.T_LONG || t == IRFormat.T_DOUBLE) ? 2 : 1;
        }
        return slot;
    }

    private void emitDynCall(String method, int argCount) {
        String desc = argCount == 2
            ? "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
            : "(Ljava/lang/Object;)Ljava/lang/Object;";
        mv.visitMethodInsn(A_INVOKESTATIC, "elite/rt/Runtime",
            method, desc, false);
    }


    /** Generate JVM exception table bytecode from a TryDescriptor. */
    private void emitTryCatch(TryDescriptor td) {
        // Compile each sub-function to bytecode
        CompiledFunction tryCF = compile(td.tryBody);
        CompiledFunction[] catchCFs = new CompiledFunction[td.catchBodies.length];
        for (int i = 0; i < catchCFs.length; i++) {
            catchCFs[i] = compile(td.catchBodies[i]);
        }
        CompiledFunction finallyCF = td.finallyBlock != null
            ? compile(td.finallyBlock) : null;

        // Resolve catch types
        String[] catchTypes = new String[td.catchTypes.length];
        for (int i = 0; i < catchTypes.length; i++) {
            if (td.catchTypes[i] != null) {
                catchTypes[i] = td.catchTypes[i].replace('.', '/');
            }
        }

        // Labels
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label finallyStart = new Label();
        Label[] handlerStarts = new Label[catchCFs.length];
        for (int i = 0; i < handlerStarts.length; i++) handlerStarts[i] = new Label();

        // --- Try body ---
        mv.visitLabel(tryStart);
        mv.visitVarInsn(A_ALOAD, S_CTX);  // ELContext for execute()
        mv.visitInsn(A_ACONST_NULL);       // locals array = null
        mv.visitMethodInsn(A_INVOKESTATIC, tryCF.internalName(), "execute",
            "(Ljavax/el/ELContext;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(A_POP);  // discard result
        mv.visitLabel(tryEnd);

        // --- Go to finally if no exception ---
        if (finallyCF != null) {
            mv.visitJumpInsn(A_GOTO, finallyStart);
        } else {
            mv.visitInsn(A_ACONST_NULL);
            mv.visitInsn(A_ARETURN);
        }

        // --- Catch handlers ---
        for (int i = 0; i < catchCFs.length; i++) {
            mv.visitLabel(handlerStarts[i]);
            // Exception is on JVM stack. Save it.
            int exSlot = S_TMP + 2;
            mv.visitVarInsn(A_ASTORE, exSlot);
            // Call handler CF with exception as locals[0]
            mv.visitVarInsn(A_ALOAD, S_CTX);    // ELContext
            mv.visitInsn(A_ICONST_1);
            mv.visitTypeInsn(A_ANEWARRAY, "java/lang/Object");
            mv.visitInsn(A_DUP);
            mv.visitInsn(A_ICONST_0);
            mv.visitVarInsn(A_ALOAD, exSlot);    // exception
            mv.visitInsn(A_AASTORE);              // locals[0] = exception
            mv.visitMethodInsn(A_INVOKESTATIC, catchCFs[i].internalName(), "execute",
                "(Ljavax/el/ELContext;[Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitInsn(A_POP);
            if (finallyCF != null) {
                mv.visitJumpInsn(A_GOTO, finallyStart);
            } else {
                mv.visitInsn(A_ACONST_NULL);
                mv.visitInsn(A_ARETURN);
            }
        }

        // --- Finally ---
        if (finallyCF != null) {
            mv.visitLabel(finallyStart);
            mv.visitVarInsn(A_ALOAD, S_CTX);
            mv.visitInsn(A_ACONST_NULL);
            mv.visitMethodInsn(A_INVOKESTATIC, finallyCF.internalName(), "execute",
                "(Ljavax/el/ELContext;[Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitInsn(A_POP);  // discard finally result
            mv.visitInsn(A_ACONST_NULL);
            mv.visitInsn(A_ARETURN);
        }

        // --- Exception table ---
        for (int i = 0; i < catchCFs.length; i++) {
            String catchType = catchTypes[i];
            if (catchType == null) catchType = "java/lang/Exception";
            mv.visitTryCatchBlock(tryStart, tryEnd, handlerStarts[i], catchType);
        }
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
        private final String className;
        private final int[] argTypes;
        private final int maxLocals;

        CompiledFunction(java.lang.reflect.Method m, byte[] bc, String className,
                         int[] argTypes, int maxLocals) {
            this.method = m; this.className = className; this.bytecode = bc;
            this.argTypes = argTypes; this.maxLocals = maxLocals;
        }

        public Object execute(javax.el.ELContext elctx, Object[] locals) {
            if (locals == null) locals = new Object[maxLocals];
            else if (locals.length < maxLocals) {
                Object[] expanded = new Object[maxLocals];
                System.arraycopy(locals, 0, expanded, 0, locals.length);
                locals = expanded;
            }
            try {
                return method.invoke(null, elctx, locals);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new RuntimeException(cause);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        /** Internal name for invokestatic bytecode. */
        public String internalName() { return className.replace('.', '/'); }
        public String methodDescriptor() { return typeDescriptor(argTypes); }
        public int[] argTypes() { return argTypes; }

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
