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

import java.lang.reflect.Array;

/**
 * An array type, e.g. Array[Int, 2].
 */
public final class ArrayType extends Type {
  private final Type element;
  private final int dims;

  public ArrayType(Type element, int dims) {
    this.element = element;
    this.dims = dims;
  }

  public Type element() {
    return element;
  }

  public int dims() {
    return dims;
  }

  @Override
  public Class<?> javaClass() {
    Class<?> componentType = element.javaClass();
    if (componentType == null)
      return null;
    return Array.newInstance(componentType, dims).getClass();
  }

  @Override
  public String toString() {
    return element + "[]".repeat(dims);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ArrayType that &&
           dims == that.dims && element.equals(that.element);
  }

  @Override
  public int hashCode() {
    return element.hashCode() * 31 + dims;
  }
}
