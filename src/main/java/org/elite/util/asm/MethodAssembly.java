/**
 * Cloudway Platform
 * Copyright (c) 2012-2013 Cloudway Technology, Inc.
 * All rights reserved.
 */
package org.elite.util.asm;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;

import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Label;

import static org.objectweb.asm.Opcodes.*;

@SuppressWarnings("unused")
public class MethodAssembly {
  private final ClassAssembly cw;
  private final MethodVisitor impl;

  public MethodAssembly(ClassAssembly cw, MethodVisitor mv) {
    this.cw   = cw;
    this.impl = mv;
  }

  public MethodVisitor getImpl() {
    return impl;
  }

  public void end() {
    impl.visitMaxs(0, 0);
    impl.visitEnd();
  }

  public AnnotationAssembly ANNOTATION(String descriptor, boolean visible) {
    return new AnnotationAssembly(
      null, impl.visitAnnotation(descriptor, visible));
  }

  public AnnotationAssembly ANNOTATION(Class<?> type, boolean visible) {
    return new AnnotationAssembly(
      null, impl.visitAnnotation(AsmType.getDescriptor(type), visible));
  }

  // zero operand instructions ...

  public MethodAssembly NOP()           { impl.visitInsn(NOP);           return this; }
  public MethodAssembly ACONST_NULL()   { impl.visitInsn(ACONST_NULL);   return this; }
  public MethodAssembly ICONST_M1()     { impl.visitInsn(ICONST_M1);     return this; }
  public MethodAssembly ICONST_0()      { impl.visitInsn(ICONST_0);      return this; }
  public MethodAssembly ICONST_1()      { impl.visitInsn(ICONST_1);      return this; }
  public MethodAssembly ICONST_2()      { impl.visitInsn(ICONST_2);      return this; }
  public MethodAssembly ICONST_3()      { impl.visitInsn(ICONST_3);      return this; }
  public MethodAssembly ICONST_4()      { impl.visitInsn(ICONST_4);      return this; }
  public MethodAssembly ICONST_5()      { impl.visitInsn(ICONST_5);      return this; }
  public MethodAssembly LCONST_0()      { impl.visitInsn(LCONST_0);      return this; }
  public MethodAssembly LCONST_1()      { impl.visitInsn(LCONST_1);      return this; }
  public MethodAssembly FCONST_0()      { impl.visitInsn(FCONST_0);      return this; }
  public MethodAssembly FCONST_1()      { impl.visitInsn(FCONST_1);      return this; }
  public MethodAssembly FCONST_2()      { impl.visitInsn(FCONST_2);      return this; }
  public MethodAssembly DCONST_0()      { impl.visitInsn(DCONST_0);      return this; }
  public MethodAssembly DCONST_1()      { impl.visitInsn(DCONST_1);      return this; }
  public MethodAssembly IALOAD()        { impl.visitInsn(IALOAD);        return this; }
  public MethodAssembly LALOAD()        { impl.visitInsn(LALOAD);        return this; }
  public MethodAssembly FALOAD()        { impl.visitInsn(FALOAD);        return this; }
  public MethodAssembly DALOAD()        { impl.visitInsn(DALOAD);        return this; }
  public MethodAssembly AALOAD()        { impl.visitInsn(AALOAD);        return this; }
  public MethodAssembly BALOAD()        { impl.visitInsn(BALOAD);        return this; }
  public MethodAssembly CALOAD()        { impl.visitInsn(CALOAD);        return this; }
  public MethodAssembly SALOAD()        { impl.visitInsn(SALOAD);        return this; }
  public MethodAssembly IASTORE()       { impl.visitInsn(IASTORE);       return this; }
  public MethodAssembly LASTORE()       { impl.visitInsn(LASTORE);       return this; }
  public MethodAssembly FASTORE()       { impl.visitInsn(FASTORE);       return this; }
  public MethodAssembly DASTORE()       { impl.visitInsn(DASTORE);       return this; }
  public MethodAssembly AASTORE()       { impl.visitInsn(AASTORE);       return this; }
  public MethodAssembly BASTORE()       { impl.visitInsn(BASTORE);       return this; }
  public MethodAssembly CASTORE()       { impl.visitInsn(CASTORE);       return this; }
  public MethodAssembly SASTORE()       { impl.visitInsn(SASTORE);       return this; }
  public MethodAssembly POP()           { impl.visitInsn(POP);           return this; }
  public MethodAssembly POP2()          { impl.visitInsn(POP2);          return this; }
  public MethodAssembly DUP()           { impl.visitInsn(DUP);           return this; }
  public MethodAssembly DUP_X1()        { impl.visitInsn(DUP_X1);        return this; }
  public MethodAssembly DUP_X2()        { impl.visitInsn(DUP_X2);        return this; }
  public MethodAssembly DUP2()          { impl.visitInsn(DUP2);          return this; }
  public MethodAssembly DUP2_X1()       { impl.visitInsn(DUP2_X1);       return this; }
  public MethodAssembly DUP2_X2()       { impl.visitInsn(DUP2_X2);       return this; }
  public MethodAssembly SWAP()          { impl.visitInsn(SWAP);          return this; }
  public MethodAssembly IADD()          { impl.visitInsn(IADD);          return this; }
  public MethodAssembly LADD()          { impl.visitInsn(LADD);          return this; }
  public MethodAssembly FADD()          { impl.visitInsn(FADD);          return this; }
  public MethodAssembly DADD()          { impl.visitInsn(DADD);          return this; }
  public MethodAssembly ISUB()          { impl.visitInsn(ISUB);          return this; }
  public MethodAssembly FSUB()          { impl.visitInsn(FSUB);          return this; }
  public MethodAssembly DSUB()          { impl.visitInsn(DSUB);          return this; }
  public MethodAssembly IMUL()          { impl.visitInsn(IMUL);          return this; }
  public MethodAssembly LMUL()          { impl.visitInsn(LMUL);          return this; }
  public MethodAssembly FMUL()          { impl.visitInsn(FMUL);          return this; }
  public MethodAssembly DMUL()          { impl.visitInsn(DMUL);          return this; }
  public MethodAssembly IDIV()          { impl.visitInsn(IDIV);          return this; }
  public MethodAssembly LDIV()          { impl.visitInsn(LDIV);          return this; }
  public MethodAssembly FDIV()          { impl.visitInsn(FDIV);          return this; }
  public MethodAssembly DDIV()          { impl.visitInsn(DDIV);          return this; }
  public MethodAssembly IREM()          { impl.visitInsn(IREM);          return this; }
  public MethodAssembly LREM()          { impl.visitInsn(LREM);          return this; }
  public MethodAssembly FREM()          { impl.visitInsn(FREM);          return this; }
  public MethodAssembly DREM()          { impl.visitInsn(DREM);          return this; }
  public MethodAssembly INEG()          { impl.visitInsn(INEG);          return this; }
  public MethodAssembly LNEG()          { impl.visitInsn(LNEG);          return this; }
  public MethodAssembly FNEG()          { impl.visitInsn(FNEG);          return this; }
  public MethodAssembly DNEG()          { impl.visitInsn(DNEG);          return this; }
  public MethodAssembly ISHL()          { impl.visitInsn(ISHL);          return this; }
  public MethodAssembly LSHL()          { impl.visitInsn(LSHL);          return this; }
  public MethodAssembly ISHR()          { impl.visitInsn(ISHR);          return this; }
  public MethodAssembly LSHR()          { impl.visitInsn(LSHR);          return this; }
  public MethodAssembly IUSHR()         { impl.visitInsn(IUSHR);         return this; }
  public MethodAssembly LUSHR()         { impl.visitInsn(LUSHR);         return this; }
  public MethodAssembly IAND()          { impl.visitInsn(IAND);          return this; }
  public MethodAssembly LAND()          { impl.visitInsn(LAND);          return this; }
  public MethodAssembly IOR()           { impl.visitInsn(IOR);           return this; }
  public MethodAssembly LOR()           { impl.visitInsn(LOR);           return this; }
  public MethodAssembly IXOR()          { impl.visitInsn(IXOR);          return this; }
  public MethodAssembly LXOR()          { impl.visitInsn(LXOR);          return this; }
  public MethodAssembly I2L()           { impl.visitInsn(I2L);           return this; }
  public MethodAssembly I2F()           { impl.visitInsn(I2F);           return this; }
  public MethodAssembly I2D()           { impl.visitInsn(I2D);           return this; }
  public MethodAssembly L2I()           { impl.visitInsn(L2I);           return this; }
  public MethodAssembly L2F()           { impl.visitInsn(L2F);           return this; }
  public MethodAssembly L2D()           { impl.visitInsn(L2D);           return this; }
  public MethodAssembly F2I()           { impl.visitInsn(F2I);           return this; }
  public MethodAssembly F2L()           { impl.visitInsn(F2L);           return this; }
  public MethodAssembly F2D()           { impl.visitInsn(F2D);           return this; }
  public MethodAssembly D2I()           { impl.visitInsn(D2I);           return this; }
  public MethodAssembly D2L()           { impl.visitInsn(D2L);           return this; }
  public MethodAssembly D2F()           { impl.visitInsn(D2F);           return this; }
  public MethodAssembly I2B()           { impl.visitInsn(I2B);           return this; }
  public MethodAssembly I2C()           { impl.visitInsn(I2C);           return this; }
  public MethodAssembly I2S()           { impl.visitInsn(I2S);           return this; }
  public MethodAssembly LCMP()          { impl.visitInsn(LCMP);          return this; }
  public MethodAssembly FCMPL()         { impl.visitInsn(FCMPL);         return this; }
  public MethodAssembly FCMPG()         { impl.visitInsn(FCMPG);         return this; }
  public MethodAssembly DCMPL()         { impl.visitInsn(DCMPL);         return this; }
  public MethodAssembly DCMPG()         { impl.visitInsn(DCMPG);         return this; }
  public MethodAssembly IRETURN()       { impl.visitInsn(IRETURN);       return this; }
  public MethodAssembly LRETURN()       { impl.visitInsn(LRETURN);       return this; }
  public MethodAssembly FRETURN()       { impl.visitInsn(FRETURN);       return this; }
  public MethodAssembly DRETURN()       { impl.visitInsn(DRETURN);       return this; }
  public MethodAssembly ARETURN()       { impl.visitInsn(ARETURN);       return this; }
  public MethodAssembly RETURN()        { impl.visitInsn(RETURN);        return this; }
  public MethodAssembly ARRAYLENGTH()   { impl.visitInsn(ARRAYLENGTH);   return this; }
  public MethodAssembly ATHROW()        { impl.visitInsn(ATHROW);        return this; }
  public MethodAssembly MONITORENTER()  { impl.visitInsn(MONITORENTER);  return this; }
  public MethodAssembly MONITOREXIT()   { impl.visitInsn(MONITOREXIT);   return this; }

  // instructions with a single int operand ...

  public MethodAssembly BIPUSH(int i)   { impl.visitIntInsn(BIPUSH, i);  return this; }
  public MethodAssembly SIPUSH(int i)   { impl.visitIntInsn(SIPUSH, i);  return this; }
  public MethodAssembly NEWARRAY(int i) { impl.visitIntInsn(NEWARRAY, i);return this; }

  public MethodAssembly NEWARRAY(Class<?> c) {
    if (c == Integer.TYPE)
      return NEWARRAY(T_INT);
    if (c == Long.TYPE)
      return NEWARRAY(T_LONG);
    if (c == Byte.TYPE)
      return NEWARRAY(T_BYTE);
    if (c == Short.TYPE)
      return NEWARRAY(T_SHORT);
    if (c == Character.TYPE)
      return NEWARRAY(T_CHAR);
    if (c == Float.TYPE)
      return NEWARRAY(T_FLOAT);
    if (c == Double.TYPE)
      return NEWARRAY(T_DOUBLE);
    if (c == Boolean.TYPE)
      return NEWARRAY(T_BOOLEAN);
    return ANEWARRAY(c);
  }

  // local variable instructions ...

  public MethodAssembly ILOAD(int var)  { impl.visitVarInsn(ILOAD, var);  return this; }
  public MethodAssembly LLOAD(int var)  { impl.visitVarInsn(LLOAD, var);  return this; }
  public MethodAssembly FLOAD(int var)  { impl.visitVarInsn(FLOAD, var);  return this; }
  public MethodAssembly DLOAD(int var)  { impl.visitVarInsn(DLOAD, var);  return this; }
  public MethodAssembly ALOAD(int var)  { impl.visitVarInsn(ALOAD, var);  return this; }
  public MethodAssembly ISTORE(int var) { impl.visitVarInsn(ISTORE, var); return this; }
  public MethodAssembly LSTORE(int var) { impl.visitVarInsn(LSTORE, var); return this; }
  public MethodAssembly FSTORE(int var) { impl.visitVarInsn(FSTORE, var); return this; }
  public MethodAssembly DSTORE(int var) { impl.visitVarInsn(DSTORE, var); return this; }
  public MethodAssembly ASTORE(int var) { impl.visitVarInsn(ASTORE, var); return this; }
  public MethodAssembly RET(int var)    { impl.visitVarInsn(RET, var);    return this; }

  // instructions that takes a type descriptor as parameter.

  public MethodAssembly NEW(String desc) {
    impl.visitTypeInsn(NEW, AsmType.toInternalName(desc));
    return this;
  }

  public MethodAssembly NEW(Class<?> clazz) {
    impl.visitTypeInsn(NEW, AsmType.toInternalName(clazz));
    return this;
  }

  public MethodAssembly NEW_INSTANCE(Class<?> clazz) {
    String name = AsmType.toInternalName(clazz);
    impl.visitTypeInsn(NEW, name);
    impl.visitInsn(DUP);
    impl.visitMethodInsn(INVOKESPECIAL, name, "<init>", "()V");
    return this;
  }

  public MethodAssembly ANEWARRAY(String desc) {
    impl.visitTypeInsn(ANEWARRAY, AsmType.toInternalName(desc));
    return this;
  }

  public MethodAssembly ANEWARRAY(Class<?> clazz) {
    impl.visitTypeInsn(ANEWARRAY, AsmType.toInternalName(clazz));
    return this;
  }

  public MethodAssembly CHECKCAST(String desc) {
    impl.visitTypeInsn(CHECKCAST, AsmType.toInternalName(desc));
    return this;
  }

  public MethodAssembly CHECKCAST(Class<?> clazz) {
    impl.visitTypeInsn(CHECKCAST, AsmType.toInternalName(clazz));
    return this;
  }

  public MethodAssembly INSTANCEOF(String desc) {
    impl.visitTypeInsn(INSTANCEOF, AsmType.toInternalName(desc));
    return this;
  }

  public MethodAssembly INSTANCEOF(Class<?> clazz) {
    impl.visitTypeInsn(INSTANCEOF, AsmType.toInternalName(clazz));
    return this;
  }

  // instructions that loads or stores the value of a field of an object.

  public MethodAssembly GETFIELD(String owner, String name, String desc) {
      impl.visitFieldInsn(GETFIELD, AsmType.toInternalName(owner), name, desc);
      return this;
  }

  public MethodAssembly GETFIELD(String owner, String name, Class<?> type) {
    impl.visitFieldInsn(GETFIELD,
                        AsmType.toInternalName(owner),
                        name,
                        AsmType.getDescriptor(type));
    return this;
  }

  public MethodAssembly GETFIELD(Class<?> owner, String name, Class<?> type) {
    impl.visitFieldInsn(GETFIELD,
                        AsmType.toInternalName(owner),
                        name,
                        AsmType.getDescriptor(type));
    return this;
  }

  public MethodAssembly PUTFIELD(String owner, String name, String desc) {
    impl.visitFieldInsn(PUTFIELD, AsmType.toInternalName(owner), name, desc);
    return this;
  }

  public MethodAssembly PUTFIELD(String owner, String name, Class<?> type) {
    impl.visitFieldInsn(PUTFIELD,
                        AsmType.toInternalName(owner),
                        name,
                        AsmType.getDescriptor(type));
    return this;
  }

  public MethodAssembly PUTFIELD(Class<?> owner, String name, Class<?> type) {
    impl.visitFieldInsn(PUTFIELD,
                        AsmType.toInternalName(owner),
                        name,
                        AsmType.getDescriptor(type));
    return this;
  }

  public MethodAssembly GETSTATIC(String owner, String name, String desc) {
    impl.visitFieldInsn(GETSTATIC, AsmType.toInternalName(owner), name, desc);
    return this;
  }

  public MethodAssembly GETSTATIC(String owner, String name, Class<?> type) {
    impl.visitFieldInsn(GETSTATIC,
                        AsmType.toInternalName(owner),
                        name,
                        AsmType.getDescriptor(type));
    return this;
  }

  public MethodAssembly GETSTATIC(Class<?> owner, String name, Class<?> type) {
    impl.visitFieldInsn(GETSTATIC,
                        AsmType.toInternalName(owner),
                        name,
                        AsmType.getDescriptor(type));
    return this;
  }

  public MethodAssembly PUTSTATIC(String owner, String name, String desc) {
    impl.visitFieldInsn(PUTSTATIC, AsmType.toInternalName(owner), name, desc);
    return this;
  }

  public MethodAssembly PUTSTATIC(String owner, String name, Class<?> type) {
    impl.visitFieldInsn(PUTSTATIC,
                        AsmType.toInternalName(owner),
                        name,
                        AsmType.getDescriptor(type));
    return this;
  }

  public MethodAssembly PUTSTATIC(Class<?> owner, String name, Class<?> type) {
    impl.visitFieldInsn(PUTSTATIC,
                        AsmType.toInternalName(owner),
                        name,
                        AsmType.getDescriptor(type));
    return this;
  }

  // instructions that invoke a method ...

  public MethodAssembly invoke(int opcode, String owner, String name, String desc) {
    impl.visitMethodInsn(opcode, AsmType.toInternalName(owner), name, desc);
    return this;
  }

  public MethodAssembly invoke(int opcode, String owner, String name,
                               Class<?> returnType, Class<?>... argumentTypes)
  {
    impl.visitMethodInsn(opcode,
                         AsmType.toInternalName(owner),
                         name,
                         AsmType.getMethodDescriptor(returnType, argumentTypes));
    return this;
  }

  public MethodAssembly invoke(int opcode, Class<?> owner, String name,
                               Class<?> returnType, Class<?>... argumentTypes)
  {
    impl.visitMethodInsn(opcode,
                         AsmType.toInternalName(owner),
                         name,
                         AsmType.getMethodDescriptor(returnType, argumentTypes));
    return this;
  }

  public MethodAssembly INVOKEVIRTUAL(String owner, String name, String desc) {
    return invoke(INVOKEVIRTUAL, owner, name, desc);
  }

  public MethodAssembly INVOKEVIRTUAL(String owner, String name,
                                      Class<?> returnType,
                                      Class<?>... argumentTypes) {
    return invoke(INVOKEVIRTUAL, owner, name, returnType, argumentTypes);
  }

  public MethodAssembly INVOKEVIRTUAL(Class<?> owner, String name,
                                      Class<?> returnType,
                                      Class<?>... argumentTypes) {
    return invoke(INVOKEVIRTUAL, owner, name, returnType, argumentTypes);
  }

  public MethodAssembly INVOKESPECIAL(String owner, String name, String desc) {
    return invoke(INVOKESPECIAL, owner, name, desc);
  }

  public MethodAssembly INVOKESPECIAL(String owner, String name,
                                      Class<?> returnType,
                                      Class<?>... argumentTypes) {
    return invoke(INVOKESPECIAL, owner, name, returnType, argumentTypes);
  }

  public MethodAssembly INVOKESPECIAL(Class<?> owner, String name,
                                      Class<?> returnType,
                                      Class<?>... argumentTypes) {
    return invoke(INVOKESPECIAL, owner, name, returnType, argumentTypes);
  }

  public MethodAssembly INVOKESTATIC(String owner, String name, String desc) {
    return invoke(INVOKESTATIC, owner, name, desc);
  }

  public MethodAssembly INVOKESTATIC(String owner, String name,
                                     Class<?> returnType,
                                     Class<?>... argumentTypes) {
    return invoke(INVOKESTATIC, owner, name, returnType, argumentTypes);
  }

  public MethodAssembly INVOKESTATIC(Class<?> owner, String name,
                                     Class<?> returnType,
                                     Class<?>... argumentTypes) {
    return invoke(INVOKESTATIC, owner, name, returnType, argumentTypes);
  }

  public MethodAssembly INVOKEINTERFACE(String owner, String name, String desc) {
    return invoke(INVOKEINTERFACE, owner, name, desc);
  }

  public MethodAssembly INVOKEINTERFACE(String owner, String name,
                                        Class<?> returnType,
                                        Class<?>... argumentTypes) {
    return invoke(INVOKEINTERFACE, owner, name, returnType, argumentTypes);
  }

  public MethodAssembly INVOKEINTERFACE(Class<?> owner, String name,
                                        Class<?> returnType,
                                        Class<?>... argumentTypes) {
    return invoke(INVOKEINTERFACE, owner, name, returnType, argumentTypes);
  }

  public MethodAssembly INVOKE(Class<?> owner, Method method) {
    int opcode;
    if (owner.isInterface()) {
      opcode = INVOKEINTERFACE;
    } else if (Modifier.isStatic(method.getModifiers())) {
      opcode = INVOKESTATIC;
    } else {
      opcode = INVOKEVIRTUAL;
    }

    impl.visitMethodInsn(opcode,
                         AsmType.toInternalName(owner),
                         method.getName(),
                         AsmType.getMethodDescriptor(method));
    return this;
  }

  public MethodAssembly INVOKE(Method method) {
      return INVOKE(method.getDeclaringClass(), method);
  }

  public MethodAssembly INVOKE(Constructor<?> cons) {
    Class<?> owner = cons.getDeclaringClass();
    impl.visitMethodInsn(INVOKESPECIAL,
                         AsmType.toInternalName(owner),
                         "<init>",
                         AsmType.getMethodDescriptor(Void.TYPE, cons.getParameterTypes()));
    return this;
  }

  /**
   * Emit an invokedynamic instruction.
   *
   * @param bootstrapMethod the bootstrap method handle
   * @param name            the method name (used as key for dynamic dispatch)
   * @param descriptor      the call site method descriptor
   * @param bootstrapArgs   static arguments for the bootstrap method
   */
  public MethodAssembly INVOKEDYNAMIC(Handle bootstrapMethod, String name,
                                       String descriptor, Object... bootstrapArgs) {
    impl.visitInvokeDynamicInsn(name, descriptor, bootstrapMethod, bootstrapArgs);
    return this;
  }

  // instructions that may jump to another instruction ...

  public MethodAssembly IFEQ(Label label)       { impl.visitJumpInsn(IFEQ, label);      return this; }
  public MethodAssembly IFNE(Label label)       { impl.visitJumpInsn(IFNE, label);      return this; }
  public MethodAssembly IFLT(Label label)       { impl.visitJumpInsn(IFLT, label);      return this; }
  public MethodAssembly IFGE(Label label)       { impl.visitJumpInsn(IFGE, label);      return this; }
  public MethodAssembly IFGT(Label label)       { impl.visitJumpInsn(IFGT, label);      return this; }
  public MethodAssembly IFLE(Label label)       { impl.visitJumpInsn(IFLE, label);      return this; }
  public MethodAssembly IF_ICMPEQ(Label label)  { impl.visitJumpInsn(IF_ICMPEQ, label); return this; }
  public MethodAssembly IF_ICMPNE(Label label)  { impl.visitJumpInsn(IF_ICMPNE, label); return this; }
  public MethodAssembly IF_ICMPLT(Label label)  { impl.visitJumpInsn(IF_ICMPLT, label); return this; }
  public MethodAssembly IF_ICMPGT(Label label)  { impl.visitJumpInsn(IF_ICMPGT, label); return this; }
  public MethodAssembly IF_ICMPLE(Label label)  { impl.visitJumpInsn(IF_ICMPLE, label); return this; }
  public MethodAssembly IF_ACMPEQ(Label label)  { impl.visitJumpInsn(IF_ACMPEQ, label); return this; }
  public MethodAssembly IF_ACMPNE(Label label)  { impl.visitJumpInsn(IF_ACMPNE, label); return this; }
  public MethodAssembly IFNULL(Label label)     { impl.visitJumpInsn(IFNULL, label);    return this; }
  public MethodAssembly IFNONNULL(Label label)  { impl.visitJumpInsn(IFNONNULL, label); return this; }
  public MethodAssembly GOTO(Label label)       { impl.visitJumpInsn(GOTO, label);      return this; }
  public MethodAssembly JSR(Label label)        { impl.visitJumpInsn(JSR, label);       return this; }

  // A label designate the instruction that will be visited just after it

  public MethodAssembly label(Label label) {
    impl.visitLabel(label);
    return this;
  }

  public Label label() {
    Label label = new Label();
    impl.visitLabel(label);
    return label;
  }

  // special instructions

  public MethodAssembly LDC(Object cst) {
    impl.visitLdcInsn(cst);
    return this;
  }

  public MethodAssembly PUSH_CONST(Object value) {
    if (value instanceof Boolean) {
      PUSH((Boolean)value ? 1 : 0);
    } else if (value instanceof Byte || value instanceof Short ||
               value instanceof Integer) {
      PUSH(((Number)value).intValue());
    } else if (value instanceof Character) {
      PUSH((Character)value);
    } else if (value instanceof Long lvalue) {
      if (lvalue == 0L)
        LCONST_0();
      else if (lvalue == 1L)
        LCONST_1();
      else
        LDC(value);
    } else if (value instanceof Float fvalue) {
      if (fvalue == 0.0f)
        FCONST_0();
      else if (fvalue == 1.0f)
        FCONST_1();
      else if (fvalue == 2.0f)
        FCONST_2();
      else
        LDC(value);
    } else if (value instanceof Double dvalue) {
      if (dvalue == 0.0d)
        DCONST_0();
      else if (dvalue == 1.0d)
        DCONST_1();
      else
        LDC(value);
    } else {
      throw new UnsupportedOperationException();
    }
    return this;
  }

  public MethodAssembly IINC(int var, int increment) {
    impl.visitIincInsn(var, increment);
    return this;
  }

  public MethodAssembly TABLESWITCH(int min, int max, Label[] targets,
                                    Label dflt) {
    impl.visitTableSwitchInsn(min, max, dflt, targets);
    return this;
  }

  public MethodAssembly LOOKUPSWITCH(int[] keys, Label[] targets, Label dflt) {
    impl.visitLookupSwitchInsn(dflt, keys, targets);
    return this;
  }

  public MethodAssembly MULTIANEWARRAY(String desc, int dims) {
    impl.visitMultiANewArrayInsn(AsmType.toInternalName(desc), dims);
    return this;
  }

  public MethodAssembly MULTIANEWARRAY(Class<?> type, int dims) {
    impl.visitMultiANewArrayInsn(AsmType.getDescriptor(type), dims);
    return this;
  }

  public MethodAssembly TryCatchBlock(Label start, Label end, Label handler,
                                      String type) {
    impl.visitTryCatchBlock(start, end, handler, AsmType.toInternalName(type));
    return this;
  }

  public MethodAssembly TryCatchBlock(Label start, Label end, Label handler,
                                      Class<?> type) {
    impl.visitTryCatchBlock(start, end, handler, AsmType.toInternalName(type));
    return this;
  }

  // Helpers ...

  public MethodAssembly THIS() {
    impl.visitVarInsn(ALOAD, 0);
    return this;
  }

  public MethodAssembly TRUE() {
    impl.visitInsn(ICONST_1);
    return this;
  }

  public MethodAssembly FALSE() {
    impl.visitInsn(ICONST_0);
    return this;
  }

  public MethodAssembly XCONST_0(Class<?> type) {
    if (type.isPrimitive()) {
      if (type == Long.TYPE) {
        impl.visitInsn(LCONST_0);
      } else if (type == Float.TYPE) {
        impl.visitInsn(FCONST_0);
      } else if (type == Double.TYPE) {
        impl.visitInsn(DCONST_0);
      } else {
        impl.visitInsn(ICONST_0);
      }
    } else {
      impl.visitInsn(ACONST_NULL);
    }
    return this;
  }

  public MethodAssembly PUSH(int value) {
    if ((value >= -1) && (value <= 5)) // Use ICONST_n
      impl.visitInsn(ICONST_0 + value);
    else if (value >= -128 && value <= 127) // Use BIPUSH
      impl.visitIntInsn(BIPUSH, value);
    else if (value >= -32768 && value <= 32767) // Use SIPUSH
      impl.visitIntInsn(SIPUSH, value);
    else // If everything fails use LDC
      impl.visitLdcInsn(value);
    return this;
  }

  public MethodAssembly XLOAD(int var, Class<?> type) {
    int opcode = AsmType.getType(type).getOpcode(ILOAD);
    impl.visitVarInsn(opcode, var);
    return this;
  }

  public MethodAssembly XSTORE(int var, Class<?> type) {
    int opcode = AsmType.getType(type).getOpcode(ISTORE);
    impl.visitVarInsn(opcode, var);
    return this;
  }

  public MethodAssembly XALOAD(Class<?> type) {
    int opcode = AsmType.getType(type).getOpcode(IALOAD);
    impl.visitInsn(opcode);
    return this;
  }

  public MethodAssembly XASTORE(Class<?> type) {
    int opcode = AsmType.getType(type).getOpcode(IASTORE);
    impl.visitInsn(opcode);
    return this;
  }

  public MethodAssembly XRETURN(Class<?> type) {
    int opcode = AsmType.getType(type).getOpcode(IRETURN);
    impl.visitInsn(opcode);
    return this;
  }

  /**
   * Generates the instructions to box the top stack value. This value
   * is replaced by its boxed equivalent on top of the stack.
   *
   * @param type the type of the top stack value.
   */
  public MethodAssembly BOX(Class<?> type) {
    if (type.isPrimitive()) {
      String t, sig;
      int size = 1;
      if (type == Boolean.TYPE) {
        t = "java/lang/Boolean";
        sig = "(Z)Ljava/lang/Boolean;";
      } else if (type == Byte.TYPE) {
        t = "java/lang/Byte";
        sig = "(B)Ljava/lang/Byte;";
      } else if (type == Character.TYPE) {
        t = "java/lang/Character";
        sig = "(C)Ljava/lang/Character;";
      } else if (type == Short.TYPE) {
        t = "java/lang/Short";
        sig = "(S)Ljava/lang/Short;";
      } else if (type == Integer.TYPE) {
        t = "java/lang/Integer";
        sig = "(I)Ljava/lang/Integer;";
      } else if (type == Long.TYPE) {
        t = "java/lang/Long";
        sig = "(J)Ljava/lang/Long;";
      } else if (type == Float.TYPE) {
        t = "java/lang/Float";
        sig = "(F)Ljava/lang/Float;";
      } else if (type == Double.TYPE) {
        t = "java/lang/Double";
        sig = "(D)Ljava/lang/Double;";
      } else {
        throw new IllegalArgumentException(type.getName());
      }
      impl.visitMethodInsn(INVOKESTATIC, t, "valueOf", sig, false);
    }
    return this;
  }

  public MethodAssembly BOX(Object value) {
    Class<?> type = value.getClass();
    if (type == Boolean.class) {
      PUSH((Boolean)value ? 1 : 0);
      impl.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                           "(Z)Ljava/lang/Boolean;", false);
    } else if (type == Byte.class) {
      PUSH((Byte)value);
      impl.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf",
                           "(B)Ljava/lang/Byte;", false);
    } else if (type == Character.class) {
      PUSH((Character)value);
      impl.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf",
                           "(C)Ljava/lang/Character;", false);
    } else if (type == Short.class) {
      PUSH((Short)value);
      impl.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf",
                           "(S)Ljava/lang/Short;", false);
    } else if (type == Integer.class) {
      PUSH((Integer)value);
      impl.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf",
                           "(I)Ljava/lang/Integer;", false);
    } else if (type == Long.class) {
      LDC(value);
      impl.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf",
                           "(J)Ljava/lang/Long;", false);
    } else if (type == Float.class) {
      LDC(value);
      impl.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf",
                           "(F)Ljava/lang/Float;", false);
    } else if (type == Double.class) {
      LDC(value);
      impl.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf",
                           "(D)Ljava/lang/Double;", false);
    }
    return this;
  }

  /**
   * Generates the instructions to unbox the top stack value. This value is
   * replaced by its unboxed equivalent on top of the stack.
   *
   * @param type the type of the top stack value.
   */
  private MethodAssembly UNBOX(Class<?> type, boolean check) {
    if (type.isPrimitive()) {
      String t, m, s;
      if (type == Boolean.TYPE) {
        t = "java/lang/Boolean";
        m = "booleanValue";
        s = "()Z";
      } else if (type == Byte.TYPE) {
        t = "java/lang/Byte";
        m = "byteValue";
        s = "()B";
      } else if (type == Character.TYPE) {
        t = "java/lang/Character";
        m = "charValue";
        s = "()C";
      } else if (type == Short.TYPE) {
        t = "java/lang/Short";
        m = "shortValue";
        s = "()S";
      } else if (type == Integer.TYPE) {
        t = "java/lang/Integer";
        m = "intValue";
        s = "()I";
      } else if (type == Long.TYPE) {
        t = "java/lang/Long";
        m = "longValue";
        s = "()J";
      } else if (type == Float.TYPE) {
        t = "java/lang/Float";
        m = "floatValue";
        s = "()F";
      } else if (type == Double.TYPE) {
        t = "java/lang/Double";
        m = "doubleValue";
        s = "()D";
      } else {
        throw new IllegalArgumentException(type.getName());
      }
      if (check)
        impl.visitTypeInsn(CHECKCAST, t);
      impl.visitMethodInsn(INVOKEVIRTUAL, t, m, s);
    } else if (type != Object.class) {
      impl.visitTypeInsn(CHECKCAST, AsmType.toInternalName(type));
    }
    return this;
  }

  public MethodAssembly UNBOX(Class<?> type) {
    return UNBOX(type, true);
  }

  public MethodAssembly UNBOX_UNCHECKED(Class<?> type) {
    return UNBOX(type, false);
  }

  public MethodAssembly THROW_NEW(Class<?> type) {
    String typename = AsmType.toInternalName(type);
    impl.visitTypeInsn(NEW, typename);
    impl.visitInsn(DUP);
    impl.visitMethodInsn(INVOKESPECIAL, typename, "<init>", "()V");
    impl.visitInsn(ATHROW);
    return this;
  }

  public MethodAssembly THROW_NEW(Class<?> type, String message) {
    String typename = AsmType.toInternalName(type);
    impl.visitTypeInsn(NEW, typename);
    impl.visitInsn(DUP);
    impl.visitLdcInsn(message);
    impl.visitMethodInsn(INVOKESPECIAL, typename, "<init>", "(Ljava/lang/String;)V");
    impl.visitInsn(ATHROW);
    return this;
  }

  public MethodAssembly SWITCH(int[] keys, Label[] targets, Label dflt, int max_gap) {
    sort(keys, targets, 0, keys.length - 1);

    if (isOrdered(keys, max_gap)) {
      fillup(keys, targets, dflt, max_gap);
    } else {
      impl.visitLookupSwitchInsn(dflt, keys, targets);
    }

    return this;
  }

  private void fillup(int[] keys, Label[] targets, Label dflt, int max_gap) {
    int     max_size = keys.length + keys.length * max_gap;
    Label[] t_vec    = new Label[max_size];
    int     count    = 1;

    t_vec[0] = targets[0];

    for (int i = 1; i < keys.length; i++) {
      int prev = keys[i-1];
      int gap  = keys[i] - prev;

      for (int j = 1; j < gap; j++) {
        t_vec[count] = dflt;
        count++;
      }

      t_vec[count] = targets[i];
      count++;
    }

    if (t_vec.length != count) {
      Label[] tmp = new Label[count];
      System.arraycopy(t_vec, 0, tmp, 0, count);
      t_vec = tmp;
    }

    int min = keys[0];
    int max = keys[keys.length - 1];
    impl.visitTableSwitchInsn(min, max, dflt, t_vec);
  }

  // Sort keys and targets array with QuickSort.
  private void sort(int[] keys, Label[] targets, int low, int high) {
    int i = low, j = high;
    int m = keys[(low + high) / 2];

    do {
      while (keys[i] < m) i++;
      while (m < keys[j]) j--;
      if (i <= j) { // swap elements
        int k2 = keys[i]; keys[i] = keys[j]; keys[j] = k2;
        Label l2 = targets[i]; targets[i] = targets[j]; targets[j] = l2;
        i++; j--;
      }
    } while (i <= j);

    if (low < j) sort(keys, targets, low, j);
    if (i < high) sort(keys, targets, i, high);
  }

  // keys is sorted in ascending order with no gap bigger than max_gap?
  private boolean isOrdered(int[] keys, int max_gap) {
    for (int i = 1; i < keys.length; i++) {
      if (keys[i] - keys[i-1] > max_gap)
        return false;
    }
    return true;
  }

  public MethodAssembly CLASS(Class<?> c) {
    if (c.isPrimitive()) {
      makePrimitiveClassLiteralRef(c);
    } else {
      makeClassLiteralCacheRef(c.getName());
    }
    return this;
  }

  public MethodAssembly CLASS(String name) {
    makeClassLiteralCacheRef(name);
    return this;
  }

  private static final String prefixClass = "class$";
  private static final String prefixArray = "array$";
  private static final String lookupSignature = "(Ljava/lang/String;)Ljava/lang/Class;";

  private void makePrimitiveClassLiteralRef(Class<?> c) {
    String wrapper;

    if (c == Void.TYPE) {
      wrapper = "java/lang/Void";
    } else if (c == Boolean.TYPE) {
      wrapper = "java/lang/Boolean";
    } else if (c == Byte.TYPE) {
      wrapper = "java/lang/Byte";
    } else if (c == Character.TYPE) {
      wrapper = "java/lang/Character";
    } else if (c == Short.TYPE) {
      wrapper = "java/lang/Short";
    } else if (c == Integer.TYPE) {
      wrapper = "java/lang/Integer";
    } else if (c == Long.TYPE) {
      wrapper = "java/lang/Long";
    } else if (c == Float.TYPE) {
      wrapper = "java/lang/Float";
    } else if (c == Double.TYPE) {
      wrapper = "java/lang/Double";
    } else {
      throw new InternalError();
    }

    this.GETSTATIC(wrapper, "TYPE", "Ljava/lang/Class;");
  }

  private void makeClassLiteralCacheRef(String className) {
    String owner = cw.getClassName();
    String lname = getClassLiteralCacheName(className);

    cw.addClassLiteralLookupMethod(prefixClass, lookupSignature);
    cw.addClassLiteralField(lname);

    Label b1 = new Label();
    Label b2 = new Label();

    GETSTATIC(owner, lname, "Ljava/lang/Class;");
    IFNULL(b1);
    GETSTATIC(owner, lname, "Ljava/lang/Class;");
    GOTO(b2);
    label(b1);
    LDC(className);
    INVOKESTATIC(owner, prefixClass, lookupSignature);
    DUP();
    PUTSTATIC(owner, lname, "Ljava/lang/Class;");
    label(b2);
  }

  private String getClassLiteralCacheName(String className) {
    // Given a class name, look for a static field to cache it.
    //     className        lname
    //     pkg.Foo          class$pkg$Foo
    //     [Lpkg.Foo;       array$Lpkg$Foo
    //     [[Lpkg.Foo;      array$$Lpkg$Foo
    //     [I               array$I
    //     [[I              array$$I
    String lname;
    if (!className.startsWith("[")) {
      lname = prefixClass + className.replace('.', '$');
    } else {
      lname = prefixArray + className.substring(1);
      lname = lname.replace('[', '$'); // [[[I => array$$$I
      if (className.endsWith(";")) {
        // [Lpkg.Foo; => array$Lpkg$Foo
        lname = lname.substring(0, lname.length() - 1);
        lname = lname.replace('.', '$');
      }
      // else [I => array$I or some such; lname is already OK
    }

    return lname;
  }
}
