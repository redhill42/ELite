package org.elite.ir;

import elite.lang.Closure;
import elite.lang.Seq;
import elite.lang.Symbol;
import org.elite.eval.ELEngine;
import org.elite.eval.EvaluationContext;
import org.elite.eval.Runtime;
import org.elite.eval.TypeCoercion;
import org.elite.eval.UserException;
import org.elite.eval.closure.LiteralClosure;
import org.elite.eval.closure.TypedClosure;
import org.elite.eval.seq.Cons;
import org.elite.eval.seq.DelayCons;
import org.elite.parser.ELNode;
import org.elite.util.DynamicClassLoader;
import org.elite.util.asm.ClassAssembly;
import org.elite.util.asm.MethodAssembly;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import javax.el.ELContext;
import javax.el.ValueExpression;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.elite.eval.ELUtils.*;
import static org.elite.ir.Opcode.*;
import static org.elite.resources.Resources.*;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;

public class BytecodeCompiler {

  private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

  private final String className;
  private final BytecodeConsumer consumer;

  private IRFunction     fn;
  private ClassAssembly  cc;
  private MethodAssembly mc;

  private final Map<IRFunction, String> methodNames = new HashMap<>();
  private final Map<IRFunction, String> closureNames = new HashMap<>();
  private final Map<Object, Integer>    constantMap = new HashMap<>();

  private Label[] blockLabels;

  // Pre-allocated slot indices.
  private static final int S_ENV   = 0;
  private static final int S_ARGS  = 1;
  private static final int S_CTX   = 2;

  // Function local slots allocated starts here. Temporary slots can be
  // allocated after SLOT_OFFSET + fn.maxLocals().
  private static final int SLOT_OFFSET = 3;

  private static final String CONSTANT_POOL_NAME = "$C";

  public static IRCompiledFunction compile(IRProgram program) {
    String name = "ELiteProgram$" + CLASS_COUNTER.incrementAndGet();
    var consumer = new JITBytecodeConsumer();
    new BytecodeCompiler(name, consumer).compileProgram(program);
    return consumer.complete();
  }

  public static void compile(IRProgram program, String name,
                             BytecodeConsumer consumer) {
    new BytecodeCompiler(name, consumer).compileProgram(program);
  }

  private BytecodeCompiler(String className, BytecodeConsumer consumer) {
    this.className = className;
    this.consumer = consumer;
  }

  private void compileProgram(IRProgram program) {
    // Assign method names.
    methodNames.put(program.entry(), "execute$main");
    int idx = 0;
    for (IRFunction f : program.functions()) {
      if (f != program.entry()) {
        String methodName = "execute$" +
                            (isJavaIdentifier(f.name()) ? f.name() : "") +
                            "$" + idx++;
        methodNames.put(f, methodName);
      }
    }

    this.cc = new ClassAssembly(ACC_PUBLIC | ACC_FINAL | ACC_SUPER, className,
                                Object.class, null);

    // Default constructor.
    cc.newMethod(ACC_PRIVATE, "<init>", "()V", null)
      .THIS()
      .INVOKESPECIAL(Object.class, "<init>", Void.TYPE, NO_ARGS)
      .RETURN()
      .end();

    // Compile entry function.
    compileFunction(program.entry(), methodNames.get(program.entry()));

    // Compile lambdas.
    for (IRFunction f : program.functions()) {
      if (f != program.entry()) {
        compileFunction(f, methodNames.get(f));
      }
    }

    // Compile final persistent constant pool.
    compileConstantPool();

    // Compile main method.
    compileMain();

    // Produce the final compiled program.
    consumer.acceptProgram(className, cc.end());
  }

  private static boolean isJavaIdentifier(String name) {
    if (name == null || name.isEmpty())
      return false;
    if (!Character.isJavaIdentifierStart(name.charAt(0)))
      return false;
    for (int i = 1; i < name.length(); i++)
      if (!Character.isJavaIdentifierPart(name.charAt(i)))
        return false;
    return true;
  }

  private int addToConstantPool(Object cst) {
    return constantMap.computeIfAbsent(cst, k -> constantMap.size());
  }

  private void compileFunction(IRFunction f, String methodName) {
    this.fn = f;

    this.blockLabels = new Label[f.blockCount()];
    for (int i = 0; i < f.blockCount(); i++)
      blockLabels[i] = new Label();

    // The function method:
    // public static Object execute$func$0(
    //    EvaluationContext env, Object[] args)
    this.mc = cc.newMethod(ACC_PUBLIC | ACC_STATIC | ACC_FINAL,
                           methodName, Object.class,
                           new Class<?>[]{
                             EvaluationContext.class, Object[].class},
                           null);

    // Store ELContext to local slot.
    mc.ALOAD(S_ENV)
      .INVOKEVIRTUAL(EvaluationContext.class, "getELContext", ELContext.class)
      .ASTORE(S_CTX);

    // Copy arguments to local slots.
    if (fn.paramCount() != 0) {
      mc.ALOAD(S_ARGS);
      for (int i = 0; i < fn.paramCount(); i++) {
        if (i != fn.paramCount() - 1)
          mc.DUP();
        mc.PUSH(i)
          .AALOAD()
          .ASTORE(i + SLOT_OFFSET);
      }
    }

    // Compile IR into Java bytecode.
    InstructionView v = new InstructionView(fn.code(), 0);
    for (; v.inBounds(); v.advance()) {
      int blockId = fn.blockOfPc(v.offset());
      if (blockId != -1)
        mc.label(blockLabels[blockId]);
      compileInst(v);
    }

    mc.end();
  }

  private void compileInst(InstructionView v) {
    switch (v.opcode()) {
    case NOP -> {}
    case DUP -> mc.DUP();
    case POP -> mc.POP();

    case PUSH_CONST -> {
      Object value = fn.getConstant(v.poolIndex());
      if (value instanceof Boolean || value instanceof Byte ||
          value instanceof Short   || value instanceof Character ||
          value instanceof Integer || value instanceof Long ||
          value instanceof Float   || value instanceof Double) {
        mc.BOX(value);
      } else if (value instanceof String) {
        mc.LDC(value);
      } else if (value instanceof Class) {
        mc.LDC(Type.getType((Class<?>)value));
      } else if (value instanceof Symbol) {
        mc.LDC(((Symbol)value).getName())
          .INVOKESTATIC(Symbol.class, "valueOf", Symbol.class, String.class);
      } else {
        // Load constant from constant pool.
        mc.GETSTATIC(className, CONSTANT_POOL_NAME, Object[].class)
          .PUSH(addToConstantPool(fn.getConstant(v.poolIndex())))
          .AALOAD();
      }
    }

    case PUSH_TRUE ->
      mc.GETSTATIC(Boolean.class, "TRUE", Boolean.class);
    case PUSH_FALSE ->
      mc.GETSTATIC(Boolean.class, "FALSE", Boolean.class);
    case PUSH_NULL ->
      mc.ACONST_NULL();

    case PUSH_ENV ->
      mc.ALOAD(S_ENV);
    case PUSH_CTX ->
      mc.ALOAD(S_CTX);

    case PUSH_VAR ->
      mc.ALOAD(v.varIndex() + SLOT_OFFSET);
    case STORE_VAR ->
      mc.DUP().ASTORE(v.varIndex() + SLOT_OFFSET);
    case STORE_VAR_POP ->
      mc.ASTORE(v.varIndex() + SLOT_OFFSET);

    case DEFINE_GLOBAL ->
      mc.LDC(fn.getConstant(v.poolIndex()))
        .ALOAD(S_ENV)
        .INVOKESTATIC(Runtime.class, "defineGlobal", void.class,
                      Object.class, String.class, EvaluationContext.class);
    case PUSH_GLOBAL ->
      mc.LDC(fn.getConstant(v.poolIndex()))
        .ALOAD(S_ENV)
        .INVOKESTATIC(Runtime.class, "resolveGlobal", Object.class,
                      String.class, EvaluationContext.class);
    case STORE_GLOBAL ->
      mc.LDC(fn.getConstant(v.poolIndex()))
        .ALOAD(S_ENV)
        .INVOKESTATIC(Runtime.class, "storeGlobal", Object.class,
                      Object.class, String.class, EvaluationContext.class);

    case ADD    -> emitBinary(v, "__add__",    Object.class);
    case SUB    -> emitBinary(v, "__sub__",    Object.class);
    case MUL    -> emitBinary(v, "__mul__",    Object.class);
    case DIV    -> emitBinary(v, "__div__",    Object.class);
    case IDIV   -> emitBinary(v, "__idiv__",   Object.class);
    case REM    -> emitBinary(v, "__rem__",    Object.class);
    case POW    -> emitBinary(v, "__pow__",    Object.class);
    case NEG    -> emitUnary (v, "__neg__",    Object.class);
    case CAT    -> emitBinary(v, "__cat__",    Object.class);
    case BITAND -> emitBinary(v, "__bitand__", Object.class);
    case BITOR  -> emitBinary(v, "__bitor__",  Object.class);
    case BITNOT -> emitUnary (v, "__bitand__", Object.class);
    case XOR    -> emitBinary(v, "__xor__",    Object.class);
    case SHL    -> emitBinary(v, "__shl__",    Object.class);
    case SHR    -> emitBinary(v, "__shr__",    Object.class);
    case USHR   -> emitBinary(v, "__ushr__",   Object.class);
    case EQ     -> emitBinary(v, "__eq__",     Boolean.TYPE);
    case NE     -> emitBinary(v, "__ne__",     Boolean.TYPE);
    case LT     -> emitBinary(v, "__lt__",     Boolean.TYPE);
    case LE     -> emitBinary(v, "__le__",     Boolean.TYPE);
    case GT     -> emitBinary(v, "__gt__",     Boolean.TYPE);
    case GE     -> emitBinary(v, "__ge__",     Boolean.TYPE);
    case IN     -> emitBinary(v, "__in__",     Boolean.TYPE);
    case EMPTY  -> emitUnary (v, "__empty__",  Boolean.TYPE);

    case IDEQ, IDNE -> {
      InstructionView next = v.peek();
      if (next.inBounds() && fn.blockOfPc(next.offset()) == -1 &&
          (next.opcode() == JUMP_IF_TRUE || next.opcode() == JUMP_IF_FALSE)) {
        if ((v.opcode() == IDEQ) ^ (next.opcode() == JUMP_IF_FALSE))
          mc.IF_ACMPEQ(blockLabels[next.jumpTarget()]);
        else
          mc.IF_ACMPNE(blockLabels[next.jumpTarget()]);
        v.advance();
      } else {
        Label t = new Label(), e = new Label();
        if (v.opcode() == IDEQ)
          mc.IF_ACMPEQ(t);
        else
          mc.IF_ACMPNE(t);
        mc.FALSE()
          .GOTO(e)
          .label(t)
          .TRUE()
          .label(e)
          .BOX(Boolean.TYPE);
      }
    }

    case INSTANCEOF -> {
      Object cls = fn.getConstant(v.poolIndex());
      if (cls instanceof Class) {
        mc.INSTANCEOF((Class<?>)cls);
      } else {
        mc.LDC(cls)
          .ALOAD(S_ENV)
          .INVOKESTATIC(Runtime.class, "__instanceof__", Boolean.TYPE,
                        Object.class, String.class, EvaluationContext.class);
      }
      emitJumpAfterCond(v);
    }

    case NOT -> {
      InstructionView next = v.peek();
      if ((next.inBounds() && fn.blockOfPc(next.offset()) == -1) &&
          (next.opcode() == JUMP_IF_TRUE || next.opcode() == JUMP_IF_FALSE)) {
        mc.UNBOX(Boolean.TYPE);
        if (next.opcode() == JUMP_IF_TRUE)
          mc.IFEQ(blockLabels[next.jumpTarget()]);
        else
          mc.IFNE(blockLabels[next.jumpTarget()]);
        v.advance();
      } else {
        mc.UNBOX(Boolean.TYPE)
          .ICONST_1()
          .IXOR()
          .BOX(Boolean.TYPE);
      }
    }

    case JUMP ->
      mc.GOTO(blockLabels[v.jumpTarget()]);
    case JUMP_IF_TRUE ->
      mc.UNBOX(Boolean.TYPE).IFNE(blockLabels[v.jumpTarget()]);
    case JUMP_IF_FALSE ->
      mc.UNBOX(Boolean.TYPE).IFEQ(blockLabels[v.jumpTarget()]);
    case JUMP_IF_NULL ->
      mc.IFNULL(blockLabels[v.jumpTarget()]);
    case JUMP_IF_NONNULL ->
      mc.IFNONNULL(blockLabels[v.jumpTarget()]);

    case RETURN -> mc.ARETURN();

    case THROW -> {
      Label b1 = new Label(), b2 = new Label(), b3 = new Label();
      int tmpSlot = SLOT_OFFSET + fn.maxLocals();
      mc.DUP()
        .ASTORE(tmpSlot)
        .INSTANCEOF(RuntimeException.class)
        .IFEQ(b1)
        .ALOAD(tmpSlot)
        .CHECKCAST(RuntimeException.class)
        .ATHROW()
      .label(b1)
        .ALOAD(tmpSlot)
        .INSTANCEOF(Throwable.class)
        .IFEQ(b2)
        .NEW(UserException.class)
        .DUP()
        .ALOAD(S_CTX)
        .ALOAD(tmpSlot)
        .CHECKCAST(Throwable.class)
        .INVOKESPECIAL(UserException.class, "<init>", Void.TYPE,
                       ELContext.class, Throwable.class)
        .ATHROW()
      .label(b2)
        .ALOAD(tmpSlot)
        .INSTANCEOF(String.class)
        .IFEQ(b3)
        .NEW(UserException.class)
        .DUP()
        .ALOAD(S_CTX)
        .ALOAD(tmpSlot)
        .CHECKCAST(String.class)
        .INVOKESPECIAL(UserException.class, "<init>", Void.TYPE,
                       ELContext.class, String.class)
        .ATHROW()
      .label(b3)
        .NEW(UserException.class)
        .DUP()
        .ALOAD(S_CTX)
        .INVOKESPECIAL(UserException.class, "<init>", Void.TYPE,
                       ELContext.class)
        .ATHROW();
    }

    case TRY -> {
      // Pop closures: finalizer, handlers+types, body (top -> bottom)
      int cnt = v.count();
      int[] handlerSlots = new int[cnt];
      int[] typeSlots    = new int[cnt];
      int tmp = SLOT_OFFSET + fn.maxLocals();
      int finalizerSlot = tmp++;
      int resultSlot    = tmp++;
      int caughtSlot    = tmp++;
      mc.ASTORE(finalizerSlot);
      for (int i = cnt - 1; i >= 0; i--) {
        handlerSlots[i] = tmp++;
        typeSlots[i]    = tmp++;
        mc.ASTORE(handlerSlots[i]);
        mc.ASTORE(typeSlots[i]);
      }

      // try {
      // TryStart:
      //     result = body.call(ctx, p[]);
      // TryEnd:
      //     if (finalizer != null)
      //         finalizer.call(ctx, [])
      //     goto done
      // CatchStart:
      // } catch (Throwable caught) {
      //     if (typecheck(type[i], caught)) {
      //         result = handler[i].call(ctx, [caught])
      //         goto CatchEnd
      //     }
      //     throw caught
      // CatchEnd:
      //     if (finalizer != null)
      //         finalizer.call(ctx, [])
      //     goto done
      // FinalStart:
      // } finally (caught) {
      //     if (finalizer != null)
      //         finalizer.call(ctx, [])
      //     throw caught
      // }
      // done:
      //
      // Try-Catch: TryStart, TryEnd, CatchStart, Throwable
      // Try-Catch: TryStart, TryEnd, FinalStart, any
      // Try-Catch: CatchStart, CatchEnd, FinalStart, any

      Label tryStart   = new Label(), tryEnd    = new Label();
      Label catchStart = new Label(), catchEnd  = new Label();
      Label finalStart = new Label(), done      = new Label();

      // body is on the stack top
      mc.label(tryStart)
        .ALOAD(S_CTX)
        .ICONST_0()
        .ANEWARRAY(Object.class)
        .INVOKEVIRTUAL(Closure.class, "call", Object.class, ELContext.class,
                       Object[].class)
        .ASTORE(resultSlot)
      .label(tryEnd)
        .ALOAD(finalizerSlot)
        .IFNULL(done)
        .ALOAD(finalizerSlot)
        .ALOAD(S_CTX)
        .ICONST_0()
        .ANEWARRAY(Object.class)
        .INVOKEVIRTUAL(Closure.class, "call", Object.class, ELContext.class,
                       Object[].class)
        .POP()
        .GOTO(done);

      if (handlerSlots.length != 0) {
        mc.label(catchStart).ASTORE(caughtSlot);
        for (int i = 0; i < handlerSlots.length; i++) {
          Label next = new Label();
          mc.ALOAD(S_ENV)             // if (!typecheck) goto next
            .ALOAD(typeSlots[i])
            .CHECKCAST(String.class)
            .ALOAD(caughtSlot)
            .INVOKESTATIC(TypedClosure.class, "typecheck", Boolean.TYPE,
                          EvaluationContext.class, String.class, Object.class)
            .IFEQ(next)               // no match, try next handler
            .ALOAD(handlerSlots[i])   // result = handler.call(ctx, [caught])
            .ALOAD(S_CTX)
            .ICONST_1()
            .ANEWARRAY(Object.class)
            .DUP()
            .ICONST_0()
            .ALOAD(caughtSlot)
            .AASTORE()
            .INVOKEVIRTUAL(Closure.class, "call", Object.class, ELContext.class,
                           Object[].class)
            .ASTORE(resultSlot)
            .GOTO(catchEnd)
          .label(next);
        }

        // Rethrow if no handler match
        mc.ALOAD(caughtSlot).ATHROW();

        mc.label(catchEnd)
          .ALOAD(finalizerSlot)
          .IFNULL(done)
          .ALOAD(finalizerSlot)
          .ALOAD(S_CTX)
          .ICONST_0()
          .ANEWARRAY(Object.class)
          .INVOKEVIRTUAL(Closure.class, "call", Object.class, ELContext.class,
                         Object[].class)
          .POP()
          .GOTO(done);
      }

      // finally { if (finalize != null) finalizer.call(ctx, []) }
      Label finalDone = new Label();
      mc.label(finalStart)
        .ASTORE(caughtSlot)
        .ALOAD(finalizerSlot)
        .IFNULL(finalDone)
        .ALOAD(finalizerSlot)
        .ALOAD(S_CTX)
        .ICONST_0()
        .ANEWARRAY(Object.class)
        .INVOKEVIRTUAL(Closure.class, "call", Object.class, ELContext.class,
                       Object[].class)
        .POP()
      .label(finalDone)
        .ALOAD(caughtSlot)
        .ATHROW();

      mc.label(done);
      mc.ALOAD(resultSlot);

      if (handlerSlots.length != 0) {
        mc.TryCatchBlock(tryStart, tryEnd, catchStart,Throwable.class);
        mc.TryCatchBlock(tryStart, tryEnd, finalStart, (String)null);
        mc.TryCatchBlock(catchStart, catchEnd, finalStart, (String)null);
      } else {
        mc.TryCatchBlock(tryStart, tryEnd, finalStart, (String)null);
      }
    }

    case SYNCHRONIZED -> {
      int tmp = SLOT_OFFSET + fn.maxLocals();
      int lockSlot = tmp++, bodySlot = tmp++, resultSlot = tmp++;
      Label tryStart = new Label(), tryEnd = new Label();
      Label catchAll = new Label(), done = new Label();

      mc.ASTORE(bodySlot)
        .DUP()
        .ASTORE(lockSlot)
        .MONITORENTER()
      .label(tryStart)
        .ALOAD(bodySlot)
        .ALOAD(S_CTX)
        .ICONST_0()
        .ANEWARRAY(Object.class)
        .INVOKEVIRTUAL(Closure.class, "call", Object.class, ELContext.class,
                       Object[].class)
        .ASTORE(resultSlot)
        .ALOAD(lockSlot)
        .MONITOREXIT()
      .label(tryEnd)
        .GOTO(done)
      .label(catchAll)
        .ALOAD(lockSlot)
        .MONITOREXIT()
        .ATHROW()
      .label(done)
        .ALOAD(resultSlot)
      .TryCatchBlock(tryStart, tryEnd, catchAll, Throwable.class);
    }

    case ASSERT -> {
      // TODO: create $assertionsDisabled field.
      Label c = new Label();
      if (v.count() == 1) {
        mc.UNBOX(Boolean.TYPE)
          .IFNE(c)
          .NEW(AssertionError.class)
          .DUP()
          .INVOKESPECIAL(AssertionError.class, "<init>", Void.TYPE)
          .ATHROW()
          .label(c)
          .ACONST_NULL();
      } else {
        int tmpSlot = SLOT_OFFSET + fn.maxLocals();
        mc.SWAP()
          .UNBOX(Boolean.TYPE)
          .IFNE(c)
          .ASTORE(tmpSlot)
          .NEW(AssertionError.class)
          .DUP()
          .ALOAD(tmpSlot)
          .INVOKESPECIAL(AssertionError.class, "<init>", Void.TYPE,
                         Object.class)
          .ATHROW()
          .label(c)
          .POP()
          .ACONST_NULL();
      }
    }

    case ENTER_SCOPE ->
      mc.ALOAD(S_ENV)
        .INVOKEVIRTUAL(EvaluationContext.class, "pushContext",
                       EvaluationContext.class)
        .ASTORE(S_ENV);

    case LEAVE_SCOPE ->
      mc.ALOAD(S_ENV)
        .INVOKEVIRTUAL(EvaluationContext.class, "popContext",
                       EvaluationContext.class)
        .ASTORE(S_ENV);

    case CLOSURE -> {
      IRFunction closure = (IRFunction)fn.getConstant(v.poolIndex());
      String closureName = compileClosure(closure);
      mc.NEW(closureName)
        .DUP()
        .ALOAD(S_ENV)
        .INVOKESPECIAL(closureName, "<init>", Void.TYPE,
                       EvaluationContext.class);
    }

    case INVOKE_DIRECT -> {
      IRFunction closure = (IRFunction)fn.getConstant(v.poolIndex());
      String methodName = methodNames.get(closure);
      assert methodName != null;

      // Invoke function method, the argument list is on stack top.
      mc.ALOAD(S_ENV)
        .INVOKEVIRTUAL(EvaluationContext.class, "pushContext",
                       EvaluationContext.class)
        .SWAP()
        .INVOKESTATIC(className, methodName, Object.class,
                      EvaluationContext.class, Object[].class);
    }

    case INVOKE_METHOD -> {
      Method m = (Method)fn.getConstant(v.poolIndex());
      if (Modifier.isStatic(m.getModifiers())) {
        mc.INVOKESTATIC(m.getDeclaringClass(), m.getName(), m.getReturnType(),
                        m.getParameterTypes());
      } else if (m.getDeclaringClass().isInterface()) {
        mc.INVOKEINTERFACE(m.getDeclaringClass(), m.getName(),
                           m.getReturnType(), m.getParameterTypes());
      } else {
        mc.INVOKEVIRTUAL(m.getDeclaringClass(), m.getName(), m.getReturnType(),
                         m.getParameterTypes());
      }

      if (m.getReturnType() == Boolean.TYPE)
        emitJumpAfterCond(v);
      else if (m.getReturnType().isPrimitive() &&
               m.getReturnType() != Void.TYPE)
        mc.BOX(m.getReturnType());
    }

    case NEW ->
      mc.NEW((Class<?>)fn.getConstant(v.poolIndex()))
        .DUP();
    case CONSTRUCTOR -> {
      Constructor<?> cons = (Constructor<?>)fn.getConstant(v.poolIndex());
      mc.INVOKESPECIAL(cons.getDeclaringClass(), "<init>", Void.TYPE,
                       cons.getParameterTypes());
    }

    case NEW_ARRAY -> {
      Class<?> c = (Class<?>)fn.getConstant(v.poolIndex());
      mc.PUSH(v.count());
      if (c == Integer.TYPE)
        mc.NEWARRAY(Opcodes.T_INT);
      else if (c == Long.TYPE)
        mc.NEWARRAY(Opcodes.T_LONG);
      else if (c == Byte.TYPE)
        mc.NEWARRAY(Opcodes.T_BYTE);
      else if (c == Short.TYPE)
        mc.NEWARRAY(Opcodes.T_SHORT);
      else if (c == Character.TYPE)
        mc.NEWARRAY(Opcodes.T_CHAR);
      else if (c == Float.TYPE)
        mc.NEWARRAY(Opcodes.T_FLOAT);
      else if (c == Double.TYPE)
        mc.NEWARRAY(Opcodes.T_DOUBLE);
      else if (c == Boolean.TYPE)
        mc.NEWARRAY(Opcodes.T_BOOLEAN);
      else
        mc.ANEWARRAY(c);
    }

    case LOAD_ARRAY -> {
      Class<?> c = (Class<?>)fn.getConstant(v.poolIndex());
      mc.PUSH(v.count());
      if (c == Integer.TYPE)
        mc.IALOAD();
      else if (c == Long.TYPE)
        mc.LALOAD();
      else if (c == Byte.TYPE || c == Boolean.TYPE)
        mc.BALOAD();
      else if (c == Short.TYPE)
        mc.SALOAD();
      else if (c == Character.TYPE)
        mc.CALOAD();
      else if (c == Float.TYPE)
        mc.FALOAD();
      else if (c == Double.TYPE)
        mc.DALOAD();
      else
        mc.AALOAD();
    }

    case STORE_ARRAY -> {
      Class<?> c = (Class<?>)fn.getConstant(v.poolIndex());

      if (c.isPrimitive()) {
        mc.UNBOX(c);
      } else if (c != Object.class) {
        mc.LDC(Type.getType(c))
          .INVOKESTATIC(TypeCoercion.class, "coerce", Object.class,
                        Object.class, Class.class)
          .CHECKCAST(c);
      }

      mc.PUSH(v.count());
      mc.SWAP();

      if (c == Integer.TYPE)
        mc.IASTORE();
      else if (c == Long.TYPE)
        mc.LASTORE();
      else if (c == Byte.TYPE || c == Boolean.TYPE)
        mc.BASTORE();
      else if (c == Short.TYPE)
        mc.SASTORE();
      else if (c == Character.TYPE)
        mc.CASTORE();
      else if (c == Float.TYPE)
        mc.FASTORE();
      else if (c == Double.TYPE)
        mc.DASTORE();
      else
        mc.AASTORE();
    }

    case LOAD_FIELD -> {
      Field f = (Field)fn.getConstant(v.poolIndex());
      if (Modifier.isStatic(f.getModifiers()))
        mc.GETSTATIC(f.getDeclaringClass(), f.getName(), f.getType());
      else
        mc.GETFIELD(f.getDeclaringClass(), f.getName(), f.getType());
      if (f.getType() == Boolean.TYPE)
        emitJumpAfterCond(v);
      else if (f.getType().isPrimitive())
        mc.BOX(f.getType());
    }

    case STORE_FIELD -> {
      Field f = (Field)fn.getConstant(v.poolIndex());
      if (f.getType().isPrimitive())
        mc.UNBOX(f.getType());
      if (Modifier.isStatic(f.getModifiers()))
        mc.PUTSTATIC(f.getDeclaringClass(), f.getName(), f.getType());
      else
        mc.PUTFIELD(f.getDeclaringClass(), f.getName(), f.getType());
    }

    case CHECKCAST ->
      mc.CHECKCAST((Class<?>)fn.getConstant(v.poolIndex()));
    case BOX ->
      mc.BOX((Class<?>)fn.getConstant(v.poolIndex()));
    case UNBOX ->
      mc.UNBOX((Class<?>)fn.getConstant(v.poolIndex()));

    case NEW_CONS -> {
      Label c = new Label();
      int head = SLOT_OFFSET + fn.maxLocals();
      int tail = head + 1;
      mc.DUP()
        .INSTANCEOF(Seq.class)
        .IFNE(c)
        .INVOKESTATIC(TypeCoercion.class, "coerceToSeq", Seq.class,
                      Object.class)
        .label(c)
        .ASTORE(tail)
        .ASTORE(head)
        .NEW(Cons.class)
        .DUP()
        .ALOAD(head)
        .ALOAD(tail)
        .INVOKESPECIAL(Cons.class, "<init>", Void.TYPE, Object.class, Seq.class);
    }

    case NEW_DELAY_CONS -> {
      int head = SLOT_OFFSET + fn.maxLocals();
      int tail = head + 1;
      mc.CHECKCAST(Closure.class)
        .ASTORE(tail)
        .CHECKCAST(Closure.class)
        .ASTORE(head)
        .NEW(DelayCons.class)
        .DUP()
        .ALOAD(head)
        .ALOAD(tail)
        .INVOKESPECIAL(DelayCons.class, "<init>", Void.TYPE,
                       Closure.class, Closure.class);
    }

    case NIL ->
      mc.INVOKESTATIC(Cons.class, "nil", Cons.class);

    case NEW_TUPLE -> {
      int cnt = v.count();
      mc.PUSH(cnt);
      mc.ANEWARRAY(Object.class);
      for (int i = cnt; i != 0; i--) {
        mc.DUP_X1()
          .SWAP()
          .PUSH(cnt)
          .SWAP()
          .AASTORE();
      }
    }

    case DECLARE_NS ->
      mc.INVOKESTATIC(TypeCoercion.class, "coerceToString", String.class,
                      Object.class)
        .ALOAD(S_ENV)
        .SWAP()
        .LDC(fn.getConstant(v.poolIndex()))
        .SWAP()
        .INVOKEVIRTUAL(EvaluationContext.class, "declarePrefix", Void.TYPE,
                       String.class, String.class);

    case TRAMPOLINE ->
      mc.GETSTATIC(className, CONSTANT_POOL_NAME, Object[].class)
        .PUSH(addToConstantPool(fn.getConstant(v.poolIndex())))
        .AALOAD()
        .CHECKCAST(ELNode.class)
        .ALOAD(S_ENV)
        .INVOKEVIRTUAL(ELNode.class, "getValue", Object.class,
                       EvaluationContext.class);

    default -> throw new CompilationError(_T(IR_BC_UNHANDLED_OPCODE, v.opcode(),
                                           Opcode.name(v.opcode())));
    }
  }

  private String compileClosure(IRFunction closure) {
    String name = closureNames.get(closure);
    if (name != null)
      return name;

    name = className + "$" + "Closure$" +
           (isJavaIdentifier(closure.name()) ? closure.name() : "") +
           "$" + closureNames.size();
    closureNames.put(closure, name);

    String methodName = methodNames.get(closure);
    assert methodName != null;

    ClassAssembly ccw = new ClassAssembly(ACC_PRIVATE | ACC_SUPER, name,
                                          IRCompiledClosure.class, null);

    // Constructor.
    ccw.newMethod(ACC_PUBLIC, "<init>", Void.TYPE,
                  new Class[]{EvaluationContext.class}, null)
      .THIS()
      .ALOAD(1)
      .INVOKESPECIAL(IRCompiledClosure.class, "<init>", Void.TYPE,
                     EvaluationContext.class)
      .RETURN()
      .end();

    // Implement abstract methods.

    ccw.newMethod(ACC_PUBLIC, "getName", String.class, new Class<?>[0], null)
      .LDC(closure.name())
      .ARETURN()
      .end();

    ccw.newMethod(ACC_PUBLIC, "arity", Integer.TYPE,
                  new Class[]{ELContext.class}, null)
      .PUSH(closure.paramCount())
      .IRETURN()
      .end();

    // Object execute(EvaluationContext env, Object[] args) {
    //     return ELiteProgram$1.execute$2(env.pushContext(), args);
    // }
    ccw.newMethod(ACC_PROTECTED, "execute", Object.class,
                  new Class[]{EvaluationContext.class, Object[].class}, null)
      .ALOAD(1)
      .INVOKEVIRTUAL(EvaluationContext.class, "pushContext",
                     EvaluationContext.class)
      .ALOAD(2)
      .INVOKESTATIC(className, methodName, Object.class,
                    EvaluationContext.class, Object[].class)
      .ARETURN()
      .end();

    consumer.acceptClosure(name, ccw.end());
    return name;
  }

  private void compileConstantPool() {
    if (constantMap.isEmpty())
      return;

    cc.addField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, CONSTANT_POOL_NAME,
                Object[].class);

    mc = cc.newMethod(ACC_STATIC, "<clinit>", "()V", null);

    mc.PUSH(constantMap.size())
      .ANEWARRAY(Object.class)
      .PUTSTATIC(className, CONSTANT_POOL_NAME, Object[].class);

    for (var e : constantMap.entrySet()) {
      try {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ObjectOutputStream oout = new ObjectOutputStream(out);
        oout.writeObject(e.getKey());
        oout.close();
        String encoded = Base64.getEncoder().encodeToString(out.toByteArray());

        mc.GETSTATIC(className, CONSTANT_POOL_NAME, Object[].class)
          .PUSH(e.getValue())
          .LDC(encoded)
          .INVOKESTATIC(Runtime.class, "decodeObject", Object.class,
                        String.class)
          .AASTORE();
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    mc.RETURN()
      .end();
  }

  private void compileMain() {
    // public static void main(String[] args) {
    //   ELContext elctx = ELEngine.createELContxt();
    //   EvaluationContext env = new EvaluationContext(elctx);
    //   env.setVariable("ARGV", new LiteralClosure(args));
    //   execute(env, new Object[0]);
    // }
    cc.newMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V",
                 null)
      .NEW(EvaluationContext.class)
      .DUP()
      .INVOKESTATIC(ELEngine.class, "createELContext", ELContext.class)
      .INVOKESPECIAL(EvaluationContext.class, "<init>", Void.TYPE,
                     ELContext.class)
      .DUP()
      .LDC("ARGV")
      .NEW(LiteralClosure.class)
      .DUP()
      .ALOAD(0)
      .INVOKESPECIAL(LiteralClosure.class, "<init>", Void.TYPE, Object.class)
      .INVOKEVIRTUAL(EvaluationContext.class, "setVariable", Void.TYPE,
                     String.class, ValueExpression.class)
      .ICONST_0()
      .ANEWARRAY(Object.class)
      .INVOKESTATIC(className, "execute$main", Object.class,
                    EvaluationContext.class, Object[].class)
      .POP()
      .RETURN()
      .end();
  }

  //----------------------------------------------------------------------------

  private void emitBinary(InstructionView v, String name, Class<?> returnType) {
    mc.ALOAD(S_CTX);
    mc.INVOKESTATIC(Runtime.class, name, returnType,
                    Object.class, Object.class, ELContext.class);
    if (returnType == Boolean.TYPE)
      emitJumpAfterCond(v);
  }

  private void emitUnary(InstructionView v, String name, Class<?> returnType) {
    mc.ALOAD(S_CTX);
    mc.INVOKESTATIC(Runtime.class, name, returnType,
                    Object.class, ELContext.class);
    if (returnType == Boolean.TYPE)
      emitJumpAfterCond(v);
  }

  private void emitJumpAfterCond(InstructionView v) {
    InstructionView next = v.peek();
    if ((next.inBounds() && fn.blockOfPc(next.offset()) == -1) &&
        (next.opcode() == JUMP_IF_TRUE || next.opcode() == JUMP_IF_FALSE)) {
      if (next.opcode() == JUMP_IF_TRUE)
        mc.IFNE(blockLabels[next.jumpTarget()]);
      else
        mc.IFEQ(blockLabels[next.jumpTarget()]);
      v.advance();
    } else {
      mc.BOX(Boolean.TYPE);
    }
  }

  //----------------------------------------------------------------------------

  private static class JITBytecodeConsumer implements BytecodeConsumer {
    private static final DynamicClassLoader LOADER = new DynamicClassLoader();
    private Class<?> programClass;

    @Override
    public void acceptProgram(String className, byte[] bytecode) {
      programClass = LOADER.addClass(className, bytecode);
    }

    @Override
    public void acceptClosure(String className, byte[] bytecode) {
      LOADER.addClass(className, bytecode);
    }

    IRCompiledFunction complete() {
      try {
        Method m = programClass.getMethod("execute$main",
                                          EvaluationContext.class,
                                          Object[].class);
        return new IRCompiledFunction(m, null);
      } catch (Exception e) {
        throw new RuntimeException(_T(IR_BYTECODE_COMPILE_FAILED), e);
      }
    }
  }
}
