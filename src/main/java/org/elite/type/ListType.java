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

import elite.lang.Seq;

/**
 * An ELite list type, e.g. List[Int].
 */
public final class ListType extends Type {
  private final Type element;

  public ListType(Type element) {
    this.element = element;
  }

  public Type element() {
    return element;
  }

  @Override
  public Class<?> javaClass() {
    return Seq.class;
  }

  @Override
  public String toString() {
    return "List[" + element + "]";
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ListType that && element.equals(that.element);
  }

  @Override
  public int hashCode() {
    return element.hashCode() * 31 + 1;
  }
}
