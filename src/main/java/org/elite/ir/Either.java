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
package org.elite.ir;

/**
 * A utility class to represent dual return value.
 */
@SuppressWarnings("unused")
public abstract class Either {
  private final Object value;

  public static Either left(Object value) {
    return new Left(value);
  }

  public static Either right(Object value) {
    return new Right(value);
  }

  protected Either(Object value) {
    this.value = value;
  }

  public abstract boolean isLeft();
  public boolean isRight() { return !isLeft(); }
  public Object value() { return value; }

  private static class Left extends Either {
    private Left(Object value) { super(value); }
    public boolean isLeft() { return true; }
  }

  private static class Right extends Either {
    private Right(Object value) { super(value); }
    public boolean isLeft() { return false; }
  }
}
