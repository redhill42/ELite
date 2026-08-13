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

package org.elite.resolver;

import elite.lang.Closure;
import org.elite.eval.ELEngine;
import org.elite.eval.EvaluationContext;
import org.elite.eval.closure.NamedClosure;
import org.elite.ir.MetaClass;
import org.elite.ir.MetaMethod;
import org.elite.util.Utils;
import javax.el.ELContext;
import javax.el.ELException;
import javax.el.MethodInfo;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.lang.reflect.Method;

import static org.elite.resources.Resources.*;

/**
 * 非重载方法的包装对象, 由于不需要匹配参数类型, 因此调用非重载方法
 * 比调用重载方法具有更好的性能.
 */
class SingleMethodClosure extends JavaMethodClosure {
  private transient Method method;

  public SingleMethodClosure(Method method) {
    this.method = method;
    init(method);
  }

  public String getName() {
    return method.getName();
  }

  protected JavaMethodClosure addMethod(Method method) {
    if (method.equals(this.method)) {
      return this;
    } else {
      JavaMethodClosure closure = new MultiMethodClosure(getName());
      closure.addMethod(this.method);
      closure.addMethod(method);
      return closure;
    }
  }

  public Method getJavaMethod() {
    return method;
  }

  public Method getJavaMethod(ELContext elctx, Object... args) {
    return method;
  }

  public int arity(ELContext elctx) {
    int nargs = method.getParameterCount();
    if (nargs > 0 && method.getParameterTypes()[0] == ELContext.class)
      nargs--;
    return nargs;
  }

  public MethodInfo getMethodInfo(ELContext elctx) {
    return new MethodInfo(method.getName(), method.getReturnType(),
                          method.getParameterTypes());
  }

  public int getModifiers() {
    return method.getModifiers();
  }

  public Object invoke(ELContext elctx, Object base, Closure[] args) {
    checkArgs(args);
    return ELEngine.invokeMethod(elctx, base, method, args);
  }

  public Object invokeSuper(ELContext elctx, Object base, Closure[] args) {
    checkArgs(args);
    return invokeSuper(elctx, method, base, args);
  }

  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o instanceof SingleMethodClosure that)
      return this.method.equals(that.method);
    return false;
  }

  public int hashCode() {
    return method.hashCode();
  }

  public String toString() {
    return "#<primitive:" + method.getName() + "[" + method + "]>";
  }

  @Serial
  private void writeObject(ObjectOutputStream out) throws IOException {
    writeMethod(out, method);
  }

  @Serial
  private void readObject(ObjectInputStream in)
    throws IOException, ClassNotFoundException
  {
    method = readMethod(in);
    init(method);
  }

  private void init(Method method) {
    Utils.setAccessible(method);
  }

  private void checkArgs(Closure[] args) {
    Class<?>[] types = method.getParameterTypes();
    int nargs = types.length;
    boolean vargs = method.isVarArgs();

    if (method.isAnnotationPresent(MetaMethod.class))
      return; // self checked

    // cache argument count for quick test
    if (nargs > 0 && types[0] == ELContext.class)
      --nargs;

    if (nargs != args.length && (!vargs || args.length < nargs - 1)) {
      throw new ELException(
        _T(EL_FN_BAD_ARG_COUNT, getName(), nargs, args.length));
    }

    StringBuilder named_args = null;
    for (Closure a : args) {
      if (a instanceof NamedClosure) {
        if (named_args == null) {
          named_args = new StringBuilder();
        } else {
          named_args.append(",");
        }
        named_args.append(((NamedClosure)a).name());
      }
    }

    if (named_args != null) {
      throw new ELException(_T(EL_UNKNOWN_ARG_NAME, named_args.toString()));
    }
  }
}
