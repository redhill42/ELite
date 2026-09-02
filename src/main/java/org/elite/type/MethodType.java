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
package org.elite.type;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * A Java method resolved by reflection; the payload is the Method.
 */
public final class MethodType extends MemberType {
  private final Method method;
  private final boolean isStatic;

  public MethodType(Method method, boolean isStatic) {
    this.method = method;
    this.isStatic = isStatic;
  }

  public Method method() {
    return method;
  }

  public boolean isStatic() {
    return isStatic;
  }

  @Override
  public Type getResultType() {
    return Type.of(method.getReturnType());
  }

  @Override
  public Class<?> javaClass() {
    return method.getReturnType();
  }

  private static String jvmTypeName(Class<?> c) {
    return c.isPrimitive() ? c.getName() : Type.of(c).toString();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("Method[")
      .append(jvmTypeName(method.getReturnType())).append(' ')
      .append(method.getDeclaringClass().getSimpleName())
      .append('.').append(method.getName()).append('(');
    Class<?>[] params = method.getParameterTypes();
    for (int i = 0; i < params.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(jvmTypeName(params[i]));
    }
    return sb.append(")]").toString();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof MethodType that && Objects.equals(method, that.method);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(method);
  }
}
