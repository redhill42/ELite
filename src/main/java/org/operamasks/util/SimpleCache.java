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
