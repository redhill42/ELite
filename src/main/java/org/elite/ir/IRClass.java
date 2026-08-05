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

import org.elite.parser.ELNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Intermediate representation of an ELite class definition.
 *
 * <p>Built during IR construction from an {@code ELNode.CLASSDEF}, then
 * consumed by {@link BytecodeCompiler} to generate a Java class.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Skeleton created by {@link SymbolTableBuilder} and registered in the
 *       symbol table (for forward reference).</li>
 *   <li>Filled by {@code IRBuilder} — variables, procedures with compiled
 *       {@link IRFunction} bodies, default values.</li>
 *   <li>Consumed by {@link BytecodeCompiler} to emit a {@code .class}.</li>
 * </ol>
 */
public class IRClass {

  /**
   * A helper class to represent a class field.
   */
  record Field(IRClass clazz, String field) {}

  /** ELite-level class name (e.g. {@code "Point"}). */
  public final String name;

  /** The ELNode that contains original class definition information. */
  public final ELNode.CLASSDEF node;

  /** Internal class name for nested class. */
  public String internalName;

  /**
   * The base class. {@link IRClass} elite class, {@link java.lang.Class}
   * for java class, null if no base class.
   */
  public Object base;

  /** The Java interfaces implemented by this class. */
  public Class<?>[] interfaces;

  /** Enclosing class (for nested / inner classes). */
  public IRClass outer;

  /** Inner classes and captured closures. */
  public final List<IRClass> inners = new ArrayList<>();
  public final List<IRFunction> closures = new ArrayList<>();

  /** The class and instance initialize procedure. */
  public ELNode.LAMBDA init_proc;
  public ELNode.LAMBDA clinit_proc;
  private final List<IRFunction> functions = new ArrayList<>();

  public IRClass(String name, ELNode.CLASSDEF node) {
    this.name = name;
    this.node = node;
  }

  public void add(IRFunction func) {
    functions.add(func);
  }

  public List<IRFunction> functions() {
    return functions;
  }

  public boolean isSingleton() {
    if (node.symbol.def.meta == null)
      return false;

    for (ELNode.METADATA meta : node.symbol.def.meta.metadata) {
      if (meta.type.equals("Singleton"))
        return true;
    }

    return false;
  }

  public String dump() {
    return IRPrinter.dumpIR(this);
  }
}
