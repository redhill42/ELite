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
package org.elite.eval;

import javax.el.ELContext;
import static org.elite.eval.ELUtils.NO_RESULT;

/**
 * The DynamicDispatcher is used for compiled elite class to dispatch member
 * procedures.
 */
public interface DynamicDispatcher extends PropertyResolvable {
  /**
   * Invoke an instance member procedure by name.
   */
  default Object
  __invoke__(EvaluationContext env, String name, Object... args) {
    return NO_RESULT;
  }

  /**
   * Invoke a static member procedure by name.
   */
  default Object
  __invokeStatic__(EvaluationContext env, String name, Object... args) {
    return NO_RESULT;
  }

  // -- Default implementation of PropertyResolvable is doing nothing.

  /**
   * {@inheritDoc}
   * <p>
   * If no property to resolve for dispatcher, return null and keep
   * propertyResolved in ELContext unchanged.
   */
  default Object getValue(ELContext elctx, Object property) {
    return null;
  }

  /**
   * {@inheritDoc}
   * <p>
   * If no property to resolve for dispatcher, return and keep propertyResolved
   * in ELContext unchanged.
   */
  default void setValue(ELContext elctx, Object property, Object value) {
  }

  /**
   * {@inheritDoc}
   * <p>
   * This method is unused for XEL. Simply return Object.class.
   */
  default Class<?> getType(ELContext elctx, Object property) {
    return Object.class;
  }

  /**
   * {@inheritDoc}
   * <p>
   * This method is unused for XEL. Simply return false.
   */
  default boolean isReadOnly(ELContext elctx, Object property) {
    return false;
  }
}
