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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotate an ELite method.
 */
@Documented
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MetaMethod {
  /**
   * Return the method name.
   */
  String name();

  /**
   * Return the number of arguments of the method.
   */
  int arity();

  /**
   * {@return {@code true} if this method was declared to take a
   * variable number of arguments; returns {@code false} otherwise}
   */
  boolean varargs();

  /**
   * Return a list of parameter names.
   */
  String[] keys();

  /**
   * The default argument values.
   */
  Value[] defaults() default {};
}
