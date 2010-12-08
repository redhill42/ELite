/*
 * $Id: SimpleCache.java,v 1.2 2009/03/22 08:37:56 danielyuan Exp $
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

package org.operamasks.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SimpleCache<K,V>
{
    private int capacity;
    private Map<Object,V> cache;
    private Map<Object,V> cache2;

    private static Object NULL_KEY = new Object();

    public static <K,V> SimpleCache<K,V> make(int capacity) {
        return new SimpleCache<K,V>(capacity);
    }

    public SimpleCache(int capacity) {
        this.capacity = capacity;
        cache = new ConcurrentHashMap<Object,V>(capacity);
        cache2 = new ConcurrentHashMap<Object,V>(capacity);
    }

    public V get(Object key) {
        if (key == null) key = NULL_KEY;
        V result = cache.get(key);
        if (result == null)
            result = cache2.get(key);
        return result;
    }

    public void put(K key, V value) {
        assert value != null;
        if (cache.size() > capacity) {
            cache2.clear();
            cache2.putAll(cache);
            cache.clear();
        }
        if (key == null) {
            cache.put(NULL_KEY, value);
        } else {
            cache.put(key, value);
        }
    }

    public void clear() {
        cache.clear();
        cache2.clear();
    }
}
