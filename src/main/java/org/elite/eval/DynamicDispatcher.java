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

/**
 * The DynamicDispatcher used for compiled elite class to dispatch member
 * procedures.
 */
public interface DynamicDispatcher {
  Object __invoke__(EvaluationContext env, String name, Object... args);

  Object __invokeStatic__(EvaluationContext env, String name, Object... args);
}
