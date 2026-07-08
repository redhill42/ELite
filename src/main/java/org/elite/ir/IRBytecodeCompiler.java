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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.elite.eval.Runtime;
import org.objectweb.asm.*;
import static org.elite.ir.Opcode.*;
import static org.elite.resources.Resources.*;

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
    private static final int A_IF_ACMPEQ = 165, A_IF_ACMPNE = 166;
    private static final int A_IFEQ = 153, A_IFNE = 154, A_IFLT = 155, A_IFLE = 158, A_IFGT = 157, A_IFGE = 156;
    private static final int A_INVOKESPECIAL = 183, A_INVOKESTATIC = 184, A_INVOKEVIRTUAL = 182;
    private static final int A_INVOKEINTERFACE = 185;
    private static final int A_CHECKCAST = 192, A_AALOAD = 50, A_AASTORE = 83;
    private static final int A_DUP = 89, A_POP = 87, A_SWAP = 95, A_GOTO = 167;
    private static final int A_DUP_X1 = 90, A_ANEWARRAY = 189;
    private static final int A_GETSTATIC = 178, A_PUTSTATIC = 179;

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
    private final String internalName;
    private final Label[] blockLabels;

    // Shared class loader so compiled callees are visible to callers.
    // TODO: In long-running applications, compiled classes accumulate in this
    //       static loader and are never GC'd. Consider per-thread ClassLoader
    //       (cleared on resetState) or a SoftReference-based class cache.
    private static final SingleLoader LOADER = new SingleLoader();

    private static class SingleLoader extends ClassLoader {
        SingleLoader() { super(IRBytecodeCompiler.class.getClassLoader()); }
        Class<?> define(String name, byte[] bc) {
            return defineClass(name, bc, 0, bc.length);
        }
    }

    public static IRCompiledFunction compile(IRFunction fn) {
        String name = "ELiteCompiled$" + CLASS_COUNTER.incrementAndGet();
        // Register IRFunction constant pool so CLOSURE bytecode can look up via funcIdx
        Runtime.setFuncPool(fn.constantPool());
        byte[] bc = new IRBytecodeCompiler(fn, name).compileBytecode();
        try {
            Class<?> c = LOADER.define(name, bc);
            java.lang.reflect.Method m = c.getMethod("execute",
                javax.el.ELContext.class, Object[].class);
            return new IRCompiledFunction(m, bc, name, fn.maxLocals(), fn.defaultValues());
        } catch (Exception e) {
            throw new RuntimeException(_T(IR_BYTECODE_COMPILE_FAILED), e);
        }
    }

    private IRBytecodeCompiler(IRFunction fn, String className) {
        this.fn = fn;
        this.internalName = className.replace('.', '/');
        this.blockLabels = new Label[fn.blockCount()];

        cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(61, 1 | 0x20, internalName, null, "java/lang/Object", null);

        MethodVisitor cm = cw.visitMethod(1, "<init>", "()V", null, null);
        cm.visitCode();
        cm.visitVarInsn(A_ALOAD, 0); // this
        cm.visitMethodInsn(A_INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        cm.visitInsn(A_RETURN);
        cm.visitMaxs(1, 1);
        cm.visitEnd();

        mv = cw.visitMethod(1 | 8, "execute", EXECUTE_DESC, null, null);
    }

    private byte[] compileBytecode() {
        mv.visitCode();

        // Default parameter values are applied in CompiledFunction.execute()
        // before the bytecode runs. The bytecode itself trusts the caller
        // to have already expanded the locals array and filled defaults.
        // For INVOKE_DIRECT cross-calls, emitPackArgsAndCall sets
        // Runtime.setProvidedArgCount(argc) which the callee uses if needed.

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

            case IDEQ -> emitIdCmp(A_IF_ACMPEQ);
            case IDNE -> emitIdCmp(A_IF_ACMPNE);

            case PUSH_VAR -> {
                mv.visitVarInsn(A_ALOAD, S_LOCALS);
                emitIntConst(v.varIndex());
                mv.visitInsn(A_AALOAD);
            }
            case STORE_VAR -> {
                int varIdx = pl & 0xFFFF;
                mv.visitInsn(A_DUP);
                mv.visitVarInsn(A_ALOAD, S_LOCALS);
                mv.visitInsn(A_SWAP);
                emitIntConst(varIdx);
                mv.visitInsn(A_SWAP);
                mv.visitInsn(A_AASTORE);
            }
            case DUP -> mv.visitInsn(A_DUP);
            case POP -> mv.visitInsn(A_POP);
            case POP_N -> { for (int i=0; i<pl; i++) mv.visitInsn(A_POP); }
            case RETURN -> { mv.visitInsn(A_ARETURN); }  // already boxed
            case RETURN_VOID -> { mv.visitInsn(A_ACONST_NULL); mv.visitInsn(A_ARETURN); }
            case THROW -> {
                // Wrap non-RuntimeException in UserException, then throw
                mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
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
                int argc = pl;
                int funcIdx = v.operand(0);
                IRFunction target = (IRFunction) fn.constantPool()[funcIdx];
                // Self-recursive in typed mode → direct typed call
                // General case: register funcId, call invokeDirect at runtime
                int funcId = registerOrGetId(target);
                emitPackArgsAndCall(argc, true, funcId);
            }
            case INVOKE_DYN -> {
                int argc = pl; // emitInvokeDyn stores argCount in payload
                emitPackArgsAndCall(argc, false, null);
            }
            // ─── Property access, globals ───
            case LOAD_PROPERTY -> {
                // Stack: [base, key]. Need [ctx, base, key].
                mv.visitVarInsn(A_ASTORE, S_TMP);      // key → temp
                mv.visitVarInsn(A_ALOAD, S_CTX);        // [base, ctx]
                mv.visitInsn(A_SWAP);                    // [ctx, base]
                mv.visitVarInsn(A_ALOAD, S_TMP);        // [ctx, base, key]
                mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
                    "loadProperty", "(Ljavax/el/ELContext;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
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
                mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
                    "storeProperty", "(Ljavax/el/ELContext;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
            case LOAD_FIELD -> {
                int idx = v.payload();
                String name = (String) fn.constantPool()[idx];
                // Stack: [base]. LDC name → [base, name] — name on top (2nd param ✓)
                mv.visitLdcInsn(name);
                mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
                    "loadField", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            }
            case STORE_FIELD -> {
                int idx = v.payload();
                String name = (String) fn.constantPool()[idx];
                // Stack: [value, base]. LDC name → [value, base, name] — name on top (3rd param ✓)
                mv.visitLdcInsn(name);
                mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
                    "storeFieldBC", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            }
            case PUSH_GLOBAL -> {
                int idx = v.payload();
                String name = (String) fn.constantPool()[idx];
                mv.visitVarInsn(A_ALOAD, S_CTX);    // ctx
                mv.visitLdcInsn(name);               // name
                mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
                    "pushGlobal", "(Ljavax/el/ELContext;Ljava/lang/String;)Ljava/lang/Object;", false);
            }
            case STORE_GLOBAL, DEFINE_GLOBAL -> {
                int idx = v.payload();
                String name = (String) fn.constantPool()[idx];
                // Stack: [value]. Need [ctx, name, value].
                mv.visitVarInsn(A_ASTORE, S_TMP);    // save value
                mv.visitVarInsn(A_ALOAD, S_CTX);     // ctx
                mv.visitLdcInsn(name);                // name
                mv.visitVarInsn(A_ALOAD, S_TMP);     // value
                mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
                    "defineGlobal", "(Ljavax/el/ELContext;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
            }

            // ─── Collections ───
            case NEW_MAP   -> emitCallN("newMap", pl * 2); // pl = pair count, 2 stack values per pair
            case NEW_TUPLE -> emitCallN("newTuple", pl);
            case NEW_RANGE -> emitCall3("newRange");

            // ─── Cons ───
            case NEW_CONS -> emitCall2("newCons");
            case NIL ->
                mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/seq/Cons",
                    "nil", "()Lorg/elite/eval/seq/Cons;");

            case INVOKE_GETTER -> {
                java.lang.reflect.Method m = (java.lang.reflect.Method) fn.constantPool()[v.constPoolIndex()];
                emitDirectGetter(m);
            }
            case CLOSURE -> {
                int funcIdx = pl;
                int captureCount = v.opCount() > 0 ? v.operand(0) : 0;
                // Push funcIdx as int (not IRFunction via LDC — ASM doesn't support it)
                mv.visitVarInsn(A_ALOAD, S_CTX);
                emitIntConst(funcIdx);
                // Pack captureCount values from stack into Object[]
                if (captureCount > 0) {
                    int[] ts = new int[captureCount];
                    for (int i = 0; i < captureCount; i++) ts[i] = i + S_TMP;
                    for (int i = captureCount - 1; i >= 0; i--) mv.visitVarInsn(A_ASTORE, ts[i]);
                    emitIntConst(captureCount);
                    mv.visitTypeInsn(A_ANEWARRAY, "java/lang/Object");
                    for (int i = 0; i < captureCount; i++) {
                        mv.visitInsn(A_DUP); emitIntConst(i);
                        mv.visitVarInsn(A_ALOAD, ts[i]);
                        mv.visitInsn(A_AASTORE);
                    }
                } else {
                    mv.visitInsn(A_ICONST_0);
                    mv.visitTypeInsn(A_ANEWARRAY, "java/lang/Object");
                }
                mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
                    "createClosureById",
                    "(Ljavax/el/ELContext;I[Ljava/lang/Object;)Lorg/elite/ir/IRClosure;", false);
            }

            case INVOKE_METHOD, INVOKE_STATIC -> {
                java.lang.reflect.Method m = (java.lang.reflect.Method) fn.constantPool()[v.constPoolIndex()];
                int argc = v.opCount() > 0 ? v.operand(0) : 0;
                emitDirectMethod(m, argc);
            }

            case INVOKE_SETTER -> {
                java.lang.reflect.Method m = (java.lang.reflect.Method) fn.constantPool()[v.constPoolIndex()];
                emitDirectSetter(m);
            }

            // Trampoline: evaluate AST node via Runtime helper.
            // Both paths go through AST evaluation (trampolineById or trampolineTry).
            // After AST eval, sync globals back to locals so PUSH_VAR sees updates.
            case TRAMPOLINE -> {
                int poolIdx = v.constPoolIndex();
                mv.visitVarInsn(A_ALOAD, S_CTX);
                mv.visitLdcInsn(poolIdx);
                mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
                    "trampolineById", "(Ljavax/el/ELContext;I)Ljava/lang/Object;", false);
            }
            case NOP -> {}
            // Dynamic ops: call static helper methods directly
            case ADD, SUB, MUL, DIV, REM, POW, CAT, IN,
                 SHL, SHR, USHR, EQ, NE, LT, LE, GT, GE,
                 BITAND, BITOR, XOR -> {
                // Binary dynamic operators, needs ELContext for runtime call.
                // Stack: [x, y]. Need [ctx, x, y].
                mv.visitVarInsn(A_ASTORE, S_TMP + 1); // y -> temp
                mv.visitVarInsn(A_ASTORE, S_TMP);     // x -> temp
                mv.visitVarInsn(A_ALOAD, S_CTX);      // ctx
                mv.visitVarInsn(A_ALOAD, S_TMP);      // x
                mv.visitVarInsn(A_ALOAD, S_TMP + 1);  // y
                switch (op) {
                case ADD -> emitDynBinOp("dynAdd");
                case SUB -> emitDynBinOp("dynSub");
                case MUL -> emitDynBinOp("dynMul");
                case DIV -> emitDynBinOp("dynDiv");
                case REM -> emitDynBinOp("dynRem");
                case POW -> emitDynBinOp("dynPow");
                case CAT -> emitDynBinOp("dynCat");
                case IN -> emitDynBinOp("dynIn");
                case SHL -> emitDynBinOp("dynShl");
                case SHR -> emitDynBinOp("dynShr");
                case USHR -> emitDynBinOp("dynUShr");
                case EQ  -> emitDynBinOp("dynEq");
                case NE -> emitDynBinOp("dynNe");
                case LT  -> emitDynBinOp("dynLt");
                case LE  -> emitDynBinOp("dynLe");
                case GT -> emitDynBinOp("dynGt");
                case GE -> emitDynBinOp("dynGe");
                case BITAND -> emitDynBinOp("dynBitAnd");
                case BITOR -> emitDynBinOp("dynBitOr");
                case XOR -> emitDynBinOp("dynXor");
                }
            }
            case NEG, BITNOT -> {
                // Unary dynamic operators.
                // Stack [x]. Need [ctx, x].
                mv.visitVarInsn(A_ALOAD, S_CTX);
                mv.visitInsn(A_SWAP);
                switch (op) {
                case NEG -> emitDynUnaryOp("dynNeg");
                case BITNOT -> emitDynUnaryOp("dynBitNot");
                }
            }
            default -> throw new CompilationError(_T(IR_BC_UNHANDLED_OPCODE, Opcode.name(op), op));
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
    /** Reference identity comparison (=== / !==). Two Object refs on stack → Boolean. */
    private void emitIdCmp(int jvmOp) {
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
    private final Map<IRFunction, IRCompiledFunction> calleeCache = new HashMap<>();
    private final Map<IRFunction, Integer> funcIdMap = new HashMap<>();
    private int nextFuncId = 1;

    private int registerOrGetId(IRFunction target) {
        return funcIdMap.computeIfAbsent(target, fn -> {
            int id = funcIdCounter.get().incrementAndGet();
            funcRegistry().put(id, fn);
            return id;
        });
    }

    private IRCompiledFunction compileOrGet(IRFunction target, int[] argTypes) {
        return calleeCache.computeIfAbsent(target, fn -> {
            return compile(target);
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
        mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/ir/IRBytecodeCompiler",
            "invokeDirect", "(Ljavax/el/ELContext;I[Ljava/lang/Object;)Ljava/lang/Object;", false);
    }

    /** Pack args from stack into Object[] and call helper. */
    private void emitPackArgsAndCall(int argc, boolean direct, IRCompiledFunction cf) {
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
            // Generic Object[] call with ELContext
            mv.visitVarInsn(A_ALOAD, S_CTX);
            mv.visitMethodInsn(A_INVOKESTATIC, cf.internalName(), "execute",
                "(Ljavax/el/ELContext;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        } else if (direct) {
            // No compiled function — invokeDyn with ELContext from slot 0
            // Stack: [target, argsArray]. Need [ctx, target, argsArray].
            mv.visitVarInsn(A_ASTORE, S_TMP);      // args → temp
            mv.visitVarInsn(A_ALOAD, S_CTX);        // ctx
            mv.visitInsn(A_SWAP);                    // target, ctx → ctx, target
            mv.visitVarInsn(A_ALOAD, S_TMP);        // args
            mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
                "invokeDyn", "(Ljavax/el/ELContext;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        } else {
            // Stack: [target, argsArray]. Need [ctx, target, argsArray].
            mv.visitVarInsn(A_ASTORE, S_TMP);       // args → temp
            mv.visitVarInsn(A_ALOAD, S_CTX);         // ctx
            mv.visitInsn(A_SWAP);                     // ctx, target → target, ctx
            mv.visitVarInsn(A_ALOAD, S_TMP);         // args
            mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
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
        compiledCache.remove();
        Runtime.clearFuncPool();
    }
    private static java.util.Map<Integer, IRFunction> funcRegistry() { return funcRegistry.get(); }

    // registerFunction removed — use compileOrGet + calleeCache instead

    // Cache of compiled functions for fast direct calls
    private static final ThreadLocal<java.util.Map<IRFunction, IRCompiledFunction>> compiledCache =
        ThreadLocal.withInitial(java.util.HashMap::new);

    /** Direct call: use compiled version if available, specialize on first call. */
    public static Object invokeDirect(javax.el.ELContext elctx, int funcId, Object[] args) {
        IRFunction fn = funcRegistry().get(funcId);
        if (fn == null) throw new RuntimeException(_T(IR_FUNCTION_NOT_REGISTERED, funcId));
        // Check cache first
        IRCompiledFunction cf = compiledCache.get().get(fn);
        if (cf == null) {
            cf = compile(fn);  // generic Object[] version
            compiledCache.get().put(fn, cf);
        }
        return cf.execute(elctx, args);
    }

    // ── Simple call helpers (1-2 args popped from stack) ──

    private void emitCall2(String method) {
        mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
            method, "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
    }
    private void emitCall3(String method) {
        mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
                method, "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
    }
    private void emitCall1Obj(String method) {
        mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
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
        mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
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

        int j = 0;
        if (paramTypes.length > 0 && paramTypes[0] == javax.el.ELContext.class) {
            mv.visitVarInsn(A_ALOAD, S_CTX);
            j++;
        }

        // Load args in order, narrow/unbox each
        for (int i = 0; i < argc; i++, j++) {
            mv.visitVarInsn(A_ALOAD, S_TMP + i);
            emitUnboxIfPrimitive(paramTypes[j]);
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
        } else if (type != Object.class) {
            // Reference type — just checkcast
            mv.visitTypeInsn(A_CHECKCAST, Type.getInternalName(type));
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

    /** Compute JVM local var slot from IR var index (accounting for category-2 types). */
    private int typedSlot(int varIdx) {
        int slot = 1; // offset for ELContext in slot 0
        for (int i = 0; i < varIdx; i++) {
            slot += 1;
        }
        return slot;
    }

    private void emitDynBinOp(String method) {
        String desc = "(Ljavax/el/ELContext;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
        mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
            method, desc, false);
    }

    private void emitDynUnaryOp(String method) {
        String desc = "(Ljavax/el/ELContext;Ljava/lang/Object;)Ljava/lang/Object;";
        mv.visitMethodInsn(A_INVOKESTATIC, "org/elite/eval/Runtime",
            method, desc, false);
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
}
