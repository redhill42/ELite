/*
 * $Id: Symbol.java,v 1.3 2009/03/22 08:37:26 danielyuan Exp $
 *
 * Copyright (C) 2006 Operamasks Community.
 * Copyright (C) 2000-2006 Apusic Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see http://www.gnu.org/licenses.
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
