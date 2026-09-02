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

import java.util.Map;
import java.util.Objects;

/**
 * An ELite map type, e.g. Map[String, Int].
 */
public final class MapType extends Type {
  private final Type key;
  private final Type value;

  public MapType(Type key, Type value) {
    this.key = key;
    this.value = value;
  }

  public Type key() {
    return key;
  }

  public Type value() {
    return value;
  }

  @Override
  public Class<?> javaClass() {
    return Map.class;
  }

  @Override
  public String toString() {
    return "Map[" + key + ", " + value + "]";
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof MapType that &&
           key.equals(that.key) && value.equals(that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key, value);
  }
}
