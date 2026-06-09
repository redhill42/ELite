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

package elite.lang;

import java.io.Serializable;

public final class Symbol implements Serializable, Comparable<Symbol>
{
    // The global symbol table.
    private static final Table GLOBAL_TABLE = new Table();

    public static Symbol valueOf(String name) {
        return GLOBAL_TABLE.getSymbol(name);
    }

    final String name;
    final int hash;

    transient Symbol next;

    Symbol(String name, int hash, Symbol next) {
        this.name = name;
        this.hash = hash;
        this.next = next;
    }

    public String getName() {
        return name;
    }

    public int compareTo(Symbol that) {
        return name.compareTo(that.name);
    }

    public boolean equals(Object obj) {
        return this == obj;
    }

    public int hashCode() {
        return hash;
    }

    public String toString() {
        return ":" + name;
    }

    private Object readResolve() {
        return valueOf(name);
    }

    public static class Table {
        static final int DEFAULT_INITIAL_CAPACITY = 2048; // must be power of 2
        static final int MAXIMUM_CAPACITY = 1 << 30;
        static final float DEFAULT_LOAD_FACTOR = 0.75f;

        private volatile Symbol[] table;
        private int size;
        private int threshold;
        private float loadFactor;

        public Table() {
            loadFactor = DEFAULT_LOAD_FACTOR;
            threshold = (int)(DEFAULT_INITIAL_CAPACITY * DEFAULT_LOAD_FACTOR);
            table = new Symbol[DEFAULT_INITIAL_CAPACITY];
        }

        private static int hash(int h) {
            h ^= (h >>> 20) ^ (h >>> 12);
            return h ^ (h >>> 7) ^ (h >>> 4);
        }

        public Symbol getSymbol(String name) {
            Symbol[] table = this.table; // volatile
            int hash = hash(name.hashCode());
            int i = hash & (table.length - 1);
            for (Symbol e = table[i]; e != null; e = e.next) {
                if (hash == e.hash && name.equals(e.name)) {
                    return e;
                }
            }
            return addSymbol(name, hash);
        }

        private synchronized Symbol addSymbol(String name, int hash) {
            Symbol[] table = this.table;
            int i = hash & (table.length - 1);
            for (Symbol e = table[i]; e != null; e = e.next) {
                if (hash == e.hash && name.equals(e.name)) {
                    return e;
                }
            }

            Symbol s = new Symbol(name, hash, table[i]);
            table[i] = s;
            if (size++ >= threshold) {
                rehash(table.length * 2);
            }
            return s;
        }

        private void rehash(int newCapacity) {
            Symbol[] oldTable = table;
            int oldCapacity = oldTable.length;
            if (oldCapacity == MAXIMUM_CAPACITY) {
                threshold = Integer.MAX_VALUE;
                return;
            }

            Symbol[] newTable = new Symbol[newCapacity];
            transfer(newTable);
            table = newTable;
            threshold = (int)(newCapacity * loadFactor);
        }

        private void transfer(Symbol[] newTable) {
            Symbol[] src = table;
            int newCapacity = newTable.length;
            for (int j = 0; j < src.length; j++) {
                Symbol e = src[j];
                if (e != null) {
                    src[j] = null;
                    do {
                        Symbol next = e.next;
                        int i = e.hash & (newCapacity - 1);
                        e.next = newTable[i];
                        newTable[i] = e;
                        e = next;
                    } while (e != null);
                }
            }
        }
    }
}
