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

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class VarArgList extends AbstractList<Object> {
  private final String[] keys;
  private final Object[] args;

  private transient List<String> keyList;

  VarArgList(String[] keys, Object[] args) {
    this.keys = keys;
    this.args = args;
  }

  @Override
  public int size() {
    return args.length;
  }

  @Override
  public Object[] toArray() {
    return Arrays.copyOf(args, args.length, Object[].class);
  }

  @Override
  public Object get(int index) {
    return args[index];
  }

  @Override
  public Object set(int index, Object element) {
    Object oldValue = args[index];
    args[index] = element;
    return oldValue;
  }

  @Override
  public int indexOf(Object o) {
    if (o == null) {
      for (int i = 0; i < args.length; i++) {
        if (null == args[i])
          return i;
      }
    } else {
      for (int i = 0; i < args.length; i++) {
        if (o.equals(args[i]))
          return i;
      }
    }
    return -1;
  }

  @Override
  public boolean contains(Object o) {
    return indexOf(o) >= 0;
  }

  @Override
  public Iterator<Object> iterator() {
    return Arrays.asList(args).iterator();
  }

  public List<String> getKeys() {
    if (keyList == null)
      keyList = Arrays.asList(keys);
    return keyList;
  }

  public Object get(String key) {
    if (key == null || key.isEmpty())
      return null;
    for (int i = 0; i < args.length; i++) {
      if (key.equals(keys[i]))
        return args[i];
    }
    return null;
  }

  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    for (int i = 0; i < args.length; i++) {
      if (i > 0)
        sb.append(", ");
      if (!keys[i].isEmpty())
        sb.append(keys[i]).append('=');
      sb.append(args[i]);
    }
    sb.append("]");
    return sb.toString();
  }
}
