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

package org.elite.parser;

import java.util.LinkedHashMap;
import java.util.Map;

public class ParseContext {
  /**
   * A variable environment.
   */
  private static class Context extends LinkedHashMap<String, ELNode.DEFINE> {
    Context next;

    Context(Context next) {
      this.next = next;
    }
  }

  /**
   * The context stack top.
   */
  private Context top;

  /**
   * Push a new context at the top of stack.
   */
  public void push() {
    top = new Context(top);
  }

  /**
   * Pop context from the top of stack.
   */
  public Map<String, ELNode.DEFINE> pop() {
    Context ret = top;
    top = top.next;
    return ret;
  }

  /**
   * Put a new variable to the environment.
   */
  public ELNode.DEFINE put(String name, ELNode.DEFINE var) {
    return top.put(name, var);
  }

  /**
   * Put a variable to the environment if it doesn't exist.
   */
  public ELNode.DEFINE putIfAbsent(String name, ELNode.DEFINE var) {
    ELNode.DEFINE prev = top.get(name);
    if (prev == null)
      top.put(name, var);
    return prev;
  }

  /**
   * Remove a variable from the environment.
   */
  public ELNode.DEFINE remove(String name) {
    return top.remove(name);
  }

  /**
   * Find the variable in the environment.
   */
  public ELNode.DEFINE get(String name) {
    for (Context env = top; env != null; env = env.next) {
      ELNode.DEFINE var = env.get(name);
      if (var != null)
        return var;
    }
    return null;
  }
}
