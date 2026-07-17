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
 * Consumer of compiled bytecode produced by {@link BytecodeCompiler}.
 * <p>
 * Two implementations are provided:
 * <ul>
 *   <li>{@code JITBytecodeConsumer} — loads classes at runtime (for REPL /
 *   scripting)</li>
 *   <li>{@code AOTBytecodeConsumer} — writes {@code .class} files to disk
 *   (for standalone compilation)</li>
 * </ul>
 */
public interface BytecodeConsumer {

  /**
   * Called with the main program class bytecode.
   *
   * @param className fully-qualified binary name of the class
   * @param bytecode  the {@code .class} file bytes
   */
  void acceptProgram(String className, byte[] bytecode);

  /**
   * Called for each closure inner class that is compiled.
   *
   * @param className fully-qualified binary name of the closure class
   * @param bytecode  the {@code .class} file bytes
   */
  void acceptClosure(String className, byte[] bytecode);
}
