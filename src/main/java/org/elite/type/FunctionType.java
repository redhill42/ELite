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

import elite.lang.Closure;
import java.util.Arrays;

/**
 * An ELite function type, e.g. Function[Int, [Int, Int]].
 */
public final class FunctionType extends Type {
  private final Type returnType;
  private final Type[] params;

  public FunctionType(Type returnType, Type[] params) {
    this.returnType = returnType;
    this.params = params;
  }

  public Type returnType() {
    return returnType;
  }

  public Type[] params() {
    return params;
  }

  @Override
  public Class<?> javaClass() {
    return Closure.class;
  }

  @Override
  public String toString() {
    if (params.length == 1)
      return params[0] + "->" + returnType;

    StringBuilder sb = new StringBuilder();
    sb.append("(");
    for (int i = 0; i < params.length; i++) {
      if (i > 0)
        sb.append(",");
      sb.append(params[i]);
    }
    return sb.append(")->").append(returnType).toString();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof FunctionType that &&
           returnType.equals(that.returnType) &&
           Arrays.equals(params, that.params);
  }

  @Override
  public int hashCode() {
    return returnType.hashCode() * 31 + Arrays.hashCode(params);
  }
}
