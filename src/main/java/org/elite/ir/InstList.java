package org.elite.ir;

import java.util.Arrays;

final class InstList {
    private long[] data;
    private int size;

    InstList() {
        this.data = new long[64];
        this.size = 0;
    }

    void add(long value) {
        ensureCapacity(size + 1);
        data[size++] = value;
    }

    void addAll(long[] values) {
        ensureCapacity(size + values.length);
        System.arraycopy(values, 0, data, size, values.length);
        size += values.length;
    }

    long get(int index) {
        return data[index];
    }

    long back() {
        if (size > 0)
            return data[size - 1];
        return 0; // NOP
    }

    void set(int index, long value) {
        data[index] = value;
    }

    void reset(int offset) {
        size = offset;
    }

    long[] data() {
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

    long[] toArray() {
        return Arrays.copyOf(data, size);
    }

    private void ensureCapacity(int needed) {
        if (needed > data.length) {
            int newLen = Math.max(data.length * 2, needed);
            data = Arrays.copyOf(data, newLen);
        }
    }
}
