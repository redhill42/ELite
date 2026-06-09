package org.operamasks.el.ir;

import java.util.Arrays;

/**
 * A mutable, resizable list of primitive {@code int} values.
 * Avoids boxing overhead of {@code ArrayList<Integer>} for IR building.
 */
class IntList {
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
