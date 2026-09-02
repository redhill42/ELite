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

import java.util.Objects;

/**
 * A reference to a Java class.
 */
public class ClassType extends Type {
  private final Class<?> javaClass;

  ClassType(Class<?> javaClass) {
    this.javaClass = javaClass;
  }

  @Override
  public Class<?> javaClass() {
    return javaClass;
  }

  @Override
  public String toString() {
    return javaClass.getSimpleName();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ClassType that && javaClass == that.javaClass;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(javaClass);
  }
}
