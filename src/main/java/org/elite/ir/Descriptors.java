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

import java.lang.reflect.Method;

/**
 * This class defines various descriptors that passed from IRBuilder to
 * BytecodeCompiler via constant pool.
 */
final class Descriptors {
  private Descriptors() {}

  /**
   * The record to describe a class field.
   */
  record Field(
    IRClass       clazz,
    String        field
  ) {}

  /**
   * The record to describe the try body, handlers, and finalizer.
   */
  record Try(
    IRFunction    body,
    IRFunction[]  handlers,
    Class<?>[]    types,
    IRFunction    finalizer
  ) {}

  /**
   * The record to describe an InvokeDynamic.
   */
  record Indy(
    Method        bootstrap,  // the bootstrap method
    String        name,       // the invoke name, may be unused
    Object[]      args,       // constant arguments passed to bootstrap
    Class<?>      rtype,      // the invoke return type
    Class<?>...   ptypes      // the invoke parameter types
  ) {
    public Indy(Method bootstrap, String name, Class<?> rtype, Class<?>... ptypes) {
      this(bootstrap, name, new Object[0], rtype, ptypes);
    }
  }
}
