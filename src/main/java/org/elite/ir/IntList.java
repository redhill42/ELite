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

import java.util.Arrays;

/**
 * A mutable, resizable list of primitive {@code int} values.
 * Avoids boxing overhead of {@code ArrayList<Integer>} for IR building.
 */
final class IntList {
  private int[] data;
  private int size;

  IntList() {
    this.data = new int[64];
    this.size = 0;
  }

  IntList(int[] data) {
    this.data = data;
    this.size = data.length;
  }

  int[] data() {
    return data;
  }

  int size() {
    return size;
  }

  boolean isEmpty() {
    return size == 0;
  }

  void add(int value) {
    ensureCapacity(size + 1);
    data[size++] = value;
  }

  void addAll(int[] values, int offset, int length) {
    ensureCapacity(size + length);
    System.arraycopy(values, offset, data, size, length);
    size += length;
  }

  void addAll(IntList il) {
    addAll(il.data, 0, il.size);
  }

  int get(int index) {
    return data[index];
  }

  /**
   * Returns the last value in the list
   */
  int back() {
    if (size > 0)
      return data[size - 1];
    return 0; // NOP
  }

  /**
   * Returns the n'th value in back of the list.
   */
  int back(int n) {
    if (size > n)
      return data[size - 1 - n];
    return 0; // NOP
  }

  void set(int index, int value) {
    data[index] = value;
  }

  void insert(int index, int value) {
    assert index < size;
    ensureCapacity(size + 1);
    System.arraycopy(data, index, data, index + 1, size - index);
    data[index] = value;
  }

  void remove(int i) {
    remove(i, 1);
  }

  void remove(int i, int len) {
    assert i < size && i + len <= size;
    if ((size -= len) > i) {
      System.arraycopy(data, i + len, data, i, size - i);
    }
  }

  void reset(int offset) {
    size = offset;
  }

  void clear() {
    size = 0;
  }

  int[] toArray() {
    return Arrays.copyOf(data, size);
  }

  private void ensureCapacity(int needed) {
    if (needed > data.length) {
      int newLen = Math.max(data.length * 2, needed);
      data = Arrays.copyOf(data, newLen);
    }
  }
}
