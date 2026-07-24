/**
 * Cloudway Platform
 * Copyright (c) 2012-2013 Cloudway Technology, Inc.
 * All rights reserved.
 */
package org.elite.util.asm;

import java.lang.reflect.Method;
import java.util.HashSet;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Label;
import static org.objectweb.asm.Opcodes.*;

@SuppressWarnings("unused")
public class ClassAssembly
{
    private final String className;
    private final String superName;
    private final ClassWriter impl;

    public ClassAssembly(int access, String name, String superName, String[] interfaces) {
        this.className = name;
        this.superName = superName;

        name       = AsmType.toInternalName(name);
        superName  = AsmType.toInternalName(superName);
        interfaces = AsmType.toInternalName(interfaces);

        impl = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        impl.visit(V17, access, name, null, superName, interfaces);
    }

    public ClassAssembly(int access, String name, Class<?> superClass,
                         Class<?>[] interfaces) {
        this.className = name;
        this.superName = superClass.getName();

        String   thisName       = AsmType.toInternalName(name);
        String   superName      = AsmType.toInternalName(superClass);
        String[] interfaceNames = AsmType.toInternalName(interfaces);

        impl = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        impl.visit(V17, access, thisName, null, superName, interfaceNames);
    }

    public String getClassName() {
        return className;
    }

    public String getSuperClassName() {
        return superName;
    }

    public ClassWriter getImpl() {
        return impl;
    }

    public AnnotationAssembly ANNOTATION(String descriptor, boolean visible) {
        return new AnnotationAssembly(
          null, impl.visitAnnotation(descriptor, visible));
    }

    public AnnotationAssembly ANNOTATION(Class<?> type, boolean visible) {
        return new AnnotationAssembly(
          null, impl.visitAnnotation(AsmType.toInternalName(type.getName()),
                                     visible));
    }

    public void addField(int access, String name, String desc) {
        FieldVisitor fw = impl.visitField(access, name, desc, null, null);
        fw.visitEnd();
    }

    public void addField(int access, String name, String desc, Object value) {
        FieldVisitor fw = impl.visitField(access, name, desc, null, value);
        fw.visitEnd();
    }

    public void addField(int access, String name, Class<?> type) {
        String desc = AsmType.getDescriptor(type);
        FieldVisitor fw = impl.visitField(access, name, desc, null, null);
        fw.visitEnd();
    }

    public void addField(int access, String name, Class<?> type, Object value) {
        String desc = AsmType.getDescriptor(type);
        FieldVisitor fw = impl.visitField(access, name, desc, null, value);
        fw.visitEnd();
    }

    public MethodAssembly newMethod(int access, String name, String desc,
                                    String[] exceptions) {
        exceptions = AsmType.toInternalName(exceptions);

        MethodVisitor mv = impl.visitMethod(access, name, desc, null, exceptions);
        return new MethodAssembly(this, mv);
    }

    public MethodAssembly
    newMethod(int access, String name, Class<?> returnType,
              Class<?>[] argumentTypes, Class<?>[] exceptionTypes)
    {
        String desc = AsmType.getMethodDescritpor(returnType, argumentTypes);
        String[] exceptions = AsmType.toInternalName(exceptionTypes);

        MethodVisitor mv = impl.visitMethod(access, name, desc, null, exceptions);
        return new MethodAssembly(this, mv);
    }

    public MethodAssembly
    newMethod(int access, String name, Class<?> returnType,
              Class<?>... argumentTypes) {
        return newMethod(access, name, returnType, argumentTypes, null);
    }

    public MethodAssembly newMethod(int access, Method method) {
        return newMethod(access, method.getName(), method.getReturnType(),
                         method.getParameterTypes(), method.getExceptionTypes());
    }

    public byte[] end() {
        impl.visitEnd();
        return impl.toByteArray();
    }

    // Class literal support

    private final HashSet<String> classLiteralFields = new HashSet<>();
    private boolean classLiteralLookupMethod = false;

    void addClassLiteralField(String name) {
        if (!classLiteralFields.contains(name)) {
            FieldVisitor fw = impl.visitField(ACC_STATIC, name,
                                              "Ljava/lang/Class;", null, null);
            fw.visitEnd();
            classLiteralFields.add(name);
        }
    }

    void addClassLiteralLookupMethod(String name, String desc) {
        if (classLiteralLookupMethod) {
            return;
        }

        /*  // The helper function looks like this.
         *  // It simply maps a checked exception to an unchecked one.
         *  static Class class$(String class$) {
         *    try { return Class.forName(class$); }
         *    catch (ClassNotFoundException forName) {
         *      throw new NoClassDefFoundError(forName.getMessage());
         *    }
         *  }
         */
        MethodAssembly mw = newMethod(ACC_STATIC, name, desc, null);

        // local variables
        int name_var = 0;
        int ex_var   = 1;

        // labels
        Label try_start = new Label();
        Label try_end   = new Label();
        Label handler   = new Label();

        mw.label(try_start);
        mw.ALOAD(name_var);
        mw.INVOKESTATIC("java/lang/Class", "forName",
                        "(Ljava/lang/String;)Ljava/lang/Class;");
        mw.label(try_end);
        mw.ARETURN();

        mw.label(handler);
        mw.ASTORE(ex_var);
        mw.NEW("java/lang/NoClassDefFoundError");
        mw.DUP();
        mw.ALOAD(ex_var);
        mw.INVOKEVIRTUAL("java/lang/Throwable", "getMessage",
                         "()Ljava/lang/String;");
        mw.INVOKESPECIAL("java/lang/NoClassDefFoundError", "<init>",
                         "(Ljava/lang/String;)V");
        mw.ATHROW();
        mw.TryCatchBlock(try_start, try_end, handler,
                         "java/lang/ClassNotFoundException");

        mw.end();
        classLiteralLookupMethod = true;
    }
}
