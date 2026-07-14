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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IRProgram {
  private final IRFunction entry;
  private final List<IRFunction> functions = new ArrayList<>();

  IRProgram(IRFunction entry) {
    this.entry = entry;
    functions.add(entry);
  }

  void add(IRFunction function) {
    functions.add(function);
  }

  public IRFunction entry() {
    return entry;
  }

  public List<IRFunction> functions() {
    return Collections.unmodifiableList(functions);
  }

  public String dump() {
    StringBuilder sb = new StringBuilder();
    for (IRFunction fn : functions)
      sb.append(fn.dump());
    return sb.toString();
  }
}
