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

import org.elite.eval.TypeCoercion;

/**
 * Base class of the ELite static type model.
 * All subclasses are immutable with structural equality.
 */
public abstract class Type {
  public static Type of(Class<?> javaClass) {
    if (javaClass == null)
      return null;

    if (javaClass == Object.class)
      return Types.ANY;

    if (javaClass == Object[].class)
      return new TupleType(new Type[]{Types.ANY});

    if (javaClass.isArray()) {
      int dims = 0;
      while (javaClass.isArray()) {
        dims++;
        javaClass = javaClass.getComponentType();
      }
      return new ArrayType(javaClass, dims);
    }

    Type t = Types.PRIMITIVES.get(TypeCoercion.getBoxedType(javaClass));
    if (t == null)
      t = new ClassType(javaClass);
    return t;
  }

  Type() {}

  public Class<?> javaClass() {
    return null;
  }

  @Override
  public abstract String toString();

  @Override
  public abstract boolean equals(Object o);

  @Override
  public abstract int hashCode();
}
