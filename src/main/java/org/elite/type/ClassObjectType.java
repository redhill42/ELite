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

public final class ClassObjectType extends ClassType {
  private final Class<?> classObj;

  public ClassObjectType(Class<?> classObj) {
    super(Class.class);
    this.classObj = classObj;
  }

  public Class<?> classObject() {
    return classObj;
  }

  @Override
  public Class<?> javaClass() {
    return Class.class;
  }

  @Override
  public String toString() {
    return "Class[" + classObj.getSimpleName() + "]";
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof ClassObjectType &&
           classObj.equals(((ClassObjectType) obj).classObj);
  }

  @Override
  public int hashCode() {
    return classObj.hashCode();
  }
}
