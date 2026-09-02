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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * A Java field resolved by reflection; the payload is the Field.
 */
public final class FieldType extends MemberType {
  private final Field field;

  public FieldType(Field field) {
    this.field = field;
  }

  public Field field() {
    return field;
  }

  public boolean isStatic() {
    return Modifier.isStatic(field.getModifiers());
  }

  @Override
  public Type getResultType() {
    return Type.of(field.getType());
  }

  @Override
  public Class<?> javaClass() {
    return field.getType();
  }

  @Override
  public String toString() {
    return "Field[" + Type.of(field.getType()) + " " +
           field.getDeclaringClass().getSimpleName() + "." + field.getName() +
           "]";
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof FieldType that && field.equals(that.field);
  }

  @Override
  public int hashCode() {
    return field.hashCode();
  }
}
