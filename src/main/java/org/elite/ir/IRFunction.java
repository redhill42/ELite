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

import java.lang.reflect.Modifier;

/**
 * A compiled function in IR form.
 * <p>
 * Holds the linear instruction stream (one contiguous int[] for all basic
 * blocks),
 * a list of basic block start offsets, a constant pool for literals, and
 * variable/source metadata.
 *
 * <p>This is the unit of execution for the {@link IRInterpreter} and the unit
 * of compilation for the JVM bytecode backend.
 */
public class IRFunction {

  /**
   * The record to describe the try body, handlers, and finalizer.
   */
  record TryDescriptor(
    IRFunction    body,
    IRFunction[]  handlers,
    String[]      types,
    IRFunction    finalizer)
  {}

  private final IRClass owner;
  private final String name;
  private final int paramCount;
  private final int modifiers;

  /**
   * Internal name used by BytecodeCompiler.
   */
  public String internalName;

  /**
   * Closure class name used by BytecodeCompiler.
   */
  public String closureName;

  /**
   * Single contiguous code array for all blocks.
   */
  private int[] code;
  /**
   * Number of local variable slots
   */
  private int maxLocals;
  /**
   * Start offset of each basic block in the code array.
   */
  private int[] blockOffsets;
  /**
   * Constant pool: literals indexed by PUSH_CONST payload.
   */
  private Object[] constantPool;
  /**
   * Source-level debug info (PC→line mapping, file/function metadata).
   */
  private DebugInfo debugInfo;

  /**
   * Default parameter values (parallel to params; null entries = no default).
   * Simple literals are evaluated at compile time; null for complex
   * expressions.
   * Applied in execute() when caller provides fewer args than paramCount.
   */
  private Object[] defaultValues;

  // Create a IRFunction skeleton for a standalone function.
  IRFunction(String name, int paramCount) {
    this(null, name, paramCount, Modifier.PUBLIC | Modifier.STATIC);
  }

  // Create a IRFunction skeleton for class member function.
  IRFunction(IRClass owner, String name, int paramCount, int modifiers) {
    this.owner = owner;
    this.name = name;
    this.paramCount = paramCount;
    this.modifiers = modifiers;
  }

  // Populate IRFunction with code after compilation.
  void populate(int[] code, int maxLocals, int[] blockOffsets,
                Object[] constantPool, DebugInfo debugInfo,
                Object[] defaultValues) {
    this.code = code;
    this.maxLocals = maxLocals;
    this.blockOffsets = blockOffsets;
    this.constantPool = constantPool;
    this.debugInfo = debugInfo;
    this.defaultValues = defaultValues;
  }

  public boolean isDeclaration() {
    return code == null;
  }

  public IRClass owner() {
    return owner;
  }

  public String name() {
    return name;
  }

  public int paramCount() {
    return paramCount;
  }

  public int modifiers() {
    return modifiers;
  }

  public boolean isStatic() {
    return Modifier.isStatic(modifiers);
  }

  public int maxLocals() {
    return maxLocals;
  }

  public int[] code() {
    return code;
  }

  public int[] blockOffsets() {
    return blockOffsets;
  }

  public Object[] constantPool() {
    return constantPool;
  }

  public Object getConstant(int index) {
    return constantPool[index];
  }

  public DebugInfo debugInfo() {
    return debugInfo;
  }

  /**
   * Default parameter values (null = no default).
   */
  public Object[] defaultValues() {
    return defaultValues;
  }

  /**
   * Return this function with the given default parameter values.
   */
  public IRFunction withDefaults(Object[] defs) {
    defaultValues = defs;
    return this;
  }

  /**
   * Get the code offset for a given block ID.
   */
  public int blockStart(int blockId) {
    return blockOffsets[blockId];
  }

  /**
   * Number of basic blocks.
   */
  public int blockCount() {
    return blockOffsets.length;
  }

  public int blockOfPc(int pc) {
    for (int blockId = 0; blockId < blockOffsets.length; blockId++) {
      if (pc == blockOffsets[blockId])
        return blockId;
    }
    return -1;
  }

  public String dump() {
    return IRPrinter.dumpIR(this);
  }

  @Override
  public String toString() {
    return "IRFunction[" + name + "] params=" + paramCount;
  }
}
