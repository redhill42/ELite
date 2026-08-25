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

import org.elite.eval.EvaluationContext;
import org.elite.util.asm.AsmType;
import org.elite.util.asm.ClassAssembly;
import org.elite.util.asm.MethodAssembly;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import javax.el.ELContext;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.invoke.MethodHandles.Lookup;
import static java.lang.invoke.MethodHandles.Lookup.ClassOption.*;
import static org.elite.eval.ELUtils.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Bootstrap method that generates Closure classes at runtime, in the
 * style of LambdaMetafactory: the lambda body is already compiled as a
 * method; the call site links to a generated subclass of
 * {@link IRCompiledClosure} whose execute() invokes that method.
 */
public final class ClosureBootstrap {
  private ClosureBootstrap() {}

  private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

  @SuppressWarnings("unused")
  public static CallSite closureBootstrap(Lookup caller, String name,
                                          MethodType callSiteType,
                                          MethodHandle impl) {
    try {
      Class<?> closureClass = generateClosureClass(caller, impl);
      MethodHandle factory = caller.findConstructor(
        closureClass, callSiteType.changeReturnType(Void.TYPE));
      return new ConstantCallSite(factory.asType(callSiteType));
    } catch (IllegalAccessException | NoSuchMethodException e) {
      throw new BootstrapMethodError(e);
    }
  }

  private static Class<?> generateClosureClass(Lookup caller, MethodHandle impl)
    throws IllegalAccessException
  {
    Method implMethod = MethodHandles.reflectAs(Method.class, impl);
    Class<?> ownerClass = implMethod.getDeclaringClass();
    String implMethodName = implMethod.getName();
    MetaMethod meta = implMethod.getAnnotation(MetaMethod.class);
    assert meta != null;
    String name = meta.name();
    boolean isStatic = Modifier.isStatic(implMethod.getModifiers());

    Handle implHandle = new Handle(
      isStatic ? H_INVOKESTATIC : H_INVOKEVIRTUAL,
      AsmType.toInternalName(ownerClass), implMethodName,
      AsmType.getMethodDescriptor(implMethod), false);

    String className = ownerClass.getName().replace('.', '/') +
                       "$Closure$" + (isJavaIdentifier(name) ? name : "") +
                       "$" + CLASS_COUNTER.getAndIncrement();

    ClassAssembly cc = new ClassAssembly(ACC_PRIVATE | ACC_SUPER, className,
                                         IRCompiledClosure.class, null);

    if (!isStatic)
      cc.addField(ACC_PRIVATE, "$this", ownerClass);

    // Unified constructor (EvaluationContext, Object).
    MethodAssembly mc = cc.newMethod(ACC_PUBLIC, "<init>", void.class,
                                     EvaluationContext.class, Object.class);
    mc.THIS()
      .ALOAD(1)
      .INVOKESPECIAL(IRCompiledClosure.class, "<init>", void.class,
                     EvaluationContext.class);
    if (!isStatic) {
      mc.THIS()
        .ALOAD(2)
        .CHECKCAST(ownerClass)
        .PUTFIELD(className, "$this", ownerClass);
    }
    mc.RETURN()
      .end();

    // Arity, returns the number of parameters.
    cc.newMethod(ACC_PUBLIC, "arity", int.class, ELContext.class)
      .PUSH(meta.arity())
      .IRETURN()
      .end();

    // The main execution entry point. Delegate to actual closure method.
    mc = cc.newMethod(ACC_PROTECTED, "execute", Object.class,
                      EvaluationContext.class, Object[].class);
    if (!isStatic)
      mc.THIS().GETFIELD(className, "$this", ownerClass);
    mc.ALOAD(1)
      .INVOKEVIRTUAL(EvaluationContext.class, "pushContext",
                     EvaluationContext.class);

    // Check argument count.
    Label next = new Label(), done = new Label();
    mc.ALOAD(2)
      .ARRAYLENGTH()
      .PUSH(meta.arity())
      .IF_ICMPNE(next)
      .ALOAD(2)
      .GOTO(done);

    mc.label(next);
    if (meta.arity() == 1 && meta.keys()[0].equals("$")) {
      // A block is a lambda with a single parameter "$".
      mc.ICONST_1()
        .ANEWARRAY(Object.class)
        .DUP()
        .ICONST_0()
        .ALOAD(2)
        .AASTORE();
    } else {
      // Fill in default values. See IRCompiledClosure.getArgs for details.
      mc.ALOAD(1)
        .INVOKEVIRTUAL(EvaluationContext.class, "getELContext", ELContext.class)
        .LDC(implHandle)
        .ALOAD(2)
        .INVOKESTATIC(IRCompiledClosure.class, "getArgs", Object[].class,
                      ELContext.class, MethodHandle.class, Object[].class);
    }
    mc.label(done);

    // Closure implementation method is an instance method if it's declared
    // in an instance procedure.  Otherwise, it's a static method.
    if (isStatic) {
      mc.INVOKESTATIC(ownerClass, implMethodName, Object.class,
                      EvaluationContext.class, Object[].class);
    } else {
      mc.INVOKEVIRTUAL(ownerClass, implMethodName, Object.class,
                       EvaluationContext.class, Object[].class);
    }
    mc.ARETURN().end();

    // toString for named closures.
    if (!name.equals("<lambda>")) {
      cc.newMethod(ACC_PUBLIC, "toString", String.class)
        .LDC("#<procedure: " + name + ">")
        .ARETURN()
        .end();
    }

    // This class is linked at the indy callsite; so define a hidden nestmate.
    Lookup lookup = caller.defineHiddenClass(cc.end(), false, NESTMATE, STRONG);
    return lookup.lookupClass();
  }
}
