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

import java.util.Arrays;

/**
 * An ELite tuple type, e.g. Tuple[String, Int].
 */
public final class TupleType extends Type {
  private final Type[] elems;

  public TupleType(Type[] elems) {
    this.elems = elems;
  }

  public Type[] elems() {
    return elems;
  }

  @Override
  public Class<?> javaClass() {
    return Object[].class;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("Tuple[");
    for (int i = 0; i < elems.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(elems[i]);
    }
    return sb.append(']').toString();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TupleType that && Arrays.equals(elems, that.elems);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(elems);
  }
}
