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

    IntList(int initialCapacity) {
        this.data = new int[initialCapacity];
        this.size = 0;
    }

    void add(int value) {
        ensureCapacity(size + 1);
        data[size++] = value;
    }

    void addAll(int[] values) {
        ensureCapacity(size + values.length);
        System.arraycopy(values, 0, data, size, values.length);
        size += values.length;
    }

    int get(int index) {
        return data[index];
    }

    void set(int index, int value) {
        data[index] = value;
    }

    void reset(int offset) {
        size = offset;
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
