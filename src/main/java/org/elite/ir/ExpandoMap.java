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
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Expando method map. Record every expando method that registered by
 * {@code Builtin.attach} during compilation. Generate direct call for
 * known expando method.
 */
class ExpandoMap {
  private record Key(Class<?> declaringClass, String name)
    implements Comparable<Key>
  {
    // Most concrete class ordered before most general class.
    @Override
    public int compareTo(Key o) {
      if (declaringClass == o.declaringClass)
        return name.compareTo(o.name);
      return declaringClass.isAssignableFrom(o.declaringClass) ? 1 : -1;
    }
  }

  private final SortedMap<Key, ELNode.LAMBDA> map = new TreeMap<>();

  void put(Class<?> declaringClass, String name, ELNode.LAMBDA expando) {
    map.put(new Key(declaringClass, name), expando);
  }

  ELNode.LAMBDA find(Class<?> declaringClass, String name) {
    for (Map.Entry<Key, ELNode.LAMBDA> entry : map.entrySet()) {
      if (name.equals(entry.getKey().name) &&
          entry.getKey().declaringClass.isAssignableFrom(declaringClass))
        return entry.getValue();
    }
    return null;
  }
}
